package com.asanvpn.app.ui.theme

import androidx.compose.ui.graphics.Color

// -------------------------------------------------------------
// NEUMORPHIC 3D MINIMALIST WHITE PALETTE (SOFT UI)
// Based on modern soft 3D design with dual shadows
// -------------------------------------------------------------

// Canvas & Surfaces
val NeumorphBg = Color(0xFFF0F3F8)          // Clean soft off-white background
val NeumorphSurface = Color(0xFFF0F3F8)     // Same tone for extruded 3D look
val NeumorphSurfaceElevated = Color(0xFFF8FAFC) // Raised top plateau / dome
val NeumorphSunken = Color(0xFFE4E9F2)      // Inset well / sunken cavity

// Dual Soft Shadows & Borders
val NeumorphLightShadow = Color(0xFFFFFFFF) // Crisp white top-left highlight
val NeumorphDarkShadow = Color(0xFFCBD5E1)  // Soft ambient bottom-right shadow (Slate-300)
val NeumorphShadowStrong = Color(0xFFBAC5D5) // Slightly deeper shadow for dials
val NeumorphBorderLight = Color(0xFFFFFFFF) // Light reflection border
val NeumorphBorderSubtle = Color(0xFFE2E8F0) // Subtle definition border

// Keypad
val KeypadButtonBg = Color(0xFFF0F3F8)
val KeypadButtonText = Color(0xFF1E293B)
val KeypadButtonPressed = Color(0xFFE4E9F2)

// Typography (Minimalist, Crisp, High-Contrast Slate)
val TextPrimary = Color(0xFF1E293B)         // Deep Charcoal Slate (Titles & Values)
val TextSecondary = Color(0xFF64748B)       // Muted Steel Slate (Labels & Body)
val TextMuted = Color(0xFF94A3B8)           // Soft Subtitle Gray
val TextLight = Color(0xFFCBD5E1)           // Subtle placeholder / inactive text

// Status & Accent Accents
val GreenConnected = Color(0xFF10B981)      // Crisp Emerald for Active VPN
val GreenConnectedGlow = Color(0x3310B981)
val GreenConnectedLight = Color(0xFF059669)

val AmberConnecting = Color(0xFFF59E0B)     // Warm Amber for Connecting
val AmberConnectingGlow = Color(0x33F59E0B)

val RedOff = Color(0xFFEF4444)              // Vibrant Red for Alert / Off
val RedOffGlow = Color(0x33EF4444)

// Palette Accents (Harmonized with user's palette)
val AccentCoral = Color(0xFFFF6F52)         // Orange Pink (#FF6F52)
val AccentTeal = Color(0xFF004E64)          // Glacial Depth (#004E64)
val AccentCyan = Color(0xFF0284C7)          // Crisp Ocean Cyan
val AccentCyanSoft = Color(0xFFE0F2FE)      // Very soft cyan chip background

// Legacy compatibility aliases to avoid any compilation breaks
val BgDark = NeumorphBg
val BgGradientBottom = NeumorphBg
val CardDark = NeumorphSurface
val CardBorder = NeumorphBorderSubtle
val CardDarkElevated = NeumorphSurfaceElevated
val TextWhite = TextPrimary
val TextDarker = TextSecondary
val GreenOn = GreenConnected
val GreenOnGlow = GreenConnectedGlow
val GreenOnLight = GreenConnectedLight
val AmberGlow = AmberConnectingGlow
val EmeraldConnected = GreenConnected
val DialBgOff = NeumorphSurface
val DialBorderOff = NeumorphDarkShadow
val DialBgOn = NeumorphSurface
val DialBorderOn = GreenConnected
