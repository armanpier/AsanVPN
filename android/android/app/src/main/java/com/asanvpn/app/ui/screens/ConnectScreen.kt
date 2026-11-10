package com.asanvpn.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asanvpn.app.data.PreferencesManager
import com.asanvpn.app.data.VpnRepository
import com.asanvpn.app.data.models.ConnectionState
import com.asanvpn.app.ui.theme.*
import com.asanvpn.app.util.AppLogger
import kotlinx.coroutines.delay

@Composable
fun ConnectScreen(
    userName: String,
    daysLeft: Int,
    trafficRemainingBytes: Long = 25L * 1024 * 1024 * 1024,
    trafficTotalBytes: Long = 30L * 1024 * 1024 * 1024,
    serverRemark: String = "🇩🇪 DE2 (سرور پیشنهادی)",
    serverPing: Long = 48L,
    connectionState: ConnectionState,
    downloadTraffic: String = "۰ B",
    uploadTraffic: String = "۰ B",
    downloadSpeed: String = "",
    uploadSpeed: String = "",
    appVersion: String = "1.3.2",
    newVersionAvailable: String? = null,
    onToggleConnect: () -> Unit,
    onContactSupport: () -> Unit = {},
    onCheckUpdate: () -> Unit = {},
    onChangePin: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { PreferencesManager(context) }
    var showDebugDialog by remember { mutableStateOf(false) }
    var showAdminAuthDialog by remember { mutableStateOf(false) }
    var adminPasswordInput by remember { mutableStateOf("") }
    var isAdminMode by remember { mutableStateOf(prefs.isAdmin) }

    fun formatTrafficRemaining(bytes: Long): String {
        if (bytes < 0L) return "نامحدود"
        if (bytes == 0L) return "۰ GB"
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        val mb = bytes / (1024.0 * 1024.0)
        return when {
            gb >= 1.0 -> String.format(java.util.Locale.US, "%.1f GB", gb)
            mb >= 1.0 -> String.format(java.util.Locale.US, "%.0f MB", mb)
            else -> "${bytes / 1024} KB"
        }
    }

    // Live connection timer
    var connectedSeconds by remember { mutableLongStateOf(0L) }
    var connectingSeconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(connectionState) {
        if (connectionState == ConnectionState.CONNECTED) {
            connectedSeconds = 0L
            connectingSeconds = 0L
            while (true) {
                delay(1000)
                connectedSeconds++
            }
        } else if (connectionState == ConnectionState.CONNECTING) {
            connectingSeconds = 0L
            while (true) {
                delay(1000)
                connectingSeconds++
            }
        } else {
            connectedSeconds = 0L
            connectingSeconds = 0L
        }
    }

    // Pulse animation for connecting state
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    // Glow alpha animation
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    // Modern Neumorphic Dial Visuals
    val isConnected = connectionState == ConnectionState.CONNECTED
    val isConnecting = connectionState == ConnectionState.CONNECTING

    val iconColor = when (connectionState) {
        ConnectionState.DISCONNECTED -> TextSecondary
        ConnectionState.CONNECTING -> AmberConnecting
        ConnectionState.CONNECTED -> GreenConnected
    }

    val statusBadgeText = when (connectionState) {
        ConnectionState.DISCONNECTED -> "خاموش"
        ConnectionState.CONNECTING -> if (connectingSeconds >= 6) "درحال بررسی لینک‌ها..." else "در حال برقراری ارتباط..."
        ConnectionState.CONNECTED -> "متصل و محافظت‌شده"
    }

    val statusSubText = when (connectionState) {
        ConnectionState.DISCONNECTED -> "برای روشن شدن وی‌پی‌ان لمس کنید"
        ConnectionState.CONNECTING -> if (connectingSeconds >= 6) "در حال بررسی لینک‌ها... (برای لغو لمس کنید)" else "در حال اتصال به سرور... (برای لغو لمس کنید)"
        ConnectionState.CONNECTED -> "ترافیک اینترنت شما کاملاً رمزگذاری شده است"
    }

    fun formatDuration(seconds: Long): String {
        val hrs = seconds / 3600
        val mins = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hrs > 0) {
            String.format("%02d:%02d:%02d", hrs, mins, secs)
        } else {
            String.format("%02d:%02d", mins, secs)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NeumorphBg)
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // ---------------- TOP APP BAR / USER PROFILE ----------------
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top App Bar: Debug button & User Profile Chip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Top Action Buttons: Info & Check Update
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Discreet Debug Info Icon in soft neumorphic button
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .neumorphicRaised(shape = RoundedCornerShape(12.dp), elevation = 4.dp)
                            .clickable { showDebugDialog = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "گزارش وضعیت",
                            tint = TextSecondary,
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    // Check for Updates button with dynamic version badge
                    Row(
                        modifier = Modifier
                            .height(40.dp)
                            .neumorphicRaised(shape = RoundedCornerShape(12.dp), elevation = 4.dp)
                            .background(
                                if (newVersionAvailable != null) Color(0xFFFEF2F2) else NeumorphSurface,
                                RoundedCornerShape(12.dp)
                            )
                            .border(
                                1.dp,
                                if (newVersionAvailable != null) AccentCoral.copy(alpha = 0.6f) else NeumorphBorderLight,
                                RoundedCornerShape(12.dp)
                            )
                            .clickable { onCheckUpdate() }
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SystemUpdate,
                            contentDescription = "بررسی به‌روزرسانی",
                            tint = if (newVersionAvailable != null) Color(0xFFDC2626) else AccentCoral,
                            modifier = Modifier.size(18.dp)
                        )
                        if (newVersionAvailable != null) {
                            Text(
                                text = "نسخه جدید: $newVersionAvailable",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFDC2626)
                            )
                        } else {
                            Text(
                                text = "نسخه $appVersion",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextSecondary
                            )
                        }
                    }
                }

                // User Profile Chip (Neumorphic Raised Pill)
                Box(
                    modifier = Modifier
                        .neumorphicRaised(shape = RoundedCornerShape(16.dp), elevation = 4.dp)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = userName,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(AccentCyanSoft),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = AccentCyan,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ---------------- SUBSCRIPTION STATUS CARDS (DAYS & TRAFFIC) ----------------
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Subscription Days Remaining Card
                val isUnlimitedDays = daysLeft < 0
                val isCriticalDays = !isUnlimitedDays && daysLeft in 0..3
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .neumorphicRaised(shape = RoundedCornerShape(18.dp), elevation = 5.dp)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(if (isCriticalDays) RedOff.copy(alpha = 0.15f) else AccentCyanSoft),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Schedule,
                                    contentDescription = null,
                                    tint = if (isCriticalDays) RedOff else AccentCyan,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            Text(
                                text = "اعتبار زمانی",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextSecondary
                            )
                        }
                        Text(
                            text = when {
                                isUnlimitedDays -> "نامحدود"
                                daysLeft == 0 -> "پایان امروز"
                                else -> "$daysLeft روز"
                            },
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isCriticalDays) RedOff else TextPrimary
                        )
                    }
                }

                // Traffic Remaining Card
                val isTrafficUnlimited = trafficRemainingBytes < 0L || trafficTotalBytes <= 0L
                val isLowRemaining = !isTrafficUnlimited && trafficRemainingBytes in 1 until (1024L * 1024L * 1024L)
                val isExhaustedRemaining = !isTrafficUnlimited && trafficRemainingBytes == 0L
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .neumorphicRaised(shape = RoundedCornerShape(18.dp), elevation = 5.dp)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            isExhaustedRemaining -> RedOff.copy(alpha = 0.15f)
                                            isLowRemaining -> AmberConnecting.copy(alpha = 0.15f)
                                            else -> Color(0xFFDCFCE7) // Soft Green
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DataUsage,
                                    contentDescription = null,
                                    tint = when {
                                        isExhaustedRemaining -> RedOff
                                        isLowRemaining -> AmberConnecting
                                        else -> GreenConnected
                                    },
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            Text(
                                text = "حجم اشتراک",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextSecondary
                            )
                        }
                        Text(
                            text = if (isTrafficUnlimited) "نامحدود" else formatTrafficRemaining(trafficRemainingBytes),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                isExhaustedRemaining -> RedOff
                                isLowRemaining -> AmberConnecting
                                else -> TextPrimary
                            }
                        )
                    }
                }
            }

            // ---------------- LOW TRAFFIC WARNING BANNER ----------------
            val isTrafficUnlimited = trafficRemainingBytes < 0L || trafficTotalBytes <= 0L
            val isLowTrafficWarning = !isTrafficUnlimited && trafficRemainingBytes in 1 until (1024L * 1024L * 1024L)
            val isExhaustedWarning = !isTrafficUnlimited && trafficRemainingBytes == 0L

            if (isLowTrafficWarning || isExhaustedWarning) {
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .neumorphicRaised(shape = RoundedCornerShape(16.dp), elevation = 4.dp)
                        .background(
                            if (isExhaustedWarning) Color(0xFFFEF2F2) else Color(0xFFFFFBEB),
                            RoundedCornerShape(16.dp)
                        )
                        .border(
                            1.dp,
                            if (isExhaustedWarning) RedOff.copy(alpha = 0.4f) else AmberConnecting.copy(alpha = 0.4f),
                            RoundedCornerShape(16.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isExhaustedWarning) RedOff.copy(alpha = 0.15f) else AmberConnecting.copy(alpha = 0.15f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "هشدار حجم",
                                tint = if (isExhaustedWarning) RedOff else AmberConnecting,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.End
                        ) {
                            Text(
                                text = if (isExhaustedWarning)
                                    "⚠️ حجم اشتراک شما به پایان رسیده است!"
                                else
                                    "⚠️ حجم رو به پایان است: (${formatTrafficRemaining(trafficRemainingBytes)})",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isExhaustedWarning) RedOff else Color(0xFFB45309),
                                textAlign = TextAlign.Right
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "جهت جلوگیری از قطع ارتباط، نسبت به تمدید اقدام فرمایید.",
                                fontSize = 11.sp,
                                color = TextSecondary,
                                textAlign = TextAlign.Right
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ---------------- SERVER SELECTION & QUALITY CARD ----------------
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .neumorphicRaised(shape = RoundedCornerShape(20.dp), elevation = 5.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Ping latency badge (Sunken soft well)
                    Box(
                        modifier = Modifier
                            .neumorphicSunken(shape = RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (isConnected) GreenConnected else TextMuted)
                            )
                            Text(
                                text = "$serverPing ms",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isConnected) GreenConnected else TextSecondary
                            )
                        }
                    }

                    // Server location info
                    Column(
                        horizontalAlignment = Alignment.End
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = serverRemark,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Icon(
                                imageVector = Icons.Default.Public,
                                contentDescription = null,
                                tint = AccentTeal,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "انتخاب هوشمند بهترین سرور با پینگ پایین",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ---------------- CENTERPIECE: 3D NEUMORPHIC POWER DIAL ----------------
        // Conceived as a multi-layered soft 3D dial directly matching the reference image thermostat
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        onToggleConnect()
                    },
                contentAlignment = Alignment.Center
            ) {
                // Layer 1: Ambient Active Glow (when connecting or connected)
                if (connectionState != ConnectionState.DISCONNECTED) {
                    Box(
                        modifier = Modifier
                            .size(236.dp)
                            .scale(if (isConnecting) pulseScale else 1f)
                            .clip(CircleShape)
                            .background(
                                if (isConnected) GreenConnectedGlow.copy(alpha = glowAlpha)
                                else AmberConnectingGlow.copy(alpha = glowAlpha)
                            )
                    )
                }

                // Layer 2: Outer Extruded Neumorphic Rim
                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .shadow(
                            elevation = if (isConnected) 12.dp else 8.dp,
                            shape = CircleShape,
                            ambientColor = NeumorphDarkShadow.copy(alpha = 0.7f),
                            spotColor = NeumorphDarkShadow.copy(alpha = 0.7f)
                        )
                        .background(NeumorphSurface, CircleShape)
                        .border(
                            width = 1.5.dp,
                            brush = Brush.linearGradient(
                                colors = listOf(Color.White, Color.White.copy(alpha = 0.3f), NeumorphDarkShadow.copy(alpha = 0.4f)),
                                start = Offset(0f, 0f),
                                end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                            ),
                            shape = CircleShape
                        )
                )

                // Layer 3: Concentric Inset Groove (Separator)
                Box(
                    modifier = Modifier
                        .size(185.dp)
                        .neumorphicSunken(shape = CircleShape, cavityColor = Color(0xFFE8EDF5))
                )

                // Layer 4: Central Raised 3D Power Dial Face
                Box(
                    modifier = Modifier
                        .size(158.dp)
                        .shadow(
                            elevation = 6.dp,
                            shape = CircleShape,
                            ambientColor = NeumorphDarkShadow.copy(alpha = 0.6f),
                            spotColor = NeumorphDarkShadow.copy(alpha = 0.6f)
                        )
                        .background(
                            Brush.radialGradient(
                                colors = if (isConnected) {
                                    listOf(Color(0xFFF0FDF4), Color(0xFFDCFCE7), NeumorphSurface)
                                } else {
                                    listOf(Color.White, NeumorphSurfaceElevated, NeumorphSurface)
                                }
                            ),
                            CircleShape
                        )
                        .border(
                            width = if (isConnected) 2.5.dp else 1.5.dp,
                            color = if (isConnected) GreenConnected.copy(alpha = 0.8f) else Color.White,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = "اتصال وی‌پی‌ان",
                            tint = iconColor,
                            modifier = Modifier.size(62.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Connection State Badges
            Text(
                text = statusBadgeText,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = when (connectionState) {
                    ConnectionState.DISCONNECTED -> TextPrimary
                    ConnectionState.CONNECTING -> AmberConnecting
                    ConnectionState.CONNECTED -> GreenConnected
                },
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = statusSubText,
                fontSize = 12.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            // Dynamic Live Connection Duration Counter (Neumorphic Pill)
            AnimatedVisibility(visible = isConnected) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .neumorphicSunken(shape = RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Timer,
                                contentDescription = null,
                                tint = GreenConnected,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "مدت اتصال: ${formatDuration(connectedSeconds)}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ---------------- IRAN SPLIT-TUNNELING ASSURANCE PILL ----------------
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .neumorphicRaised(shape = RoundedCornerShape(16.dp), elevation = 4.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "🇮🇷 بانک‌ها و سایت‌های ایرانی مستقیم باز می‌شوند",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ---------------- BOTTOM ACTIONS: STATS, SUPPORT & SWITCH PIN ----------------
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ---------------- TRAFFIC STATISTICS CARD (IN / OUT) ----------------
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp)
                    .neumorphicRaised(shape = RoundedCornerShape(20.dp), elevation = 5.dp)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // IN (دریافت / دانلود)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(AccentCyanSoft),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowDownward,
                                contentDescription = "دانلود (In)",
                                tint = AccentCyan,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "دریافت (In)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextSecondary
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = downloadTraffic,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                if (downloadSpeed.isNotBlank()) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = downloadSpeed,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AccentCyan
                                    )
                                }
                            }
                        }
                    }

                    // Vertical Divider
                    Box(
                        modifier = Modifier
                            .height(28.dp)
                            .width(1.dp)
                            .background(NeumorphDarkShadow.copy(alpha = 0.5f))
                    )

                    // OUT (ارسال / آپلود)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFDCFCE7)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowUpward,
                                contentDescription = "ارسال (Out)",
                                tint = GreenConnected,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "ارسال (Out)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextSecondary
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = uploadTraffic,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                if (uploadSpeed.isNotBlank()) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = uploadSpeed,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = GreenConnected
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Telegram Bot Buy / Renew Button (Neumorphic Warm Accent Pill)
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .height(48.dp)
                    .neumorphicRaised(shape = RoundedCornerShape(16.dp), elevation = 4.dp)
                    .clickable {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/asan_vpn_p_bot"))
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "تلگرام یافت نشد: t.me/asan_vpn_p_bot", Toast.LENGTH_SHORT).show()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ShoppingCart,
                        contentDescription = null,
                        tint = AccentCoral,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "خرید یا تمدید اشتراک در تلگرام",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentCoral
                    )
                }
            }

            // Change PIN / Switch Account
            TextButton(
                onClick = onChangePin,
                modifier = Modifier.padding(bottom = 2.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "ورود با کد اشتراک دیگر",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
            }

            Text(
                text = "نسخه $appVersion • سامانه ابری آسان وی‌پی‌ان",
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
                color = TextMuted,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
    }

    // ---------------- DEBUG LOG DIALOG ----------------
    if (showDebugDialog) {
        val currentLogText = if (isAdminMode) AppLogger.getAdminLog() else AppLogger.getUserLog()

        AlertDialog(
            onDismissRequest = { showDebugDialog = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isAdminMode) "گزارش فنی و دیباگ (مدیر)" else "گزارش وضعیت اتصال",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    if (isAdminMode) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = GreenConnected.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "دسترسی مدیر",
                                color = GreenConnected,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 340.dp)
                            .background(NeumorphSunken, RoundedCornerShape(12.dp))
                            .border(1.dp, NeumorphBorderSubtle, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = currentLogText,
                            fontSize = 12.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = if (isAdminMode) AccentTeal else TextSecondary,
                            lineHeight = 17.sp
                        )
                    }

                    if (!isAdminMode) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = { showAdminAuthDialog = true }) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = AccentCoral
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("مشاهده گزارش کامل (مخصوص مدیر)", fontSize = 11.sp, color = AccentCoral)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("AsanVPN Log", currentLogText)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "گزارش در حافظه کپی شد", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("کپی گزارش", color = AccentTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDebugDialog = false }) {
                    Text("بستن", color = TextSecondary)
                }
            },
            containerColor = NeumorphSurface,
            shape = RoundedCornerShape(20.dp)
        )
    }

    if (showAdminAuthDialog) {
        AlertDialog(
            onDismissRequest = { 
                showAdminAuthDialog = false 
                adminPasswordInput = ""
            },
            title = {
                Text("احراز هویت مدیر", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            },
            text = {
                Column {
                    Text(
                        "جهت مشاهده آدرس سرورها، کانفیگ‌ها و لاگ فنی، رمز عبور یا کد مدیریت را وارد فرمایید:",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = adminPasswordInput,
                        onValueChange = { adminPasswordInput = it },
                        singleLine = true,
                        placeholder = { Text("رمز عبور یا کد مدیریت", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val input = adminPasswordInput.trim()
                        if (input == "Arman4237340" || input == "22858950" || input == prefs.savedPin) {
                            isAdminMode = true
                            prefs.isAdmin = true
                            showAdminAuthDialog = false
                            adminPasswordInput = ""
                            Toast.makeText(context, "دسترسی مدیریت تایید شد", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "رمز عبور مدیریت نادرست است", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("تایید", color = AccentTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showAdminAuthDialog = false 
                    adminPasswordInput = ""
                }) {
                    Text("انصراف", color = TextSecondary)
                }
            },
            containerColor = NeumorphSurface,
            shape = RoundedCornerShape(18.dp)
        )
    }
}

@Preview(name = "Connect Screen - Neumorphic Light", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun ConnectScreenPreview() {
    AsanVpnTheme {
        ConnectScreen(
            userName = "کاربر گرامی (آرمان)",
            daysLeft = 29,
            trafficRemainingBytes = 25L * 1024 * 1024 * 1024,
            trafficTotalBytes = 30L * 1024 * 1024 * 1024,
            serverRemark = "🇩🇪 DE2 (سرور پیشنهادی)",
            serverPing = 48L,
            connectionState = ConnectionState.DISCONNECTED,
            downloadTraffic = "۱.۲ GB",
            uploadTraffic = "۳۴۰ MB",
            downloadSpeed = "۲.۴ MB/s",
            uploadSpeed = "۵۴۰ KB/s",
            onToggleConnect = {},
            onChangePin = {}
        )
    }
}

