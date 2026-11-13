package com.asanvpn.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// =============================================================
// LUXURY 3D TWO-THEMED SYSTEM (PORCELAIN vs OBSIDIAN)
// Defined according to asan_vpn_luxury_3d_ui.html
// =============================================================

data class LuxuryColors(
    val isDark: Boolean,
    val backgroundBrush: Brush,
    val cushionBrush: Brush,
    val cushionBorder: Brush,
    val cushionShadowAmbient: Color,
    val cushionShadowSpot: Color,
    val wellBrush: Brush,
    val wellBorder: Brush,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val jadeGreen: Color,
    val jadeGlow: Color,
    val skyBlue: Color,
    val skyGlow: Color,
    val coralRed: Color,
    val coralCtaBrush: Brush,
    val coralCtaBorder: Color,
    val coralCtaText: Color,
    val amberWarning: Color,
    val amberGlow: Color,
    val torusOuterBrush: Brush,
    val torusShadow: Color,
    val pebbleCapBrushConnected: Brush,
    val pebbleCapBrushConnecting: Brush,
    val pebbleCapBrushDisconnected: Brush,
    val domeGlossBrush: Brush,
    val dividerSoft: Color
)

val PorcelainColors = LuxuryColors(
    isDark = false,
    backgroundBrush = Brush.verticalGradient(
        listOf(PorcelainBgStart, PorcelainBgMid, PorcelainBgEnd)
    ),
    cushionBrush = Brush.linearGradient(
        colors = listOf(
            PorcelainCardStart.copy(alpha = 0.95f),
            PorcelainCardMid.copy(alpha = 0.88f),
            PorcelainCardEnd.copy(alpha = 0.82f)
        ),
        start = Offset(0f, 0f),
        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
    ),
    cushionBorder = Brush.linearGradient(
        colors = listOf(Color.White, Color.White.copy(alpha = 0.5f), Color(0xFFCBD5E1).copy(alpha = 0.35f)),
        start = Offset(0f, 0f),
        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
    ),
    cushionShadowAmbient = Color(0x1F64748B),
    cushionShadowSpot = Color(0x2864748B),
    wellBrush = Brush.linearGradient(
        colors = listOf(PorcelainWellStart, PorcelainWellEnd),
        start = Offset(0f, 0f),
        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
    ),
    wellBorder = Brush.linearGradient(
        colors = listOf(Color(0xFF94A3B8).copy(alpha = 0.35f), Color.White.copy(alpha = 0.9f)),
        start = Offset(0f, 0f),
        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
    ),
    textPrimary = PorcelainTextPrimary,
    textSecondary = PorcelainTextSecondary,
    textMuted = PorcelainTextMuted,
    jadeGreen = LuxuryJade,
    jadeGlow = LuxuryJadeGlow,
    skyBlue = LuxurySky,
    skyGlow = LuxurySkyGlow,
    coralRed = LuxuryCoral,
    coralCtaBrush = Brush.linearGradient(
        colors = listOf(Color(0xFFFFFFFF), Color(0xFFFFF5F5), Color(0xFFFEE2E2)),
        start = Offset(0f, 0f),
        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
    ),
    coralCtaBorder = Color(0xFFFFFFFF),
    coralCtaText = Color(0xFFEF4444),
    amberWarning = LuxuryAmber,
    amberGlow = LuxuryAmberGlow,
    torusOuterBrush = Brush.linearGradient(
        colors = listOf(Color(0xFFFFFFFF), Color(0xFFE6EEF8), Color(0xFFD5E0EF)),
        start = Offset(0f, 0f),
        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
    ),
    torusShadow = Color(0x3864748B),
    pebbleCapBrushConnected = Brush.radialGradient(
        colors = listOf(Color(0xFFFFFFFF), Color(0xFFF4F9F7), Color(0xFFE0EEE9))
    ),
    pebbleCapBrushConnecting = Brush.radialGradient(
        colors = listOf(Color(0xFFFFFFFF), Color(0xFFFFFBEB), Color(0xFFFEF3C7))
    ),
    pebbleCapBrushDisconnected = Brush.radialGradient(
        colors = listOf(Color(0xFFFFFFFF), Color(0xFFF1F5F9), Color(0xFFE2E8F0))
    ),
    domeGlossBrush = Brush.verticalGradient(
        listOf(Color.White.copy(alpha = 0.78f), Color.White.copy(alpha = 0.0f))
    ),
    dividerSoft = Color(0x4094A3B8)
)

val ObsidianColors = LuxuryColors(
    isDark = true,
    backgroundBrush = Brush.verticalGradient(
        listOf(ObsidianBgStart, ObsidianBgMid, ObsidianBgEnd)
    ),
    cushionBrush = Brush.linearGradient(
        colors = listOf(
            ObsidianCardStart.copy(alpha = 0.88f),
            ObsidianCardMid.copy(alpha = 0.92f),
            ObsidianCardEnd.copy(alpha = 0.95f)
        ),
        start = Offset(0f, 0f),
        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
    ),
    cushionBorder = Brush.linearGradient(
        colors = listOf(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.04f)),
        start = Offset(0f, 0f),
        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
    ),
    cushionShadowAmbient = Color(0xAA000000),
    cushionShadowSpot = Color(0xCC000000),
    wellBrush = Brush.linearGradient(
        colors = listOf(ObsidianWellStart, ObsidianWellEnd),
        start = Offset(0f, 0f),
        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
    ),
    wellBorder = Brush.linearGradient(
        colors = listOf(Color.Black.copy(alpha = 0.75f), Color.White.copy(alpha = 0.08f)),
        start = Offset(0f, 0f),
        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
    ),
    textPrimary = ObsidianTextPrimary,
    textSecondary = ObsidianTextSecondary,
    textMuted = ObsidianTextMuted,
    jadeGreen = LuxuryJade,
    jadeGlow = Color(0x5510B981),
    skyBlue = LuxurySkyLight,
    skyGlow = Color(0x4438BDF8),
    coralRed = LuxuryCoralPink,
    coralCtaBrush = Brush.linearGradient(
        colors = listOf(Color(0xFF2E212C), Color(0xFF1E1620)),
        start = Offset(0f, 0f),
        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
    ),
    coralCtaBorder = Color(0x44FB7185),
    coralCtaText = Color(0xFFFB7185),
    amberWarning = LuxuryAmber,
    amberGlow = Color(0x55F59E0B),
    torusOuterBrush = Brush.linearGradient(
        colors = listOf(Color(0xFF263044), Color(0xFF171D2B), Color(0xFF0F141F)),
        start = Offset(0f, 0f),
        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
    ),
    torusShadow = Color(0xEE000000),
    pebbleCapBrushConnected = Brush.radialGradient(
        colors = listOf(Color(0xFF2B374F), Color(0xFF1A2233), Color(0xFF111724))
    ),
    pebbleCapBrushConnecting = Brush.radialGradient(
        colors = listOf(Color(0xFF3B2A1E), Color(0xFF241A13), Color(0xFF150F0B))
    ),
    pebbleCapBrushDisconnected = Brush.radialGradient(
        colors = listOf(Color(0xFF283142), Color(0xFF181E2C), Color(0xFF10141F))
    ),
    domeGlossBrush = Brush.verticalGradient(
        listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.0f))
    ),
    dividerSoft = Color(0x26FFFFFF)
)

val LocalLuxuryColors = staticCompositionLocalOf { PorcelainColors }

object LuxuryTheme {
    val colors: LuxuryColors
        @Composable
        get() = LocalLuxuryColors.current
}

@Composable
fun AsanVpnTheme(
    isDark: Boolean = false,
    content: @Composable () -> Unit
) {
    val luxuryColors = if (isDark) ObsidianColors else PorcelainColors

    val materialColorScheme = if (isDark) {
        darkColorScheme(
            primary = luxuryColors.jadeGreen,
            secondary = luxuryColors.skyBlue,
            background = ObsidianBgMid,
            surface = ObsidianCardMid,
            onPrimary = Color.White,
            onSecondary = Color.White,
            onBackground = luxuryColors.textPrimary,
            onSurface = luxuryColors.textPrimary
        )
    } else {
        lightColorScheme(
            primary = luxuryColors.jadeGreen,
            secondary = luxuryColors.skyBlue,
            background = PorcelainBgMid,
            surface = PorcelainCardMid,
            onPrimary = Color.White,
            onSecondary = Color.White,
            onBackground = luxuryColors.textPrimary,
            onSurface = luxuryColors.textPrimary
        )
    }

    CompositionLocalProvider(LocalLuxuryColors provides luxuryColors) {
        MaterialTheme(
            colorScheme = materialColorScheme,
            content = content
        )
    }
}

// -------------------------------------------------------------
// REUSABLE 3D MODIFIERS (LUXURY CUSHION & WELL)
// -------------------------------------------------------------

@Composable
fun Modifier.luxuryCushion(
    shape: Shape = RoundedCornerShape(26.dp),
    elevation: Dp = 6.dp
): Modifier {
    val c = LuxuryTheme.colors
    return this
        .shadow(
            elevation = elevation,
            shape = shape,
            ambientColor = c.cushionShadowAmbient,
            spotColor = c.cushionShadowSpot
        )
        .background(c.cushionBrush, shape)
        .border(1.dp, c.cushionBorder, shape)
}

@Composable
fun Modifier.luxuryWell(
    shape: Shape = RoundedCornerShape(16.dp)
): Modifier {
    val c = LuxuryTheme.colors
    return this
        .background(c.wellBrush, shape)
        .border(1.dp, c.wellBorder, shape)
}

/**
 * Raised Neumorphic Surface (legacy compatibility):
 */
fun Modifier.neumorphicRaised(
    shape: Shape = RoundedCornerShape(20.dp),
    elevation: Dp = 6.dp,
    surfaceColor: Color = NeumorphSurface,
    lightHighlight: Color = NeumorphLightShadow.copy(alpha = 0.9f),
    darkShadow: Color = NeumorphDarkShadow.copy(alpha = 0.65f)
): Modifier = this
    .shadow(
        elevation = elevation,
        shape = shape,
        ambientColor = darkShadow,
        spotColor = darkShadow
    )
    .background(surfaceColor, shape)
    .border(
        width = 1.dp,
        brush = Brush.linearGradient(
            colors = listOf(lightHighlight, Color.White.copy(alpha = 0.2f), darkShadow.copy(alpha = 0.3f)),
            start = Offset(0f, 0f),
            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        ),
        shape = shape
    )

/**
 * Sunken / Inset Neumorphic Well (legacy compatibility):
 */
fun Modifier.neumorphicSunken(
    shape: Shape = RoundedCornerShape(14.dp),
    cavityColor: Color = NeumorphSunken,
    borderDark: Color = Color(0xFFD1D9E6),
    borderLight: Color = Color.White
): Modifier = this
    .background(cavityColor, shape)
    .border(
        width = 1.2.dp,
        brush = Brush.linearGradient(
            colors = listOf(borderDark.copy(alpha = 0.7f), borderLight.copy(alpha = 0.8f)),
            start = Offset(0f, 0f),
            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        ),
        shape = shape
    )
