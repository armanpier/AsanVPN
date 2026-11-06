package com.asanvpn.app.service.v2ray

import android.content.Context
import android.util.Log
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

object V2RayCoreManager {

    private const val TAG = "V2RayCoreManager"
    private val initialized = AtomicBoolean(false)

    private val callbackHandler = object : CoreCallbackHandler {
        override fun startup(): Long {
            Log.i(TAG, "V2Ray Core started successfully")
            return 0L
        }

        override fun shutdown(): Long {
            Log.i(TAG, "V2Ray Core stopped")
            return 0L
        }

        override fun onEmitStatus(status: Long, msg: String?): Long {
            Log.d(TAG, "V2Ray Status [$status]: $msg")
            return 0L
        }
    }

    private val coreController: CoreController by lazy {
        Libv2ray.newCoreController(callbackHandler)
    }

    fun initCore(context: Context) {
        if (initialized.compareAndSet(false, true)) {
            try {
                Seq.setContext(context.applicationContext)
                val assetDir = context.getDir("assets", Context.MODE_PRIVATE)

                // Copy geoip.dat & geosite.dat from apk assets if missing
                copyAssetIfMissing(context, "geoip.dat", assetDir)
                copyAssetIfMissing(context, "geosite.dat", assetDir)

                Libv2ray.initCoreEnv(assetDir.absolutePath, "")
                Log.i(TAG, "V2Ray Core Env Initialized: ${Libv2ray.checkVersionX()}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize V2Ray core environment", e)
                initialized.set(false)
            }
        }
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
                Log.i(TAG, "Copied asset $fileName to ${targetFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Could not copy asset $fileName: ${e.message}")
            }
        }
    }

    @Synchronized
    fun startLoop(configJson: String, tunFd: Int): Boolean {
        return try {
            if (coreController.isRunning) {
                Log.w(TAG, "Core already running, stopping first")
                coreController.stopLoop()
            }
            coreController.startLoop(configJson, tunFd)
            coreController.isRunning
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start V2Ray loop", e)
            false
        }
    }

    @Synchronized
    fun stopLoop() {
        try {
            if (coreController.isRunning) {
                coreController.stopLoop()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping V2Ray loop", e)
        }
    }

    val isRunning: Boolean
        get() = try {
            coreController.isRunning
        } catch (e: Exception) {
            false
        }

    fun measureOutboundDelay(configJson: String, url: String = "https://cp.cloudflare.com/generate_204"): Long {
        return try {
            Libv2ray.measureOutboundDelay(configJson, url)
        } catch (e: Exception) {
            -1L
        }
    }

    data class TrafficSnapshot(val downlinkBytes: Long, val uplinkBytes: Long)

    fun queryTrafficStats(): TrafficSnapshot? {
        return try {
            if (!coreController.isRunning) return null
            val raw = coreController.queryAllOutboundTrafficStats() ?: return null
            var up = 0L
            var down = 0L
            raw.lines().forEach { line ->
                val parts = line.split(":")
                if (parts.size >= 2) {
                    val key = parts[0].trim().lowercase()
                    val value = parts[1].trim().toLongOrNull() ?: 0L
                    if (key.contains("downlink")) {
                        down += value
                    } else if (key.contains("uplink")) {
                        up += value
                    }
                }
            }
            TrafficSnapshot(down, up)
        } catch (e: Exception) {
            null
        }
    }

    fun measureDelay(url: String = "https://cp.cloudflare.com/generate_204"): Long {
        return try {
            if (coreController.isRunning) {
                coreController.measureDelay(url)
            } else {
                -1L
            }
        } catch (e: Exception) {
            -1L
        }
    }
}
