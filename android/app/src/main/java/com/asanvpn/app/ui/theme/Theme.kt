package com.asanvpn.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val LightNeumorphicColorScheme = lightColorScheme(
    primary = AccentTeal,
    secondary = AccentCoral,
    background = NeumorphBg,
    surface = NeumorphSurface,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

@Composable
fun AsanVpnTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightNeumorphicColorScheme,
        content = content
    )
}

// -------------------------------------------------------------
// REUSABLE NEUMORPHIC 3D MODIFIERS (SOFT UI)
// Emulates 3D extrusion with dual soft shadows and linear highlights
// -------------------------------------------------------------

/**
 * Raised Neumorphic Surface: creates an extruded 3D element that softly
 * rises out of the background with a top-left white highlight and a bottom-right soft shadow.
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
 * Sunken / Inset Neumorphic Well: creates a softly indented well into the surface,
 * ideal for inputs, active indicators, and pressed states.
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
