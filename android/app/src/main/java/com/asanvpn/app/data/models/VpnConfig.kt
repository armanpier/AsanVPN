package com.asanvpn.app.data.models

import com.google.gson.annotations.SerializedName

data class ConnectRequest(
    @SerializedName("pin") val pin: String,
    @SerializedName("device_id") val deviceId: String
)

data class ConnectResponse(
    @SerializedName("status") val status: String,
    @SerializedName("message_fa") val messageFa: String?,
    @SerializedName("support_phone") val supportPhone: String?,
    @SerializedName("support_telegram") val supportTelegram: String?,
    @SerializedName("retry_after_seconds") val retryAfterSeconds: Long? = null,
    @SerializedName("locked_until_timestamp") val lockedUntilTimestamp: Long? = null,
    @SerializedName("user_info") val userInfo: UserInfo?,
    @SerializedName("nodes") val nodes: List<VpnNode>?,
    @SerializedName("xray_config") val xrayConfig: Any?,
    @SerializedName("singbox_config") val singboxConfig: Any?
)

data class UserInfo(
    @SerializedName("name") val name: String?,
    @SerializedName("days_left") val daysLeft: Int?,
    @SerializedName("traffic_remaining_bytes") val trafficRemainingBytes: Long? = null,
    @SerializedName("traffic_total_bytes") val trafficTotalBytes: Long? = null,
    @SerializedName("max_devices") val maxDevices: Int? = null,
    @SerializedName("active_devices_count") val activeDevicesCount: Int? = null,
    @SerializedName("support_phone") val supportPhone: String?,
    @SerializedName("support_telegram") val supportTelegram: String?,
    @SerializedName("is_admin") val isAdmin: Boolean? = false
)

data class VpnNode(
    @SerializedName("protocol") val protocol: String,
    @SerializedName("remark") val remark: String,
    @SerializedName("server") val server: String,
    @SerializedName("port") val port: Int,
    @SerializedName("uuid") val uuid: String? = null,
    @SerializedName("password") val password: String? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("security") val security: String? = null,
    @SerializedName("sni") val sni: String? = null,
    @SerializedName("path") val path: String? = null,
    @SerializedName("flow") val flow: String? = null,
    @SerializedName("pbk") val pbk: String? = null,
    @SerializedName("sid") val sid: String? = null,
    @SerializedName("spx") val spx: String? = null,
    @SerializedName("fingerprint") val fingerprint: String? = null,
    @SerializedName("hostHeader") val hostHeader: String? = null,
    @SerializedName("serviceName") val serviceName: String? = null,
    @SerializedName("alpn") val alpn: List<String>? = null,
    @SerializedName("cipher") val cipher: String? = null,
    @SerializedName("latency") var latencyMs: Long = -1L,
    @SerializedName("panel_id") val panelId: String? = null,
    @SerializedName("panel_name") val panelName: String? = null,
    @SerializedName("panel_priority") val panelPriority: Int? = 1
)

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}
