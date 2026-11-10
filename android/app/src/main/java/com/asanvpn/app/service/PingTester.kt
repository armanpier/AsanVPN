package com.asanvpn.app.service

import android.content.Context
import android.util.Log
import com.asanvpn.app.data.models.VpnNode
import com.asanvpn.app.service.v2ray.V2RayConfigBuilder
import com.asanvpn.app.service.v2ray.V2RayCoreManager
import com.asanvpn.app.util.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import libv2ray.Libv2ray
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object PingTester {

    private const val TAG = "PingTester"
    private const val TEST_URL_PRIMARY = "https://cp.cloudflare.com/generate_204"
    private const val TEST_URL_FALLBACK = "https://www.gstatic.com/generate_204"

    /**
     * Connects IMMEDIATELY to the first verified working link (fast connect),
     * while continuing in the background to evaluate all candidates to switch
     * to the fastest one without keeping the user waiting.
     */
    /**
     * Priority-based Waterfall / Cascade Connect & Optimize:
     * 1. Groups candidate nodes strictly by panel priority (Priority 1 = highest, Priority 2 = next, etc.).
     * 2. Tests the highest-priority panel (Priority 1) first using REAL HTTP 204 tunnel delay.
     * 3. If ANY node in Priority 1 succeeds with a valid real delay (> 0):
     *    - Connects immediately to it (fast connect).
     *    - Optimizes within Priority 1 only.
     *    - HALTS the cascade completely (does NOT touch lower priority panels).
     * 4. If and ONLY if ALL nodes in Priority 1 fail/timeout (blocked/down):
     *    - Cascades to Priority 2, runs real delay testing on Priority 2 nodes.
     * 5. Repeats down the cascade for Priority 3, etc.
     */
    suspend fun connectFastAndOptimize(
        context: Context? = null,
        nodes: List<VpnNode>,
        onFirstOkNode: suspend (VpnNode) -> Unit,
        onFastestNodeFound: suspend (VpnNode) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (nodes.isEmpty()) return@withContext
        if (nodes.size == 1) {
            onFirstOkNode(nodes[0])
            onFastestNodeFound(nodes[0])
            return@withContext
        }

        context?.let { V2RayCoreManager.initCore(it) }

        val usableNodes = nodes.filter { isUsableEgressNode(it) }
        val candidatePool = if (usableNodes.isNotEmpty()) usableNodes else nodes

        // Group candidate nodes strictly by panel priority (1 = highest, 2, 3...)
        val priorityTiers = candidatePool
            .groupBy { it.panelPriority ?: 1 }
            .toSortedMap()

        val firstConnected = AtomicBoolean(false)
        val bestLatency = java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE)
        var bestNode: VpnNode? = null

        // WATERFALL CASCADE LOOP:
        // Always test Priority 1 first with increased patience/timeout. Only cascade to Priority 2/3 if Priority 1 completely fails.
        for ((tier, tierNodes) in priorityTiers) {
            val tierName = tierNodes.firstOrNull()?.panelName ?: "پنل اولویت $tier"
            val isTopPriority = (tier == priorityTiers.firstKey())
            val tcpTimeout = if (isTopPriority) 2500 else 1200
            val realDelayTimeout = if (isTopPriority) 7000L else 3500L

            Log.i(TAG, "==> [Priority Cascade] Testing Priority Tier $tier ($tierName) with ${tierNodes.size} candidate nodes (timeout: ${realDelayTimeout}ms)...")
            AppLogger.logUser("🔍 بررسی سرورهای $tierName...")
            AppLogger.logAdmin("Testing Tier $tier ($tierName) with ${tierNodes.size} nodes")

            // Parallel TCP prescan for this tier to identify responsive endpoints
            val tcpResults = tierNodes.map { node ->
                async {
                    val tcp = testTcpLatency(node.server, node.port, tcpTimeout)
                    Pair(node, tcp)
                }
            }.awaitAll()

            val responsive = tcpResults.filter { it.second > 0 }.sortedBy { it.second }.map { it.first }
            val tierCandidatesToTest = if (responsive.isNotEmpty()) responsive.take(10) else tierNodes.take(10)

            // Test Real Delay via Libv2ray HTTP 204 on this tier's candidates with generous patience for top tier
            coroutineScope {
                tierCandidatesToTest.forEach { node ->
                    launch {
                        val realDelay = measureRealDelay(node, realDelayTimeout)
                        node.latencyMs = realDelay
                        if (realDelay in 1 until bestLatency.get()) {
                            Log.i(TAG, "Tier $tier Verified OK: [${node.remark}] -> REAL latency: ${realDelay}ms")
                            AppLogger.logLinkTest(node.remark, realDelay, isSuccess = true)

                            if (firstConnected.compareAndSet(false, true)) {
                                bestNode = node
                                bestLatency.set(realDelay)
                                Log.i(TAG, ">> [Cascade SUCCESS] Connecting immediately to Priority Tier $tier server [${node.remark}] (${realDelay}ms)")
                                AppLogger.logUser("🚀 انتخاب سرور پایدار [${node.remark}]")
                                AppLogger.logAdmin("Cascade SUCCESS. Assigned node [${node.remark}]")
                                onFirstOkNode(node)
                            } else if (realDelay < bestLatency.get() - 25L) {
                                val prevRemark = bestNode?.remark ?: "unknown"
                                val prevLat = bestLatency.get()
                                bestNode = node
                                bestLatency.set(realDelay)
                                Log.i(TAG, ">> [Cascade OPTIMIZE] Faster server found in Priority Tier $tier [${node.remark}] (${realDelay}ms vs $prevRemark ${prevLat}ms) -> Switching")
                                AppLogger.logUser("⚡ تعویض به سرور سریع‌تر: [${node.remark}] (${realDelay}ms)")
                                AppLogger.logAdmin("Cascade OPTIMIZE: [${node.remark}] (${realDelay}ms vs $prevRemark ${prevLat}ms)")
                                onFastestNodeFound(node)
                            }
                        } else {
                            Log.d(TAG, "Tier $tier candidate failed: [${node.remark}]")
                            AppLogger.logLinkTest(node.remark, -1L, isSuccess = false)
                        }
                    }
                }
            }

            // If ANY node in this priority tier succeeded, stop the cascade!
            if (firstConnected.get()) {
                Log.i(TAG, ">> [Cascade HALTED] Connected to working server in Priority Tier $tier ($tierName). Cascade complete.")
                AppLogger.logUser("✅ اتصال به سرورهای $tierName نهایی شد")
                AppLogger.logAdmin(">> [Cascade HALTED] Tier $tier successful.")
                return@withContext
            }

            Log.w(TAG, ">> [Cascade FAILOVER] All candidate nodes in Priority Tier $tier failed real delay test! Cascading to next priority tier...")
            AppLogger.logUser("⚠️ سرورهای $tierName پاسخ ندادند. در حال بررسی اولویت بعدی...")
            AppLogger.logAdmin(">> [Cascade FAILOVER] Tier $tier timed out. Cascading to next tier.")
        }

        // If ALL priority tiers failed real delay test, fallback to first candidate of highest priority tier
        if (firstConnected.compareAndSet(false, true)) {
            val fallback = candidatePool[0]
            Log.w(TAG, ">> [Cascade EXHAUSTED] No node in any tier passed real delay; using emergency fallback [${fallback.remark}]")
            onFirstOkNode(fallback)
        }
    }

    /**
     * Finds the fastest, verified working proxy using REALTIME HTTP roundtrip latency
     * through the V2Ray/Xray tunnel rather than a simple TCP handshake, adhering strictly
     * to panel waterfall priority.
     */
    suspend fun findFastestNode(context: Context? = null, nodes: List<VpnNode>): VpnNode = withContext(Dispatchers.IO) {
        if (nodes.isEmpty()) throw IllegalArgumentException("Node list is empty")
        if (nodes.size == 1) return@withContext nodes[0]

        context?.let { V2RayCoreManager.initCore(it) }

        val usableNodes = nodes.filter { isUsableEgressNode(it) }
        val candidatePool = if (usableNodes.isNotEmpty()) usableNodes else nodes

        val priorityTiers = candidatePool
            .groupBy { it.panelPriority ?: 1 }
            .toSortedMap()

        val semaphore = Semaphore(6)

        // Waterfall cascade: test each priority tier in order
        for ((tier, tierNodes) in priorityTiers) {
            val tierName = tierNodes.firstOrNull()?.panelName ?: "اولویت $tier"
            val isTopPriority = (tier == priorityTiers.firstKey())
            val tcpTimeout = if (isTopPriority) 2500 else 1200
            val realDelayTimeout = if (isTopPriority) 7000L else 3500L

            Log.i(TAG, "findFastestNode: Checking Priority Tier $tier ($tierName, ${tierNodes.size} nodes, timeout: ${realDelayTimeout}ms)")

            val tcpResults = tierNodes.map { node ->
                async {
                    val tcp = testTcpLatency(node.server, node.port, tcpTimeout)
                    Pair(node, tcp)
                }
            }.awaitAll()

            val responsive = tcpResults.filter { it.second > 0 }.sortedBy { it.second }.map { it.first }
            val poolToTest = if (responsive.isNotEmpty()) responsive.take(8) else tierNodes.take(8)

            val realResults = poolToTest.map { node ->
                async {
                    semaphore.withPermit {
                        val realDelay = measureRealDelay(node, realDelayTimeout)
                        node.latencyMs = realDelay
                        Pair(node, realDelay)
                    }
                }
            }.awaitAll()

            val workingNode = realResults.filter { it.second > 0 }.minByOrNull { it.second }
            if (workingNode != null) {
                Log.i(TAG, "findFastestNode: Selected fastest verified proxy in Priority Tier $tier: [${workingNode.first.remark}] with REAL delay ${workingNode.second}ms")
                return@withContext workingNode.first
            }

            Log.w(TAG, "findFastestNode: All nodes in Priority Tier $tier failed real delay test. Cascading to next tier...")
        }

        val fallback = candidatePool[0]
        Log.w(TAG, "findFastestNode: No proxy passed real delay test across all tiers; using fallback [${fallback.remark}]")
        return@withContext fallback
    }

    /**
     * Backward compatible overload without context
     */
    suspend fun findFastestNode(nodes: List<VpnNode>): VpnNode {
        return findFastestNode(null, nodes)
    }

    /**
     * Measures true end-to-end HTTP round-trip latency through the proxy tunnel using Libv2ray.
     * Tries primary Cloudflare endpoint first, and automatically falls back to Google generate_204
     * if primary times out or is blocked.
     * Returns true latency in milliseconds if working, or -1L if broken/blocked.
     */
    suspend fun measureRealDelay(node: VpnNode, timeoutMs: Long = 3500L): Long = withContext(Dispatchers.IO) {
        val configJson = try {
            V2RayConfigBuilder.buildTestConfig(node)
        } catch (e: Exception) {
            return@withContext -1L
        }

        withTimeoutOrNull(timeoutMs) {
            // First attempt with primary test endpoint (Cloudflare)
            try {
                val delay1 = Libv2ray.measureOutboundDelay(configJson, TEST_URL_PRIMARY)
                if (delay1 > 0) return@withTimeoutOrNull delay1
            } catch (_: Exception) {}

            // Second attempt with fallback test endpoint (Google / gstatic)
            try {
                val delay2 = Libv2ray.measureOutboundDelay(configJson, TEST_URL_FALLBACK)
                if (delay2 > 0) return@withTimeoutOrNull delay2
            } catch (_: Exception) {}

            -1L
        } ?: -1L
    }

    /**
     * Measures the live realtime latency of the active connection while the VPN is ON.
     */
    suspend fun testConnectedRealLatency(timeoutMs: Long = 2500L): Long = withContext(Dispatchers.IO) {
        // Method 1: Ask V2Ray Core directly
        try {
            val coreDelay = V2RayCoreManager.measureDelay(TEST_URL_PRIMARY)
            if (coreDelay > 0) return@withContext coreDelay
        } catch (_: Exception) {}

        // Method 2: HTTP GET 204 via OkHttp (routes through the active VPN tun0 interface)
        val start = System.currentTimeMillis()
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()

            val req = Request.Builder()
                .url(TEST_URL_PRIMARY)
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful || resp.code == 204) {
                    return@withContext System.currentTimeMillis() - start
                }
            }
        } catch (_: Exception) {}

        -1L
    }

    private fun isUsableEgressNode(node: VpnNode): Boolean {
        // Exclude dummy or informational nodes (such as remarks with 👤, 📊, ⌛ or localhost/null IP)
        if (node.server == "127.0.0.1" || node.server == "0.0.0.0" || node.server.equals("localhost", ignoreCase = true) ||
            (node.server == "1.1.1.1" && node.port == 1080) || node.port <= 0) {
            return false
        }
        val remark = node.remark.trim()
        if (remark.startsWith("👤") || remark.startsWith("📊") || remark.startsWith("⌛") || remark.startsWith("⏳") ||
            (remark.contains("🔋") && (remark.contains("⌛") || remark.contains("⏳") || remark.contains("👤")))) {
            return false
        }

        // If node has Reality public key (pbk), it is an encrypted Reality tunnel, NOT plaintext!
        if (!node.pbk.isNullOrBlank()) {
            return true
        }

        // Plaintext nodes (security=none) cannot bypass DPI filtering
        val sec = node.security?.lowercase()?.trim() ?: "none"
        if (sec == "none" || sec.isEmpty()) {
            return false
        }
        // Exclude fake CDN anycast IPs that give fake 2ms TCP handshakes without real proxying
        if (node.type.equals("xhttp", ignoreCase = true) || node.type.equals("splithttp", ignoreCase = true)) {
            return false
        }
        val s = node.server.lowercase().trim()
        if (s.startsWith("104.") || s.startsWith("172.67.") || s.startsWith("162.159.")) {
            return false
        }
        return true
    }

    private fun testTcpLatency(host: String, port: Int, timeoutMs: Int): Long {
        val start = System.currentTimeMillis()
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                System.currentTimeMillis() - start
            }
        } catch (e: Exception) {
            -1L // Timed out or connection refused
        }
    }
}
