package com.asanvpn.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.asanvpn.app.ui.theme.*
import com.asanvpn.app.util.AppVersionInfo

@Composable
fun UpdateDialog(
    versionInfo: AppVersionInfo,
    isDownloading: Boolean,
    downloadProgress: Float,
    downloadedMb: Float,
    totalMb: Float,
    downloadError: String?,
    isDownloadComplete: Boolean,
    onConfirmUpdate: () -> Unit,
    onInstallApk: () -> Unit,
    onDismiss: () -> Unit
) {
    val animatedProgress by animateFloatAsState(
        targetValue = downloadProgress,
        label = "update_download_progress"
    )

    Dialog(
        onDismissRequest = {
            if (!isDownloading && !versionInfo.forceUpdate) {
                onDismiss()
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = !isDownloading && !versionInfo.forceUpdate,
            dismissOnClickOutside = !isDownloading && !versionInfo.forceUpdate
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .neumorphicRaised(shape = RoundedCornerShape(28.dp), elevation = 12.dp)
                .background(NeumorphSurface, RoundedCornerShape(28.dp))
                .border(1.dp, NeumorphBorderSubtle, RoundedCornerShape(28.dp))
                .padding(24.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header 3D Icon Box
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .neumorphicRaised(shape = CircleShape, elevation = 6.dp)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(AccentCyanSoft, Color.White)
                            ),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isDownloadComplete) Icons.Default.CheckCircle else Icons.Default.SystemUpdate,
                        contentDescription = null,
                        tint = if (isDownloadComplete) GreenConnected else AccentCoral,
                        modifier = Modifier.size(36.dp)
                    )
                }

                // Title & Version Chip
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "به‌روزرسانی جدید موجود است",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = TextPrimary,
                        textAlign = TextAlign.Center
                    )

                    Box(
                        modifier = Modifier
                            .neumorphicSunken(shape = RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "نسخه جدید ${versionInfo.versionName}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccentCoral
                        )
                    }
                }

                // Release notes / Description box
                if (!isDownloading && !isDownloadComplete) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .neumorphicSunken(shape = RoundedCornerShape(16.dp))
                            .padding(14.dp)
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val notes = versionInfo.releaseNotes?.takeIf { it.isNotBlank() }
                                ?: "طراحی جدید و مدرن سفید سه‌بعدی، آیکون اختصاصی و ارتقای پایداری اتصال"

                            Text(
                                text = "تغییرات این نسخه:",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = notes,
                                fontSize = 12.sp,
                                color = TextSecondary,
                                lineHeight = 18.sp
                            )
                        }
                    }

                    Text(
                        text = "آیا مایلید نسخه جدید هم‌اکنون به صورت مستقیم دریافت و نصب شود؟",
                        fontSize = 13.sp,
                        color = TextPrimary,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )
                }

                // Downloading Progress Section
                if (isDownloading) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .neumorphicSunken(shape = RoundedCornerShape(18.dp))
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "در حال دانلود نسخه جدید...",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            val computedRatio = if (totalMb > 0f) (downloadedMb / totalMb).coerceIn(0f, 1f) else 0f
                            val effectiveProgress = if (animatedProgress > 0.001f) animatedProgress else computedRatio
                            val percent = (effectiveProgress * 100).toInt().coerceIn(0, 100)
                            Text(
                                text = "$percent%",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = AccentCoral
                            )
                        }

                        // Linear Progress Indicator
                        LinearProgressIndicator(
                            progress = {
                                val computedRatio = if (totalMb > 0f) (downloadedMb / totalMb).coerceIn(0f, 1f) else 0f
                                if (animatedProgress > 0.001f) animatedProgress else computedRatio
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = AccentCoral,
                            trackColor = Color(0xFFE2E8F0),
                        )

                        // Size Info
                        val downloadedStr = String.format(java.util.Locale.US, "%.1f", downloadedMb)
                        val totalStr = if (totalMb > 0) String.format(java.util.Locale.US, "%.1f", totalMb) else "--"
                        Text(
                            text = "$downloadedStr مگابایت از $totalStr مگابایت",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }

                // Download Complete State
                if (isDownloadComplete) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .neumorphicSunken(shape = RoundedCornerShape(16.dp))
                            .padding(14.dp)
                    ) {
                        Text(
                            text = "فایل نصبی نسخه جدید با موفقیت دریافت شد.\nبرای تکمیل به‌روزرسانی روی دکمه زیر ضربه بزنید.",
                            fontSize = 12.sp,
                            color = GreenConnected,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            lineHeight = 18.sp,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // Error Message if any
                if (!downloadError.isNullOrBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(RedOff.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                            .border(1.dp, RedOff.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = RedOff,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = downloadError,
                            fontSize = 11.sp,
                            color = RedOff
                        )
                    }
                }

                // Action Buttons Row (Yes / No in Persian)
                when {
                    isDownloadComplete -> {
                        Button(
                            onClick = onInstallApk,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = GreenConnected
                            ),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
                        ) {
                            Text(
                                text = "نصب نسخه جدید",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                    isDownloading -> {
                        // User can cancel if not force update
                        if (!versionInfo.forceUpdate) {
                            TextButton(
                                onClick = onDismiss,
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text(
                                    text = "انصراف و بستن",
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                    else -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // "No" Button (فعلاً نه)
                            if (!versionInfo.forceUpdate) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .neumorphicRaised(shape = RoundedCornerShape(16.dp), elevation = 4.dp)
                                        .background(NeumorphSurface, RoundedCornerShape(16.dp))
                                        .border(1.dp, NeumorphBorderSubtle, RoundedCornerShape(16.dp))
                                        .clickable { onDismiss() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "فعلاً نه",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextSecondary
                                    )
                                }
                            }

                            // "Yes" Button (بله، دانلود و نصب)
                            Box(
                                modifier = Modifier
                                    .weight(1.3f)
                                    .height(48.dp)
                                    .neumorphicRaised(shape = RoundedCornerShape(16.dp), elevation = 6.dp)
                                    .background(
                                        Brush.horizontalGradient(
                                            colors = listOf(AccentCoral, Color(0xFFFF8A73))
                                        ),
                                        RoundedCornerShape(16.dp)
                                    )
                                    .clickable { onConfirmUpdate() },
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CloudDownload,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = "بله، به‌روزرسانی شود",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
