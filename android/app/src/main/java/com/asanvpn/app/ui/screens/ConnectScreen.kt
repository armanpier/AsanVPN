package com.asanvpn.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    appVersion: String = "1.3.12",
    newVersionAvailable: String? = null,
    isDarkMode: Boolean = false,
    onToggleTheme: () -> Unit = {},
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

    val colors = LuxuryTheme.colors

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

    val isTrafficUnlimited = trafficRemainingBytes < 0L || trafficTotalBytes <= 0L
    val isLowRemaining = !isTrafficUnlimited && trafficRemainingBytes in 1 until (1024L * 1024L * 1024L)
    val isExhaustedRemaining = !isTrafficUnlimited && trafficRemainingBytes == 0L

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

    // Infinite transitions for rotating silk light sheen & aura breathing
    val infiniteTransition = rememberInfiniteTransition(label = "luxury_orb")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb_pulse_scale"
    )

    val silkSheenRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orb_silk_sheen_rotation"
    )

    val isConnected = connectionState == ConnectionState.CONNECTED
    val isConnecting = connectionState == ConnectionState.CONNECTING

    val statusBadgeText = when (connectionState) {
        ConnectionState.DISCONNECTED -> "اتصال قطع شده است"
        ConnectionState.CONNECTING -> if (connectingSeconds >= 6) "درحال بررسی لینک‌ها..." else "در حال برقراری اتصال امن..."
        ConnectionState.CONNECTED -> "متصل و محافظت‌شده"
    }

    val statusSubText = when (connectionState) {
        ConnectionState.DISCONNECTED -> "برای محافظت و رمزگذاری ترافیک دکمه را لمس کنید"
        ConnectionState.CONNECTING -> "در حال یافتن بهترین مسیر ابری با پینگ پایین"
        ConnectionState.CONNECTED -> "ترافیک اینترنت شما کاملاً رمزگذاری شده است"
    }

    val statusTitleColor = when (connectionState) {
        ConnectionState.DISCONNECTED -> colors.textPrimary
        ConnectionState.CONNECTING -> colors.amberWarning
        ConnectionState.CONNECTED -> colors.jadeGreen
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.backgroundBrush)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // ==================== TOP NAVIGATION BAR ====================
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 1. Info (i) Sculpted Pebble Button
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .luxuryCushion(shape = RoundedCornerShape(18.dp), elevation = 4.dp)
                            .clickable { showDebugDialog = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(
                                    if (colors.isDark) Color(0xFF334155) else Color(0xFF475569)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "i",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                        }
                    }

                    // 2. Luxury Theme Switcher (Light Porcelain <-> Dark Obsidian)
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .luxuryCushion(shape = RoundedCornerShape(18.dp), elevation = 4.dp)
                            .clickable { onToggleTheme() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (colors.isDark) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = if (colors.isDark) "حالت روز" else "حالت شب",
                            tint = if (colors.isDark) Color(0xFFFBBF24) else Color(0xFF6366F1),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // 3. Version Pill (Center)
                    Row(
                        modifier = Modifier
                            .height(42.dp)
                            .luxuryCushion(shape = RoundedCornerShape(20.dp), elevation = 4.dp)
                            .clickable { onCheckUpdate() }
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    Brush.radialGradient(
                                        listOf(Color(0xFFFED7AA), Color(0xFFFB7185), Color(0xFFE11D48))
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.SystemUpdate,
                                contentDescription = "نسخه",
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Text(
                            text = if (newVersionAvailable != null) "نسخه جدید: $newVersionAvailable" else "نسخه $appVersion",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (newVersionAvailable != null) Color(0xFFEF4444) else colors.textSecondary
                        )
                    }

                    // 4. Telegram Profile Sculpted Pill (Right side)
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .luxuryCushion(shape = RoundedCornerShape(20.dp), elevation = 4.dp)
                            .clickable {
                                Toast.makeText(context, "حساب کاربری: $userName", Toast.LENGTH_SHORT).show()
                            }
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        listOf(Color(0xFFBAE6FD), Color(0xFF38BDF8), Color(0xFF0284C7))
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = userName,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // ==================== DUAL SCULPTED 3D METRIC CUSHIONS ====================
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Right Card (In RTL): حجم اشتراک
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .luxuryCushion(shape = RoundedCornerShape(26.dp), elevation = 5.dp)
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.Start,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "حجم اشتراک",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.textSecondary
                                )
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .luxuryWell(shape = RoundedCornerShape(12.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Canvas(modifier = Modifier.size(18.dp)) {
                                        drawCircle(
                                            color = colors.jadeGreen.copy(alpha = 0.22f),
                                            radius = size.minDimension / 2,
                                            style = Stroke(width = 3.dp.toPx())
                                        )
                                        drawArc(
                                            color = if (isExhaustedRemaining) colors.coralRed else colors.jadeGreen,
                                            startAngle = -90f,
                                            sweepAngle = if (isTrafficUnlimited) 360f else 280f,
                                            useCenter = false,
                                            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                                        )
                                    }
                                }
                            }
                            Text(
                                text = if (isTrafficUnlimited) "نامحدود" else formatTrafficRemaining(trafficRemainingBytes),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = when {
                                    isExhaustedRemaining -> colors.coralRed
                                    isLowRemaining -> colors.amberWarning
                                    else -> colors.textPrimary
                                }
                            )
                        }
                    }

                    // Left Card (In RTL): اعتبار زمانی
                    val isUnlimitedDays = daysLeft < 0
                    val isCriticalDays = !isUnlimitedDays && daysLeft in 0..3

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .luxuryCushion(shape = RoundedCornerShape(26.dp), elevation = 5.dp)
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.Start,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "اعتبار زمانی",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.textSecondary
                                )
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .luxuryWell(shape = RoundedCornerShape(12.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Schedule,
                                        contentDescription = null,
                                        tint = if (isCriticalDays) colors.coralRed else colors.skyBlue,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Text(
                                text = when {
                                    isUnlimitedDays -> "نامحدود"
                                    daysLeft == 0 -> "پایان امروز"
                                    else -> "$daysLeft روز"
                                },
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (isCriticalDays) colors.coralRed else colors.textPrimary
                            )
                        }
                    }
                }

                // ==================== LOW TRAFFIC WARNING BANNER ====================
                if (isLowRemaining || isExhaustedRemaining) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .luxuryCushion(shape = RoundedCornerShape(18.dp), elevation = 4.dp)
                            .border(
                                1.dp,
                                if (isExhaustedRemaining) colors.coralRed.copy(alpha = 0.5f) else colors.amberWarning.copy(alpha = 0.5f),
                                RoundedCornerShape(18.dp)
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
                                        if (isExhaustedRemaining) colors.coralRed.copy(alpha = 0.15f) else colors.amberWarning.copy(alpha = 0.15f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "هشدار حجم",
                                    tint = if (isExhaustedRemaining) colors.coralRed else colors.amberWarning,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.End
                            ) {
                                Text(
                                    text = if (isExhaustedRemaining)
                                        "⚠️ حجم اشتراک شما به پایان رسیده است!"
                                    else
                                        "⚠️ حجم رو به پایان است: (${formatTrafficRemaining(trafficRemainingBytes)})",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isExhaustedRemaining) colors.coralRed else colors.amberWarning,
                                    textAlign = TextAlign.Right
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "جهت جلوگیری از قطع ارتباط، نسبت به تمدید اقدام فرمایید.",
                                    fontSize = 11.sp,
                                    color = colors.textSecondary,
                                    textAlign = TextAlign.Right
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // ==================== SERVER SELECTOR 3D SCULPTED CARD ====================
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .luxuryCushion(shape = RoundedCornerShape(26.dp), elevation = 5.dp)
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Ping Badge on Left
                            Box(
                                modifier = Modifier
                                    .luxuryWell(shape = RoundedCornerShape(16.dp))
                                    .padding(horizontal = 12.dp, vertical = 7.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(if (isConnected) colors.jadeGreen else colors.textMuted)
                                    )
                                    Text(
                                        text = "$serverPing ms",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = if (isConnected) colors.jadeGreen else colors.textSecondary
                                    )
                                }
                            }

                            // Server Name & Stats on Right
                            Column(
                                horizontalAlignment = Alignment.End,
                                modifier = Modifier.weight(1f).padding(start = 10.dp)
                            ) {
                                Text(
                                    text = serverRemark,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = colors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Right
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    // Days remaining
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.HourglassBottom,
                                            contentDescription = null,
                                            tint = colors.amberWarning,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text(
                                            text = if (daysLeft >= 0) "${daysLeft}D" else "∞",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = colors.textPrimary
                                        )
                                    }
                                    Text(text = "|", fontSize = 11.sp, color = colors.textMuted.copy(alpha = 0.5f))
                                    // Quota remaining
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.SignalCellularAlt,
                                            contentDescription = null,
                                            tint = colors.jadeGreen,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text(
                                            text = if (trafficRemainingBytes >= 0) formatTrafficRemaining(trafficRemainingBytes) else "∞",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = colors.textPrimary
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "انتخاب هوشمند بهترین سرور با پینگ پایین",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.textSecondary,
                            textAlign = TextAlign.Right,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // ==================== CENTERPIECE: 3D POWER ORB ====================
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(228.dp)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            onToggleConnect()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // 1. Breathing Subsurface Aura
                    Box(
                        modifier = Modifier
                            .size(232.dp)
                            .scale(if (isConnecting) pulseScale else if (isConnected) 1.02f else 1f)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        when {
                                            isConnected -> colors.jadeGlow
                                            isConnecting -> colors.amberGlow
                                            else -> colors.cushionShadowAmbient.copy(alpha = 0.25f)
                                        },
                                        Color.Transparent
                                    )
                                )
                            )
                    )

                    // 2. Outer Smooth Torus Cushion Ring
                    Box(
                        modifier = Modifier
                            .size(214.dp)
                            .shadow(
                                elevation = if (isConnected) 12.dp else 8.dp,
                                shape = CircleShape,
                                ambientColor = colors.torusShadow,
                                spotColor = colors.torusShadow
                            )
                            .background(colors.torusOuterBrush, CircleShape)
                            .border(
                                width = 1.5.dp,
                                brush = Brush.linearGradient(
                                    colors = if (colors.isDark) {
                                        listOf(Color.White.copy(alpha = 0.2f), Color.Transparent)
                                    } else {
                                        listOf(Color.White, Color(0xFFCBD5E1).copy(alpha = 0.4f))
                                    }
                                ),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        // 3. Translucent Liquid Jade / Amber / Slate Glass Halo Channel
                        val channelGradient = when {
                            isConnected -> {
                                if (colors.isDark) {
                                    Brush.radialGradient(listOf(Color(0xFF34D399), Color(0x6610B981), Color(0x88064E3B)))
                                } else {
                                    Brush.radialGradient(listOf(Color(0xFFA7F3D0), Color(0x7710B981), Color(0x99059669)))
                                }
                            }
                            isConnecting -> {
                                if (colors.isDark) {
                                    Brush.radialGradient(listOf(Color(0xFFFBBF24), Color(0x66F59E0B), Color(0x8892400E)))
                                } else {
                                    Brush.radialGradient(listOf(Color(0xFFFDE68A), Color(0x77F59E0B), Color(0x99D97706)))
                                }
                            }
                            else -> {
                                if (colors.isDark) {
                                    Brush.radialGradient(listOf(Color(0xFF1E293B), Color(0x66334155), Color(0x880F172A)))
                                } else {
                                    Brush.radialGradient(listOf(Color(0xFFE2E8F0), Color(0x6694A3B8), Color(0x8864748B)))
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .size(184.dp)
                                .clip(CircleShape)
                                .background(channelGradient)
                                .border(
                                    width = 1.dp,
                                    color = if (isConnected) colors.jadeGreen.copy(alpha = 0.4f) else Color.Transparent,
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            // Rotating Silk Light Sheen
                            if (isConnected || isConnecting) {
                                Box(
                                    modifier = Modifier
                                        .size(184.dp)
                                        .rotate(silkSheenRotation)
                                        .background(
                                            Brush.sweepGradient(
                                                colors = listOf(
                                                    Color.Transparent,
                                                    Color.White.copy(alpha = if (colors.isDark) 0.35f else 0.6f),
                                                    Color.Transparent,
                                                    if (isConnected) colors.jadeGreen.copy(alpha = 0.3f) else colors.amberWarning.copy(alpha = 0.3f),
                                                    Color.Transparent
                                                )
                                            ),
                                            CircleShape
                                        )
                                )
                            }

                            // 4. Domed Tactile 3D Pebble Button Cap
                            val pebbleBrush = when {
                                isConnected -> colors.pebbleCapBrushConnected
                                isConnecting -> colors.pebbleCapBrushConnecting
                                else -> colors.pebbleCapBrushDisconnected
                            }

                            Box(
                                modifier = Modifier
                                    .size(152.dp)
                                    .shadow(
                                        elevation = 8.dp,
                                        shape = CircleShape,
                                        ambientColor = colors.cushionShadowAmbient,
                                        spotColor = colors.cushionShadowSpot
                                    )
                                    .background(pebbleBrush, CircleShape)
                                    .border(
                                        width = if (isConnected || isConnecting) 2.5.dp else 1.5.dp,
                                        color = when {
                                            isConnected -> colors.jadeGreen.copy(alpha = 0.8f)
                                            isConnecting -> colors.amberWarning.copy(alpha = 0.8f)
                                            else -> if (colors.isDark) Color.White.copy(alpha = 0.15f) else Color.White
                                        },
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                // Top Specular Liquid Glass Dome Reflection
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(top = 6.dp)
                                        .size(width = 96.dp, height = 38.dp)
                                        .clip(CircleShape)
                                        .background(colors.domeGlossBrush)
                                )

                                // Power Glyph Symbol
                                Icon(
                                    imageVector = Icons.Default.PowerSettingsNew,
                                    contentDescription = "اتصال وی‌پی‌ان",
                                    tint = when {
                                        isConnected -> colors.jadeGreen
                                        isConnecting -> colors.amberWarning
                                        else -> if (colors.isDark) Color(0xFF64748B) else Color(0xFF94A3B8)
                                    },
                                    modifier = Modifier.size(56.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Status Title & Subtitle
                Text(
                    text = statusBadgeText,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    color = statusTitleColor,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(3.dp))

                Text(
                    text = statusSubText,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center
                )

                // Floating Duration Pill
                AnimatedVisibility(visible = isConnected) {
                    Box(
                        modifier = Modifier
                            .padding(top = 10.dp)
                            .luxuryWell(shape = RoundedCornerShape(20.dp))
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "مدت اتصال: ${formatDuration(connectedSeconds)}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = colors.textPrimary
                            )
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.radialGradient(
                                            listOf(Color(0xFFA7F3D0), Color(0xFF10B981), Color(0xFF059669))
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Timer,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // ==================== BOTTOM SECTION ====================
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 1. Direct Iranian Routing Pill
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .luxuryCushion(shape = RoundedCornerShape(22.dp), elevation = 4.dp)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "بانک‌ها و سایت‌های ایرانی مستقیم باز می‌شوند",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = colors.textPrimary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        // Glossy 3D Iranian Flag Pebble
                        Box(
                            modifier = Modifier
                                .size(width = 24.dp, height = 15.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .border(0.5.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                        ) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Box(modifier = Modifier.weight(1f).fillMaxWidth().background(IranFlagGreen))
                                Box(modifier = Modifier.weight(1f).fillMaxWidth().background(IranFlagWhite), contentAlignment = Alignment.Center) {
                                    Box(modifier = Modifier.size(3.dp).clip(CircleShape).background(IranFlagRed))
                                }
                                Box(modifier = Modifier.weight(1f).fillMaxWidth().background(IranFlagRed))
                            }
                        }
                    }
                }

                // 2. Unified 3D Traffic Capsule (In / Out)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .luxuryCushion(shape = RoundedCornerShape(26.dp), elevation = 5.dp)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // IN (دریافت)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .luxuryWell(shape = CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowDownward,
                                    contentDescription = "دریافت",
                                    tint = colors.skyBlue,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "دریافت (In)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.textSecondary
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = downloadTraffic,
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = colors.textPrimary
                                    )
                                    if (downloadSpeed.isNotBlank()) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = downloadSpeed,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = colors.skyBlue
                                        )
                                    }
                                }
                            }
                        }

                        // Soft Vertical Divider
                        Box(
                            modifier = Modifier
                                .height(32.dp)
                                .width(1.dp)
                                .background(colors.dividerSoft)
                        )

                        // OUT (ارسال)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .luxuryWell(shape = CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowUpward,
                                    contentDescription = "ارسال",
                                    tint = colors.jadeGreen,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "ارسال (Out)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.textSecondary
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = uploadTraffic,
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = colors.textPrimary
                                    )
                                    if (uploadSpeed.isNotBlank()) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = uploadSpeed,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = colors.jadeGreen
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. Bottom CTA: Coral Pearl Sculpted Pill
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .shadow(
                            elevation = 4.dp,
                            shape = RoundedCornerShape(24.dp),
                            ambientColor = colors.coralRed.copy(alpha = 0.2f),
                            spotColor = colors.coralRed.copy(alpha = 0.2f)
                        )
                        .background(colors.coralCtaBrush, RoundedCornerShape(24.dp))
                        .border(1.dp, colors.coralCtaBorder, RoundedCornerShape(24.dp))
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
                            tint = colors.coralCtaText,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "خرید یا تمدید اشتراک در تلگرام",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = colors.coralCtaText
                        )
                    }
                }

                // 4. Switch PIN & Footer Version
                TextButton(
                    onClick = onChangePin,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "ورود با کد اشتراک دیگر",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textSecondary
                        )
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = null,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }

                Text(
                    text = "نسخه $appVersion • سامانه ابری آسان وی‌پی‌ان",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textMuted
                )
            }
        }
    }

    // Debug Report Dialog
    if (showDebugDialog) {
        val currentLogText = if (isAdminMode) AppLogger.getAdminLog() else AppLogger.getUserLog()
        AlertDialog(
            onDismissRequest = { showDebugDialog = false },
            title = {
                Text(
                    text = "گزارش وضعیت و عیب‌یابی",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = colors.textPrimary
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = currentLogText,
                        fontSize = 11.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = colors.textSecondary
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("AsanVPN Diagnostics", currentLogText))
                        Toast.makeText(context, "گزارش در حافظه کپی شد", Toast.LENGTH_SHORT).show()
                        showDebugDialog = false
                    }
                ) {
                    Text("کپی گزارش", color = colors.jadeGreen, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDebugDialog = false }) {
                    Text("بستن", color = colors.textSecondary)
                }
            }
        )
    }
}
