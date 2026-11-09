package com.asanvpn.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dual-Tier Security Logger for AsanVPN:
 * - Simple Users: Only see high-level connection progress, errors/warnings, and friendly link remarks.
 *   NO panel URLs, NO IPs, NO ports, NO tokens, and NO raw config bodies are ever exposed.
 * - Admin Mode: Only visible to administrators with admin code/credentials, providing full technical diagnostics.
 */
object AppLogger {
    private val userLogs = mutableListOf<String>()
    private val adminLogs = mutableListOf<String>()
    private const val MAX_LOG_ENTRIES = 200

    @Synchronized
    fun logUser(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = "[$time] $message"
        userLogs.add(entry)
        adminLogs.add(entry) // Admin log contains everything user sees + technical details
        trim()
    }

    @Synchronized
    fun logAdmin(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = "[$time] [ADMIN] $message"
        adminLogs.add(entry)
        trim()
    }

    @Synchronized
    fun getUserLog(): String {
        return if (userLogs.isEmpty()) "گزارشی برای نمایش وجود ندارد." else userLogs.joinToString("\n")
    }

    @Synchronized
    fun getAdminLog(): String {
        return if (adminLogs.isEmpty()) "گزارشی برای نمایش وجود ندارد." else adminLogs.joinToString("\n")
    }

    @Synchronized
    fun clear() {
        userLogs.clear()
        adminLogs.clear()
    }

    private fun trim() {
        while (userLogs.size > MAX_LOG_ENTRIES) {
            userLogs.removeAt(0)
        }
        while (adminLogs.size > MAX_LOG_ENTRIES) {
            adminLogs.removeAt(0)
        }
    }
}
