package com.asanvpn.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.asanvpn.app.MainActivity
import com.asanvpn.app.data.PreferencesManager
import com.asanvpn.app.data.VpnRepository
import com.asanvpn.app.service.v2ray.V2RayCoreManager
import com.asanvpn.app.util.IranAppBypassHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AsanVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var prefs: PreferencesManager
    private var sessionValidatorJob: Job? = null

    companion object {
        const val ACTION_CONNECT = "com.asanvpn.app.CONNECT"
        const val ACTION_DISCONNECT = "com.asanvpn.app.DISCONNECT"
        const val ACTION_SWITCH_CONFIG = "com.asanvpn.app.SWITCH_CONFIG"
        const val EXTRA_CONFIG = "com.asanvpn.app.EXTRA_CONFIG"
        const val NOTIFICATION_CHANNEL_ID = "asan_vpn_channel"
        const val NOTIFICATION_ID = 1001

        private val _vpnRunningState = MutableStateFlow(false)
        val vpnRunningState: StateFlow<Boolean> = _vpnRunningState.asStateFlow()

        var isRunning: Boolean
            get() = _vpnRunningState.value
            private set(value) {
                _vpnRunningState.value = value
            }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesManager(this)
        createNotificationChannel()
        V2RayCoreManager.initCore(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val config = intent.getStringExtra(EXTRA_CONFIG) ?: prefs.activeV2RayConfig
                if (isRunning) {
                    switchVpnConfig(config)
                } else {
                    startVpnTunnel(config)
                }
            }
            ACTION_SWITCH_CONFIG -> {
                val config = intent.getStringExtra(EXTRA_CONFIG) ?: prefs.activeV2RayConfig
                switchVpnConfig(config)
            }
            ACTION_DISCONNECT -> {
                stopVpnTunnel()
            }
        }
        return START_NOT_STICKY
    }

    private fun switchVpnConfig(configJson: String?) {
        if (!isRunning || vpnInterface == null || configJson.isNullOrBlank()) {
            startVpnTunnel(configJson)
            return
        }
        try {
            val tunFd = vpnInterface!!.fd
            Log.i("AsanVpnService", "Seamlessly switching V2Ray core to fastest proxy on tunFd=$tunFd")
            val switched = V2RayCoreManager.startLoop(configJson, tunFd)
            Log.i("AsanVpnService", "V2Ray Core loop switched: $switched")
            if (switched) {
                prefs.activeV2RayConfig = configJson
            } else {
                Log.w("AsanVpnService", "Failed to switch V2Ray loop: core returned false")
            }
        } catch (e: Exception) {
            Log.e("AsanVpnService", "Failed to switch V2Ray config", e)
        }
    }

    private fun startVpnTunnel(configJson: String?) {
        if (isRunning) return
        if (configJson.isNullOrBlank()) {
            Log.e("AsanVpnService", "Cannot start VPN: Xray config is null or blank")
            return
        }

        try {
            val builder = Builder()
                .setSession("آسان وی‌پی‌ان")
                .setMtu(1500)
                .addAddress("172.19.0.1", 30)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .addRoute("0.0.0.0", 0)

            try {
                // Allow this application process to bypass the tunnel so Xray outbound connections don't loop
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                Log.w("AsanVpnService", "Could not set addDisallowedApplication: ${e.message}")
            }

            // Exclude Iranian domestic applications from tunnel routing
            IranAppBypassHelper.applyDisallowedApplications(builder, this)

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e("AsanVpnService", "Builder.establish() returned null")
                isRunning = false
                prefs.isConnected = false
                return
            }

            val pfd = vpnInterface!!
            val tunFd = pfd.fd

            // Start V2Ray/Xray Core loop with the TUN file descriptor
            val coreStarted = V2RayCoreManager.startLoop(configJson, tunFd)
            Log.i("AsanVpnService", "V2Ray Core started: $coreStarted on tunFd=$tunFd")

            if (!coreStarted) {
                Log.e("AsanVpnService", "CRITICAL: V2Ray Core failed to start! Tearing down tunnel...")
                stopVpnTunnel()
                return
            }

            isRunning = true
            prefs.isConnected = true

            // Periodic session check: passive validation every 10 minutes to keep quota synced & save Cloudflare limits
            sessionValidatorJob?.cancel()
            sessionValidatorJob = serviceScope.launch {
                val repo = VpnRepository(this@AsanVpnService)
                while (isRunning && isActive) {
                    kotlinx.coroutines.delay(600_000L) // 10 minutes
                    val pin = prefs.savedPin
                    if (pin.isNullOrBlank()) {
                        Log.i("AsanVpnService", "No saved PIN found, stopping VPN tunnel")
                        stopVpnTunnel()
                        break
                    }
                    val res = repo.syncWithWorker(pin)
                    res.onFailure { error ->
                        val isRevoked = error is com.asanvpn.app.data.AuthRevokedException &&
                            (error.status == "invalid_pin" || error.status == "deleted" || error.status == "disabled")

                        if (isRevoked) {
                            Log.w("AsanVpnService", "Session revoked on server! Killing VPN tunnel...")
                            prefs.clearAll()
                            stopVpnTunnel()
                        }
                    }
                }
            }

            // Start foreground notification safely
            try {
                val notification = buildNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } catch (fgsEx: Exception) {
                fgsEx.printStackTrace()
            }

        } catch (e: Exception) {
            Log.e("AsanVpnService", "Exception starting VPN tunnel", e)
            stopVpnTunnel()
        }
    }

    private fun stopVpnTunnel() {
        sessionValidatorJob?.cancel()
        sessionValidatorJob = null

        isRunning = false
        prefs.isConnected = false

        try {
            V2RayCoreManager.stopLoop()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            stopForeground(true)
        } catch (e: Exception) {
            // Ignore
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpnTunnel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "وضعیت اتصال آسان وی‌پی‌ان",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "نمایش وضعیت روشن بودن اینترنت امن"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent, piFlags
        )

        val disconnectIntent = Intent(this, AsanVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this, 1, disconnectIntent, piFlags
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("آسان وی‌پی‌ان روشن است")
            .setContentText("اینترنت شما امن و متصل است")
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "قطع اتصال", disconnectPendingIntent)
            .setOngoing(true)
            .build()
    }
}
