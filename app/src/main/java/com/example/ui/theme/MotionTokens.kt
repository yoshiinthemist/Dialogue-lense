package com.example.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween

/**
 * Calm, restrained motion tokens for DialogueLens 2.0 (Section 44).
 * Respects Reduced Motion by snapping immediately when enabled.
 */
object MotionTokens {
    const val BUBBLE_ENTRANCE_MS = 120
    const val CONTROL_FADE_MS = 180
    const val OVERLAY_TRANSITION_MS = 220
    const val COLOR_TRANSITION_MS = 220
    const val BEACON_CONFIRMATION_MS = 240

    fun <T> calmTween(durationMs: Int, reducedMotion: Boolean): AnimationSpec<T> =
        if (reducedMotion) snap() else tween(durationMillis = durationMs)
}
