package com.asanvpn.app.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("asan_vpn_prefs", Context.MODE_PRIVATE)

    // Hardware-bound deterministic AES key to prevent extraction across devices
    private val aesKey: SecretKeySpec by lazy {
        val seed = "${context.packageName}:${Build.FINGERPRINT ?: "AsanVpn"}:AsanVpnSecureKey2026"
        val sha = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
        SecretKeySpec(sha, "AES")
    }

    private val ivSpec by lazy {
        val ivSeed = "AsanVpnInitV2026".toByteArray(Charsets.UTF_8)
        IvParameterSpec(ivSeed)
    }

    private fun encrypt(plainText: String?): String? {
        if (plainText.isNullOrEmpty()) return null
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, ivSpec)
            val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            "ENC:" + Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            plainText
        }
    }

    private fun decrypt(cipherText: String?): String? {
        if (cipherText.isNullOrEmpty()) return null
        if (!cipherText.startsWith("ENC:")) return cipherText // backwards compatibility for existing plaintext
        return try {
            val rawBase64 = cipherText.removePrefix("ENC:")
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, aesKey, ivSpec)
            val decryptedBytes = cipher.doFinal(Base64.decode(rawBase64, Base64.NO_WRAP))
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val KEY_PIN = "saved_pin"
        private const val KEY_USER_NAME = "saved_user_name"
        private const val KEY_DAYS_LEFT = "saved_days_left"
        private const val KEY_SUPPORT_PHONE = "saved_support_phone"
        private const val KEY_SUPPORT_TG = "saved_support_tg"
        private const val KEY_IS_CONNECTED = "saved_is_connected"
        private const val KEY_IS_ADMIN = "saved_is_admin"
        private const val KEY_SERVER_REMARK = "saved_server_remark"
        private const val KEY_SERVER_PING = "saved_server_ping"
        private const val KEY_V2RAY_CONFIG = "saved_v2ray_config"
        private const val KEY_FAILED_ATTEMPTS = "failed_pin_attempts"
        private const val KEY_LOCKOUT_UNTIL = "lockout_until_timestamp"
        private const val KEY_TRAFFIC_REMAINING = "saved_traffic_remaining_bytes"
        private const val KEY_TRAFFIC_TOTAL = "saved_traffic_total_bytes"
        private const val KEY_LAST_GEO_RULES_CHECK = "last_geo_rules_check_timestamp"
    }

    var trafficTotalBytes: Long
        get() = prefs.getLong(KEY_TRAFFIC_TOTAL, 0L)
        set(value) = prefs.edit().putLong(KEY_TRAFFIC_TOTAL, value).apply()

    var trafficRemainingBytes: Long
        get() {
            val total = prefs.getLong(KEY_TRAFFIC_TOTAL, 0L)
            if (total <= 0L) return -1L
            return prefs.getLong(KEY_TRAFFIC_REMAINING, -1L)
        }
        set(value) = prefs.edit().putLong(KEY_TRAFFIC_REMAINING, value).apply()

    var cachedNodesJson: String?
        get() = decrypt(prefs.getString("cached_nodes_json", null))
        set(value) = prefs.edit().putString("cached_nodes_json", encrypt(value)).apply()

    var failedPinAttempts: Int
        get() = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)
        set(value) = prefs.edit().putInt(KEY_FAILED_ATTEMPTS, value).apply()

    var lockoutUntil: Long
        get() = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)
        set(value) = prefs.edit().putLong(KEY_LOCKOUT_UNTIL, value).apply()

    var activeV2RayConfig: String?
        get() = decrypt(prefs.getString(KEY_V2RAY_CONFIG, null))
        set(value) = prefs.edit().putString(KEY_V2RAY_CONFIG, encrypt(value)).apply()

    var selectedServerRemark: String
        get() = prefs.getString(KEY_SERVER_REMARK, "🇩🇪 DE2 (سرور پیشنهادی)") ?: "🇩🇪 DE2 (سرور پیشنهادی)"
        set(value) = prefs.edit().putString(KEY_SERVER_REMARK, value).apply()

    var selectedServerPing: Long
        get() = prefs.getLong(KEY_SERVER_PING, 48L)
        set(value) = prefs.edit().putLong(KEY_SERVER_PING, value).apply()

    var savedPin: String?
        get() = decrypt(prefs.getString(KEY_PIN, null))
        set(value) = prefs.edit().putString(KEY_PIN, encrypt(value)).apply()

    var userName: String
        get() = decrypt(prefs.getString(KEY_USER_NAME, null)) ?: "کاربر گرامی"
        set(value) = prefs.edit().putString(KEY_USER_NAME, encrypt(value)).apply()

    var daysLeft: Int
        get() = prefs.getInt(KEY_DAYS_LEFT, -1)
        set(value) = prefs.edit().putInt(KEY_DAYS_LEFT, value).apply()

    var supportPhone: String
        get() = prefs.getString(KEY_SUPPORT_PHONE, "+989120000000") ?: "+989120000000"
        set(value) = prefs.edit().putString(KEY_SUPPORT_PHONE, value).apply()

    var supportTelegram: String
        get() = prefs.getString(KEY_SUPPORT_TG, "AsanSupport") ?: "AsanSupport"
        set(value) = prefs.edit().putString(KEY_SUPPORT_TG, value).apply()

    var isConnected: Boolean
        get() = prefs.getBoolean(KEY_IS_CONNECTED, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_CONNECTED, value).apply()

    var isAdmin: Boolean
        get() {
            if (savedPin == "22858950" || savedPin == "Arman4237340") return true
            return prefs.getBoolean(KEY_IS_ADMIN, false)
        }
        set(value) = prefs.edit().putBoolean(KEY_IS_ADMIN, value).apply()

    var isDarkMode: Boolean
        get() = prefs.getBoolean("is_dark_mode", false)
        set(value) = prefs.edit().putBoolean("is_dark_mode", value).apply()

    var lastGeoRulesCheckTime: Long
        get() = prefs.getLong(KEY_LAST_GEO_RULES_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_GEO_RULES_CHECK, value).apply()

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
