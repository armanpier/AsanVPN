package com.asanvpn.app.service

import android.content.Context
import android.util.Log
import com.asanvpn.app.data.models.VpnNode
import com.asanvpn.app.service.v2ray.V2RayConfigBuilder
import com.asanvpn.app.service.v2ray.V2RayCoreManager
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
    private const val TEST_URL_FALLBACK = "http://www.gstatic.com/generate_204"

    /**
     * Connects IMMEDIATELY to the first verified working link (fast connect),
     * while continuing in the background to evaluate all candidates to switch
     * to the fastest one without keeping the user waiting.
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

        // Step 1: Rapid parallel TCP prescan (500ms timeout) to rank by proximity
        val tcpResults = candidatePool.map { node ->
            async {
                val tcpLatency = testTcpLatency(node.server, node.port, 500)
                Pair(node, tcpLatency)
            }
        }.awaitAll()

        val responsiveCandidates = tcpResults
            .filter { it.second > 0 }
            .sortedBy { it.second }
            .map { it.first }

        val poolToTest = if (responsiveCandidates.isNotEmpty()) responsiveCandidates else candidatePool

        // Step 2: Concurrent real HTTP 204 delay test
        val firstConnected = AtomicBoolean(false)
        val bestLatency = java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE)
        var bestNode: VpnNode? = null
        val candidatesToTest = poolToTest.take(15)

        coroutineScope {
            candidatesToTest.forEach { node ->
                launch {
                    val realDelay = measureRealDelay(node)
                    node.latencyMs = realDelay
                    if (realDelay in 1 until bestLatency.get()) {
                        Log.i(TAG, "Candidate verified: [${node.remark}] (${node.server}:${node.port}) -> REAL latency: ${realDelay}ms")

                        // Case 1: First working node found -> Connect immediately!
                        if (firstConnected.compareAndSet(false, true)) {
                            bestNode = node
                            bestLatency.set(realDelay)
                            Log.i(TAG, ">> FAST CONNECT: Connecting immediately to first OK link [${node.remark}] (${realDelay}ms)")
                            onFirstOkNode(node)
                        }
                        // Case 2: Subsequent verified node is significantly faster (> 25ms improvement) -> Switch immediately!
                        else if (realDelay < bestLatency.get() - 25L) {
                            val prevRemark = bestNode?.remark ?: "unknown"
                            val prevLat = bestLatency.get()
                            bestNode = node
                            bestLatency.set(realDelay)
                            Log.i(TAG, ">> OPTIMIZING SWITCH: Faster node found [${node.remark}] (${realDelay}ms vs $prevRemark ${prevLat}ms) -> Switching now!")
                            onFastestNodeFound(node)
                        }
                    } else if (realDelay <= 0) {
                        Log.d(TAG, "Candidate failed: [${node.remark}]")
                    }
                }
            }
        }

        // If no candidate passed real delay test, fallback to best TCP or first node
        if (firstConnected.compareAndSet(false, true)) {
            val fallback = responsiveCandidates.firstOrNull() ?: candidatePool[0]
            Log.w(TAG, "No candidate passed real delay, falling back to [${fallback.remark}]")
            onFirstOkNode(fallback)
        }
    }

    /**
     * Finds the fastest, verified working proxy using REALTIME HTTP roundtrip latency
     * through the V2Ray/Xray tunnel rather than a simple TCP handshake.
     *
     * 1. Pre-filters out domestic bridges, plaintext relays, and fake CDN IPs.
     * 2. Runs a fast parallel TCP pre-scan to identify responsive candidate hosts.
     * 3. Tests real end-to-end HTTP/204 tunnel latency on top candidates via Libv2ray.measureOutboundDelay.
     * 4. Selects the proxy with the absolute lowest REAL latency that actually routes internet traffic.
     */
    suspend fun findFastestNode(context: Context? = null, nodes: List<VpnNode>): VpnNode = withContext(Dispatchers.IO) {
        if (nodes.isEmpty()) throw IllegalArgumentException("Node list is empty")
        if (nodes.size == 1) return@withContext nodes[0]

        // 1. Initialize V2Ray Core environment if context is provided
        context?.let { V2RayCoreManager.initCore(it) }

        // 2. Separate genuine foreign egress nodes from domestic bridges/CDN
        val usableNodes = nodes.filter { isUsableEgressNode(it) }
        val candidatePool = if (usableNodes.isNotEmpty()) usableNodes else nodes

        // 3. Fast Parallel TCP Pre-scan to rank candidates by local network responsiveness
        val tcpResults = candidatePool.map { node ->
            async {
                val tcpLatency = testTcpLatency(node.server, node.port, 650)
                Pair(node, tcpLatency)
            }
        }.awaitAll()

        val responsiveCandidates = tcpResults
            .filter { it.second > 0 }
            .sortedBy { it.second }
            .map { it.first }

        val poolToTest = if (responsiveCandidates.isNotEmpty()) responsiveCandidates else candidatePool

        // 4. Test REALTIME tunnel latency on top candidates (up to 12 nodes) with 6 concurrent workers
        val topCandidates = poolToTest.take(12)
        val semaphore = Semaphore(6)

        val realResults = topCandidates.map { node ->
            async {
                semaphore.withPermit {
                    val realDelay = measureRealDelay(node, 1900L)
                    node.latencyMs = realDelay
                    if (realDelay > 0) {
                        Log.i(TAG, "Proxy verified working: [${node.remark}] (${node.server}:${node.port}) -> REAL latency: ${realDelay}ms")
                    } else {
                        Log.d(TAG, "Proxy test failed: [${node.remark}] (${node.server}:${node.port})")
                    }
                    Pair(node, realDelay)
                }
            }
        }.awaitAll()

        // 5. Select the verified working node with the lowest REAL latency
        val workingReal = realResults
            .filter { it.second > 0 }
            .minByOrNull { it.second }

        if (workingReal != null) {
            Log.i(TAG, "Selected fastest VERIFIED proxy: [${workingReal.first.remark}] with REAL delay ${workingReal.second}ms")
            return@withContext workingReal.first
        }

        // Batch 2 check if first 12 had dead/expired servers
        if (poolToTest.size > 12) {
            val batch2 = poolToTest.drop(12).take(8)
            val batch2Results = batch2.map { node ->
                async {
                    semaphore.withPermit {
                        val realDelay = measureRealDelay(node, 1900L)
                        node.latencyMs = realDelay
                        Pair(node, realDelay)
                    }
                }
            }.awaitAll()

            val workingBatch2 = batch2Results.filter { it.second > 0 }.minByOrNull { it.second }
            if (workingBatch2 != null) {
                Log.i(TAG, "Selected verified proxy from batch 2: [${workingBatch2.first.remark}] with REAL delay ${workingBatch2.second}ms")
                return@withContext workingBatch2.first
            }
        }

        // Resilient fallback (e.g. device is offline during setup)
        val fallback = responsiveCandidates.firstOrNull() ?: candidatePool[0]
        Log.w(TAG, "No proxy passed real delay test; using fallback [${fallback.remark}]")
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
     * Returns true latency in milliseconds if working, or -1L if broken/blocked.
     */
    suspend fun measureRealDelay(node: VpnNode, timeoutMs: Long = 2500L): Long = withContext(Dispatchers.IO) {
        val configJson = try {
            V2RayConfigBuilder.buildTestConfig(node)
        } catch (e: Exception) {
            return@withContext -1L
        }

        try {
            val delay = Libv2ray.measureOutboundDelay(configJson, TEST_URL_PRIMARY)
            if (delay > 0) delay else -1L
        } catch (e: Exception) {
            -1L
        }
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
        // Exclude Iranian domestic bridges/relays that don't egress directly to foreign web
        if (s.endsWith(".ir") || s.contains(".ir:")) {
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
