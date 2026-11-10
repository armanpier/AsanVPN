package com.asanvpn.app

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.asanvpn.app.data.PreferencesManager
import com.asanvpn.app.util.GeoAssetManager
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * EMPIRICAL ADVERSARIAL TEST SUITE for GeoAssetManager & PreferencesManager (Milestone 2)
 *
 * Verifies:
 * 1. SHA256 Checksum Verification & Atomic Replacement:
 *    - Matching checksum -> triggers download and atomically replaces asset
 *    - Mismatching checksum -> corrupt download is discarded, original asset preserved
 *    - Empty, whitespace, malformed, non-hex checksums -> safely rejected without download
 * 2. Network Failure & Resilience Handling:
 *    - Simulated HTTP 500 (primary mirror fallback to secondary, and dual mirror 500 failure)
 *    - Socket timeouts during checksum check and file download
 *    - Network disconnection (UnknownHostException / IOException)
 *    - Truncated / interrupted download streams
 * 3. 24-Hour Throttling & Force Bypass:
 *    - Subsequent checks within 24h return false without network queries
 *    - force=true bypasses 24h throttle
 *    - Check after 24h triggers remote update
 *    - Clock skew handling
 * 4. PreferencesManager Integration:
 *    - Persistence, default values, clearAll()
 * 5. Concurrency Stress:
 *    - 20 concurrent coroutines hammering checkAndUpdateRules simultaneously
 */
class GeoAssetManagerAdversarialTest {

    @org.junit.Test
    fun testGeoAssetManagerAdversarial() = kotlinx.coroutines.runBlocking {
        main(emptyArray())
    }

    companion object {
        private var testsPassed = 0
        private var testsFailed = 0

        fun assertTrue(testName: String, condition: Boolean, message: String) {
            if (condition) {
                println("[PASS] $testName: $message")
                testsPassed++
            } else {
                System.err.println("[FAIL] $testName: $message")
                testsFailed++
            }
        }

        fun computeSha256(data: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(data)
            return hash.joinToString("") { "%02x".format(it) }
        }

        fun injectHttpClient(client: OkHttpClient) {
            val delegateField = GeoAssetManager::class.java.getDeclaredField("httpClient\$delegate")
            UnsafeHelper.setStaticObject(delegateField, lazyOf(client))
        }

        fun createMockContext(testDir: File, prefs: SharedPreferences): android.content.Context {
            val ctx = MockContext()
            ctx.testDir = testDir
            ctx.testPrefs = prefs
            return ctx
        }

        @JvmStatic
        fun main(args: Array<String>) = runBlocking {
            println("================================================================================")
            println("   GEOASSETMANAGER EMPIRICAL ADVERSARIAL TEST SUITE (JDK 17)                    ")
            println("================================================================================")

            val baseTempDir = File("build/adversarial_test_assets")
            if (baseTempDir.exists()) baseTempDir.deleteRecursively()
            baseTempDir.mkdirs()

            try {
                // Suite 1: SHA256 Checksum Verification & Asset Replacement
                testMatchingChecksumReplacesAsset(File(baseTempDir, "case1_match"))
                testMismatchingChecksumDiscardsCorruptDownload(File(baseTempDir, "case1_mismatch"))
                testEmptyAndWhitespaceChecksumRejected(File(baseTempDir, "case1_empty"))
                testMalformedAndNonHexChecksumRejected(File(baseTempDir, "case1_malformed"))
                testGnuSha256LineFormatParsing(File(baseTempDir, "case1_gnu_format"))
                testUppercaseSha256Normalized(File(baseTempDir, "case1_uppercase"))
                testIdenticalChecksumSkipsRedundantDownload(File(baseTempDir, "case1_identical"))

                // Suite 2: Network Failure Handling
                testHttp500PrimaryFallbackToSecondary(File(baseTempDir, "case2_fallback"))
                testHttp500DualMirrorFailure(File(baseTempDir, "case2_dual500"))
                testSocketTimeoutDuringChecksum(File(baseTempDir, "case2_timeout_sum"))
                testSocketTimeoutDuringDownload(File(baseTempDir, "case2_timeout_dl"))
                testNetworkDisconnectionUnknownHost(File(baseTempDir, "case2_disconn"))
                testTruncatedStreamFailure(File(baseTempDir, "case2_truncated"))

                // Suite 3: 24-Hour Throttling & Force Bypass
                test24HourThrottlingAndForce(File(baseTempDir, "case3_throttling"))

                // Suite 4: PreferencesManager Integration
                testPreferencesManagerIntegration(File(baseTempDir, "case4_prefs"))

                // Suite 5: Concurrency & Stress Testing
                testConcurrentUpdateStress(File(baseTempDir, "case5_concurrency"))

            } finally {
                baseTempDir.deleteRecursively()
            }

            println("\n================================================================================")
            println("   TEST SUITE EXECUTION COMPLETE: $testsPassed PASSED, $testsFailed FAILED")
            println("================================================================================")

            if (testsFailed > 0) {
                throw AssertionError("$testsFailed tests failed")
            }
        }

        // =====================================================================
        // SUITE 1: SHA256 Checksum Verification & Asset Replacement
        // =====================================================================

        private suspend fun testMatchingChecksumReplacesAsset(workDir: File) {
            println("\n--- SUITE 1.1: Matching SHA256 Checksum Replaces Asset Atomically ---")
            workDir.mkdirs()
            val prefs = MockSharedPreferences()
            val context = createMockContext(workDir, prefs)

            val assetDir = GeoAssetManager.getAssetDir(context)
            val geoipFile = File(assetDir, "geoip.dat")
            val geositeFile = File(assetDir, "geosite.dat")

            // Initialize baseline asset with old content
            val oldContent = "OLD_GEOIP_DATA_V1".toByteArray(Charsets.UTF_8)
            geoipFile.writeBytes(oldContent)
            geositeFile.writeBytes(oldContent)

            val newGeoipData = "NEW_CHOCOLATE4IRAN_GEOIP_2026_NOVEMBER".toByteArray(Charsets.UTF_8)
            val newGeositeData = "NEW_CHOCOLATE4IRAN_GEOSITE_2026_NOVEMBER".toByteArray(Charsets.UTF_8)
            val expectedGeoipHash = computeSha256(newGeoipData)
            val expectedGeositeHash = computeSha256(newGeositeData)

            val downloadCalls = AtomicInteger(0)

            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val url = chain.request().url.toString()
                when {
                    url.endsWith("geoip.dat.sha256sum") -> {
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(expectedGeoipHash.toResponseBody("text/plain".toMediaType()))
                            .build()
                    }
                    url.endsWith("geosite.dat.sha256sum") -> {
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(expectedGeositeHash.toResponseBody("text/plain".toMediaType()))
                            .build()
                    }
                    url.endsWith("geoip.dat") -> {
                        downloadCalls.incrementAndGet()
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(newGeoipData.toResponseBody("application/octet-stream".toMediaType()))
                            .build()
                    }
                    url.endsWith("geosite.dat") -> {
                        downloadCalls.incrementAndGet()
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(newGeositeData.toResponseBody("application/octet-stream".toMediaType()))
                            .build()
                    }
                    else -> error("Unexpected URL: $url")
                }
            }.build()

            injectHttpClient(client)

            val result = GeoAssetManager.checkAndUpdateRules(context, force = true)

            assertTrue("MatchingChecksum_ResultSuccess", result.isSuccess, "checkAndUpdateRules returned Result.success")
            assertTrue("MatchingChecksum_UpdatedTrue", result.getOrNull() == true, "Result value is true (assets updated)")
            assertTrue("MatchingChecksum_DownloadCalled", downloadCalls.get() == 2, "Both assets triggered download (got ${downloadCalls.get()})")
            assertTrue("MatchingChecksum_GeoipReplaced", geoipFile.readText() == "NEW_CHOCOLATE4IRAN_GEOIP_2026_NOVEMBER", "geoip.dat replaced with new content")
            assertTrue("MatchingChecksum_GeositeReplaced", geositeFile.readText() == "NEW_CHOCOLATE4IRAN_GEOSITE_2026_NOVEMBER", "geosite.dat replaced with new content")

            val geoipTmp = File(assetDir, "geoip.dat.tmp")
            val geositeTmp = File(assetDir, "geosite.dat.tmp")
            assertTrue("MatchingChecksum_TempFilesCleaned", !geoipTmp.exists() && !geositeTmp.exists(), "Temporary .tmp files were cleaned up after atomic rename")
        }

        private suspend fun testMismatchingChecksumDiscardsCorruptDownload(workDir: File) {
            println("\n--- SUITE 1.2: Mismatching Checksum Discards Corrupt Download & Preserves Original ---")
            workDir.mkdirs()
            val prefs = MockSharedPreferences()
            val context = createMockContext(workDir, prefs)

            val assetDir = GeoAssetManager.getAssetDir(context)
            val geoipFile = File(assetDir, "geoip.dat")

            val originalData = "ORIGINAL_UNTOUCHED_ASSET_DATA".toByteArray(Charsets.UTF_8)
            geoipFile.writeBytes(originalData)

            val claimedHash = "1111222233334444555566667777888899990000aaaabbbbccccddddeeeeffff"
            val corruptPayload = "MALICIOUS_OR_CORRUPT_DOWNLOAD_BYTES".toByteArray(Charsets.UTF_8)

            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val url = chain.request().url.toString()
                when {
                    url.contains("sha256sum") -> {
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(claimedHash.toResponseBody("text/plain".toMediaType()))
                            .build()
                    }
                    else -> {
                        // Return corrupt bytes whose hash does NOT match claimedHash
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(corruptPayload.toResponseBody("application/octet-stream".toMediaType()))
                            .build()
                    }
                }
            }.build()

            injectHttpClient(client)

            val result = GeoAssetManager.checkAndUpdateRules(context, force = true)

            assertTrue("Mismatch_ResultSuccess", result.isSuccess, "checkAndUpdateRules completed safely without throwing")
            assertTrue("Mismatch_UpdatedFalse", result.getOrNull() == false, "Result value is false (no assets updated due to corruption)")
            assertTrue("Mismatch_OriginalPreserved", geoipFile.readText() == "ORIGINAL_UNTOUCHED_ASSET_DATA", "Original geoip.dat asset file is strictly preserved")

            val geoipTmp = File(assetDir, "geoip.dat.tmp")
            assertTrue("Mismatch_TempFileDeleted", !geoipTmp.exists(), "Corrupted .tmp download file was immediately deleted")
        }

        private suspend fun testEmptyAndWhitespaceChecksumRejected(workDir: File) {
            println("\n--- SUITE 1.3: Empty & Whitespace Checksums Safely Rejected ---")
            workDir.mkdirs()
            val prefs = MockSharedPreferences()
            val context = createMockContext(workDir, prefs)

            val assetDir = GeoAssetManager.getAssetDir(context)
            val geoipFile = File(assetDir, "geoip.dat")
            geoipFile.writeBytes("BASELINE_DATA".toByteArray(Charsets.UTF_8))

            val downloadAttempts = AtomicInteger(0)

            val emptyResponses = listOf("", "   ", "\t\n\r  ", "   \n")

            for ((idx, emptyStr) in emptyResponses.withIndex()) {
                downloadAttempts.set(0)

                val client = OkHttpClient.Builder().addInterceptor { chain ->
                    val url = chain.request().url.toString()
                    if (url.contains("sha256sum")) {
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(emptyStr.toResponseBody("text/plain".toMediaType()))
                            .build()
                    } else {
                        downloadAttempts.incrementAndGet()
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("DOWNLOAD_PAYLOAD".toByteArray().toResponseBody("application/octet-stream".toMediaType()))
                            .build()
                    }
                }.build()

                injectHttpClient(client)

                val result = GeoAssetManager.checkAndUpdateRules(context, force = true)

                assertTrue("EmptyChecksum_${idx}_ZeroDownloads", downloadAttempts.get() == 0, "No downloads triggered for empty/whitespace checksum #$idx")
                assertTrue("EmptyChecksum_${idx}_NoUpdate", result.getOrNull() == false, "Result is false for empty/whitespace checksum #$idx")
                assertTrue("EmptyChecksum_${idx}_AssetUntouched", geoipFile.readText() == "BASELINE_DATA", "Asset untouched for case #$idx")
            }
        }

        private suspend fun testMalformedAndNonHexChecksumRejected(workDir: File) {
            println("\n--- SUITE 1.4: Malformed & Non-Hex Checksums Safely Rejected ---")
            workDir.mkdirs()
            val prefs = MockSharedPreferences()
            val context = createMockContext(workDir, prefs)

            val assetDir = GeoAssetManager.getAssetDir(context)
            val geoipFile = File(assetDir, "geoip.dat")
            geoipFile.writeBytes("SAFE_LOCAL_DATA".toByteArray(Charsets.UTF_8))

            val malformedCases = mapOf(
                "TooShort_63Hex" to "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b85", // 63 chars
                "TooLong_65Hex" to "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b8555", // 65 chars
                "InvalidChars_NonHex" to "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b85g", // contains 'g'
                "HtmlErrorPayload" to "<!DOCTYPE html><html><body><h1>404 Not Found</h1></body></html>",
                "InjectionPayload" to "; rm -rf /; echo deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef ;",
                "GarbageBytes" to "\u0000\u0001\u0002\u0003\uffff"
            )

            for ((testName, badHash) in malformedCases) {
                val downloadAttempts = AtomicInteger(0)

                val client = OkHttpClient.Builder().addInterceptor { chain ->
                    val url = chain.request().url.toString()
                    if (url.contains("sha256sum")) {
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(badHash.toResponseBody("text/plain".toMediaType()))
                            .build()
                    } else {
                        downloadAttempts.incrementAndGet()
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("DOWNLOAD_DATA".toByteArray().toResponseBody("application/octet-stream".toMediaType()))
                            .build()
                    }
                }.build()

                injectHttpClient(client)

                val result = GeoAssetManager.checkAndUpdateRules(context, force = true)

                assertTrue("MalformedCheck_$testName", downloadAttempts.get() == 0, "Rejected malformed hash '$testName' with 0 download attempts")
                assertTrue("MalformedResult_$testName", result.getOrNull() == false, "Returned false for '$testName'")
                assertTrue("AssetPreserved_$testName", geoipFile.readText() == "SAFE_LOCAL_DATA", "Local asset preserved for '$testName'")
            }
        }

        private suspend fun testGnuSha256LineFormatParsing(workDir: File) {
            println("\n--- SUITE 1.5: Standard GNU sha256sum Format Parsing ---")
            workDir.mkdirs()
            val prefs = MockSharedPreferences()
            val context = createMockContext(workDir, prefs)

            val assetDir = GeoAssetManager.getAssetDir(context)
            val geoipFile = File(assetDir, "geoip.dat")

            val payload = "UPDATED_FROM_GNU_FORMAT_OUTPUT".toByteArray(Charsets.UTF_8)
            val correctHash = computeSha256(payload)

            // Format standard output: "<hash>  <filename>\n"
            val gnuSha256Line = "$correctHash  geoip.dat\n"

            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val url = chain.request().url.toString()
                when {
                    url.contains("sha256sum") -> {
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(gnuSha256Line.toResponseBody("text/plain".toMediaType()))
                            .build()
                    }
                    else -> {
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(payload.toResponseBody("application/octet-stream".toMediaType()))
                            .build()
                    }
                }
            }.build()

            injectHttpClient(client)

            val result = GeoAssetManager.checkAndUpdateRules(context, force = true)

            assertTrue("GnuFormat_Success", result.isSuccess && result.getOrNull() == true, "Successfully parsed '<hash>  <filename>' format")
            assertTrue("GnuFormat_FileUpdated", geoipFile.readText() == "UPDATED_FROM_GNU_FORMAT_OUTPUT", "File updated after parsing GNU sha256sum format")
        }

        private suspend fun testUppercaseSha256Normalized(workDir: File) {
            println("\n--- SUITE 1.6: Uppercase SHA256 String Normalization ---")
            workDir.mkdirs()
            val prefs = MockSharedPreferences()
            val context = createMockContext(workDir, prefs)

            val assetDir = GeoAssetManager.getAssetDir(context)
            val geoipFile = File(assetDir, "geoip.dat")

            val payload = "UPPERCASE_HASH_TEST_PAYLOAD".toByteArray(Charsets.UTF_8)
            val lowerHash = computeSha256(payload)
            val upperHash = lowerHash.uppercase()

            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val url = chain.request().url.toString()
                when {
                    url.contains("sha256sum") -> {
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(upperHash.toResponseBody("text/plain".toMediaType()))
                            .build()
                    }
                    else -> {
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(payload.toResponseBody("application/octet-stream".toMediaType()))
                            .build()
                    }
                }
            }.build()

            injectHttpClient(client)

            val result = GeoAssetManager.checkAndUpdateRules(context, force = true)

            assertTrue("Uppercase_NormalizedAndAccepted", result.isSuccess && result.getOrNull() == true, "Uppercase hash parsed and normalized to lowercase")
            assertTrue("Uppercase_FileUpdated", geoipFile.readText() == "UPPERCASE_HASH_TEST_PAYLOAD", "File updated with uppercase hash response")
        }

        private suspend fun testIdenticalChecksumSkipsRedundantDownload(workDir: File) {
            println("\n--- SUITE 1.7: Matching Existing Checksum Skips Download ---")
            workDir.mkdirs()
            val prefs = MockSharedPreferences()
            val context = createMockContext(workDir, prefs)

            val assetDir = GeoAssetManager.getAssetDir(context)
            val geoipFile = File(assetDir, "geoip.dat")
            val geositeFile = File(assetDir, "geosite.dat")

            val currentData = "IDENTICAL_DATA_ALREADY_INSTALLED".toByteArray(Charsets.UTF_8)
            geoipFile.writeBytes(currentData)
            geositeFile.writeBytes(currentData)

            val currentHash = computeSha256(currentData)
            val downloadCalls = AtomicInteger(0)

            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val url = chain.request().url.toString()
                when {
                    url.contains("sha256sum") -> {
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(currentHash.toResponseBody("text/plain".toMediaType()))
                            .build()
                    }
                    else -> {
                        downloadCalls.incrementAndGet()
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(currentData.toResponseBody("application/octet-stream".toMediaType()))
                            .build()
                    }
                }
            }.build()

            injectHttpClient(client)

            // Run with force=false: local hash matches remote hash
            val result = GeoAssetManager.checkAndUpdateRules(context, force = false)

            assertTrue("IdenticalHash_ResultSuccess", result.isSuccess, "checkAndUpdateRules completed successfully")
            assertTrue("IdenticalHash_NoUpdateNeeded", result.getOrNull() == false, "Result is false (no update needed)")
            assertTrue("IdenticalHash_ZeroDownloads", downloadCalls.get() == 0, "Zero download calls made since hashes match")
        }

        // =====================================================================
        // SUITE 2: Network Failure Handling
        // =====================================================================

        private suspend fun testHttp500PrimaryFallbackToSecondary(workDir: File) {
            println("\n--- SUITE 2.1: HTTP 500 on Primary Mirror Falls Back to Secondary Mirror ---")
            workDir.mkdirs()
            val prefs = MockSharedPreferences()
            val context = createMockContext(workDir, prefs)

            val assetDir = GeoAssetManager.getAssetDir(context)
            val geoipFile = File(assetDir, "geoip.dat")
            geoipFile.writeBytes("INITIAL_DATA".toByteArray())

            val fallbackPayload = "DATA_FROM_GITHUB_FALLBACK".toByteArray(Charsets.UTF_8)
            val fallbackHash = computeSha256(fallbackPayload)

            val primaryCalls = AtomicInteger(0)
            val fallbackCalls = AtomicInteger(0)

            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val url = chain.request().url.toString()
                if (url.contains("jsdelivr")) {
                    primaryCalls.incrementAndGet()
                    // Primary CDN mirror fails with HTTP 500
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(500)
                        .message("CDN Internal Server Error")
                        .body("CDN Server Error".toResponseBody("text/plain".toMediaType()))
                        .build()
                } else if (url.contains("githubusercontent")) {
                    fallbackCalls.incrementAndGet()
                    // Secondary GitHub Raw mirror succeeds
                    if (url.contains("sha256sum")) {
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(fallbackHash.toResponseBody("text/plain".toMediaType()))
                            .build()
                    } else {
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(fallbackPayload.toResponseBody("application/octet-stream".toMediaType()))
                            .build()
                    }
                } else {
                    error("Unknown host in $url")
                }
            }.build()

            injectHttpClient(client)

            val result = GeoAssetManager.checkAndUpdateRules(context, force = true)

            assertTrue("Primary500_PrimaryAttempted", primaryCalls.get() > 0, "Primary jsDelivr mirror was queried first (${primaryCalls.get()} attempts)")
            assertTrue("Primary500_FallbackAttempted", fallbackCalls.get() > 0, "Secondary GitHub mirror was queried upon primary 500 (${fallbackCalls.get()} attempts)")
            assertTrue("Primary500_FallbackSucceeded", result.isSuccess && result.getOrNull() == true, "Update succeeded through secondary fallback mirror")
            assertTrue("Primary500_FileUpdated", geoipFile.readText() == "DATA_FROM_GITHUB_FALLBACK", "File updated with payload from secondary mirror")
        }

        private suspend fun testHttp500DualMirrorFailure(workDir: File) {
            println("\n--- SUITE 2.2: HTTP 500 on Both Mirrors Safely Handled ---")
            workDir.mkdirs()
            val prefs = MockSharedPreferences()
            val context = createMockContext(workDir, prefs)

            val assetDir = GeoAssetManager.getAssetDir(context)
            val geoipFile = File(assetDir, "geoip.dat")
            geoipFile.writeBytes("SAFE_EXISTING_ASSET".toByteArray())

            val client = OkHttpClient.Builder().addInterceptor { chain ->
                // Both mirrors return HTTP 500
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(500)
                    .message("Internal Server Error")
                    .body("All mirrors 500".toResponseBody("text/plain".toMediaType()))
                    .build()
            }.build()

            injectHttpClient(client)

            val result = GeoAssetManager.checkAndUpdateRules(context, force = true)

            assertTrue("Dual500_SafeResult", result.isSuccess, "checkAndUpdateRules completed safely without throwing unhandled exception")
            assertTrue("Dual500_NoUpdate", result.getOrNull() == false, "Result is false when all mirrors fail")
            assertTrue("Dual500_AssetPreserved", geoipFile.readText() == "SAFE_EXISTING_ASSET", "Existing asset preserved intact")
        }

        private suspend fun testSocketTimeoutDuringChecksum(workDir: File) {
            println("\n--- SUITE 2.3: Socket Timeout During Checksum Safely Caught ---")
            workDir.mkdirs()
            val prefs = MockSharedPreferences()
            val context = createMockContext(workDir, prefs)

            val assetDir = GeoAssetManager.getAssetDir(context)
            val geoipFile = File(assetDir, "geoip.dat")
            geoipFile.writeBytes("PRESERVED_ASSET".toByteArray())

            val client = OkHttpClient.Builder().addInterceptor { chain ->
                throw SocketTimeoutException("Simulated connection timeout to " + chain.request().url)
            }.build()

            injectHttpClient(client)

            val result = GeoAssetManager.checkAndUpdateRules(context, force = true)

            assertTrue("TimeoutChecksum_Safe", result.isSuccess, "SocketTimeoutException during checksum fetch caught safely")
            assertTrue("TimeoutChecksum_ResultFalse", result.getOrNull() == false, "Result is false on timeout")
            assertTrue("TimeoutChecksum_AssetIntact", geoipFile.readText() == "PRESERVED_ASSET", "Original asset preserved during network timeout")
        }

        private suspend fun testSocketTimeoutDuringDownload(workDir: File) {
            println("\n--- SUITE 2.4: Socket Timeout During Download Stream Safely Cleaned Up ---")
            workDir.mkdirs()
            val prefs = MockSharedPreferences()
            val context = createMockContext(workDir, prefs)

            val assetDir = GeoAssetManager.getAssetDir(context)
            val geoipFile = File(assetDir, "geoip.dat")
            geoipFile.writeBytes("ORIGINAL_FILE_CONTENT".toByteArray())

            val validHash = "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"

            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val url = chain.request().url.toString()
                if (url.contains("sha256sum")) {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(validHash.toResponseBody("text/plain".toMediaType()))
                        .build()
                } else {
                    // Timeout during download call
                    throw SocketTimeoutException("Read timeout on download socket: $url")
                }
            }.build()

            injectHttpClient(client)

            val result = GeoAssetManager.checkAndUpdateRules(context, force = true)

            assertTrue("TimeoutDownload_Safe", result.isSuccess, "SocketTimeoutException during download caught safely")
            assertTrue("TimeoutDownload_ResultFalse", result.getOrNull() == false, "Result is false when download times out")
            assertTrue("TimeoutDownload_FileIntact", geoipFile.readText() == "ORIGINAL_FILE_CONTENT", "Original asset intact")

            val geoipTmp = File(assetDir, "geoip.dat.tmp")
            assertTrue("TimeoutDownload_TempCleaned", !geoipTmp.exists(), "Partial .tmp file cleaned up on timeout")
        }

        private suspend fun testNetworkDisconnectionUnknownHost(workDir: File) {
            println("\n--- SUITE 2.5: Network Disconnection (UnknownHostException) Safely Handled ---")
            workDir.mkdirs()
            val prefs = MockSharedPreferences()
            val context = createMockContext(workDir, prefs)

            val assetDir = GeoAssetManager.getAssetDir(context)
            val geoipFile = File(assetDir, "geoip.dat")
            geoipFile.writeBytes("OFFLINE_PRESERVED_DATA".toByteArray())

            val client = OkHttpClient.Builder().addInterceptor { chain ->
                throw UnknownHostException("Unable to resolve host: " + chain.request().url.host)
            }.build()

            injectHttpClient(client)

            val result = GeoAssetManager.checkAndUpdateRules(context, force = true)

            assertTrue("Disconnection_Safe", result.isSuccess, "Network disconnection handled safely without crash")
            assertTrue("Disconnection_ResultFalse", result.getOrNull() == false, "Result is false when disconnected")
            assertTrue("Disconnection_AssetPreserved", geoipFile.readText() == "OFFLINE_PRESERVED_DATA", "Local asset completely preserved when offline")
        }

        private suspend fun testTruncatedStreamFailure(workDir: File) {
            println("\n--- SUITE 2.6: Truncated Download Stream Detected by SHA256 Check ---")
            workDir.mkdirs()
            val prefs = MockSharedPreferences()
            val context = createMockContext(workDir, prefs)

            val assetDir = GeoAssetManager.getAssetDir(context)
            val geoipFile = File(assetDir, "geoip.dat")
            geoipFile.writeBytes("BASELINE_FULL_ASSET".toByteArray())

            val fullExpectedPayload = "THIS_IS_A_VERY_LONG_PAYLOAD_THAT_WILL_BE_CUT_SHORT_AT_HALF".toByteArray(Charsets.UTF_8)
            val expectedHash = computeSha256(fullExpectedPayload)

            // Server only delivers first 10 bytes instead of full payload
            val truncatedBytes = fullExpectedPayload.copyOfRange(0, 10)

            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val url = chain.request().url.toString()
                if (url.contains("sha256sum")) {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(expectedHash.toResponseBody("text/plain".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(truncatedBytes.toResponseBody("application/octet-stream".toMediaType()))
                        .build()
                }
            }.build()

            injectHttpClient(client)

            val result = GeoAssetManager.checkAndUpdateRules(context, force = true)

            assertTrue("Truncated_SafeResult", result.isSuccess && result.getOrNull() == false, "Truncated stream caught by hash verification and rejected")
            assertTrue("Truncated_AssetPreserved", geoipFile.readText() == "BASELINE_FULL_ASSET", "Original asset not overwritten by truncated payload")

            val geoipTmp = File(assetDir, "geoip.dat.tmp")
            assertTrue("Truncated_TempDeleted", !geoipTmp.exists(), "Truncated .tmp file deleted")
        }

        // =====================================================================
        // SUITE 3: 24-Hour Throttling & Force Bypass Logic
        // =====================================================================

        private suspend fun test24HourThrottlingAndForce(workDir: File) {
            println("\n--- SUITE 3: 24-Hour Throttling & Force Bypass Verification ---")
            workDir.mkdirs()
            val prefs = MockSharedPreferences()
            val context = createMockContext(workDir, prefs)

            val requestsCount = AtomicInteger(0)
            val dummyHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

            val client = OkHttpClient.Builder().addInterceptor { chain ->
                requestsCount.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(dummyHash.toResponseBody("text/plain".toMediaType()))
                    .build()
            }.build()

            injectHttpClient(client)

            val now = System.currentTimeMillis()

            // 1. Initial run: lastGeoRulesCheckTime = 0
            prefs.edit().putLong("last_geo_rules_check_timestamp", 0L).apply()
            requestsCount.set(0)

            val r1 = GeoAssetManager.checkAndUpdateRules(context, force = false)
            assertTrue("Throttle_InitialRunExecuted", requestsCount.get() > 0, "First run with lastCheck=0 triggers network queries (${requestsCount.get()} requests)")
            val savedTime1 = prefs.getLong("last_geo_rules_check_timestamp", 0L)
            assertTrue("Throttle_TimestampUpdated", savedTime1 >= now - 1000, "prefs.lastGeoRulesCheckTime was updated to ~now ($savedTime1)")

            // 2. Subsequent run 10 minutes later with force=false -> must return false with ZERO network requests
            prefs.edit().putLong("last_geo_rules_check_timestamp", System.currentTimeMillis() - (10 * 60 * 1000L)).apply()
            requestsCount.set(0)

            val r2 = GeoAssetManager.checkAndUpdateRules(context, force = false)
            assertTrue("Throttle_SubsequentSkipped", r2.getOrNull() == false, "Check 10m later returns Result.success(false)")
            assertTrue("Throttle_ZeroNetworkCalls10m", requestsCount.get() == 0, "ZERO network requests made within 24h check (actual: ${requestsCount.get()})")

            // 3. Subsequent run 23 hours later with force=false -> still throttled
            prefs.edit().putLong("last_geo_rules_check_timestamp", System.currentTimeMillis() - (23 * 60 * 60 * 1000L)).apply()
            requestsCount.set(0)

            val r3 = GeoAssetManager.checkAndUpdateRules(context, force = false)
            assertTrue("Throttle_23hSkipped", r3.getOrNull() == false, "Check 23h later returns false")
            assertTrue("Throttle_ZeroNetworkCalls23h", requestsCount.get() == 0, "ZERO network requests made at 23h mark")

            // 4. Subsequent run with force=true -> bypasses 24h throttle
            prefs.edit().putLong("last_geo_rules_check_timestamp", System.currentTimeMillis() - (5 * 1000L)).apply() // 5 seconds ago
            requestsCount.set(0)

            val r4 = GeoAssetManager.checkAndUpdateRules(context, force = true)
            assertTrue("Throttle_ForcedBypass", requestsCount.get() > 0, "force=true successfully bypassed 24h throttle (${requestsCount.get()} requests)")

            // 5. Run after 24 hours + 10 minutes -> throttle expired, queries executed
            prefs.edit().putLong("last_geo_rules_check_timestamp", System.currentTimeMillis() - (24 * 60 * 60 * 1000L + 10 * 60 * 1000L)).apply()
            requestsCount.set(0)

            val r5 = GeoAssetManager.checkAndUpdateRules(context, force = false)
            assertTrue("Throttle_ExpiredExecuted", requestsCount.get() > 0, "Check after >24h executes network queries (${requestsCount.get()} requests)")

            // 6. System clock jumped backwards into the future
            prefs.edit().putLong("last_geo_rules_check_timestamp", System.currentTimeMillis() + (2 * 60 * 60 * 1000L)).apply()
            requestsCount.set(0)

            val r6 = GeoAssetManager.checkAndUpdateRules(context, force = false)
            assertTrue("Throttle_ClockJumpHandled", requestsCount.get() > 0, "Clock skew backward (now < lastCheck) does not freeze updates (${requestsCount.get()} requests)")
        }

        // =====================================================================
        // SUITE 4: PreferencesManager Integration
        // =====================================================================

        private fun testPreferencesManagerIntegration(workDir: File) {
            println("\n--- SUITE 4: PreferencesManager Integration ---")
            workDir.mkdirs()
            val prefs = MockSharedPreferences()
            val context = createMockContext(workDir, prefs)

            val mgr = PreferencesManager(context)

            // Default value should be 0L
            assertTrue("Prefs_DefaultZero", mgr.lastGeoRulesCheckTime == 0L, "Default lastGeoRulesCheckTime is 0L (got: ${mgr.lastGeoRulesCheckTime})")

            // Write and read
            val testTimestamp = 1794267437000L
            mgr.lastGeoRulesCheckTime = testTimestamp
            assertTrue("Prefs_WriteReadRoundtrip", mgr.lastGeoRulesCheckTime == testTimestamp, "lastGeoRulesCheckTime write and read roundtrip match")

            // Persisted in underlying SharedPreferences
            assertTrue("Prefs_UnderlyingStored", prefs.getLong("last_geo_rules_check_timestamp", -1L) == testTimestamp, "Stored under key 'last_geo_rules_check_timestamp'")

            // clearAll resets
            mgr.clearAll()
            assertTrue("Prefs_ClearAllResets", mgr.lastGeoRulesCheckTime == 0L, "clearAll() resets lastGeoRulesCheckTime back to 0L")
        }

        // =====================================================================
        // SUITE 5: Concurrency & Stress Testing
        // =====================================================================

        private suspend fun testConcurrentUpdateStress(workDir: File) {
            println("\n--- SUITE 5: Concurrency Stress Test (20 Coroutines Simultaneous checkAndUpdateRules) ---")
            workDir.mkdirs()
            val prefs = MockSharedPreferences()
            val context = createMockContext(workDir, prefs)

            val assetDir = GeoAssetManager.getAssetDir(context)
            val geoipFile = File(assetDir, "geoip.dat")
            val geositeFile = File(assetDir, "geosite.dat")

            val initialBytes = "CONCURRENT_BASELINE".toByteArray()
            geoipFile.writeBytes(initialBytes)
            geositeFile.writeBytes(initialBytes)

            val targetPayload = "CONCURRENT_FINAL_PAYLOAD_VALID_2026".toByteArray(Charsets.UTF_8)
            val targetHash = computeSha256(targetPayload)

            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val url = chain.request().url.toString()
                if (url.contains("sha256sum")) {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(targetHash.toResponseBody("text/plain".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(targetPayload.toResponseBody("application/octet-stream".toMediaType()))
                        .build()
                }
            }.build()

            injectHttpClient(client)

            val coroutineCount = 20
            val failures = AtomicInteger(0)
            val startBarrier = CompletableDeferred<Unit>()

            val jobs = (1..coroutineCount).map { id ->
                CoroutineScope(Dispatchers.Default).async {
                    startBarrier.await()
                    try {
                        val res = GeoAssetManager.checkAndUpdateRules(context, force = true)
                        if (!res.isSuccess) {
                            failures.incrementAndGet()
                        }
                    } catch (t: Throwable) {
                        t.printStackTrace()
                        failures.incrementAndGet()
                    }
                }
            }

            // Release all 20 coroutines at the exact same millisecond
            startBarrier.complete(Unit)
            jobs.awaitAll()

            assertTrue("Concurrent_ZeroFailures", failures.get() == 0, "All 20 concurrent update coroutines completed without uncaught exceptions")
            assertTrue("Concurrent_GeoipValid", geoipFile.readText() == "CONCURRENT_FINAL_PAYLOAD_VALID_2026", "geoip.dat contains valid updated content after concurrent race")
            assertTrue("Concurrent_GeositeValid", geositeFile.readText() == "CONCURRENT_FINAL_PAYLOAD_VALID_2026", "geosite.dat contains valid updated content after concurrent race")

            val geoipTmp = File(assetDir, "geoip.dat.tmp")
            val geositeTmp = File(assetDir, "geosite.dat.tmp")
            assertTrue("Concurrent_NoOrphanTemps", !geoipTmp.exists() && !geositeTmp.exists(), "No orphan temp files remain after concurrent execution")
        }
    }
}

/**
 * Lightweight mock Context implementation for empirical testing.
 */
class MockContext : android.content.Context() {
    lateinit var testDir: File
    lateinit var testPrefs: SharedPreferences

    override fun getDir(name: String?, mode: Int): File {
        val d = File(testDir, name ?: "default")
        d.mkdirs()
        return d
    }

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
        return testPrefs
    }

    override fun getPackageName(): String = "com.asanvpn.app"
}

/**
 * Thread-safe in-memory SharedPreferences implementation for unit and adversarial testing.
 */
class MockSharedPreferences : SharedPreferences {
    val map = ConcurrentHashMap<String, Any>()

    override fun getAll(): MutableMap<String, *> = HashMap(map)
    override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = (map[key] as? Set<String>)?.toMutableSet() ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = map.containsKey(key)
    override fun edit(): SharedPreferences.Editor = MockEditor(map)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    class MockEditor(private val map: ConcurrentHashMap<String, Any>) : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private var clear = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor { if (key != null) pending[key] = value; return this }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor { if (key != null) pending[key] = values; return this }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor { if (key != null) pending[key] = value; return this }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor { if (key != null) pending[key] = value; return this }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { if (key != null) pending[key] = value; return this }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { if (key != null) pending[key] = value; return this }
        override fun remove(key: String?): SharedPreferences.Editor { if (key != null) pending[key] = this; return this }
        override fun clear(): SharedPreferences.Editor { clear = true; return this }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clear) map.clear()
            for ((k, v) in pending) {
                if (v === this) map.remove(k)
                else if (v != null) map[k] = v
            }
        }
    }
}
