package com.example.ui.theme

import androidx.compose.ui.graphics.Color
import com.example.data.model.SpeakerColorOrder

// DialogueLens 2.0 Cinema-First Color System (Section 42)
// Neutral obsidian/graphite foundation so the video frame and speaker colors remain the hero.
val CinemaObsidian = Color(0xFF101419)
val CinemaSurface = Color(0xFF171D24)
val CinemaGraphite = Color(0xFF20272E)
val CinemaElevated = CinemaGraphite
val CinemaSurfaceHigh = Color(0xFF28313B)
val CinemaBorder = Color(0xFF323D49)

val InkPrimary = Color(0xFFF3F1EC)     // Warm off-white
val InkSecondary = Color(0xFF9AA5B1)   // Muted cool gray
val InkMuted = Color(0xFF6B7785)

// Signature 2.0 semantic accents
val SoftJade = Color(0xFFBCE5CE)       // Primary calm action & verified coverage
val SoftJadeContainer = Color(0xFF1B352B)
val AmethystHeal = Color(0xFFCDB4F1)   // Signature Heal Beacon accent
val AmethystContainer = Color(0xFF2E2342)
val WarmAmber = Color(0xFFD5A36A)      // Attention & graceful limitation accent
val WarmAmberContainer = Color(0xFF3A2B1A)

// Legacy aliases harmonized with the 2.0 cinematic palette
val LensSky = SoftJade
val LensSkyContainer = SoftJadeContainer
val LensAmber = WarmAmber
val LensAmberContainer = WarmAmberContainer
val LensMint = Color(0xFF8FD8B4)
val LensRose = Color(0xFFE07A8C)

// Confidence tiers
val ConfidenceHighColor = Color(0xFF8FD8B4)
val ConfidenceMediumColor = WarmAmber
val ConfidenceLowColor = Color(0xFFE07A8C)

// Speaker palette: Primary -> Secondary -> Distinct unused
val SpeakerAccentPalette = SpeakerColorOrder.PALETTE_HEX_COLORS.map { Color(it) }

// Dotted neutral gray for unknown speakers until discovered or manually assigned
val UnknownSpeakerGray = Color(SpeakerColorOrder.UNKNOWN_SPEAKER_GRAY_HEX)

// White reserved strictly for non-speaker-specific bubbles (Narration, Voice-Over, SFX, or non-speaker mode)
val NonSpeakerBubbleWhite = Color(SpeakerColorOrder.NON_SPEAKER_WHITE_HEX)

// Moving-scene high-legibility balloon surface & ink tokens
val CinemaBalloonGlass = Color(0xFF121820)
val MangaPaperWarm = Color(0xFFFAF7F0)
val BalloonLightSurface = Color(0xFFF8FAFC)
val BalloonLightInk = Color(0xFF0F172A)
val BalloonLightSubtleInk = Color(0xFF475569)
val BalloonLightBorder = Color(0xFFE2E8F0)

val BalloonHighContrastSurface = Color(0xFF020617)
val BalloonHighContrastInk = Color(0xFFFFFFFF)
val BalloonHighContrastBorder = Color(0xFFFACC15)
