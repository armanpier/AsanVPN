package com.asanvpn.app.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import com.asanvpn.app.data.CloudflareEdgeTester
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.util.concurrent.TimeUnit

data class AppVersionInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val releaseNotes: String? = null,
    val totalSize: Long = 0L,
    val forceUpdate: Boolean = false
)

object AppUpdateManager {

    private val gson = Gson()

    private class ResilientDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (hostname.contains("workers.dev") || hostname.contains("hi-deploy") ||
                hostname == "spareone.icu" || hostname == "onthepoint.live") {
                val cleanIps = CloudflareEdgeTester.getWorkingCloudflareIps(hostname)
                if (cleanIps.isNotEmpty()) return cleanIps
            }
            return try {
                val sys = Dns.SYSTEM.lookup(hostname)
                val clean = sys.filter { addr ->
                    val h = addr.hostAddress ?: ""
                    !h.startsWith("10.10.") && h != "127.0.0.1" && h != "0.0.0.0"
                }
                if (clean.isNotEmpty()) clean else CloudflareEdgeTester.getWorkingCloudflareIps(hostname)
            } catch (e: Exception) {
                CloudflareEdgeTester.getWorkingCloudflareIps(hostname)
            }
        }
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(ResilientDns())
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun getCurrentVersion(context: Context): Pair<Int, String> {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
            val name = packageInfo.versionName ?: "1.0.0"
            Pair(code, name)
        } catch (e: Exception) {
            Pair(5, "1.2.0")
        }
    }

    /**
     * Checks whether a remote version is newer than current app version.
     */
    fun isNewerVersion(remoteVersion: String, currentVersion: String): Boolean {
        try {
            val remoteParts = remoteVersion.trim().removePrefix("v").split(".").map { it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
            val currentParts = currentVersion.trim().removePrefix("v").split(".").map { it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
            val maxLen = maxOf(remoteParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val r = remoteParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
            return false
        } catch (e: Exception) {
            return false
        }
    }

    private fun sec(encoded: String): String {
        return try {
            val bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
            val decoded = ByteArray(bytes.size)
            val k = 0x5A
            for (i in bytes.indices) {
                decoded[i] = (bytes[i].toInt() xor k).toByte()
            }
            String(decoded, Charsets.UTF_8)
        } catch (e: Exception) {
            encoded
        }
    }

    /**
     * Check for update by querying Cloudflare Worker first, then GitHub API fallback.
     */
    suspend fun checkForUpdate(context: Context): Result<AppVersionInfo?> = withContext(Dispatchers.IO) {
        val (currentCode, currentName) = getCurrentVersion(context)

        // 1. Try Cloudflare Worker version endpoint (ultra-fast CDN & clean IPs)
        val workerUrls = listOf(
            sec("Mi4uKilgdXU7KTs0LCo0dy01KDE/KHQyM3c+Pyo2NSN0LTUoMT8oKXQ+Pyx1OyozdSxrdTsqKnUsPygpMzU0"),
            sec("Mi4uKilgdXUpKjsoPzU0P3QzOS91OyozdSxrdTsqKnUsPygpMzU0")
        )

        for (endpoint in workerUrls) {
            try {
                val request = Request.Builder()
                    .url(endpoint)
                    .header("User-Agent", "AsanVPN-Android/${currentName}")
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (!body.isNullOrBlank()) {
                            val json = gson.fromJson(body, JsonObject::class.java)
                            val remoteName = json.get("versionName")?.asString ?: ""
                            val remoteCode = json.get("versionCode")?.asInt ?: 0
                            val downloadUrl = json.get("downloadUrl")?.asString ?: "https://asanvpn-worker.hi-deploy.workers.dev/download"
                            val releaseNotes = json.get("releaseNotes")?.asString
                            val totalSize = json.get("totalSize")?.asLong ?: 0L
                            val force = json.get("forceUpdate")?.asBoolean ?: false

                            val isNewer = remoteCode > currentCode || isNewerVersion(remoteName, currentName)
                            if (isNewer && remoteName.isNotBlank()) {
                                return@withContext Result.success(
                                    AppVersionInfo(
                                        versionCode = remoteCode,
                                        versionName = remoteName,
                                        downloadUrl = downloadUrl,
                                        releaseNotes = releaseNotes,
                                        totalSize = totalSize,
                                        forceUpdate = force
                                    )
                                )
                            } else {
                                // No newer update on worker
                                return@withContext Result.success(null)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Fallback to next endpoint or GitHub
            }
        }

        // 2. Fallback to GitHub Releases API
        try {
            val ghUrl = "https://api.github.com/repos/armanpier/AsanVPN/releases/latest"
            val ghRequest = Request.Builder()
                .url(ghUrl)
                .header("User-Agent", "AsanVPN-Android")
                .header("Accept", "application/vnd.github.v3+json")
                .get()
                .build()

            httpClient.newCall(ghRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrBlank()) {
                        val json = gson.fromJson(body, JsonObject::class.java)
                        val tagName = json.get("tag_name")?.asString ?: ""
                        val remoteName = tagName.removePrefix("v").trim()
                        val releaseNotes = json.get("body")?.asString

                        var apkUrl: String? = null
                        var apkSize = 0L
                        val assets = json.getAsJsonArray("assets")
                        if (assets != null) {
                            // Find universal APK or first APK asset
                            for (assetElem in assets) {
                                val assetObj = assetElem.asJsonObject
                                val name = assetObj.get("name")?.asString ?: ""
                                if (name.endsWith(".apk", ignoreCase = true)) {
                                    val dlUrl = assetObj.get("browser_download_url")?.asString
                                    val sz = assetObj.get("size")?.asLong ?: 0L
                                    if (name.contains("universal", ignoreCase = true)) {
                                        apkUrl = dlUrl
                                        apkSize = sz
                                        break
                                    } else if (apkUrl == null) {
                                        apkUrl = dlUrl
                                        apkSize = sz
                                    }
                                }
                            }
                        }

                        if (apkUrl == null) {
                            apkUrl = "https://github.com/armanpier/AsanVPN/releases/download/$tagName/AsanVPN-$tagName-universal.apk"
                        }

                        if (isNewerVersion(remoteName, currentName)) {
                            return@withContext Result.success(
                                AppVersionInfo(
                                    versionCode = currentCode + 1,
                                    versionName = remoteName,
                                    downloadUrl = apkUrl,
                                    releaseNotes = releaseNotes,
                                    totalSize = apkSize
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Both failed
        }

        Result.success(null)
    }

    /**
     * Downloads APK file internally and provides download progress callbacks.
     */
    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        expectedTotalBytes: Long = 0L,
        onProgress: (progress: Float, downloadedMb: Float, totalMb: Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val updateDir = File(context.cacheDir, "updates")
            if (!updateDir.exists()) updateDir.mkdirs()

            // Delete any existing older update files
            updateDir.listFiles()?.forEach { it.delete() }

            val targetFile = File(updateDir, "AsanVPN-update.apk")
            if (targetFile.exists()) targetFile.delete()

            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "AsanVPN-Updater")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("خطا در برقراری ارتباط با سرور دانلود (کد ${response.code})"))
            }

            val body = response.body ?: return@withContext Result.failure(Exception("پاسخ دریافتی خالی بود."))
            
            // Resolve actual total content length across multiple sources
            var contentLength = body.contentLength()
            if (contentLength <= 0) {
                val headerLength = response.header("X-Total-Length")?.toLongOrNull()
                    ?: response.header("X-File-Size")?.toLongOrNull()
                    ?: response.header("Content-Length")?.toLongOrNull()
                if (headerLength != null && headerLength > 0) {
                    contentLength = headerLength
                }
            }
            if (contentLength <= 0 && expectedTotalBytes > 0) {
                contentLength = expectedTotalBytes
            }

            val totalMb = if (contentLength > 0) contentLength / (1024f * 1024f) else 0f

            body.byteStream().use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = 0L
                    var lastReportTime = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastReportTime > 60 || totalRead == contentLength) {
                            lastReportTime = now
                            val progress = if (contentLength > 0) {
                                (totalRead.toFloat() / contentLength.toFloat()).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                            val downloadedMb = totalRead / (1024f * 1024f)
                            withContext(Dispatchers.Main) {
                                onProgress(progress, downloadedMb, totalMb)
                            }
                        }
                    }
                    output.flush()
                }
            }

            if (targetFile.length() < 1024 * 1024) {
                return@withContext Result.failure(Exception("فایل دانلود شده ناقص است. لطفاً مجدداً امتحان کنید."))
            }

            Result.success(targetFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Prompts the Android OS package installer to install the downloaded APK.
     */
    fun installApk(context: Context, apkFile: File): Boolean {
        return try {
            if (!apkFile.exists()) return false

            // Check if app has permission to install unknown apps (API 26+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(settingsIntent)
                }
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(installIntent)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
