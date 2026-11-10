package com.asanvpn.app.util

import android.content.Context
import android.util.Log
import com.asanvpn.app.data.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Manages Chocolate4Iran (Chocolate4U/Iran-v2ray-rules) routing assets:
 * `geoip.dat` and `geosite.dat`.
 *
 * Provides APK asset initialization, lightweight SHA256 checksum polling against
 * CDN and fallback mirrors, and atomic download/replacement for runtime rule updates.
 */
object GeoAssetManager {

    private const val TAG = "GeoAssetManager"

    // Primary jsDelivr CDN mirror (fast and accessible without censorship in Iran)
    private const val JSDELIVR_BASE = "https://cdn.jsdelivr.net/gh/Chocolate4U/Iran-v2ray-rules@release"
    // GitHub Raw fallback mirror
    private const val GITHUB_RAW_BASE = "https://raw.githubusercontent.com/Chocolate4U/Iran-v2ray-rules/release"

    // 24-hour update check interval
    private const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L

    // Concurrency control: ensures only one rule update check/download executes at a time
    private val updateMutex = Mutex()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * Returns the internal storage directory where Xray loads routing rule databases.
     */
    fun getAssetDir(context: Context): File = context.getDir("assets", Context.MODE_PRIVATE)

    /**
     * Ensures baseline assets (`geoip.dat` and `geosite.dat`) exist in internal storage
     * by copying them from APK assets on first launch or if missing.
     */
    fun ensureAssetsInitialized(context: Context) {
        val assetDir = getAssetDir(context)
        if (!assetDir.exists()) {
            assetDir.mkdirs()
        }
        // Purge any stale temporary files from previous interrupted runs
        assetDir.listFiles { _, name -> name.endsWith(".tmp") }?.forEach { it.delete() }

        copyAssetIfMissing(context, "geoip.dat", assetDir)
        copyAssetIfMissing(context, "geosite.dat", assetDir)
    }

    private fun copyAssetIfMissing(context: Context, fileName: String, targetDir: File) {
        val targetFile = File(targetDir, fileName)
        if (!targetFile.exists() || targetFile.length() == 0L) {
            try {
                context.assets.open(fileName).use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.i(TAG, "Initialized asset $fileName in ${targetFile.absolutePath} (${targetFile.length()} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed copying asset $fileName from APK assets: ${e.message}")
            }
        }
    }

    /**
     * Checks remote SHA256 checksums from Chocolate4Iran release and updates
     * geoip.dat and geosite.dat if newer versions are available.
     *
     * @param context Application context
     * @param force If true, bypasses the 24-hour check throttle
     * @return Result containing true if any asset was updated, false otherwise
     */
    suspend fun checkAndUpdateRules(context: Context, force: Boolean = false): Result<Boolean> = withContext(Dispatchers.IO) {
        // Non-blocking tryLock: Coalesce concurrent update requests
        if (!updateMutex.tryLock()) {
            Log.d(TAG, "Rule update check already in progress; skipping concurrent request")
            return@withContext Result.success(false)
        }
        try {
            val prefs = PreferencesManager(context)
            val now = System.currentTimeMillis()
            val lastCheck = prefs.lastGeoRulesCheckTime

            if (!force && (now - lastCheck < CHECK_INTERVAL_MS) && (now >= lastCheck)) {
                Log.d(TAG, "Skipping geo rules check: last checked ${(now - lastCheck) / 1000}s ago")
                return@withContext Result.success(false)
            }

            val assetDir = getAssetDir(context)
            if (!assetDir.exists()) {
                assetDir.mkdirs()
            }

            // Cleanup any orphan temporary files
            assetDir.listFiles { _, name -> name.endsWith(".tmp") }?.forEach { it.delete() }

            var anyUpdated = false
            val assetsToCheck = listOf("geoip.dat", "geosite.dat")

            for (assetName in assetsToCheck) {
                val targetFile = File(assetDir, assetName)
                val currentHash = if (targetFile.exists() && targetFile.length() > 0) computeSha256(targetFile) else ""
                val remoteHash = fetchRemoteChecksum(assetName)

                if (remoteHash != null) {
                    val needsUpdate = force || !targetFile.exists() || targetFile.length() == 0L ||
                            !currentHash.equals(remoteHash, ignoreCase = true)

                    if (needsUpdate) {
                        Log.i(TAG, "Update detected for $assetName: local=$currentHash, remote=$remoteHash")
                        val downloaded = downloadAndVerifyAsset(assetName, remoteHash, assetDir)
                        if (downloaded) {
                            anyUpdated = true
                        }
                    } else {
                        Log.d(TAG, "Asset $assetName is already up to date ($currentHash)")
                    }
                } else {
                    Log.w(TAG, "Could not fetch remote checksum for $assetName from any mirror")
                }
            }

            prefs.lastGeoRulesCheckTime = now
            Result.success(anyUpdated)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking/updating Chocolate4Iran rules: ${e.message}", e)
            Result.failure(e)
        } finally {
            updateMutex.unlock()
        }
    }

    private fun fetchRemoteChecksum(assetName: String): String? {
        val checkUrls = listOf(
            "$JSDELIVR_BASE/$assetName.sha256sum",
            "$GITHUB_RAW_BASE/$assetName.sha256sum"
        )
        for (url in checkUrls) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "AsanVPN-GeoAssetSync/1.0")
                    .build()

                httpClient.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()?.trim() ?: ""
                        val hash = body.split(Regex("\\s+")).firstOrNull()?.trim()?.lowercase()
                        if (!hash.isNullOrBlank() && hash.length == 64 && hash.all { it in "0123456789abcdef" }) {
                            return hash
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Checksum fetch failed from $url: ${e.message}")
            }
        }
        return null
    }

    private fun downloadAndVerifyAsset(assetName: String, expectedHash: String, targetDir: File): Boolean {
        val downloadUrls = listOf(
            "$JSDELIVR_BASE/$assetName",
            "$GITHUB_RAW_BASE/$assetName"
        )
        val finalFile = File(targetDir, assetName)

        for (url in downloadUrls) {
            // Unique temporary file path per download operation
            val tempFile = File(targetDir, "$assetName.${UUID.randomUUID()}.tmp")
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "AsanVPN-GeoAssetSync/1.0")
                    .build()

                httpClient.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        resp.body?.byteStream()?.use { input ->
                            FileOutputStream(tempFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }

                if (tempFile.exists() && tempFile.length() > 0) {
                    val actualHash = computeSha256(tempFile)
                    if (actualHash.equals(expectedHash, ignoreCase = true)) {
                        val replaced = replaceFileSafely(tempFile, finalFile)
                        if (replaced) {
                            Log.i(TAG, "Successfully updated $assetName to hash $actualHash (${finalFile.length()} bytes)")
                            return true
                        }
                    } else {
                        Log.w(TAG, "SHA256 mismatch for $assetName: expected $expectedHash, got $actualHash")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Download attempt failed for $assetName from $url: ${e.message}")
            } finally {
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            }
        }
        return false
    }

    private fun replaceFileSafely(source: File, target: File): Boolean {
        // Attempt 1: Direct rename (atomic POSIX on Linux/Android)
        if (source.renameTo(target)) {
            return true
        }
        // Attempt 2: Target deletion followed by rename (Windows compatibility)
        if (target.exists()) {
            target.delete()
        }
        if (source.renameTo(target)) {
            return true
        }
        // Attempt 3: Byte copy fallback
        return try {
            source.copyTo(target, overwrite = true)
            source.delete()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed replacing ${target.name}: ${e.message}")
            false
        }
    }

    private fun computeSha256(file: File): String {
        if (!file.exists() || file.length() == 0L) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buf).also { bytesRead = it } != -1) {
                digest.update(buf, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
