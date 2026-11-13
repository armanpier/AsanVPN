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

private const val PIN_LENGTH = 10

@Composable
fun PinScreen(
    isLoading: Boolean,
    errorMessage: String?,
    isSuccess: Boolean,
    lockoutUntil: Long = 0L,
    appVersion: String = "1.3.11",
    newVersionAvailable: String? = null,
    isDarkMode: Boolean = false,
    onToggleTheme: () -> Unit = {},
    onCheckUpdate: () -> Unit = {},
    onPinComplete: (String) -> Unit,
    onLockoutExpired: () -> Unit = {},
    onCheckLockout: (String, () -> Unit) -> Unit = { _, done -> done() }
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

    val colors = LuxuryTheme.colors

    // Live lockout ticker
    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lockoutUntil) {
        var tickCounter = 0
        while (true) {
            currentTime = System.currentTimeMillis()
            if (lockoutUntil > 0L && currentTime >= lockoutUntil) {
                onLockoutExpired()
                break
            }
            if (lockoutUntil > currentTime) {
                tickCounter++
                if (tickCounter % 15 == 0) {
                    onCheckLockout(enteredPin.ifEmpty { prefs.savedPin ?: "" }) {}
                }
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
            screenGlowColor = colors.coralRed.copy(alpha = 0.2f)
            delay(1000)
            screenGlowColor = Color.Transparent
        }
    }

    // Effect on Success: vibrate and green glow for 1 second
    LaunchedEffect(isSuccess) {
        if (isSuccess) {
            performSuccessHaptic()
            screenGlowColor = colors.jadeGreen.copy(alpha = 0.25f)
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
                    Toast.makeText(context, "کد ۱۰ رقمی جای‌گذاری شد", Toast.LENGTH_SHORT).show()
                    onPinComplete(finalPin)
                } else if (cleaned.length == 8) {
                    val finalPin = cleaned
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
            .background(colors.backgroundBrush)
    ) {
        // Dynamic Glow Layer
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(animatedBgColor)
        )

        val scrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header Bar with Theme Toggle & Info
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Info (i) button
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .luxuryCushion(shape = RoundedCornerShape(16.dp), elevation = 4.dp)
                        .clickable { showDebugDialog = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "اطلاعات",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(19.dp)
                    )
                }

                // Title Brand Badge
                Text(
                    text = "آسان وی‌پی‌ان",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black,
                    color = colors.textPrimary
                )

                // Theme Switcher Button
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .luxuryCushion(shape = RoundedCornerShape(16.dp), elevation = 4.dp)
                        .clickable { onToggleTheme() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (colors.isDark) Icons.Default.LightMode else Icons.Default.DarkMode,
                        contentDescription = "تغییر تم",
                        tint = if (colors.isDark) Color(0xFFFBBF24) else Color(0xFF6366F1),
                        modifier = Modifier.size(19.dp)
                    )
                }
            }

            // Input Instructions
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Text(
                    text = "کد ۱۰ رقمی خود را وارد کنید",
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Black,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "کد اشتراک اختصاصی خود را وارد یا جای‌گذاری نمایید",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(18.dp))

                // Invisible BasicTextField for keyboard handling
                BasicTextField(
                    value = enteredPin,
                    onValueChange = { newRaw ->
                        if (!isLockedOut && !isLoading && !isSuccess) {
                            val cleaned = cleanPinDigits(newRaw).take(PIN_LENGTH)
                            val isPasteJump = cleaned.length - enteredPin.length > 1
                            if (cleaned != enteredPin) {
                                performClickHaptic()
                                enteredPin = cleaned
                                if (cleaned.length == PIN_LENGTH) {
                                    onPinComplete(cleaned)
                                } else if (isPasteJump && cleaned.length == 8) {
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
                            if (enteredPin.length == PIN_LENGTH || enteredPin.length == 8) {
                                onPinComplete(enteredPin)
                            }
                        }
                    )
                )

                // 10 Visible Pin Indicator Boxes (Two rows of 5 luxury wells)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Row 1: Digits 1 to 5
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            for (i in 0 until 5) {
                                val hasDigit = i < enteredPin.length
                                val isCurrent = i == enteredPin.length && !isLockedOut
                                val digitChar = if (hasDigit) enteredPin[i].toString() else null

                                Box(
                                    modifier = Modifier
                                        .size(width = 46.dp, height = 48.dp)
                                        .then(
                                            if (hasDigit) {
                                                Modifier.luxuryCushion(shape = RoundedCornerShape(14.dp), elevation = 4.dp)
                                            } else {
                                                Modifier.luxuryWell(shape = RoundedCornerShape(14.dp))
                                            }
                                        )
                                        .then(
                                            if (isCurrent) {
                                                Modifier.border(2.dp, colors.jadeGreen, RoundedCornerShape(14.dp))
                                            } else if (isSuccess) {
                                                Modifier.border(2.dp, colors.jadeGreen, RoundedCornerShape(14.dp))
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
                                            color = colors.textPrimary,
                                            textAlign = TextAlign.Center
                                        )
                                    } else if (isCurrent) {
                                        Box(
                                            modifier = Modifier
                                                .width(2.5.dp)
                                                .height(18.dp)
                                                .background(colors.jadeGreen, RoundedCornerShape(1.dp))
                                        )
                                    } else {
                                        Text(
                                            text = "·",
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = colors.textMuted,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }

                        // Row 2: Digits 6 to 10
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            for (i in 5 until PIN_LENGTH) {
                                val hasDigit = i < enteredPin.length
                                val isCurrent = i == enteredPin.length && !isLockedOut
                                val digitChar = if (hasDigit) enteredPin[i].toString() else null

                                Box(
                                    modifier = Modifier
                                        .size(width = 46.dp, height = 48.dp)
                                        .then(
                                            if (hasDigit) {
                                                Modifier.luxuryCushion(shape = RoundedCornerShape(14.dp), elevation = 4.dp)
                                            } else {
                                                Modifier.luxuryWell(shape = RoundedCornerShape(14.dp))
                                            }
                                        )
                                        .then(
                                            if (isCurrent) {
                                                Modifier.border(2.dp, colors.jadeGreen, RoundedCornerShape(14.dp))
                                            } else if (isSuccess) {
                                                Modifier.border(2.dp, colors.jadeGreen, RoundedCornerShape(14.dp))
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
                                            color = colors.textPrimary,
                                            textAlign = TextAlign.Center
                                        )
                                    } else if (isCurrent) {
                                        Box(
                                            modifier = Modifier
                                                .width(2.5.dp)
                                                .height(18.dp)
                                                .background(colors.jadeGreen, RoundedCornerShape(1.dp))
                                        )
                                    } else {
                                        Text(
                                            text = "·",
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = colors.textMuted,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Live Preview Badge
                if (enteredPin.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .luxuryWell(shape = RoundedCornerShape(16.dp))
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "کد وارد شده:",
                                fontSize = 12.sp,
                                color = colors.textSecondary
                            )
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                val formattedPin = when {
                                    enteredPin.length == 8 -> enteredPin.take(4) + " " + enteredPin.drop(4)
                                    enteredPin.length > 5 -> enteredPin.take(5) + " " + enteredPin.drop(5)
                                    else -> enteredPin
                                }
                                Text(
                                    text = formattedPin,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = colors.jadeGreen,
                                    letterSpacing = 2.sp
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Paste from Clipboard Button
                Box(
                    modifier = Modifier
                        .luxuryCushion(shape = RoundedCornerShape(16.dp), elevation = 4.dp)
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
                            tint = colors.skyBlue,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "جای‌گذاری کد (Paste)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.skyBlue
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Lockout Banner or Status Message
                if (isLockedOut) {
                    val mm = remainingSeconds / 60
                    val ss = remainingSeconds % 60
                    val timerStr = String.format("%02d:%02d", mm, ss)

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .luxuryCushion(shape = RoundedCornerShape(18.dp), elevation = 4.dp)
                            .border(1.dp, colors.coralRed.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
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
                                    tint = colors.coralRed,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "ورود ۳ بار ناموفق بود - حساب موقتاً مسدود شد",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.coralRed
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "امکان تلاش مجدد تا: $timerStr",
                                fontSize = 16.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            var isCheckingUnlock by remember { mutableStateOf(false) }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(colors.jadeGreen.copy(alpha = 0.15f))
                                    .clickable(enabled = !isCheckingUnlock) {
                                        isCheckingUnlock = true
                                        onCheckLockout(enteredPin.ifEmpty { prefs.savedPin ?: "" }) {
                                            isCheckingUnlock = false
                                        }
                                    }
                                    .padding(horizontal = 14.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    text = if (isCheckingUnlock) "در حال استعلام..." else "🔓 استعلام رفع مسدودی از سرور",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.jadeGreen
                                )
                            }
                        }
                    }
                } else if (isLoading) {
                    CircularProgressIndicator(
                        color = colors.jadeGreen,
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "در حال بررسی کد اشتراک...",
                        fontSize = 12.sp,
                        color = colors.textSecondary
                    )
                } else if (!errorMessage.isNullOrEmpty()) {
                    Text(
                        text = errorMessage,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.coralRed,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            // ==================== 3D ATM-STYLE TACTILE KEYPAD ====================
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
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
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        for (item in row) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(56.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                val buttonAlpha = if (isLockedOut) 0.35f else 1f
                                when (item) {
                                    "back" -> {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .luxuryCushion(shape = RoundedCornerShape(18.dp), elevation = 4.dp)
                                                .clickable(enabled = !isLockedOut) { onBackspacePress() },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.Backspace,
                                                contentDescription = "پاک کردن",
                                                tint = colors.textSecondary.copy(alpha = buttonAlpha),
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                    }
                                    "submit" -> {
                                        val canSubmit = (enteredPin.length == PIN_LENGTH || enteredPin.length == 8) && !isLoading && !isSuccess && !isLockedOut
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .then(
                                                    if (canSubmit) {
                                                        Modifier
                                                            .shadow(6.dp, RoundedCornerShape(18.dp), ambientColor = colors.jadeGreen.copy(alpha = 0.45f))
                                                            .background(colors.jadeGreen, RoundedCornerShape(18.dp))
                                                    } else {
                                                        Modifier.luxuryCushion(shape = RoundedCornerShape(18.dp), elevation = 3.dp)
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
                                                    tint = if (canSubmit) Color.White else colors.textMuted.copy(alpha = 0.4f),
                                                    modifier = Modifier.size(22.dp)
                                                )
                                                if (canSubmit) {
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(
                                                        text = "تایید",
                                                        fontSize = 14.sp,
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
                                                .luxuryCushion(shape = RoundedCornerShape(18.dp), elevation = 5.dp)
                                                .clickable(enabled = !isLockedOut) { onDigitPress(item) },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = item,
                                                fontSize = 26.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = colors.textPrimary.copy(alpha = buttonAlpha)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Telegram Bot Buy / Renew Button
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .height(48.dp)
                        .padding(top = 4.dp)
                        .shadow(4.dp, RoundedCornerShape(20.dp), ambientColor = colors.coralRed.copy(alpha = 0.2f))
                        .background(colors.coralCtaBrush, RoundedCornerShape(20.dp))
                        .border(1.dp, colors.coralCtaBorder, RoundedCornerShape(20.dp))
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
                            tint = colors.coralCtaText,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "خرید یا تمدید اشتراک در تلگرام",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = colors.coralCtaText
                        )
                    }
                }

                // Version and Diagnostic Link
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
                            color = if (newVersionAvailable != null) Color(0xFFEF4444) else colors.textMuted
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    TextButton(onClick = { showDebugDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.BugReport,
                            contentDescription = "دیباگ",
                            tint = colors.textMuted,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "گزارش وضعیت",
                            fontSize = 11.sp,
                            color = colors.textSecondary
                        )
                    }
                }
            }
        }

        // Diagnostics Report Dialog
        if (showDebugDialog) {
            val currentLogText = if (isAdminMode) AppLogger.getAdminLog() else AppLogger.getUserLog()

            AlertDialog(
                onDismissRequest = { showDebugDialog = false },
                title = {
                    Text(
                        text = if (isAdminMode) "گزارش فنی و دیباگ (مدیر)" else "گزارش وضعیت اتصال",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )
                },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp, max = 280.dp)
                                .luxuryWell(shape = RoundedCornerShape(14.dp))
                                .padding(12.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = currentLogText,
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colors.textSecondary,
                                lineHeight = 16.sp
                            )
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
                        colors = ButtonDefaults.buttonColors(containerColor = colors.jadeGreen)
                    ) {
                        Text("کپی لاگ", fontSize = 12.sp)
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
}
