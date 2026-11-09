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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asanvpn.app.data.VpnRepository
import com.asanvpn.app.data.models.ConnectionState
import com.asanvpn.app.ui.theme.*
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
    onToggleConnect: () -> Unit,
    onContactSupport: () -> Unit = {},
    onChangePin: () -> Unit
) {
    val context = LocalContext.current
    var showDebugDialog by remember { mutableStateOf(false) }

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
    LaunchedEffect(connectionState) {
        if (connectionState == ConnectionState.CONNECTED) {
            connectedSeconds = 0L
            while (true) {
                delay(1000)
                connectedSeconds++
            }
        } else {
            connectedSeconds = 0L
        }
    }

    // Pulse animation for connecting state
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.05f,
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

    // Status attributes
    val dialGradient = when (connectionState) {
        ConnectionState.DISCONNECTED -> listOf(DialBgOff, Color(0xFF131720))
        ConnectionState.CONNECTING -> listOf(Color(0xFF3B2A08), Color(0xFF1E1706))
        ConnectionState.CONNECTED -> listOf(DialBgOn, Color(0xFF042F24))
    }

    val dialBorderColor = when (connectionState) {
        ConnectionState.DISCONNECTED -> DialBorderOff
        ConnectionState.CONNECTING -> AmberConnecting
        ConnectionState.CONNECTED -> DialBorderOn
    }

    val iconColor = when (connectionState) {
        ConnectionState.DISCONNECTED -> RedOff
        ConnectionState.CONNECTING -> AmberConnecting
        ConnectionState.CONNECTED -> GreenOnLight
    }

    val statusBadgeText = when (connectionState) {
        ConnectionState.DISCONNECTED -> "خاموش"
        ConnectionState.CONNECTING -> "در حال برقراری ارتباط..."
        ConnectionState.CONNECTED -> "متصل و محافظت‌شده"
    }

    val statusSubText = when (connectionState) {
        ConnectionState.DISCONNECTED -> "برای روشن شدن وی‌پی‌ان لمس کنید"
        ConnectionState.CONNECTING -> "در حال پیدا کردن سریع‌ترین مسیر..."
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
            .background(
                Brush.verticalGradient(
                    colors = listOf(BgDark, BgGradientBottom)
                )
            )
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
            // Top App Bar: Debug button & User Profile
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Discreet Debug Info Icon
                IconButton(
                    onClick = { showDebugDialog = true },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "گزارش وضعیت",
                        tint = TextDarker,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // User Profile Chip
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = userName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextWhite
                    )
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(CardDarkElevated)
                            .border(1.dp, CardBorder, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = AccentCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ---------------- SUBSCRIPTION STATUS PILLS (DAYS & TRAFFIC) ----------------
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Subscription Days Remaining Pill
                val isUnlimitedDays = daysLeft < 0
                val isCriticalDays = !isUnlimitedDays && daysLeft in 0..3
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(CardDarkElevated)
                        .border(
                            width = 1.dp,
                            color = if (isCriticalDays) RedOff.copy(alpha = 0.5f) else CardBorder,
                            shape = RoundedCornerShape(14.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            tint = if (isCriticalDays) RedOff else AccentCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = when {
                                isUnlimitedDays -> "اشتراک نامحدود"
                                daysLeft == 0 -> "امروز آخرین مهلت"
                                else -> "$daysLeft روز اعتبار"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isCriticalDays) RedOff else TextWhite
                        )
                    }
                }

                // Traffic Remaining Pill
                val isTrafficUnlimited = trafficRemainingBytes < 0L || trafficTotalBytes <= 0L
                val isLowRemaining = !isTrafficUnlimited && trafficRemainingBytes in 1 until (1024L * 1024L * 1024L)
                val isExhaustedRemaining = !isTrafficUnlimited && trafficRemainingBytes == 0L
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(CardDarkElevated)
                        .border(
                            width = 1.dp,
                            color = when {
                                isExhaustedRemaining -> RedOff.copy(alpha = 0.7f)
                                isLowRemaining -> Color(0xFFF59E0B).copy(alpha = 0.7f)
                                else -> CardBorder
                            },
                            shape = RoundedCornerShape(14.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DataUsage,
                            contentDescription = null,
                            tint = when {
                                isExhaustedRemaining -> RedOff
                                isLowRemaining -> Color(0xFFF59E0B)
                                else -> AccentCyan
                            },
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (isTrafficUnlimited) "حجم نامحدود" else "${formatTrafficRemaining(trafficRemainingBytes)} باقی‌مانده",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                isExhaustedRemaining -> RedOff
                                isLowRemaining -> Color(0xFFF59E0B)
                                else -> TextWhite
                            }
                        )
                    }
                }
            }

            // ---------------- PERSISTENT LOW TRAFFIC WARNING NOTIFICATION ----------------
            val isTrafficUnlimited = trafficRemainingBytes < 0L || trafficTotalBytes <= 0L
            val isLowTrafficWarning = !isTrafficUnlimited && trafficRemainingBytes in 1 until (1024L * 1024L * 1024L)
            val isExhaustedWarning = !isTrafficUnlimited && trafficRemainingBytes == 0L

            if (isLowTrafficWarning || isExhaustedWarning) {
                Spacer(modifier = Modifier.height(10.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isExhaustedWarning) Color(0xFF330C0C) else Color(0xFF2E1A05)
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isExhaustedWarning) RedOff.copy(alpha = 0.8f) else Color(0xFFF59E0B).copy(alpha = 0.8f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isExhaustedWarning) RedOff.copy(alpha = 0.2f) else Color(0xFFF59E0B).copy(alpha = 0.2f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "هشدار حجم",
                                tint = if (isExhaustedWarning) RedOff else Color(0xFFF59E0B),
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
                                    "⚠️ حجم رو به پایان است: کمتر از ۱ گیگابایت (${formatTrafficRemaining(trafficRemainingBytes)})",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isExhaustedWarning) RedOff else Color(0xFFFFB74D),
                                textAlign = TextAlign.Right
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "جهت جلوگیری از قطع ارتباط، نسبت به تمدید اشتراک اقدام فرمایید.",
                                fontSize = 11.sp,
                                color = TextMuted,
                                textAlign = TextAlign.Right
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ---------------- SERVER SELECTION & QUALITY CARD ----------------
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = CardDark),
                border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Ping latency badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0F172A))
                        .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
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
                                    .background(if (connectionState == ConnectionState.CONNECTED) GreenOn else TextDarker)
                            )
                            Text(
                                text = "$serverPing ms",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (connectionState == ConnectionState.CONNECTED) GreenOnLight else TextMuted
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
                                color = TextWhite
                            )
                            Icon(
                                imageVector = Icons.Default.Public,
                                contentDescription = null,
                                tint = AccentCyan,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "انتخاب هوشمند بهترین سرور با پینگ پایین",
                            fontSize = 11.sp,
                            color = TextDarker
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ---------------- CENTERPIECE: SLEEK NEAT POWER DIAL ----------------
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
                        indication = null,
                        enabled = connectionState != ConnectionState.CONNECTING
                    ) {
                        onToggleConnect()
                    },
                contentAlignment = Alignment.Center
            ) {
                // Outer Ambient Glow
                if (connectionState != ConnectionState.DISCONNECTED) {
                    Box(
                        modifier = Modifier
                            .size(230.dp)
                            .scale(if (connectionState == ConnectionState.CONNECTING) pulseScale else 1f)
                            .clip(CircleShape)
                            .background(
                                if (connectionState == ConnectionState.CONNECTED) {
                                    GreenOnGlow.copy(alpha = glowAlpha)
                                } else {
                                    AmberGlow.copy(alpha = glowAlpha)
                                }
                            )
                    )
                }

                // Decorative Outer Ring
                Box(
                    modifier = Modifier
                        .size(210.dp)
                        .clip(CircleShape)
                        .border(
                            width = 2.dp,
                            color = dialBorderColor.copy(alpha = 0.4f),
                            shape = CircleShape
                        )
                )

                // Interactive Main Power Dial
                Box(
                    modifier = Modifier
                        .size(180.dp)
                        .shadow(elevation = 16.dp, shape = CircleShape)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(colors = dialGradient)
                        )
                        .border(
                            width = 3.dp,
                            color = dialBorderColor,
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
                            modifier = Modifier.size(64.dp)
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
                    ConnectionState.DISCONNECTED -> TextWhite
                    ConnectionState.CONNECTING -> AmberConnecting
                    ConnectionState.CONNECTED -> GreenOnLight
                },
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = statusSubText,
                fontSize = 12.sp,
                color = TextMuted,
                textAlign = TextAlign.Center
            )

            // Dynamic Live Connection Duration Counter
            AnimatedVisibility(visible = connectionState == ConnectionState.CONNECTED) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(CardDarkElevated)
                            .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Timer,
                                contentDescription = null,
                                tint = GreenOn,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "مدت اتصال: ${formatDuration(connectedSeconds)}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextWhite
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ---------------- IRAN SPLIT-TUNNELING ASSURANCE PILL ----------------
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF141922))
                .border(1.dp, Color(0xFF1F2633), RoundedCornerShape(14.dp))
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
                    fontWeight = FontWeight.Medium,
                    color = TextMuted,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ---------------- BOTTOM ACTIONS: SUPPORT & SWITCH PIN ----------------
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ---------------- TRAFFIC STATISTICS (IN / OUT) ----------------
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = CardDarkElevated),
                border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
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
                                .background(AccentCyan.copy(alpha = 0.15f), CircleShape),
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
                                color = TextMuted
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = downloadTraffic,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextWhite
                                )
                                if (downloadSpeed.isNotBlank()) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = downloadSpeed,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = AccentCyan
                                    )
                                }
                            }
                        }
                    }

                    // Vertical Divider
                    Box(
                        modifier = Modifier
                            .height(30.dp)
                            .width(1.dp)
                            .background(CardBorder)
                    )

                    // OUT (ارسال / آپلود)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .background(GreenOn.copy(alpha = 0.15f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowUpward,
                                contentDescription = "ارسال (Out)",
                                tint = GreenOn,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "ارسال (Out)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextMuted
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = uploadTraffic,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextWhite
                                )
                                if (uploadSpeed.isNotBlank()) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = uploadSpeed,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = GreenOn
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Telegram Bot Buy / Renew Button
            OutlinedButton(
                onClick = {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/asan_vpn_p_bot"))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(context, "تلگرام یافت نشد: t.me/asan_vpn_p_bot", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AmberConnecting.copy(alpha = 0.5f))
            ) {
                Icon(
                    imageVector = Icons.Default.ShoppingCart,
                    contentDescription = null,
                    tint = AmberConnecting,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "خرید یا تمدید اشتراک در تلگرام",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = AmberConnecting
                )
            }

            // Change PIN / Switch Account
            TextButton(
                onClick = onChangePin,
                modifier = Modifier.padding(bottom = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = null,
                    tint = TextDarker,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "ورود با کد اشتراک دیگر",
                    fontSize = 13.sp,
                    color = TextMuted
                )
            }
        }
    }

    // ---------------- DEBUG LOG DIALOG (For Seller / Admin) ----------------
    if (showDebugDialog) {
        AlertDialog(
            onDismissRequest = { showDebugDialog = false },
            title = {
                Text(
                    text = "گزارش وضعیت اتصال",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite
                )
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = VpnRepository.lastDebugLog,
                        fontSize = 12.sp,
                        color = TextMuted,
                        lineHeight = 16.sp
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("AsanVPN Debug Log", VpnRepository.lastDebugLog)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "گزارش در حافظه کپی شد", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("کپی گزارش", color = AccentCyan)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDebugDialog = false }) {
                    Text("بستن", color = TextMuted)
                }
            },
            containerColor = CardDark,
            shape = RoundedCornerShape(16.dp)
        )
    }
}
