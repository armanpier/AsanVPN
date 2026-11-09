package com.asanvpn.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import com.asanvpn.app.data.models.ConnectRequest
import com.asanvpn.app.data.models.ConnectResponse
import com.asanvpn.app.data.models.UserInfo
import com.asanvpn.app.data.models.VpnNode
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.TimeUnit

class AuthRevokedException(val status: String, override val message: String) : Exception(message)

object CloudflareEdgeTester {
    private val candidateIps = listOf(
        "162.159.153.4",
        "162.159.152.4",
        "162.159.138.1",
        "162.159.143.1",
        "141.101.121.1",
        "141.101.122.1",
        "188.114.97.7",
        "188.114.96.7",
        "104.16.132.229",
        "104.16.133.229",
        "198.41.214.162",
        "108.162.193.1"
    )

    @Volatile
    private var cachedWorkingIps: List<InetAddress>? = null
    private var lastCheckTime = 0L

    fun getWorkingCloudflareIps(hostname: String): List<InetAddress> {
        val now = System.currentTimeMillis()
        val cached = cachedWorkingIps
        if (cached != null && now - lastCheckTime < 180_000L) {
            return cached
        }

        val responsive = Collections.synchronizedList(mutableListOf<Pair<String, Long>>())
        val threads = candidateIps.map { ip ->
            Thread {
                val start = System.currentTimeMillis()
                try {
                    Socket().use { s ->
                        s.connect(InetSocketAddress(ip, 443), 400)
                        val elapsed = System.currentTimeMillis() - start
                        responsive.add(Pair(ip, elapsed))
                    }
                } catch (_: Exception) {}
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(450) }

        val sortedIps = if (responsive.isNotEmpty()) {
            responsive.sortedBy { it.second }.map { it.first }
        } else {
            listOf("162.159.153.4", "162.159.138.1", "141.101.121.1", "188.114.97.7")
        }

        val result = sortedIps.mapNotNull { ipStr ->
            try {
                InetAddress.getByAddress(hostname, InetAddress.getByName(ipStr).address)
            } catch (_: Exception) {
                null
            }
        }

        cachedWorkingIps = result
        lastCheckTime = now
        return result
    }
}

class VpnRepository(private val context: Context) {

    private val prefs = PreferencesManager(context)
    private val gson = Gson()

    companion object {
        var lastDebugLog: String = "هنوز خطایی ثبت نشده است."

        fun getBootstrapNodes(): List<VpnNode> {
            return listOf(
                VpnNode(
                    protocol = "vless",
                    remark = "🇳🇴 Norway (سرور راه‌انداز)",
                    server = "netshield.officialvpn.shop",
                    port = 20125,
                    uuid = "7e1a90d4-1f7c-48be-9b3f-1d8f6d7a4c9e",
                    type = "tcp",
                    security = "reality",
                    sni = "speedtest.net",
                    pbk = "1A8VfHw1l9sN0pX8zK3mQ7rT2vY4bC6xZ9jL5wP8dE",
                    sid = "6a",
                    spx = "/",
                    flow = "xtls-rprx-vision"
                ),
                VpnNode(
                    protocol = "vless",
                    remark = "🇨🇿 Czechia (سرور راه‌انداز)",
                    server = "netshield.officialvpn.shop",
                    port = 20014,
                    uuid = "7e1a90d4-1f7c-48be-9b3f-1d8f6d7a4c9e",
                    type = "tcp",
                    security = "reality",
                    sni = "speedtest.net",
                    pbk = "1A8VfHw1l9sN0pX8zK3mQ7rT2vY4bC6xZ9jL5wP8dE",
                    sid = "6a",
                    spx = "/",
                    flow = "xtls-rprx-vision"
                )
            )
        }
    }

    /**
     * Resilient DNS that uses tested clean Cloudflare Edge IPs to bypass Iranian ISP poisoning
     */
    private class ResilientDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (hostname.contains("workers.dev") || hostname.contains("hi-deploy") ||
                hostname == "spareone.icu" || hostname == "onthepoint.live") {
                val cleanIps = CloudflareEdgeTester.getWorkingCloudflareIps(hostname)
                if (cleanIps.isNotEmpty()) {
                    return cleanIps
                }
            }

            return try {
                val systemAddresses = Dns.SYSTEM.lookup(hostname)
                val cleanSystem = systemAddresses.filter { addr ->
                    val host = addr.hostAddress ?: ""
                    !host.startsWith("10.10.") && host != "127.0.0.1" && host != "0.0.0.0"
                }
                if (cleanSystem.isNotEmpty()) cleanSystem else CloudflareEdgeTester.getWorkingCloudflareIps(hostname)
            } catch (e: Exception) {
                CloudflareEdgeTester.getWorkingCloudflareIps(hostname)
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .dns(ResilientDns())
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // Real working endpoints: primary worker + custom domain routes
    private val workerEndpoints = listOf(
        "https://asanvpn-worker.hi-deploy.workers.dev",
        "https://spareone.icu",
        "https://onthepoint.live"
    )

    @SuppressLint("HardwareIds")
    fun getDeviceId(): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN_DEVICE"
    }

    suspend fun syncWithWorker(pin: String): Result<ConnectResponse> = withContext(Dispatchers.IO) {
        val deviceId = getDeviceId()
        val requestBody = gson.toJson(ConnectRequest(pin = pin, deviceId = deviceId))
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val debugLogs = StringBuilder()
        debugLogs.append("Device ID: $deviceId\n")
        debugLogs.append("Entered PIN: $pin\n\n")

        var isPinRejected = false
        var rejectionMessageFa: String? = null

        for (baseUrl in workerEndpoints) {
            val url = "$baseUrl/api/v1/connect"
            debugLogs.append("Trying: $url ... ")
            try {
                val req = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .header("User-Agent", "AsanVPN-Android/1.0.0")
                    .build()

                client.newCall(req).execute().use { response ->
                    val responseStr = response.body?.string() ?: ""
                    debugLogs.append("HTTP ${response.code}\nBody: $responseStr\n")

                    val parsed = try {
                        gson.fromJson(responseStr, ConnectResponse::class.java)
                    } catch (e: Exception) {
                        null
                    }

                    if (response.isSuccessful && parsed?.status == "success") {
                        prefs.failedPinAttempts = 0
                        prefs.lockoutUntil = 0L
                        parsed.userInfo?.let { info ->
                            prefs.savedPin = pin
                            prefs.userName = info.name ?: "کاربر گرامی"
                            prefs.daysLeft = info.daysLeft ?: -1
                            if (info.trafficTotalBytes != null) {
                                prefs.trafficTotalBytes = info.trafficTotalBytes
                            }
                            if (info.trafficRemainingBytes != null) {
                                val rem = if (info.trafficTotalBytes != null && info.trafficTotalBytes <= 0L) -1L else info.trafficRemainingBytes
                                prefs.trafficRemainingBytes = rem
                            }
                            prefs.supportPhone = info.supportPhone ?: "+989120000000"
                            prefs.supportTelegram = info.supportTelegram ?: "AsanSupport"
                        }
                        if (!parsed.nodes.isNullOrEmpty()) {
                            prefs.cachedNodesJson = gson.toJson(parsed.nodes)
                        }
                        lastDebugLog = debugLogs.toString()
                        return@withContext Result.success(parsed)
                    } else if (response.code == 403 || parsed?.status == "invalid_pin" || parsed?.status == "disabled" || parsed?.status == "expired" || parsed?.status == "deleted" || parsed?.status == "device_locked") {
                        isPinRejected = true
                        rejectionMessageFa = parsed?.messageFa ?: "کد اشتراک نامعتبر است یا توسط مدیر غیرفعال/حذف شده است."
                        lastDebugLog = debugLogs.toString()
                        return@withContext Result.failure(AuthRevokedException(parsed?.status ?: "invalid_pin", rejectionMessageFa))
                    } else if (response.code == 429 || parsed?.status == "rate_limited") {
                        isPinRejected = true
                        val lockUntil = parsed?.lockedUntilTimestamp ?: (System.currentTimeMillis() + ((parsed?.retryAfterSeconds ?: 1800L) * 1000L))
                        prefs.lockoutUntil = lockUntil
                        prefs.failedPinAttempts = 3
                        rejectionMessageFa = parsed?.messageFa ?: "تعداد تلاش‌ها بیش از حد مجاز است. لطفاً ۳۰ دقیقه دیگر تلاش کنید."
                        lastDebugLog = debugLogs.toString()
                        return@withContext Result.failure(Exception(rejectionMessageFa))
                    } else {
                        debugLogs.append("Server returned non-success: ${response.code} / ${parsed?.status}\n")
                    }
                }
            } catch (e: Exception) {
                debugLogs.append("Failed: ${e.javaClass.simpleName} - ${e.message}\n")
            }
        }

        lastDebugLog = debugLogs.toString()

        if (isPinRejected) {
            Result.failure(AuthRevokedException("invalid_pin", rejectionMessageFa ?: "کد وارد شده نامعتبر است یا توسط مدیر حذف شده است."))
        } else {
            // General network failure (VPN is OFF and panel blocked by ISP):
            // Fallback 1: Cached subscription nodes from previous session
            val cachedJson = prefs.cachedNodesJson
            if (!cachedJson.isNullOrBlank()) {
                val cached = try {
                    gson.fromJson(cachedJson, Array<VpnNode>::class.java).toList()
                } catch (_: Exception) { null }
                if (!cached.isNullOrEmpty()) {
                    android.util.Log.i("VpnRepository", "Panel unreachable with VPN off: serving cached subscription nodes")
                    return@withContext Result.success(
                        ConnectResponse(
                            status = "success",
                            messageFa = null,
                            supportPhone = prefs.supportPhone,
                            supportTelegram = prefs.supportTelegram,
                            retryAfterSeconds = null,
                            lockedUntilTimestamp = null,
                            userInfo = UserInfo(
                                name = prefs.userName,
                                daysLeft = prefs.daysLeft,
                                trafficRemainingBytes = prefs.trafficRemainingBytes,
                                trafficTotalBytes = prefs.trafficTotalBytes,
                                supportPhone = prefs.supportPhone,
                                supportTelegram = prefs.supportTelegram
                            ),
                            nodes = cached,
                            xrayConfig = null,
                            singboxConfig = null
                        )
                    )
                }
            }

            // Fallback 2: Bootstrap proxies for first-time connection & PIN authentication
            android.util.Log.i("VpnRepository", "Panel unreachable with VPN off: serving bootstrap proxy to allow initial connection")
            val bootstrap = getBootstrapNodes()
            return@withContext Result.success(
                ConnectResponse(
                    status = "success",
                    messageFa = null,
                    supportPhone = prefs.supportPhone,
                    supportTelegram = prefs.supportTelegram,
                    retryAfterSeconds = null,
                    lockedUntilTimestamp = null,
                    userInfo = UserInfo(
                        name = prefs.userName,
                        daysLeft = prefs.daysLeft,
                        trafficRemainingBytes = prefs.trafficRemainingBytes,
                        trafficTotalBytes = prefs.trafficTotalBytes,
                        supportPhone = prefs.supportPhone,
                        supportTelegram = prefs.supportTelegram
                    ),
                    nodes = bootstrap,
                    xrayConfig = null,
                    singboxConfig = null
                )
            )
        }
    }
}
