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
import androidx.compose.ui.draw.shadow
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
    val colors = LuxuryTheme.colors

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
                .padding(horizontal = 4.dp)
                .luxuryCushion(shape = RoundedCornerShape(28.dp), elevation = 12.dp)
                .padding(22.dp)
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
                        .luxuryWell(shape = CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(
                                if (isDownloadComplete) colors.jadeGreen.copy(alpha = 0.2f) else colors.skyBlue.copy(alpha = 0.2f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isDownloadComplete) Icons.Default.CheckCircle else Icons.Default.SystemUpdate,
                            contentDescription = null,
                            tint = if (isDownloadComplete) colors.jadeGreen else colors.skyBlue,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                // Title & Version Chip
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "به‌روزرسانی جدید موجود است",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        color = colors.textPrimary,
                        textAlign = TextAlign.Center
                    )

                    Box(
                        modifier = Modifier
                            .luxuryWell(shape = RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "نسخه جدید ${versionInfo.versionName}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = colors.skyBlue
                        )
                    }
                }

                // Release notes / Description box
                if (!isDownloading && !isDownloadComplete) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .luxuryWell(shape = RoundedCornerShape(16.dp))
                            .padding(14.dp)
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val notes = versionInfo.releaseNotes?.takeIf { it.isNotBlank() }
                                ?: "طراحی جدید لوکس سه‌بعدی نرم (پورسلین و آبسیدین)، سرعت و پایداری ارتقا یافته"

                            Text(
                                text = "تغییرات این نسخه:",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary
                            )
                            Text(
                                text = notes,
                                fontSize = 12.sp,
                                color = colors.textSecondary,
                                lineHeight = 18.sp
                            )
                        }
                    }

                    Text(
                        text = "آیا مایلید نسخه جدید هم‌اکنون به صورت مستقیم دریافت و نصب شود؟",
                        fontSize = 13.sp,
                        color = colors.textPrimary,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )
                }

                // Downloading Progress Section
                if (isDownloading) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .luxuryWell(shape = RoundedCornerShape(18.dp))
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
                                color = colors.textPrimary
                            )
                            val computedRatio = if (totalMb > 0f) (downloadedMb / totalMb).coerceIn(0f, 1f) else 0f
                            val effectiveProgress = if (animatedProgress > 0.001f) animatedProgress else computedRatio
                            val percent = (effectiveProgress * 100).toInt().coerceIn(0, 100)
                            Text(
                                text = "$percent%",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = colors.skyBlue
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
                            color = colors.skyBlue,
                            trackColor = colors.cushionShadowAmbient.copy(alpha = 0.2f),
                        )

                        // Size Info
                        val downloadedStr = String.format(java.util.Locale.US, "%.1f", downloadedMb)
                        val totalStr = if (totalMb > 0) String.format(java.util.Locale.US, "%.1f", totalMb) else "--"
                        Text(
                            text = "$downloadedStr مگابایت از $totalStr مگابایت",
                            fontSize = 11.sp,
                            color = colors.textSecondary
                        )
                    }
                }

                // Download Complete State
                if (isDownloadComplete) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .luxuryWell(shape = RoundedCornerShape(16.dp))
                            .background(colors.jadeGreen.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                            .padding(14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "دانلود با موفقیت انجام شد. آماده نصب است.",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.jadeGreen,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Error Message
                if (!downloadError.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .luxuryWell(shape = RoundedCornerShape(14.dp))
                            .background(colors.coralRed.copy(alpha = 0.1f), RoundedCornerShape(14.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "خطا در دانلود: $downloadError",
                            fontSize = 11.sp,
                            color = colors.coralRed,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (!versionInfo.forceUpdate && !isDownloading) {
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "بعداً",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.textSecondary
                            )
                        }
                    }

                    if (!isDownloading && !isDownloadComplete) {
                        Button(
                            onClick = onConfirmUpdate,
                            modifier = Modifier.weight(1.5f),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.jadeGreen),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudDownload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "دانلود و نصب",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    if (isDownloadComplete) {
                        Button(
                            onClick = onInstallApk,
                            modifier = Modifier.weight(1.5f),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.jadeGreen),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(
                                text = "نصب نسخه جدید",
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
