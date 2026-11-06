package com.asanvpn.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import com.asanvpn.app.data.PreferencesManager
import com.asanvpn.app.data.VpnRepository
import com.asanvpn.app.data.models.ConnectionState
import com.asanvpn.app.service.AsanVpnService
import com.asanvpn.app.service.PingTester
import com.asanvpn.app.ui.screens.ConnectScreen
import com.asanvpn.app.ui.screens.ExpiredScreen
import com.asanvpn.app.ui.screens.PinScreen
import com.asanvpn.app.ui.theme.AsanVpnTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var prefs: PreferencesManager
    private lateinit var repository: VpnRepository

    private var currentPinState by mutableStateOf<String?>(null)
    private var currentUserName by mutableStateOf("کاربر گرامی")
    private var currentDaysLeft by mutableIntStateOf(-1)
    private var currentTrafficRemaining by mutableLongStateOf(25L * 1024 * 1024 * 1024)
    private var currentTrafficTotal by mutableLongStateOf(30L * 1024 * 1024 * 1024)
    private var currentServerRemark by mutableStateOf("🇩🇪 DE2 (سرور پیشنهادی)")
    private var currentServerPing by mutableLongStateOf(48L)
    private var connectionState by mutableStateOf(ConnectionState.DISCONNECTED)
    private var isExpired by mutableStateOf(false)
    private var isPinLoading by mutableStateOf(false)
    private var isPinSuccess by mutableStateOf(false)
    private var pinError by mutableStateOf<String?>(null)

    // In / Out Traffic Statistics
    private var downloadTrafficState by mutableStateOf("۰ B")
    private var uploadTrafficState by mutableStateOf("۰ B")
    private var downloadSpeedState by mutableStateOf("")
    private var uploadSpeedState by mutableStateOf("")

    private var optimizationJob: Job? = null
    private var trafficMonitorJob: Job? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnServiceInternal()
            startTrafficMonitoring()
        } else {
            connectionState = ConnectionState.DISCONNECTED
            Toast.makeText(this, "مجوز اتصال برای روشن شدن برنامه الزامی است", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = PreferencesManager(this)
        repository = VpnRepository(this)

        handleIncomingIntent(intent)

        currentPinState = prefs.savedPin
        currentUserName = prefs.userName
        currentDaysLeft = prefs.daysLeft
        currentTrafficRemaining = prefs.trafficRemainingBytes
        currentTrafficTotal = prefs.trafficTotalBytes
        currentServerRemark = prefs.selectedServerRemark
        currentServerPing = prefs.selectedServerPing

        if (AsanVpnService.isRunning) {
            connectionState = ConnectionState.CONNECTED
            startTrafficMonitoring()
        }

        // Initialize V2Ray Core environment at startup
        com.asanvpn.app.service.v2ray.V2RayCoreManager.initCore(applicationContext)

        // Keep connection state reactive to the real VPN service lifecycle
        lifecycleScope.launch {
            AsanVpnService.vpnRunningState.collect { running ->
                if (connectionState != ConnectionState.CONNECTING || running) {
                    connectionState = if (running) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED
                }
            }
        }

        // Realtime latency monitoring while VPN is active
        lifecycleScope.launch {
            while (true) {
                if (connectionState == ConnectionState.CONNECTED) {
                    val livePing = PingTester.testConnectedRealLatency()
                    if (livePing > 0) {
                        currentServerPing = livePing
                        prefs.selectedServerPing = livePing
                    }
                }
                kotlinx.coroutines.delay(10000L)
            }
        }

        setContent {
            AsanVpnTheme {
                val savedPin = currentPinState

                when {
                    isExpired -> {
                        ExpiredScreen(
                            supportPhone = prefs.supportPhone,
                            onCallSupport = { makeSupportCall(prefs.supportPhone) },
                            onChangePin = {
                                disconnectVpnService()
                                prefs.clearAll()
                                currentPinState = null
                                isExpired = false
                                isPinSuccess = false
                                isPinLoading = false
                                pinError = null
                                connectionState = ConnectionState.DISCONNECTED
                            }
                        )
                    }
                    savedPin == null -> {
                        PinScreen(
                            isLoading = isPinLoading,
                            errorMessage = pinError,
                            isSuccess = isPinSuccess,
                            lockoutUntil = prefs.lockoutUntil,
                            onPinComplete = { enteredPin ->
                                verifyPinAndSetup(enteredPin)
                            },
                            onLockoutExpired = {
                                prefs.failedPinAttempts = 0
                                prefs.lockoutUntil = 0L
                                pinError = null
                            }
                        )
                    }
                    else -> {
                        ConnectScreen(
                            userName = currentUserName,
                            daysLeft = currentDaysLeft,
                            trafficRemainingBytes = currentTrafficRemaining,
                            trafficTotalBytes = currentTrafficTotal,
                            serverRemark = currentServerRemark,
                            serverPing = currentServerPing,
                            connectionState = connectionState,
                            downloadTraffic = downloadTrafficState,
                            uploadTraffic = uploadTrafficState,
                            downloadSpeed = downloadSpeedState,
                            uploadSpeed = uploadSpeedState,
                            onToggleConnect = {
                                handleToggleConnect()
                            },
                            onContactSupport = {
                                makeSupportCall(prefs.supportPhone)
                            },
                            onChangePin = {
                                disconnectVpnService()
                                prefs.clearAll()
                                currentPinState = null
                                currentTrafficRemaining = 25L * 1024 * 1024 * 1024
                                currentTrafficTotal = 30L * 1024 * 1024 * 1024
                                isExpired = false
                                isPinSuccess = false
                                isPinLoading = false
                                pinError = null
                                connectionState = ConnectionState.DISCONNECTED
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (AsanVpnService.isRunning) {
            connectionState = ConnectionState.CONNECTED
            startTrafficMonitoring()
        } else if (connectionState == ConnectionState.CONNECTED) {
            connectionState = ConnectionState.DISCONNECTED
            stopTrafficMonitoring()
        }
    }

    private fun verifyPinAndSetup(pin: String) {
        isPinLoading = true
        pinError = null

        lifecycleScope.launch {
            val result = repository.syncWithWorker(pin)
            isPinLoading = false

            result.onSuccess { response ->
                val nodes = response.nodes
                if (!nodes.isNullOrEmpty()) {
                    val initialNode = nodes.firstOrNull { it.remark.contains("DE") || it.remark.contains("FR") } ?: nodes[0]
                    prefs.selectedServerRemark = initialNode.remark
                    currentServerRemark = initialNode.remark
                    prefs.selectedServerPing = 125L
                    currentServerPing = 125L

                    val configJson = com.asanvpn.app.service.v2ray.V2RayConfigBuilder.buildConfig(initialNode)
                    prefs.activeV2RayConfig = configJson
                }
                response.userInfo?.let { info ->
                    val name = info.name ?: "کاربر گرامی"
                    val days = info.daysLeft ?: -1
                    prefs.userName = name
                    prefs.daysLeft = days
                    currentUserName = name
                    currentDaysLeft = days
                    if (info.trafficTotalBytes != null) {
                        prefs.trafficTotalBytes = info.trafficTotalBytes
                        currentTrafficTotal = info.trafficTotalBytes
                    }
                    if (info.trafficRemainingBytes != null) {
                        val rem = if (info.trafficTotalBytes != null && info.trafficTotalBytes <= 0L) -1L else info.trafficRemainingBytes
                        prefs.trafficRemainingBytes = rem
                        currentTrafficRemaining = rem
                    }
                }
                isPinSuccess = true
                prefs.failedPinAttempts = 0
                prefs.lockoutUntil = 0L
                isExpired = false
                kotlinx.coroutines.delay(1000)
                prefs.savedPin = pin
                currentPinState = pin
                isPinSuccess = false
            }.onFailure { error ->
                isPinSuccess = false
                val msg = error.message ?: "کد وارد شده صحیح نمی‌باشد"
                if (msg.contains("اشتراک شما به پایان رسیده") || msg.contains("expired")) {
                    isExpired = true
                    return@launch
                }
                if (msg.contains("نامعتبر") || msg.contains("فرصت") || msg.contains("invalid") || msg.contains("صحیح نمی‌باشد")) {
                    prefs.failedPinAttempts += 1
                    if (prefs.failedPinAttempts >= 3 && prefs.lockoutUntil <= System.currentTimeMillis()) {
                        prefs.lockoutUntil = System.currentTimeMillis() + (30 * 60 * 1000L)
                    }
                }
                pinError = msg
            }
        }
    }

    private fun handleToggleConnect() {
        if (connectionState == ConnectionState.CONNECTED) {
            disconnectVpnService()
        } else {
            connectionState = ConnectionState.CONNECTING
            val pin = prefs.savedPin ?: return

            lifecycleScope.launch {
                val result = repository.syncWithWorker(pin)
                result.onSuccess { response ->
                    response.userInfo?.let { info ->
                        info.name?.let { prefs.userName = it; currentUserName = it }
                        info.daysLeft?.let { prefs.daysLeft = it; currentDaysLeft = it }
                        info.trafficTotalBytes?.let {
                            prefs.trafficTotalBytes = it
                            currentTrafficTotal = it
                        }
                        info.trafficRemainingBytes?.let {
                            val rem = if (prefs.trafficTotalBytes <= 0L) -1L else it
                            prefs.trafficRemainingBytes = rem
                            currentTrafficRemaining = rem
                        }
                    }

                    val nodes = response.nodes
                    if (nodes.isNullOrEmpty()) {
                        connectionState = ConnectionState.DISCONNECTED
                        Toast.makeText(this@MainActivity, "سرور فعالی یافت نشد", Toast.LENGTH_SHORT).show()
                        return@launch
                    }

                    // Fast Connect & Background Optimization
                    optimizationJob?.cancel()
                    optimizationJob = launch {
                        var initialConnectedNode: com.asanvpn.app.data.models.VpnNode? = null

                        PingTester.connectFastAndOptimize(
                            context = this@MainActivity,
                            nodes = nodes,
                            onFirstOkNode = { firstNode ->
                                withContext(Dispatchers.Main) {
                                    connectionState = ConnectionState.CONNECTED
                                    prefs.isConnected = true
                                    initialConnectedNode = firstNode
                                    prefs.selectedServerRemark = firstNode.remark
                                    currentServerRemark = firstNode.remark
                                    val ping = if (firstNode.latencyMs > 0) firstNode.latencyMs else 140L
                                    prefs.selectedServerPing = ping
                                    currentServerPing = ping

                                    // Build V2Ray/Xray config for the first OK node
                                    val configJson = com.asanvpn.app.service.v2ray.V2RayConfigBuilder.buildConfig(firstNode)
                                    prefs.activeV2RayConfig = configJson

                                    val intent = VpnService.prepare(this@MainActivity)
                                    if (intent != null) {
                                        vpnPermissionLauncher.launch(intent)
                                    } else {
                                        startVpnServiceInternal(configJson)
                                    }
                                    startTrafficMonitoring()
                                }
                            },
                            onFastestNodeFound = { fastestNode ->
                                withContext(Dispatchers.Main) {
                                    val first = initialConnectedNode
                                    if (first != null && first.remark != fastestNode.remark && fastestNode.latencyMs > 0) {
                                        android.util.Log.i("MainActivity", ">> Optimizing: switching from [${first.remark}] (${first.latencyMs}ms) to fastest [${fastestNode.remark}] (${fastestNode.latencyMs}ms)")
                                        prefs.selectedServerRemark = fastestNode.remark
                                        currentServerRemark = fastestNode.remark
                                        prefs.selectedServerPing = fastestNode.latencyMs
                                        currentServerPing = fastestNode.latencyMs

                                        val newConfigJson = com.asanvpn.app.service.v2ray.V2RayConfigBuilder.buildConfig(fastestNode)
                                        prefs.activeV2RayConfig = newConfigJson

                                        // Seamless switch
                                        switchVpnConfigInternal(newConfigJson)
                                    } else if (fastestNode.latencyMs > 0) {
                                        prefs.selectedServerPing = fastestNode.latencyMs
                                        currentServerPing = fastestNode.latencyMs
                                    }
                                }
                            }
                        )
                    }
                }.onFailure { error ->
                    val cachedConfig = prefs.activeV2RayConfig
                    if (!cachedConfig.isNullOrBlank()) {
                        // Upstream panel temporarily unreachable, connect using cached working configuration!
                        val intent = VpnService.prepare(this@MainActivity)
                        if (intent != null) {
                            vpnPermissionLauncher.launch(intent)
                        } else {
                            startVpnServiceInternal(cachedConfig)
                        }
                        startTrafficMonitoring()
                        return@launch
                    }
                    connectionState = ConnectionState.DISCONNECTED
                    val msg = error.message ?: "عدم اتصال به خدمات دهنده"
                    if (msg.contains("اشتراک شما به پایان رسیده") || msg.contains("expired")) {
                        isExpired = true
                    } else {
                        Toast.makeText(this@MainActivity, "عدم اتصال به خدمات دهنده", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun switchVpnConfigInternal(configJson: String) {
        val intent = Intent(this, AsanVpnService::class.java).apply {
            action = AsanVpnService.ACTION_SWITCH_CONFIG
            putExtra(AsanVpnService.EXTRA_CONFIG, configJson)
        }
        startService(intent)
    }

    private fun startVpnServiceInternal(configJson: String? = null) {
        val config = configJson ?: prefs.activeV2RayConfig
        val intent = Intent(this, AsanVpnService::class.java).apply {
            action = AsanVpnService.ACTION_CONNECT
            if (!config.isNullOrBlank()) {
                putExtra(AsanVpnService.EXTRA_CONFIG, config)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun disconnectVpnService() {
        optimizationJob?.cancel()
        optimizationJob = null
        stopTrafficMonitoring()
        val intent = Intent(this, AsanVpnService::class.java).apply {
            action = AsanVpnService.ACTION_DISCONNECT
        }
        startService(intent)
        connectionState = ConnectionState.DISCONNECTED
    }

    private fun startTrafficMonitoring() {
        if (trafficMonitorJob?.isActive == true) return
        trafficMonitorJob = lifecycleScope.launch(Dispatchers.IO) {
            val uid = android.os.Process.myUid()
            val baseUidRx = android.net.TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L)
            val baseUidTx = android.net.TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L)
            val initialRemaining = prefs.trafficRemainingBytes
            val totalBytes = prefs.trafficTotalBytes
            val isUnlimited = initialRemaining < 0L || totalBytes <= 0L

            var lastRx = 0L
            var lastTx = 0L
            var lastTime = System.currentTimeMillis()

            while (isActive && connectionState == ConnectionState.CONNECTED) {
                kotlinx.coroutines.delay(1000L)
                val nowTime = System.currentTimeMillis()
                val timeDiffSec = ((nowTime - lastTime) / 1000.0).coerceAtLeast(0.1)

                // Query V2Ray Core internal traffic statistics (exact proxy bytes)
                val coreStats = com.asanvpn.app.service.v2ray.V2RayCoreManager.queryTrafficStats()
                val (currentRx, currentTx) = if (coreStats != null && (coreStats.downlinkBytes > 0 || coreStats.uplinkBytes > 0)) {
                    Pair(coreStats.downlinkBytes, coreStats.uplinkBytes)
                } else {
                    val uidRx = android.net.TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L)
                    val uidTx = android.net.TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L)
                    Pair(
                        (uidRx - baseUidRx).coerceAtLeast(0L),
                        (uidTx - baseUidTx).coerceAtLeast(0L)
                    )
                }

                val speedRx = if (lastRx > 0) ((currentRx - lastRx) / timeDiffSec).toLong().coerceAtLeast(0L) else 0L
                val speedTx = if (lastTx > 0) ((currentTx - lastTx) / timeDiffSec).toLong().coerceAtLeast(0L) else 0L
                val sessionTraffic = currentRx + currentTx
                val liveRemaining = if (isUnlimited) {
                    -1L
                } else {
                    (initialRemaining - sessionTraffic).coerceAtLeast(0L)
                }

                withContext(Dispatchers.Main) {
                    currentTrafficRemaining = liveRemaining
                    downloadTrafficState = formatBytes(currentRx)
                    uploadTrafficState = formatBytes(currentTx)
                    downloadSpeedState = if (speedRx > 256) "↓ ${formatSpeed(speedRx)}" else ""
                    uploadSpeedState = if (speedTx > 256) "↑ ${formatSpeed(speedTx)}" else ""
                }

                lastRx = currentRx
                lastTx = currentTx
                lastTime = nowTime
            }
        }
    }

    private fun stopTrafficMonitoring() {
        trafficMonitorJob?.cancel()
        trafficMonitorJob = null
        if (currentTrafficRemaining >= 0L && prefs.trafficTotalBytes > 0L) {
            prefs.trafficRemainingBytes = currentTrafficRemaining
        }
        downloadSpeedState = ""
        uploadSpeedState = ""
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "۰ B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(java.util.Locale.US, "%.2f GB", gb)
            mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f MB", mb)
            kb >= 1.0 -> String.format(java.util.Locale.US, "%.0f KB", kb)
            else -> "$bytes B"
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0) return ""
        val kb = bytesPerSec / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f MB/s", mb)
            kb >= 1.0 -> String.format(java.util.Locale.US, "%.0f KB/s", kb)
            else -> "$bytesPerSec B/s"
        }
    }

    private fun makeSupportCall(phone: String) {
        try {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$phone")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "شماره پشتیبانی: $phone", Toast.LENGTH_LONG).show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(incomingIntent: Intent?) {
        val clipText = incomingIntent?.getStringExtra("clipboard_text")
        if (!clipText.isNullOrEmpty()) {
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("pin", clipText))
        }
    }
}
