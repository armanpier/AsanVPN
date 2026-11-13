package com.asanvpn.app.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// -------------------------------------------------------------
// ASAN VPN LUXURY 3D TWO-THEMED PALETTE (SOFT PORCELAIN & OBSIDIAN)
// Based on exact design in asan_vpn_luxury_3d_ui.html
// -------------------------------------------------------------

// Porcelain (Light - Default)
val PorcelainBgStart = Color(0xFFF3F6FB)
val PorcelainBgMid = Color(0xFFEDF2F9)
val PorcelainBgEnd = Color(0xFFE6EDF7)
val PorcelainCardStart = Color(0xFFFFFFFF)
val PorcelainCardMid = Color(0xFFF4F7FC)
val PorcelainCardEnd = Color(0xFFEBF1F9)
val PorcelainWellStart = Color(0xFFE2E8F3)
val PorcelainWellEnd = Color(0xFFEEF3FA)
val PorcelainTextPrimary = Color(0xFF1E293B)
val PorcelainTextSecondary = Color(0xFF64748B)
val PorcelainTextMuted = Color(0xFF94A3B8)
val PorcelainShadow = Color(0x2464748B)
val PorcelainBorder = Color(0xCCFFFFFF)

// Obsidian (Dark - Luxury Velvet Silicone)
val ObsidianBgStart = Color(0xFF111622)
val ObsidianBgMid = Color(0xFF0D111B)
val ObsidianBgEnd = Color(0xFF090C14)
val ObsidianCardStart = Color(0xFF232C40)
val ObsidianCardMid = Color(0xFF161C2A)
val ObsidianCardEnd = Color(0xFF101521)
val ObsidianWellStart = Color(0xFF0B0E17)
val ObsidianWellEnd = Color(0xFF151B29)
val ObsidianTextPrimary = Color(0xFFF8FAFC)
val ObsidianTextSecondary = Color(0xFF94A3B8)
val ObsidianTextMuted = Color(0xFF64748B)
val ObsidianShadow = Color(0xAA000000)
val ObsidianBorder = Color(0x1FFFFFFF)

// Accent Spheres & Status (Shared)
val LuxuryJade = Color(0xFF10B981)
val LuxuryJadeGlow = Color(0x3810B981)
val LuxuryJadeLight = Color(0xFF34D399)
val LuxuryJadeDark = Color(0xFF059669)

val LuxurySky = Color(0xFF0284C7)
val LuxurySkyGlow = Color(0x330284C7)
val LuxurySkyLight = Color(0xFF38BDF8)

val LuxuryCoral = Color(0xFFEF4444)
val LuxuryCoralPink = Color(0xFFFB7185)
val LuxuryCoralGlow = Color(0x33EF4444)

val LuxuryAmber = Color(0xFFF59E0B)
val LuxuryAmberGlow = Color(0x38F59E0B)

// Iranian Flag Colors
val IranFlagGreen = Color(0xFF239F40)
val IranFlagWhite = Color(0xFFFFFFFF)
val IranFlagRed = Color(0xFFDA0000)

// -------------------------------------------------------------
// LEGACY COMPATIBILITY CONSTANTS
// Preserved to ensure zero regressions in existing code
// -------------------------------------------------------------
val NeumorphBg = PorcelainBgMid
val NeumorphSurface = PorcelainCardStart
val NeumorphSurfaceElevated = Color(0xFFF8FAFC)
val NeumorphSunken = PorcelainWellStart
val NeumorphLightShadow = Color(0xFFFFFFFF)
val NeumorphDarkShadow = Color(0xFFCBD5E1)
val NeumorphShadowStrong = Color(0xFFBAC5D5)
val NeumorphBorderLight = Color(0xFFFFFFFF)
val NeumorphBorderSubtle = Color(0xFFE2E8F0)

val KeypadButtonBg = PorcelainCardStart
val KeypadButtonText = PorcelainTextPrimary
val KeypadButtonPressed = PorcelainWellStart

val TextPrimary = PorcelainTextPrimary
val TextSecondary = PorcelainTextSecondary
val TextMuted = PorcelainTextMuted
val TextLight = Color(0xFFCBD5E1)

val GreenConnected = LuxuryJade
val GreenConnectedGlow = LuxuryJadeGlow
val GreenConnectedLight = LuxuryJadeDark

val AmberConnecting = LuxuryAmber
val AmberConnectingGlow = LuxuryAmberGlow

val RedOff = LuxuryCoral
val RedOffGlow = LuxuryCoralGlow

val AccentCoral = Color(0xFFFF6F52)
val AccentTeal = Color(0xFF004E64)
val AccentCyan = LuxurySky
val AccentCyanSoft = Color(0xFFE0F2FE)

val BgDark = ObsidianBgMid
val BgGradientBottom = ObsidianBgEnd
val CardDark = ObsidianCardMid
val CardBorder = ObsidianBorder
val CardDarkElevated = ObsidianCardStart
val TextWhite = Color(0xFFF8FAFC)
val TextDarker = ObsidianTextSecondary
val GreenOn = LuxuryJade
val GreenOnGlow = LuxuryJadeGlow
val GreenOnLight = LuxuryJadeDark
val AmberGlow = LuxuryAmberGlow
val EmeraldConnected = LuxuryJade
val DialBgOff = PorcelainCardStart
val DialBorderOff = PorcelainShadow
val DialBgOn = PorcelainCardStart
val DialBorderOn = LuxuryJade
