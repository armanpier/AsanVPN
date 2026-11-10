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
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
    onPinComplete: (String) -> Unit,
    onLockoutExpired: () -> Unit = {}
) {
    var enteredPin by remember { mutableStateOf("") }
    var screenGlowColor by remember { mutableStateOf(Color.Transparent) }
    var showDebugDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
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
            screenGlowColor = Color(0xFFB71C1C).copy(alpha = 0.45f) // Red Glow
            delay(1000)
            screenGlowColor = Color.Transparent
        }
    }

    // Effect on Success: vibrate and green glow for 1 second
    LaunchedEffect(isSuccess) {
        if (isSuccess) {
            performSuccessHaptic()
            screenGlowColor = Color(0xFF2E7D32).copy(alpha = 0.55f) // Green Glow
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
            .background(BgDark)
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
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header Section
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 12.dp)
            ) {
                Text(
                    text = "کد ۸ رقمی خود را وارد کنید",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "کد ۸ رقمی اختصاصی خود را وارد نمایید",
                    fontSize = 14.sp,
                    color = TextMuted,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Invisible BasicTextField to handle software keyboard and hardware keyboard input seamlessly
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

                // 8 Visible Pin Indicator Boxes (Always LTR: Left to Right)
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
                                val boxBorderColor = when {
                                    isSuccess -> GreenOn
                                    errorMessage != null && enteredPin.isEmpty() -> RedOff.copy(alpha = 0.5f)
                                    isCurrent -> AccentCyan
                                    hasDigit -> GreenOnLight
                                    else -> CardBorder
                                }
                                val boxBgColor = when {
                                    isSuccess -> GreenOn.copy(alpha = 0.15f)
                                    hasDigit -> CardDarkElevated
                                    isCurrent -> Color(0xFF131C28)
                                    else -> CardDark
                                }

                                Box(
                                    modifier = Modifier
                                        .size(width = 34.dp, height = 48.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(boxBgColor)
                                        .border(
                                            width = if (isCurrent || hasDigit) 2.dp else 1.dp,
                                            color = boxBorderColor,
                                            shape = RoundedCornerShape(10.dp)
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
                                            color = Color.White,
                                            textAlign = TextAlign.Center
                                        )
                                    } else if (isCurrent) {
                                        Box(
                                            modifier = Modifier
                                                .width(2.5.dp)
                                                .height(20.dp)
                                                .background(AccentCyan, RoundedCornerShape(1.dp))
                                        )
                                    } else {
                                        Text(
                                            text = "·",
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextDarker,
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
                                .height(2.5.dp)
                                .background(AccentCyan.copy(alpha = 0.6f), RoundedCornerShape(1.dp))
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
                                val boxBorderColor = when {
                                    isSuccess -> GreenOn
                                    errorMessage != null && enteredPin.isEmpty() -> RedOff.copy(alpha = 0.5f)
                                    isCurrent -> AccentCyan
                                    hasDigit -> GreenOnLight
                                    else -> CardBorder
                                }
                                val boxBgColor = when {
                                    isSuccess -> GreenOn.copy(alpha = 0.15f)
                                    hasDigit -> CardDarkElevated
                                    isCurrent -> Color(0xFF131C28)
                                    else -> CardDark
                                }

                                Box(
                                    modifier = Modifier
                                        .size(width = 34.dp, height = 48.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(boxBgColor)
                                        .border(
                                            width = if (isCurrent || hasDigit) 2.dp else 1.dp,
                                            color = boxBorderColor,
                                            shape = RoundedCornerShape(10.dp)
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
                                            color = Color.White,
                                            textAlign = TextAlign.Center
                                        )
                                    } else if (isCurrent) {
                                        Box(
                                            modifier = Modifier
                                                .width(2.5.dp)
                                                .height(20.dp)
                                                .background(AccentCyan, RoundedCornerShape(1.dp))
                                        )
                                    } else {
                                        Text(
                                            text = "·",
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextDarker,
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
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF162334),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AccentCyan.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "کد وارد شده:",
                                fontSize = 12.sp,
                                color = TextMuted
                            )
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Text(
                                    text = enteredPin,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = GreenOnLight,
                                    letterSpacing = 2.5.sp
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Action Buttons Row: Paste from Clipboard
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Paste from Clipboard Button
                    Surface(
                        onClick = { handlePasteFromClipboard() },
                        shape = RoundedCornerShape(16.dp),
                        color = CardDarkElevated,
                        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = "جای‌گذاری کد",
                                tint = AccentCyan,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "جای‌گذاری کد (Paste)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AccentCyan
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Lockout Banner or Status Message
                if (isLockedOut) {
                    val mm = remainingSeconds / 60
                    val ss = remainingSeconds % 60
                    val timerStr = String.format("%02d:%02d", mm, ss)

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = RedOff.copy(alpha = 0.12f)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, RedOff.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp, horizontal = 16.dp),
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
                                color = TextWhite
                            )
                        }
                    }
                } else if (isLoading) {
                    CircularProgressIndicator(
                        color = AmberConnecting,
                        modifier = Modifier.size(30.dp),
                        strokeWidth = 3.dp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "در حال بررسی کد اشتراک...",
                        fontSize = 14.sp,
                        color = AmberConnecting
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

            // Giant ATM-Style Keypad
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
                                    .height(68.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                val buttonAlpha = if (isLockedOut) 0.35f else 1f
                                when (item) {
                                    "back" -> {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(RoundedCornerShape(18.dp))
                                                .background(KeypadButtonBg.copy(alpha = 0.7f * buttonAlpha))
                                                .clickable(enabled = !isLockedOut) { onBackspacePress() },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.Backspace,
                                                contentDescription = "پاک کردن",
                                                tint = TextWhite.copy(alpha = buttonAlpha),
                                                modifier = Modifier.size(26.dp)
                                            )
                                        }
                                    }
                                    "submit" -> {
                                        val canSubmit = enteredPin.length == PIN_LENGTH && !isLoading && !isSuccess && !isLockedOut
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(RoundedCornerShape(18.dp))
                                                .background(
                                                    if (canSubmit) GreenOn else KeypadButtonBg.copy(alpha = 0.25f * buttonAlpha)
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
                                                    tint = if (canSubmit) Color.White else TextMuted.copy(alpha = 0.25f * buttonAlpha),
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
                                                .clip(RoundedCornerShape(18.dp))
                                                .background(KeypadButtonBg.copy(alpha = buttonAlpha))
                                                .clickable(enabled = !isLockedOut) { onDigitPress(item) },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = item,
                                                fontSize = 30.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = KeypadButtonText.copy(alpha = buttonAlpha)
                                            )
                                        }
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
                        .fillMaxWidth(0.85f)
                        .padding(top = 8.dp, bottom = 4.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = AmberConnecting
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AmberConnecting.copy(alpha = 0.6f))
                ) {
                    Icon(
                        imageVector = Icons.Default.ShoppingCart,
                        contentDescription = "خرید اشتراک",
                        tint = AmberConnecting,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "خرید یا تمدید اشتراک (ربات تلگرام)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = AmberConnecting
                    )
                }

                // App Version and Discreet Debug Button at the bottom
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "نسخه ۱.۱.۱",
                        fontSize = 11.sp,
                        color = TextMuted.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    TextButton(
                        onClick = { showDebugDialog = true }
                    ) {
                        Icon(
                            imageVector = Icons.Default.BugReport,
                            contentDescription = "دیباگ",
                            tint = TextMuted.copy(alpha = 0.5f),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "مشاهده گزارش خطا",
                            fontSize = 11.sp,
                            color = TextMuted.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }

        // Debug Dialog (Shows technical details without leaking to normal users)
        if (showDebugDialog) {
            AlertDialog(
                onDismissRequest = { showDebugDialog = false },
                title = {
                    Text(
                        text = "گزارش فنی وضعیت شبکه",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState())
                            .background(CardDark, RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = VpnRepository.lastDebugLog,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = AmberConnecting
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("AsanVPN Debug Log", VpnRepository.lastDebugLog)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "گزارش در کلیپ‌بورد کپی شد", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = GreenOn)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "کپی", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("کپی لاگ", fontSize = 13.sp)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDebugDialog = false }) {
                        Text("بستن", color = TextMuted)
                    }
                },
                containerColor = BgDark
            )
        }
    }
}
