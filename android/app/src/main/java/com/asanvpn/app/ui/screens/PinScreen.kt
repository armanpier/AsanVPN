package com.asanvpn.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.asanvpn.app.data.PreferencesManager
import com.asanvpn.app.util.AppLogger
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asanvpn.app.data.VpnRepository
import com.asanvpn.app.ui.theme.*
import kotlinx.coroutines.delay

private const val PIN_LENGTH = 8

@Composable
fun PinScreen(
    isLoading: Boolean,
    errorMessage: String?,
    isSuccess: Boolean,
    lockoutUntil: Long = 0L,
    appVersion: String = "1.3.2",
    newVersionAvailable: String? = null,
    onCheckUpdate: () -> Unit = {},
    onPinComplete: (String) -> Unit,
    onLockoutExpired: () -> Unit = {}
) {
    var enteredPin by remember { mutableStateOf("") }
    var screenGlowColor by remember { mutableStateOf(Color.Transparent) }
    val context = LocalContext.current
    val prefs = remember { PreferencesManager(context) }
    var showDebugDialog by remember { mutableStateOf(false) }
    var showAdminAuthDialog by remember { mutableStateOf(false) }
    var adminPasswordInput by remember { mutableStateOf("") }
    var isAdminMode by remember { mutableStateOf(prefs.isAdmin) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // Live lockout ticker
    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lockoutUntil) {
        while (true) {
            currentTime = System.currentTimeMillis()
            if (lockoutUntil > 0L && currentTime >= lockoutUntil) {
                onLockoutExpired()
                break
            }
            delay(1000)
        }
    }

    val isLockedOut = lockoutUntil > currentTime
    val remainingSeconds = if (isLockedOut) maxOf(0L, (lockoutUntil - currentTime) / 1000) else 0L

    // Animate background glow
    val animatedBgColor by animateColorAsState(
        targetValue = screenGlowColor,
        animationSpec = tween(durationMillis = 400),
        label = "screen_glow"
    )

    fun performClickHaptic() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                v?.vibrate(35)
            }
        } catch (e: Exception) {}
    }

    fun performErrorHaptic() {
        try {
            val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 150, 80, 250), -1))
            } else {
                @Suppress("DEPRECATION")
                v?.vibrate(longArrayOf(0, 150, 80, 250), -1)
            }
        } catch (e: Exception) {}
    }

    fun performSuccessHaptic() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                v?.vibrate(80)
            }
        } catch (e: Exception) {}
    }

    // Effect on Error: clear numbers, vibrate, and red glow for 1 second
    LaunchedEffect(errorMessage) {
        if (!errorMessage.isNullOrEmpty()) {
            enteredPin = ""
            performErrorHaptic()
            screenGlowColor = RedOff.copy(alpha = 0.2f)
            delay(1000)
            screenGlowColor = Color.Transparent
        }
    }

    // Effect on Success: vibrate and green glow for 1 second
    LaunchedEffect(isSuccess) {
        if (isSuccess) {
            performSuccessHaptic()
            screenGlowColor = GreenConnected.copy(alpha = 0.25f)
            delay(1000)
            screenGlowColor = Color.Transparent
        }
    }

    fun onDigitPress(digit: String) {
        if (isLockedOut) return
        if (enteredPin.length < PIN_LENGTH && !isLoading && !isSuccess) {
            performClickHaptic()
            val newPin = enteredPin + digit
            enteredPin = newPin
            if (newPin.length == PIN_LENGTH) {
                onPinComplete(newPin)
            }
        }
    }

    fun onBackspacePress() {
        if (isLockedOut) return
        if (enteredPin.isNotEmpty() && !isLoading && !isSuccess) {
            performClickHaptic()
            enteredPin = enteredPin.dropLast(1)
        }
    }

    fun cleanPinDigits(raw: String): String {
        val persianDigits = "۰۱۲۳۴۵۶۷۸۹"
        val arabicDigits = "٠١٢٣٤٥٦٧٨٩"
        val sb = StringBuilder()
        for (ch in raw) {
            val pIdx = persianDigits.indexOf(ch)
            if (pIdx >= 0) {
                sb.append(pIdx)
                continue
            }
            val aIdx = arabicDigits.indexOf(ch)
            if (aIdx >= 0) {
                sb.append(aIdx)
                continue
            }
            if (ch in '0'..'9') {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun handlePasteFromClipboard() {
        if (isLockedOut) return
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clipData = clipboard?.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                val rawText = clipData.getItemAt(0)?.text?.toString() ?: ""
                val cleaned = cleanPinDigits(rawText)
                if (cleaned.length >= PIN_LENGTH) {
                    val finalPin = cleaned.take(PIN_LENGTH)
                    enteredPin = finalPin
                    performClickHaptic()
                    Toast.makeText(context, "کد ۸ رقمی جای‌گذاری شد", Toast.LENGTH_SHORT).show()
                    onPinComplete(finalPin)
                } else if (cleaned.isNotEmpty()) {
                    enteredPin = cleaned.take(PIN_LENGTH)
                    performClickHaptic()
                    Toast.makeText(context, "${cleaned.length} رقم جای‌گذاری شد", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "کد عددی در کلیپ‌بورد یافت نشد", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, "کلیپ‌بورد خالی است", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "خطا در خواندن کلیپ‌بورد", Toast.LENGTH_SHORT).show()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NeumorphBg)
    ) {
        // Dynamic Glow Layer (Red on Error / Green on Success)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(animatedBgColor)
        )

        // Main UI Column
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header Section
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 10.dp)
            ) {
                Text(
                    text = "کد ۸ رقمی خود را وارد کنید",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "کد ۸ رقمی اختصاصی خود را وارد نمایید",
                    fontSize = 13.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Invisible BasicTextField for keyboard handling
                BasicTextField(
                    value = enteredPin,
                    onValueChange = { newRaw ->
                        if (!isLockedOut && !isLoading && !isSuccess) {
                            val cleaned = cleanPinDigits(newRaw).take(PIN_LENGTH)
                            if (cleaned != enteredPin) {
                                performClickHaptic()
                                enteredPin = cleaned
                                if (cleaned.length == PIN_LENGTH) {
                                    onPinComplete(cleaned)
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .size(1.dp)
                        .focusRequester(focusRequester),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (enteredPin.length == PIN_LENGTH) {
                                onPinComplete(enteredPin)
                            }
                        }
                    )
                )

                // 8 Visible Pin Indicator Boxes (3D Neumorphic Inset Wells)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // First 4 Digits
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            for (i in 0 until 4) {
                                val hasDigit = i < enteredPin.length
                                val isCurrent = i == enteredPin.length && !isLockedOut
                                val digitChar = if (hasDigit) enteredPin[i].toString() else null

                                Box(
                                    modifier = Modifier
                                        .size(width = 34.dp, height = 48.dp)
                                        .then(
                                            if (hasDigit) {
                                                Modifier.neumorphicRaised(shape = RoundedCornerShape(12.dp), elevation = 4.dp)
                                            } else {
                                                Modifier.neumorphicSunken(shape = RoundedCornerShape(12.dp))
                                            }
                                        )
                                        .then(
                                            if (isCurrent) {
                                                Modifier.border(2.dp, AccentTeal, RoundedCornerShape(12.dp))
                                            } else if (isSuccess) {
                                                Modifier.border(2.dp, GreenConnected, RoundedCornerShape(12.dp))
                                            } else Modifier
                                        )
                                        .clickable {
                                            focusRequester.requestFocus()
                                            keyboardController?.show()
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (digitChar != null) {
                                        Text(
                                            text = digitChar,
                                            fontSize = 22.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = TextPrimary,
                                            textAlign = TextAlign.Center
                                        )
                                    } else if (isCurrent) {
                                        Box(
                                            modifier = Modifier
                                                .width(2.5.dp)
                                                .height(20.dp)
                                                .background(AccentTeal, RoundedCornerShape(1.dp))
                                        )
                                    } else {
                                        Text(
                                            text = "·",
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextMuted,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }

                        // Separator between the two groups of 4 digits
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .width(10.dp)
                                .height(3.dp)
                                .background(AccentTeal.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
                        )

                        // Second 4 Digits
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            for (i in 4 until PIN_LENGTH) {
                                val hasDigit = i < enteredPin.length
                                val isCurrent = i == enteredPin.length && !isLockedOut
                                val digitChar = if (hasDigit) enteredPin[i].toString() else null

                                Box(
                                    modifier = Modifier
                                        .size(width = 34.dp, height = 48.dp)
                                        .then(
                                            if (hasDigit) {
                                                Modifier.neumorphicRaised(shape = RoundedCornerShape(12.dp), elevation = 4.dp)
                                            } else {
                                                Modifier.neumorphicSunken(shape = RoundedCornerShape(12.dp))
                                            }
                                        )
                                        .then(
                                            if (isCurrent) {
                                                Modifier.border(2.dp, AccentTeal, RoundedCornerShape(12.dp))
                                            } else if (isSuccess) {
                                                Modifier.border(2.dp, GreenConnected, RoundedCornerShape(12.dp))
                                            } else Modifier
                                        )
                                        .clickable {
                                            focusRequester.requestFocus()
                                            keyboardController?.show()
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (digitChar != null) {
                                        Text(
                                            text = digitChar,
                                            fontSize = 22.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = TextPrimary,
                                            textAlign = TextAlign.Center
                                        )
                                    } else if (isCurrent) {
                                        Box(
                                            modifier = Modifier
                                                .width(2.5.dp)
                                                .height(20.dp)
                                                .background(AccentTeal, RoundedCornerShape(1.dp))
                                        )
                                    } else {
                                        Text(
                                            text = "·",
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextMuted,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Live Preview Badge when digits are entered
                if (enteredPin.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .neumorphicSunken(shape = RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "کد وارد شده:",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Text(
                                    text = enteredPin,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = AccentTeal,
                                    letterSpacing = 2.5.sp
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Paste from Clipboard Button (Neumorphic Raised Pill)
                Box(
                    modifier = Modifier
                        .neumorphicRaised(shape = RoundedCornerShape(16.dp), elevation = 4.dp)
                        .clickable(enabled = !isLockedOut) { handlePasteFromClipboard() }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentPaste,
                            contentDescription = "جای‌گذاری کد",
                            tint = AccentTeal,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "جای‌گذاری کد (Paste)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccentTeal
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Lockout Banner or Status Message
                if (isLockedOut) {
                    val mm = remainingSeconds / 60
                    val ss = remainingSeconds % 60
                    val timerStr = String.format("%02d:%02d", mm, ss)

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                            .neumorphicRaised(shape = RoundedCornerShape(16.dp), elevation = 4.dp)
                            .background(Color(0xFFFEF2F2), RoundedCornerShape(16.dp))
                            .border(1.dp, RedOff.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                            .padding(vertical = 12.dp, horizontal = 16.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LockClock,
                                    contentDescription = "قفل موقت",
                                    tint = RedOff,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "ورود ۳ بار ناموفق بود - حساب موقتاً مسدود شد",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = RedOff
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "امکان تلاش مجدد تا: $timerStr",
                                fontSize = 16.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }
                    }
                } else if (isLoading) {
                    CircularProgressIndicator(
                        color = AccentTeal,
                        modifier = Modifier.size(30.dp),
                        strokeWidth = 3.dp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "در حال بررسی کد اشتراک...",
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                } else if (!errorMessage.isNullOrEmpty()) {
                    Text(
                        text = errorMessage,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = RedOff,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            // ---------------- 3D NEUMORPHIC ATM-STYLE KEYPAD ----------------
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val rows = listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                    listOf("back", "0", "submit")
                )

                for (row in rows) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        for (item in row) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(66.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                val buttonAlpha = if (isLockedOut) 0.35f else 1f
                                when (item) {
                                    "back" -> {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .neumorphicRaised(shape = RoundedCornerShape(18.dp), elevation = 4.dp)
                                                .clickable(enabled = !isLockedOut) { onBackspacePress() },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.Backspace,
                                                contentDescription = "پاک کردن",
                                                tint = TextSecondary.copy(alpha = buttonAlpha),
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                    "submit" -> {
                                        val canSubmit = enteredPin.length == PIN_LENGTH && !isLoading && !isSuccess && !isLockedOut
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .then(
                                                    if (canSubmit) {
                                                        Modifier
                                                            .shadow(6.dp, RoundedCornerShape(18.dp), ambientColor = GreenConnected.copy(alpha = 0.4f))
                                                            .background(GreenConnected, RoundedCornerShape(18.dp))
                                                    } else {
                                                        Modifier.neumorphicRaised(shape = RoundedCornerShape(18.dp), elevation = 2.dp)
                                                    }
                                                )
                                                .clickable(enabled = canSubmit) {
                                                    performClickHaptic()
                                                    onPinComplete(enteredPin)
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = "تایید",
                                                    tint = if (canSubmit) Color.White else TextMuted.copy(alpha = 0.4f),
                                                    modifier = Modifier.size(24.dp)
                                                )
                                                if (canSubmit) {
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(
                                                        text = "تایید",
                                                        fontSize = 15.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color.White
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    else -> {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .neumorphicRaised(shape = RoundedCornerShape(18.dp), elevation = 5.dp)
                                                .clickable(enabled = !isLockedOut) { onDigitPress(item) },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = item,
                                                fontSize = 28.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = TextPrimary.copy(alpha = buttonAlpha)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Telegram Bot Buy / Renew Button (Neumorphic Warm Accent Pill)
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.88f)
                        .height(46.dp)
                        .padding(top = 4.dp)
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
                            contentDescription = "خرید اشتراک",
                            tint = AccentCoral,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "خرید یا تمدید اشتراک (ربات تلگرام)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccentCoral
                        )
                    }
                }

                // App Version and Discreet Debug Button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.clickable { onCheckUpdate() }
                    ) {
                        Text(
                            text = if (newVersionAvailable != null) "نسخه جدید: $newVersionAvailable" else "نسخه $appVersion",
                            fontSize = 11.sp,
                            fontWeight = if (newVersionAvailable != null) FontWeight.Bold else FontWeight.Normal,
                            color = if (newVersionAvailable != null) Color(0xFFDC2626) else TextMuted
                        )
                        if (newVersionAvailable != null) {
                            Icon(
                                imageVector = Icons.Default.SystemUpdate,
                                contentDescription = "به‌روزرسانی",
                                tint = Color(0xFFDC2626),
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    TextButton(
                        onClick = { showDebugDialog = true }
                    ) {
                        Icon(
                            imageVector = Icons.Default.BugReport,
                            contentDescription = "دیباگ",
                            tint = TextMuted,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "مشاهده گزارش خطا",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }
            }
        }

        // Debug Dialog (Dual-Tier Security: Simple user vs Admin mode)
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
                                .heightIn(min = 120.dp, max = 280.dp)
                                .neumorphicSunken(shape = RoundedCornerShape(12.dp))
                                .padding(12.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = currentLogText,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
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
                    Button(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("AsanVPN Debug Log", currentLogText)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "گزارش در کلیپ‌بورد کپی شد", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentTeal)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "کپی", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("کپی لاگ", fontSize = 13.sp)
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
}

@Preview(name = "Pin Screen - Neumorphic Light", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun PinScreenPreview() {
    AsanVpnTheme {
        PinScreen(
            isLoading = false,
            errorMessage = null,
            isSuccess = false,
            onPinComplete = {}
        )
    }
}

