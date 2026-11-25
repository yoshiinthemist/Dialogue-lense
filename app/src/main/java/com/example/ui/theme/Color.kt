package com.example.ui.theme

import androidx.compose.ui.graphics.Color
import com.example.data.model.SpeakerColorOrder

// Cinema-first Obsidian studio palette — restrained so the video remains the hero
val CinemaObsidian = Color(0xFF0A0E17)
val CinemaSurface = Color(0xFF121826)
val CinemaElevated = Color(0xFF1A2233)
val CinemaSurfaceHigh = Color(0xFF222D42)
val CinemaBorder = Color(0xFF2B3750)

val InkPrimary = Color(0xFFF1F5F9)
val InkSecondary = Color(0xFF94A3B8)
val InkMuted = Color(0xFF64748B)

val LensSky = Color(0xFF38BDF8)
val LensSkyContainer = Color(0xFF0C4A6E)
val LensAmber = Color(0xFFF59E0B)
val LensAmberContainer = Color(0xFF451A03)
val LensMint = Color(0xFF34D399)
val LensRose = Color(0xFFFB7185)

// Confidence tiers
val ConfidenceHighColor = Color(0xFF22C55E)
val ConfidenceMediumColor = Color(0xFFF59E0B)
val ConfidenceLowColor = Color(0xFFEF4444)

// Speaker palette: Primary (Red, Blue, Yellow) -> Secondary (Green, Orange, Purple) -> Distinct unused (Cyan, Magenta, Lime, Teal)
val SpeakerAccentPalette = SpeakerColorOrder.PALETTE_HEX_COLORS.map { Color(it) }

// Dotted neutral gray for unknown speakers until discovered or manually assigned
val UnknownSpeakerGray = Color(SpeakerColorOrder.UNKNOWN_SPEAKER_GRAY_HEX)

// White reserved strictly for non-speaker-specific bubbles (Narration, Voice-Over, SFX, or non-speaker mode)
val NonSpeakerBubbleWhite = Color(SpeakerColorOrder.NON_SPEAKER_WHITE_HEX)

// Moving-scene high-legibility balloon surface & ink tokens
val CinemaBalloonGlass = Color(0xFF0A0F1A)
val MangaPaperWarm = Color(0xFFFAF7F0)
val BalloonLightSurface = Color(0xFFF8FAFC)
val BalloonLightInk = Color(0xFF0F172A)
val BalloonLightSubtleInk = Color(0xFF475569)
val BalloonLightBorder = Color(0xFFE2E8F0)

val BalloonHighContrastSurface = Color(0xFF020617)
val BalloonHighContrastInk = Color(0xFFFFFFFF)
val BalloonHighContrastBorder = Color(0xFFFACC15)

