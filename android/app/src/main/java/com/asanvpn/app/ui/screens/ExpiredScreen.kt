package com.asanvpn.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asanvpn.app.ui.theme.*

@Composable
fun ExpiredScreen(
    supportPhone: String,
    onCallSupport: () -> Unit,
    onChangePin: () -> Unit
) {
    val context = LocalContext.current
    val colors = LuxuryTheme.colors

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.backgroundBrush)
            .padding(28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Soft 3D Sunken Warning Icon Well
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .luxuryWell(shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(colors.coralRed.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "هشدار",
                        tint = colors.coralRed,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(26.dp))

            Text(
                text = "اشتراک شما به پایان رسیده است",
                fontSize = 21.sp,
                fontWeight = FontWeight.Black,
                color = colors.textPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "برای فعال‌سازی و تمدید آنی، از طریق ربات تلگرام اقدام فرمایید یا با کد اشتراک دیگری وارد شوید.",
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Telegram Bot Instant Renewal Button (Coral Sculpted Pill)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .shadow(
                        elevation = 5.dp,
                        shape = RoundedCornerShape(22.dp),
                        ambientColor = colors.coralRed.copy(alpha = 0.25f),
                        spotColor = colors.coralRed.copy(alpha = 0.25f)
                    )
                    .background(colors.coralCtaBrush, RoundedCornerShape(22.dp))
                    .border(1.dp, colors.coralCtaBorder, RoundedCornerShape(22.dp))
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
                        contentDescription = "تمدید در تلگرام",
                        tint = colors.coralCtaText,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "تمدید فوری اشتراک در تلگرام",
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = colors.coralCtaText
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Switch PIN Button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .luxuryCushion(shape = RoundedCornerShape(22.dp), elevation = 4.dp)
                    .clickable { onChangePin() },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = "تغییر کد",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "ورود با کد اشتراک دیگر",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )
                }
            }
        }
    }
}
