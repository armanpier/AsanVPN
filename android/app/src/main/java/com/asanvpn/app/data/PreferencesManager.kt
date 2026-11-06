package com.asanvpn.app.data

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("asan_vpn_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_PIN = "saved_pin"
        private const val KEY_USER_NAME = "saved_user_name"
        private const val KEY_DAYS_LEFT = "saved_days_left"
        private const val KEY_SUPPORT_PHONE = "saved_support_phone"
        private const val KEY_SUPPORT_TG = "saved_support_tg"
        private const val KEY_IS_CONNECTED = "saved_is_connected"
        private const val KEY_SERVER_REMARK = "saved_server_remark"
        private const val KEY_SERVER_PING = "saved_server_ping"
        private const val KEY_V2RAY_CONFIG = "saved_v2ray_config"
        private const val KEY_FAILED_ATTEMPTS = "failed_pin_attempts"
        private const val KEY_LOCKOUT_UNTIL = "lockout_until_timestamp"
        private const val KEY_TRAFFIC_REMAINING = "saved_traffic_remaining_bytes"
        private const val KEY_TRAFFIC_TOTAL = "saved_traffic_total_bytes"
    }

    var trafficTotalBytes: Long
        get() = prefs.getLong(KEY_TRAFFIC_TOTAL, 30L * 1024 * 1024 * 1024)
        set(value) = prefs.edit().putLong(KEY_TRAFFIC_TOTAL, value).apply()

    var trafficRemainingBytes: Long
        get() {
            val total = prefs.getLong(KEY_TRAFFIC_TOTAL, 30L * 1024 * 1024 * 1024)
            if (total <= 0L) return -1L
            return prefs.getLong(KEY_TRAFFIC_REMAINING, 25L * 1024 * 1024 * 1024)
        }
        set(value) = prefs.edit().putLong(KEY_TRAFFIC_REMAINING, value).apply()

    var cachedNodesJson: String?
        get() = prefs.getString("cached_nodes_json", null)
        set(value) = prefs.edit().putString("cached_nodes_json", value).apply()

    var failedPinAttempts: Int
        get() = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)
        set(value) = prefs.edit().putInt(KEY_FAILED_ATTEMPTS, value).apply()

    var lockoutUntil: Long
        get() = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)
        set(value) = prefs.edit().putLong(KEY_LOCKOUT_UNTIL, value).apply()

    var activeV2RayConfig: String?
        get() = prefs.getString(KEY_V2RAY_CONFIG, null)
        set(value) = prefs.edit().putString(KEY_V2RAY_CONFIG, value).apply()

    var selectedServerRemark: String
        get() = prefs.getString(KEY_SERVER_REMARK, "🇩🇪 DE2 (سرور پیشنهادی)") ?: "🇩🇪 DE2 (سرور پیشنهادی)"
        set(value) = prefs.edit().putString(KEY_SERVER_REMARK, value).apply()

    var selectedServerPing: Long
        get() = prefs.getLong(KEY_SERVER_PING, 48L)
        set(value) = prefs.edit().putLong(KEY_SERVER_PING, value).apply()

    var savedPin: String?
        get() = prefs.getString(KEY_PIN, null)
        set(value) = prefs.edit().putString(KEY_PIN, value).apply()

    var userName: String
        get() = prefs.getString(KEY_USER_NAME, "کاربر گرامی") ?: "کاربر گرامی"
        set(value) = prefs.edit().putString(KEY_USER_NAME, value).apply()

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

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
