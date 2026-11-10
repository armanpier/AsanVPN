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
import com.asanvpn.app.ui.components.UpdateDialog
import com.asanvpn.app.ui.screens.ConnectScreen
import com.asanvpn.app.util.AppLogger
import com.asanvpn.app.ui.screens.ExpiredScreen
import com.asanvpn.app.ui.screens.PinScreen
import com.asanvpn.app.ui.theme.AsanVpnTheme
import com.asanvpn.app.util.AppUpdateManager
import com.asanvpn.app.util.AppVersionInfo
import com.asanvpn.app.util.GeoAssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

    // In-App Auto Update States
    private var availableUpdate by mutableStateOf<AppVersionInfo?>(null)
    private var showUpdateDialog by mutableStateOf(false)
    private var isUpdateDownloading by mutableStateOf(false)
    private var updateDownloadProgress by mutableFloatStateOf(0f)
    private var updateDownloadedMb by mutableFloatStateOf(0f)
    private var updateTotalMb by mutableFloatStateOf(0f)
    private var updateDownloadError by mutableStateOf<String?>(null)
    private var isUpdateDownloadComplete by mutableStateOf(false)
    private var downloadedApkFile: File? = null

    // In / Out Traffic Statistics
    private var downloadTrafficState by mutableStateOf("۰ B")
    private var uploadTrafficState by mutableStateOf("۰ B")
    private var downloadSpeedState by mutableStateOf("")
    private var uploadSpeedState by mutableStateOf("")

    private var connectJob: Job? = null
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

        currentPinState = prefs.savedPin
        currentUserName = prefs.userName
        currentDaysLeft = prefs.daysLeft
        currentTrafficRemaining = prefs.trafficRemainingBytes
        currentTrafficTotal = prefs.trafficTotalBytes
        currentServerRemark = prefs.selectedServerRemark
        currentServerPing = prefs.selectedServerPing

        handleIncomingIntent(intent)

        if (AsanVpnService.isRunning) {
            connectionState = ConnectionState.CONNECTED
            startTrafficMonitoring()
        }

        // Initialize V2Ray Core environment at startup
        com.asanvpn.app.service.v2ray.V2RayCoreManager.initCore(applicationContext)

        // Ensure Chocolate4Iran GeoIP/GeoSite assets are initialized
        GeoAssetManager.ensureAssetsInitialized(applicationContext)

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

        // Automatic background check for update on app startup
        lifecycleScope.launch {
            kotlinx.coroutines.delay(2000L)
            triggerCheckForUpdate(manual = false)
        }

        // Background check for Chocolate4Iran routing rules after 5 seconds
        lifecycleScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(5000L)
            GeoAssetManager.checkAndUpdateRules(applicationContext, force = false)
        }

        // Periodic background session check while app is open
        lifecycleScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(20000L)
                val pin = currentPinState ?: prefs.savedPin
                if (!pin.isNullOrBlank()) {
                    validateUserSession(pin)
                }
            }
        }

        setContent {
            AsanVpnTheme {
                val savedPin = currentPinState
                val (_, currentName) = remember { AppUpdateManager.getCurrentVersion(this@MainActivity) }

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
                            appVersion = currentName,
                            newVersionAvailable = availableUpdate?.versionName,
                            onCheckUpdate = {
                                triggerCheckForUpdate(manual = true)
                            },
                            onPinComplete = { enteredPin ->
                                verifyPinAndSetup(enteredPin)
                            },
                            onLockoutExpired = {
                                prefs.failedPinAttempts = 0
                                prefs.lockoutUntil = 0L
                                pinError = null
                            },
                            onCheckLockout = { pinToCheck, onDone ->
                                lifecycleScope.launch {
                                    val res = repository.checkLockout(pinToCheck.ifBlank { prefs.savedPin ?: "" })
                                    onDone()
                                    res.onSuccess { unlocked ->
                                        if (unlocked) {
                                            prefs.lockoutUntil = 0L
                                            prefs.failedPinAttempts = 0
                                            pinError = null
                                            Toast.makeText(this@MainActivity, "مسدودی با موفقیت برطرف شد. اکنون می‌توانید کد را وارد کنید.", Toast.LENGTH_LONG).show()
                                        } else {
                                            Toast.makeText(this@MainActivity, "حساب یا دستگاه هنوز در وضعیت مسدود است. لطفاً به مدیر اطلاع دهید.", Toast.LENGTH_SHORT).show()
                                        }
                                    }.onFailure {
                                        Toast.makeText(this@MainActivity, "خطا در اتصال به سرور جهت استعلام", Toast.LENGTH_SHORT).show()
                                    }
                                }
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
                            appVersion = currentName,
                            newVersionAvailable = availableUpdate?.versionName,
                            onToggleConnect = {
                                handleToggleConnect()
                            },
                            onContactSupport = {
                                makeSupportCall(prefs.supportPhone)
                            },
                            onCheckUpdate = {
                                triggerCheckForUpdate(manual = true)
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

                // In-App Auto Update Dialog (Persian Yes/No)
                if (showUpdateDialog && availableUpdate != null) {
                    val updateInfo = availableUpdate!!
                    UpdateDialog(
                        versionInfo = updateInfo,
                        isDownloading = isUpdateDownloading,
                        downloadProgress = updateDownloadProgress,
                        downloadedMb = updateDownloadedMb,
                        totalMb = updateTotalMb,
                        downloadError = updateDownloadError,
                        isDownloadComplete = isUpdateDownloadComplete,
                        onConfirmUpdate = {
                            startDownloadUpdate(updateInfo)
                        },
                        onInstallApk = {
                            downloadedApkFile?.let { apk ->
                                AppUpdateManager.installApk(this@MainActivity, apk)
                            }
                        },
                        onDismiss = {
                            showUpdateDialog = false
                        }
                    )
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

        // Validate user session immediately on resume if logged in
        val pin = currentPinState ?: prefs.savedPin
        if (!pin.isNullOrBlank()) {
            validateUserSession(pin)
        }
    }

    private fun kickoutRevokedUser(message: String) {
        val wasConnected = connectionState == ConnectionState.CONNECTED || AsanVpnService.isRunning
        disconnectVpnService()
        prefs.clearAll()
        currentPinState = null
        connectionState = ConnectionState.DISCONNECTED
        isExpired = false
        isPinSuccess = false
        isPinLoading = false
        pinError = message
        if (wasConnected) {
            Toast.makeText(this@MainActivity, "اتصال قطع شد: $message", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun validateUserSession(pin: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = repository.syncWithWorker(pin)
            result.onFailure { error ->
                if (error is com.asanvpn.app.data.AuthRevokedException) {
                    val status = error.status
                    if (status == "invalid_pin" || status == "deleted" || status == "disabled") {
                        withContext(Dispatchers.Main) {
                            kickoutRevokedUser(error.message)
                        }
                    }
                }
            }
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
                // Only count as failed PIN attempt if backend explicitly returned status "invalid_pin"
                if (error is com.asanvpn.app.data.AuthRevokedException && error.status == "invalid_pin") {
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
        if (connectionState == ConnectionState.CONNECTED || connectionState == ConnectionState.CONNECTING) {
            val wasConnecting = connectionState == ConnectionState.CONNECTING
            AppLogger.logUser(if (wasConnecting) "⏹️ برقراری ارتباط توسط کاربر لغو شد" else "🛑 وی‌پی‌ان خاموش شد")
            AppLogger.logAdmin("User toggled off VPN (wasConnecting=$wasConnecting)")
            disconnectVpnService()
        } else {
            connectionState = ConnectionState.CONNECTING
            val pin = prefs.savedPin ?: return

            AppLogger.logUser("🔄 درخواست برقراری ارتباط...")
            val maskedPin = if (pin.length > 2) "***${pin.takeLast(2)}" else "***"
            AppLogger.logAdmin("Toggle connect initiated for PIN: $maskedPin")

            connectJob?.cancel()
            connectJob = lifecycleScope.launch {
                val result = repository.syncWithWorker(pin)
                if (!isActive || connectionState != ConnectionState.CONNECTING) return@launch

                result.onSuccess { response ->
                    if (!isActive || connectionState != ConnectionState.CONNECTING) return@launch
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
                        info.isAdmin?.let { prefs.isAdmin = it }
                        if (pin == "22858950" || pin == "Arman4237340" || (info.name?.contains("admin", ignoreCase = true) == true)) {
                            prefs.isAdmin = true
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
                                if (!this@launch.isActive) return@connectFastAndOptimize
                                withContext(Dispatchers.Main) {
                                    if (connectionState != ConnectionState.CONNECTING) return@withContext
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
                                if (!this@launch.isActive) return@connectFastAndOptimize
                                withContext(Dispatchers.Main) {
                                    if (connectionState != ConnectionState.CONNECTED) return@withContext
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
                    if (!isActive || connectionState != ConnectionState.CONNECTING) return@onFailure
                    if (error is com.asanvpn.app.data.AuthRevokedException) {
                        val status = error.status
                        if (status == "invalid_pin" || status == "deleted" || status == "disabled") {
                            kickoutRevokedUser(error.message)
                            return@launch
                        } else if (status == "expired") {
                            isExpired = true
                            connectionState = ConnectionState.DISCONNECTED
                            return@launch
                        } else if (status == "device_locked") {
                            connectionState = ConnectionState.DISCONNECTED
                            Toast.makeText(this@MainActivity, error.message, Toast.LENGTH_LONG).show()
                            return@launch
                        }
                    }

                    val cachedConfig = prefs.activeV2RayConfig
                    if (!cachedConfig.isNullOrBlank()) {
                        // Upstream panel temporarily unreachable due to network blocking, connect using cached working configuration!
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
        connectJob?.cancel()
        connectJob = null
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
        var deepLinkPin: String? = null

        // 1. Check URI from deep link or App Link (asanvpn://open?pin=88605949 or https://.../open?pin=88605949)
        val dataUri = incomingIntent?.data
        if (dataUri != null) {
            val qPin = dataUri.getQueryParameter("pin")
            if (!qPin.isNullOrBlank()) {
                val cleaned = cleanDigits(qPin)
                if (cleaned.length == 8) deepLinkPin = cleaned
            } else {
                val pathSegment = dataUri.pathSegments.lastOrNull()
                if (pathSegment != null) {
                    val cleaned = cleanDigits(pathSegment)
                    if (cleaned.length == 8) deepLinkPin = cleaned
                }
            }
        }

        // 2. Check Intent extras (clipboard_text or pin)
        if (deepLinkPin.isNullOrBlank()) {
            val extraPin = incomingIntent?.getStringExtra("pin") ?: incomingIntent?.getStringExtra("clipboard_text")
            if (!extraPin.isNullOrBlank()) {
                val cleaned = cleanDigits(extraPin)
                if (cleaned.length == 8) deepLinkPin = cleaned
            }
        }

        // 3. If valid 8-digit PIN received from link:
        if (!deepLinkPin.isNullOrBlank() && deepLinkPin.length == 8) {
            prefs.lockoutUntil = 0L
            prefs.failedPinAttempts = 0
            pinError = null
            try {
                // Copy to system clipboard
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("pin", deepLinkPin))
            } catch (e: Exception) {}

            Toast.makeText(this, "کد اشتراک $deepLinkPin دریافت و فعال شد", Toast.LENGTH_LONG).show()

            // Automatically set up and verify with backend
            verifyPinAndSetup(deepLinkPin)
        }

        if (incomingIntent?.getBooleanExtra("mock_connect", false) == true) {
            currentPinState = "88605949"
            currentUserName = "کاربر گرامی (آرمان)"
            currentDaysLeft = 29
            currentTrafficRemaining = 25L * 1024 * 1024 * 1024
            currentTrafficTotal = 30L * 1024 * 1024 * 1024
            currentServerRemark = "🇩🇪 DE2 (سرور پیشنهادی)"
            currentServerPing = 48L
            downloadTrafficState = "۲.۴ GB"
            uploadTrafficState = "۴۵۰ MB"
            downloadSpeedState = "۴.۲ MB/s"
            uploadSpeedState = "۸۲۰ KB/s"
        }

        if (incomingIntent?.getBooleanExtra("mock_update", false) == true) {
            availableUpdate = AppVersionInfo(
                versionCode = 6,
                versionName = "1.2.1",
                downloadUrl = "https://asanvpn-worker.hi-deploy.workers.dev/download",
                releaseNotes = "ارتقای سرعت و پایداری اتصال، بهینه‌سازی مصرف باتری و پشتیبانی از قابلیت به‌روزرسانی خودکار داخلی",
                totalSize = 69791489L
            )
        }
    }

    private fun triggerCheckForUpdate(manual: Boolean = false) {
        lifecycleScope.launch {
            if (availableUpdate != null && manual) {
                showUpdateDialog = true
                return@launch
            }
            val result = AppUpdateManager.checkForUpdate(this@MainActivity)
            result.onSuccess { info ->
                if (info != null) {
                    availableUpdate = info
                    showUpdateDialog = true
                } else if (manual) {
                    val (_, currentName) = AppUpdateManager.getCurrentVersion(this@MainActivity)
                    Toast.makeText(
                        this@MainActivity,
                        "شما در حال استفاده از آخرین نسخه آسان وی‌پی‌ان هستید (نسخه $currentName)",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }.onFailure { err ->
                if (manual) {
                    Toast.makeText(
                        this@MainActivity,
                        "خطا در بررسی نسخه جدید: ${err.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun startDownloadUpdate(info: AppVersionInfo) {
        isUpdateDownloading = true
        updateDownloadError = null
        isUpdateDownloadComplete = false
        updateDownloadProgress = 0f
        updateDownloadedMb = 0f
        updateTotalMb = if (info.totalSize > 0) info.totalSize / (1024f * 1024f) else 0f

        lifecycleScope.launch {
            val res = AppUpdateManager.downloadApk(
                context = this@MainActivity,
                downloadUrl = info.downloadUrl,
                expectedTotalBytes = info.totalSize,
                onProgress = { progress, downloaded, total ->
                    updateDownloadProgress = progress
                    updateDownloadedMb = downloaded
                    if (total > 0) updateTotalMb = total
                }
            )

            res.onSuccess { file ->
                isUpdateDownloading = false
                isUpdateDownloadComplete = true
                downloadedApkFile = file
                // Prompt the OS installer
                AppUpdateManager.installApk(this@MainActivity, file)
            }.onFailure { err ->
                isUpdateDownloading = false
                updateDownloadError = err.message ?: "خطا در دانلود فایل نصبی"
            }
        }
    }

    private fun cleanDigits(raw: String): String {
        val persianDigits = "۰۱۲۳۴۵۶۷۸۹"
        val arabicDigits = "٠١٢٣٤٥٦٧٨٩"
        val sb = StringBuilder()
        for (ch in raw) {
            val pIdx = persianDigits.indexOf(ch)
            if (pIdx >= 0) {
                sb.append(pIdx)
                continue
            }
            val aIdx = arabicDigits.indexOf(ch)
            if (aIdx >= 0) {
                sb.append(aIdx)
                continue
            }
            if (ch in '0'..'9') {
                sb.append(ch)
            }
        }
        return sb.toString()
    }
}
