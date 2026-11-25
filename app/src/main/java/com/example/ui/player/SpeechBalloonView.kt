package com.example.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.BalloonStyleSettings
import com.example.data.model.BubbleVisualStyle
import com.example.data.model.ConfidenceTier
import com.example.data.model.DialogueType
import com.example.data.model.NormalizedPoint
import com.example.data.model.NormalizedRect
import com.example.data.model.PlacementMode
import com.example.data.model.SpeakerLabelMode
import com.example.data.model.TranslationMode
import com.example.data.model.isBubbleNonSpeakerSpecific
import com.example.data.model.isBubbleSpeakerUnknown
import com.example.data.model.isNonSpeakerSpecificEvent
import com.example.domain.layout.PlacedBalloon
import com.example.ui.theme.BalloonHighContrastBorder
import com.example.ui.theme.BalloonHighContrastInk
import com.example.ui.theme.BalloonHighContrastSurface
import com.example.ui.theme.BalloonLightInk
import com.example.ui.theme.BalloonLightSubtleInk
import com.example.ui.theme.BalloonLightSurface
import com.example.ui.theme.CinemaBalloonGlass
import com.example.ui.theme.CinemaObsidian
import com.example.ui.theme.ConfidenceHighColor
import com.example.ui.theme.ConfidenceLowColor
import com.example.ui.theme.ConfidenceMediumColor
import com.example.ui.theme.LexendFamily
import com.example.ui.theme.MangaPaperWarm
import com.example.ui.theme.NonSpeakerBubbleWhite
import com.example.ui.theme.NunitoFamily
import com.example.ui.theme.SpeakerAccentPalette
import com.example.ui.theme.UnknownSpeakerGray
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private val MangaPaperWhite = MangaPaperWarm
private val MangaSumiInk = Color(0xFF0B0F17)
private val MangaSubtleInk = Color(0xFF475569)
private val MangaNarrationBg = Color(0xFFFAF4E4)
private val CinemaGlassDark = CinemaBalloonGlass
private val CinemaWhisperScrim = Color(0xFF090E17)

@Composable
fun SpeechBalloonView(
    placed: PlacedBalloon,
    translationMode: TranslationMode,
    style: BalloonStyleSettings,
    isEditMode: Boolean,
    isSelected: Boolean,
    containerWidthPx: Float,
    containerHeightPx: Float,
    faceExclusionRects: List<NormalizedRect> = emptyList(),
    occupiedBubbleRects: List<Rect> = emptyList(),
    onPixelBoundsResolved: (Rect) -> Unit = {},
    onMeasuredNormalizedSize: (Float, Float) -> Unit = { _, _ -> },
    onClick: () -> Unit,
    onDragCommit: (NormalizedPoint) -> Unit,
    forceTaillessFixedZone: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val event = placed.event
    val isUnknownAtRoot = placed.isUnknownSpeaker || isBubbleSpeakerUnknown(event, placed.speaker)
    val isNonSpeakerAtRoot = isBubbleNonSpeakerSpecific(event, style)
    val rawSpeakerAccent = when {
        isUnknownAtRoot -> UnknownSpeakerGray
        isNonSpeakerAtRoot -> NonSpeakerBubbleWhite
        else -> placed.speaker?.let {
            SpeakerAccentPalette[it.accentIndex % SpeakerAccentPalette.size]
        } ?: UnknownSpeakerGray
    }
    val accentAnimSpec = if (style.reducedMotion) snap<Color>() else tween<Color>(durationMillis = 220)
    val speakerAccent by animateColorAsState(
        targetValue = rawSpeakerAccent,
        animationSpec = accentAnimSpec,
        label = "balloon_speaker_accent",
    )

    val activePreset = if (style.highContrastMode) BubbleVisualStyle.STREAMING_CLEAN else style.visualStyle
    val isManga = activePreset == BubbleVisualStyle.MANGA_INK
    val isCinemaSubtle = activePreset == BubbleVisualStyle.CINEMA_SUBTLE
    val isReadingComfort = activePreset == BubbleVisualStyle.READING_COMFORT

    val isSpecialNonSpoken = event.dialogueType == DialogueType.NARRATION ||
        event.dialogueType == DialogueType.VOICE_OVER ||
        event.dialogueType == DialogueType.SOUND_DESCRIPTION ||
        event.dialogueType == DialogueType.INTERNAL_MONOLOGUE

    var measuredSize by remember(event.id) { mutableStateOf(IntSize.Zero) }

    // Wait until first pixel measurement finishes before fading in so balloons never hop on Frame 1 -> Frame 2
    val enterAlpha = remember(event.id) { Animatable(if (style.reducedMotion) 1f else 0f) }
    LaunchedEffect(event.id, measuredSize != IntSize.Zero, style.reducedMotion) {
        if (measuredSize == IntSize.Zero && !style.reducedMotion) return@LaunchedEffect
        if (style.reducedMotion) {
            enterAlpha.snapTo(1f)
        } else {
            enterAlpha.animateTo(1f, animationSpec = tween(durationMillis = 120))
        }
    }

    var dragOffsetXPx by remember(event.id, placed.rect.centerX) { mutableFloatStateOf(0f) }
    var dragOffsetYPx by remember(event.id, placed.rect.centerY) { mutableFloatStateOf(0f) }

    val density = LocalDensity.current.density
    val stageWidthDp = (containerWidthPx / density).coerceAtLeast(180f)
    val rawDisplayText = event.activeText(translationMode)
    val charWidthDp = 8.4f * style.fontScale
    val targetLines = style.maxLines.coerceIn(1, 4)
    val idealContentWidthDp = ((rawDisplayText.length.coerceAtLeast(1).toFloat() / targetLines) * charWidthDp) + 34f
    val minCapDp = if (isSpecialNonSpoken || placed.isAmbiguousFallback) {
        (stageWidthDp * 0.44f).coerceIn(170f, 280f)
    } else {
        (stageWidthDp * 0.30f).coerceIn(124f, 196f)
    }
    val maxCapDp = when {
        event.dialogueType == DialogueType.NARRATION ||
            event.dialogueType == DialogueType.VOICE_OVER ||
            placed.isAmbiguousFallback -> (stageWidthDp * 0.84f).coerceIn(220f, 400f)
        else -> (stageWidthDp * 0.56f).coerceIn(170f, 268f)
    }
    val maxBubbleWidthDp = idealContentWidthDp
        .coerceIn(minCapDp, maxCapDp)
        .coerceAtMost((stageWidthDp - 16f).coerceAtLeast(140f))
        .dp

    val actualW = if (measuredSize.width > 0) measuredSize.width.toFloat() else (placed.rect.width * containerWidthPx)
    val actualH = if (measuredSize.height > 0) measuredSize.height.toFloat() else (placed.rect.height * containerHeightPx)

    val safeMarginX = 10f * density
    val safeMarginY = 8f * density
    val maxLeftPx = (containerWidthPx - actualW - safeMarginX).coerceAtLeast(safeMarginX)
    val maxTopPx = (containerHeightPx - actualH - safeMarginY).coerceAtLeast(safeMarginY)
    val desiredCenterX = placed.rect.centerX * containerWidthPx + dragOffsetXPx
    val desiredCenterY = placed.rect.centerY * containerHeightPx + dragOffsetYPx

    val baseLeftPx = (desiredCenterX - actualW * 0.5f).coerceIn(safeMarginX, maxLeftPx)
    val baseTopPx = (desiredCenterY - actualH * 0.5f).coerceIn(safeMarginY, maxTopPx)

    // DialogueLens 2.0 Single-Authority Positioning: trust BalloonLayoutEngine's committed rect;
    // only run emergency pixel escape if an actual severe face or sibling overlap occurs (and skip in Fixed Zones mode).
    val (bubbleLeftPx, bubbleTopPx) = if (
        !forceTaillessFixedZone &&
        measuredSize != IntSize.Zero &&
        (faceExclusionRects.isNotEmpty() || occupiedBubbleRects.isNotEmpty()) &&
        event.placementMode != PlacementMode.MANUAL_OVERRIDE
    ) {
        resolveFaceSafePixelPosition(
            baseLeftPx = baseLeftPx,
            baseTopPx = baseTopPx,
            actualW = actualW,
            actualH = actualH,
            safeMarginX = safeMarginX,
            safeMarginY = safeMarginY,
            maxLeftPx = maxLeftPx,
            maxTopPx = maxTopPx,
            containerWidthPx = containerWidthPx,
            containerHeightPx = containerHeightPx,
            faceExclusionRects = faceExclusionRects,
            occupiedBubbleRects = occupiedBubbleRects,
            density = density,
            allowBelowFace = !style.lipReadingSafeZone || (event.faceTrackId == null && event.anchorPosition != null),
        )
    } else {
        baseLeftPx to baseTopPx
    }

    // Smooth critically-damped body motion: snaps cleanly on first measured frame, then glides
    // with zero overshoot or tweaking on subsequent gentle-drift updates.
    var hasInitializedBodyOffset by remember(event.id) { mutableStateOf(false) }
    val isDraggingNow = dragOffsetXPx != 0f || dragOffsetYPx != 0f
    val targetBodyOffset = Offset(bubbleLeftPx, bubbleTopPx)
    val animatedBodyOffset by animateOffsetAsState(
        targetValue = targetBodyOffset,
        animationSpec = if (
            measuredSize == IntSize.Zero ||
            !hasInitializedBodyOffset ||
            style.reducedMotion ||
            isDraggingNow
        ) {
            snap()
        } else {
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = if (forceTaillessFixedZone) Spring.StiffnessVeryLow else Spring.StiffnessLow,
            )
        },
        label = "balloon_body_gentle_drift",
    )
    LaunchedEffect(event.id, measuredSize != IntSize.Zero) {
        if (measuredSize != IntSize.Zero) {
            hasInitializedBodyOffset = true
        }
    }

    val renderedLeftPx = animatedBodyOffset.x
    val renderedTopPx = animatedBodyOffset.y
    val actualCenterX = renderedLeftPx + actualW * 0.5f
    val actualCenterY = renderedTopPx + actualH * 0.5f

    // Report pixel bounds for sibling avoidance without re-triggering normalized size feedback
    LaunchedEffect(event.id, measuredSize, bubbleLeftPx, bubbleTopPx) {
        if (measuredSize != IntSize.Zero) {
            onPixelBoundsResolved(
                Rect(
                    left = bubbleLeftPx,
                    top = bubbleTopPx,
                    right = bubbleLeftPx + actualW,
                    bottom = bubbleTopPx + actualH,
                )
            )
        }
    }

    // Report normalized dimensions ONLY when actual measured pixel size or container size changes
    // (breaks the position <-> resize oscillation feedback loop).
    LaunchedEffect(event.id, measuredSize, containerWidthPx, containerHeightPx) {
        if (measuredSize != IntSize.Zero && containerWidthPx > 0f && containerHeightPx > 0f) {
            onMeasuredNormalizedSize(
                (actualW / containerWidthPx).coerceIn(0.06f, 0.88f),
                (actualH / containerHeightPx).coerceIn(0.04f, 0.48f),
            )
        }
    }

    val showPointerTail = !forceTaillessFixedZone &&
        style.showTail &&
        !isReadingComfort &&
        !isSpecialNonSpoken &&
        !placed.isOffScreenEdge &&
        !placed.isAmbiguousFallback &&
        placed.tailTarget != null

    val targetXPx = (placed.tailTarget?.x ?: placed.rect.centerX) * containerWidthPx
    val targetYPx = (placed.tailTarget?.y ?: placed.rect.centerY) * containerHeightPx
    val rawGoalOffset = Offset(targetXPx, targetYPx)

    // Velocity-preserving spring tracking (never cancels in-flight springs on 160ms live face updates)
    val interpolatedTipWorld by animateOffsetAsState(
        targetValue = rawGoalOffset,
        animationSpec = if (!showPointerTail || style.reducedMotion) {
            snap()
        } else {
            spring(
                dampingRatio = 0.78f,
                stiffness = Spring.StiffnessMediumLow,
            )
        },
        label = "tail_tip_chase",
    )

    val rawAimAngleRad = if (showPointerTail) {
        atan2(
            (interpolatedTipWorld.y - actualCenterY).toDouble(),
            (interpolatedTipWorld.x - actualCenterX).toDouble(),
        ).toFloat()
    } else {
        Float.NaN
    }

    // Continuous unwrapped angular target so tails glide seamlessly across +/-PI without 360-degree spins
    var unwrappedAimAngle by remember(event.id) {
        mutableFloatStateOf(if (rawAimAngleRad.isNaN()) 0f else rawAimAngleRad)
    }
    if (!rawAimAngleRad.isNaN()) {
        val step = shortestAngularDeltaRad(unwrappedAimAngle, rawAimAngleRad)
        if (abs(step) > 0.002f) {
            unwrappedAimAngle += step
        }
    }
    val smoothAimAngleRad by animateFloatAsState(
        targetValue = unwrappedAimAngle,
        animationSpec = if (!showPointerTail || style.reducedMotion) {
            snap()
        } else {
            spring(
                dampingRatio = 0.82f,
                stiffness = Spring.StiffnessMedium,
            )
        },
        label = "tail_angle_glide",
    )

    val localTarget = if (showPointerTail) {
        Offset(x = interpolatedTipWorld.x - renderedLeftPx, y = interpolatedTipWorld.y - renderedTopPx)
    } else {
        null
    }

    val rootModifier = modifier
        .offset {
            IntOffset(
                x = renderedLeftPx.roundToInt(),
                y = renderedTopPx.roundToInt(),
            )
        }
        .graphicsLayer { alpha = if (measuredSize == IntSize.Zero) 0f else enterAlpha.value }
        .wrapContentSize(unbounded = false)
        .widthIn(max = maxBubbleWidthDp)
        .onSizeChanged { measuredSize = it }
        .then(
            if (isEditMode) {
                Modifier.pointerInput(event.id) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragOffsetXPx += dragAmount.x
                            dragOffsetYPx += dragAmount.y
                        },
                        onDragEnd = {
                            val newCx = (actualCenterX / containerWidthPx).coerceIn(0.10f, 0.90f)
                            val newCy = (actualCenterY / containerHeightPx).coerceIn(0.10f, 0.88f)
                            dragOffsetXPx = 0f
                            dragOffsetYPx = 0f
                            onDragCommit(NormalizedPoint(newCx, newCy))
                        },
                        onDragCancel = {
                            dragOffsetXPx = 0f
                            dragOffsetYPx = 0f
                        },
                    )
                }
            } else {
                Modifier
            }
        )
        .testTag("balloon_${event.id}")

    UnifiedBalloonBody(
        placed = placed,
        translationMode = translationMode,
        style = style,
        isManga = isManga,
        isCinemaSubtle = isCinemaSubtle,
        isEditMode = isEditMode,
        isSelected = isSelected,
        speakerAccent = speakerAccent,
        isSpecialNonSpoken = isSpecialNonSpoken,
        showPointerTail = showPointerTail,
        tailAimAngleRad = if (showPointerTail) smoothAimAngleRad else Float.NaN,
        localTarget = localTarget,
        maxBubbleWidthDp = maxBubbleWidthDp.value,
        containerWidthPx = containerWidthPx,
        onClick = onClick,
        modifier = rootModifier,
    )
}

@Composable
private fun UnifiedBalloonBody(
    placed: PlacedBalloon,
    translationMode: TranslationMode,
    style: BalloonStyleSettings,
    isManga: Boolean,
    isCinemaSubtle: Boolean,
    isEditMode: Boolean,
    isSelected: Boolean,
    speakerAccent: Color,
    isSpecialNonSpoken: Boolean,
    showPointerTail: Boolean,
    tailAimAngleRad: Float,
    localTarget: Offset?,
    maxBubbleWidthDp: Float,
    containerWidthPx: Float = 0f,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val event = placed.event
    val isNonSpeakerEvent = isNonSpeakerSpecificEvent(event)
    val isUnknownSpeaker = !isNonSpeakerEvent && (placed.isUnknownSpeaker || isBubbleSpeakerUnknown(event, placed.speaker))
    val isNonSpeakerBubble = isNonSpeakerEvent || (!isUnknownSpeaker && !style.speakerSpecificColors)
    val isSilentTsubuyaki = placed.isAmbiguousFallback && !isEditMode
    val isHighEnergyShout = (placed.isShouted || placed.emphasisScore >= 0.66f) &&
        !isSilentTsubuyaki &&
        event.dialogueType != DialogueType.NARRATION &&
        event.dialogueType != DialogueType.VOICE_OVER
    val isSoftWhisper = !isHighEnergyShout &&
        !isSpecialNonSpoken &&
        (event.audioFeatures?.let { it.speechEnergy in 0.05f..0.28f } == true)

    // Keep shouted contours crisp & rock-steady (zero oscillating idle wiggle for distraction-free cinema reading)
    val jitterPhase = 0f

    val luminanceBias = ((placed.sceneLuminance - 0.35f) * 0.14f).coerceIn(-0.04f, 0.07f)
    val adaptiveOpacity = (style.balloonOpacity + luminanceBias).coerceIn(0.58f, 0.98f)

    val colorAnimSpec = if (style.reducedMotion) snap<Color>() else tween<Color>(durationMillis = 220)

    val isReadingComfort = style.visualStyle == BubbleVisualStyle.READING_COMFORT && !style.highContrastMode

    val rawFillColor = when {
        style.highContrastMode -> BalloonHighContrastSurface.copy(alpha = 0.98f)
        isReadingComfort -> Color(0xFF151C24).copy(alpha = style.balloonOpacity.coerceAtLeast(0.92f))
        isSilentTsubuyaki -> CinemaWhisperScrim.copy(alpha = (adaptiveOpacity * 0.88f).coerceIn(0.62f, 0.86f))
        placed.isAmbiguousFallback -> Color(0xFF1E293B).copy(alpha = 0.92f)
        isCinemaSubtle -> when {
            event.dialogueType == DialogueType.NARRATION || event.dialogueType == DialogueType.VOICE_OVER ->
                CinemaWhisperScrim.copy(alpha = (adaptiveOpacity * 0.96f).coerceIn(0.68f, 0.94f))
            else -> CinemaGlassDark.copy(alpha = adaptiveOpacity)
        }
        isManga -> when {
            event.dialogueType == DialogueType.NARRATION || event.dialogueType == DialogueType.VOICE_OVER ->
                MangaNarrationBg.copy(alpha = style.balloonOpacity.coerceAtLeast(0.84f))
            else -> MangaPaperWhite.copy(alpha = style.balloonOpacity.coerceAtLeast(0.84f))
        }
        else -> when {
            event.dialogueType == DialogueType.NARRATION || event.dialogueType == DialogueType.VOICE_OVER ->
                Color(0xFF0F172A).copy(alpha = 0.90f)
            else -> BalloonLightSurface.copy(alpha = style.balloonOpacity.coerceAtLeast(0.82f))
        }
    }
    val fillColor by animateColorAsState(
        targetValue = rawFillColor,
        animationSpec = colorAnimSpec,
        label = "balloon_fill_color",
    )

    val textColor = when {
        style.highContrastMode -> BalloonHighContrastInk
        isReadingComfort -> Color(0xFFF3F1EC)
        isSilentTsubuyaki -> Color(0xFFE2E8F0)
        placed.isAmbiguousFallback -> Color(0xFFF8FAFC)
        isCinemaSubtle -> Color(0xFFF8FAFC)
        isManga -> MangaSumiInk
        event.dialogueType == DialogueType.NARRATION || event.dialogueType == DialogueType.VOICE_OVER -> Color(0xFFF8FAFC)
        else -> BalloonLightInk
    }

    val subTextColor = when {
        style.highContrastMode -> Color(0xFFE2E8F0)
        isReadingComfort -> Color(0xFFCBD5E1)
        isCinemaSubtle || isSilentTsubuyaki || placed.isAmbiguousFallback -> Color(0xFFCBD5E1)
        isManga -> MangaSubtleInk
        textColor == BalloonLightInk -> BalloonLightSubtleInk
        else -> Color(0xFFCBD5E1)
    }

    val hasCharacterOutline = style.speakerSpecificColors && !isNonSpeakerEvent && !isUnknownSpeaker

    val rawOutlineColor = when {
        isSelected -> MaterialTheme.colorScheme.primary
        isUnknownSpeaker -> UnknownSpeakerGray.copy(alpha = if (isEditMode) 0.96f else 0.88f)
        style.highContrastMode -> BalloonHighContrastBorder
        isReadingComfort -> speakerAccent.copy(alpha = 0.82f)
        isNonSpeakerBubble -> NonSpeakerBubbleWhite.copy(
            alpha = when {
                isManga -> 0.94f
                isCinemaSubtle -> if (isHighEnergyShout) 0.92f else 0.80f
                else -> 0.96f
            }
        )
        isCinemaSubtle -> speakerAccent.copy(alpha = if (isHighEnergyShout) 0.98f else 0.84f)
        isManga -> speakerAccent
        else -> speakerAccent.copy(alpha = if (isHighEnergyShout) 1f else 0.90f)
    }
    val outlineColor by animateColorAsState(
        targetValue = rawOutlineColor,
        animationSpec = colorAnimSpec,
        label = "balloon_outline_color",
    )

    val innerPadH = when {
        isReadingComfort -> 14.dp
        isSilentTsubuyaki -> 12.dp
        isHighEnergyShout -> 13.dp
        isCinemaSubtle -> 12.dp
        isManga -> 11.dp
        else -> 10.dp
    }
    val innerPadV = when {
        isReadingComfort -> 8.dp
        isSilentTsubuyaki -> 5.dp
        isHighEnergyShout -> 7.dp
        isCinemaSubtle -> 6.5.dp
        isManga -> 6.dp
        else -> 5.5.dp
    }

    Box(
        modifier = modifier
            .clickable(onClick = onClick)
            .drawBehind {
                val bodyRect = Rect(0f, 0f, size.width, size.height)

                val baseStrokeWidth = when {
                    isSelected -> 2.5.dp.toPx()
                    isUnknownSpeaker -> 2.0.dp.toPx()
                    isCinemaSubtle -> 1.35.dp.toPx()
                    isManga -> 2.0.dp.toPx()
                    style.highContrastMode -> 2.dp.toPx()
                    else -> 1.35.dp.toPx()
                }
                val emphasisMultiplier = when {
                    isHighEnergyShout -> 1.90f
                    placed.emphasisScore >= 0.45f -> 1.32f
                    isSoftWhisper -> 0.82f
                    else -> 1f
                }
                val strokeWidth = baseStrokeWidth * emphasisMultiplier

                val dottedDash = when {
                    isUnknownSpeaker -> PathEffect.dashPathEffect(
                        floatArrayOf(1.5.dp.toPx(), strokeWidth + 4.5.dp.toPx()),
                        0f,
                    )
                    isManga && event.dialogueType == DialogueType.INTERNAL_MONOLOGUE ->
                        PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                    else -> null
                }
                val outlineStroke = Stroke(
                    width = strokeWidth,
                    cap = StrokeCap.Round,
                    join = if (isHighEnergyShout) StrokeJoin.Miter else StrokeJoin.Round,
                    pathEffect = dottedDash,
                )

                val cornerFraction = when {
                    isCinemaSubtle -> 0.44f
                    isManga -> 0.42f
                    else -> 0.28f
                }

                val bubblePath = when {
                    isSilentTsubuyaki ->
                        Path().apply {
                            addRoundRect(RoundRect(bodyRect, CornerRadius(10.dp.toPx(), 10.dp.toPx())))
                        }
                    (event.dialogueType == DialogueType.NARRATION || event.dialogueType == DialogueType.VOICE_OVER) ->
                        Path().apply {
                            val r = if (isManga) 3.dp.toPx() else 7.dp.toPx()
                            addRoundRect(RoundRect(bodyRect, CornerRadius(r, r)))
                        }
                    event.dialogueType == DialogueType.INTERNAL_MONOLOGUE ->
                        buildThoughtCloudPath(bodyRect)
                    else -> {
                        val elasticCapPx = minOf(
                            if (isCinemaSubtle) 200.dp.toPx() else 215.dp.toPx(),
                            if (containerWidthPx > 0f) containerWidthPx * 0.58f else Float.MAX_VALUE,
                        )
                        buildPointedSpeechPath(
                            bodyRect = bodyRect,
                            showPointerTail = showPointerTail,
                            aimAngleRad = tailAimAngleRad,
                            target = localTarget,
                            cornerFraction = cornerFraction,
                            isManga = isManga,
                            isCinemaSubtle = isCinemaSubtle,
                            mouthClearancePx = 11.dp.toPx(),
                            maxTailReachPx = elasticCapPx,
                            minTailReachPx = 13.dp.toPx(),
                        )
                    }
                }

                val drawContourPath = if (isHighEnergyShout) {
                    val shoutElasticCapPx = minOf(
                        205.dp.toPx(),
                        if (containerWidthPx > 0f) containerWidthPx * 0.56f else Float.MAX_VALUE,
                    )
                    buildJitteredShoutPath(
                        bodyRect = bodyRect,
                        showPointerTail = showPointerTail,
                        aimAngleRad = tailAimAngleRad,
                        target = localTarget,
                        spikeAmplitudePx = (if (isManga) 3.4.dp else 2.2.dp).toPx(),
                        jitterPhase = jitterPhase,
                        mouthClearancePx = 11.dp.toPx(),
                        maxTailReachPx = shoutElasticCapPx,
                        minTailReachPx = 13.dp.toPx(),
                    )
                } else {
                    bubblePath
                }

                // Clip shadow strictly outside the translucent body so interior stays clear & crisp
                clipPath(drawContourPath, clipOp = ClipOp.Difference) {
                    if (isManga) {
                        translate(left = 2.4.dp.toPx(), top = 2.8.dp.toPx()) {
                            drawPath(path = drawContourPath, color = MangaSumiInk.copy(alpha = 0.78f))
                        }
                    } else {
                        translate(left = 0f, top = 1.8.dp.toPx()) {
                            drawPath(path = drawContourPath, color = Color.Black.copy(alpha = 0.34f))
                        }
                    }
                }

                drawPath(path = drawContourPath, color = fillColor)

                clipPath(drawContourPath) {
                    if (isManga && !isUnknownSpeaker) {
                        drawMangaHalftoneCorner(
                            bodyRect = bodyRect,
                            dotColor = if (hasCharacterOutline) {
                                speakerAccent.copy(alpha = 0.16f)
                            } else {
                                MangaSumiInk.copy(alpha = 0.06f)
                            },
                        )
                    }
                    if (isManga && (event.dialogueType == DialogueType.NARRATION || event.dialogueType == DialogueType.VOICE_OVER)) {
                        val inset = 3.5.dp.toPx()
                        drawRect(
                            color = MangaSumiInk.copy(alpha = 0.30f),
                            topLeft = Offset(bodyRect.left + inset, bodyRect.top + inset),
                            size = Size(bodyRect.width - inset * 2f, bodyRect.height - inset * 2f),
                            style = Stroke(width = 1.dp.toPx()),
                        )
                    }
                }

                if (isManga && hasCharacterOutline && !isUnknownSpeaker) {
                    // Crisp inner dark ink definition paired with character's remembered hue
                    drawPath(
                        path = drawContourPath,
                        color = outlineColor.copy(alpha = 0.92f),
                        style = Stroke(width = strokeWidth + 1.3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                    drawPath(
                        path = drawContourPath,
                        color = MangaSumiInk,
                        style = Stroke(width = (strokeWidth * 0.55f).coerceAtLeast(1.1.dp.toPx()), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                } else {
                    drawPath(path = drawContourPath, color = outlineColor, style = outlineStroke)
                }

                if ((isManga || isCinemaSubtle) && event.dialogueType == DialogueType.INTERNAL_MONOLOGUE) {
                    val dotX = bodyRect.left + bodyRect.width * 0.26f
                    val dotY = bodyRect.bottom + 5.dp.toPx()
                    drawCircle(color = fillColor, radius = 3.2.dp.toPx(), center = Offset(dotX, dotY))
                    drawCircle(color = outlineColor, radius = 3.2.dp.toPx(), center = Offset(dotX, dotY), style = Stroke(1.1.dp.toPx()))
                }
            }
            .padding(horizontal = innerPadH, vertical = innerPadV),
    ) {
        BalloonContentColumn(
            placed = placed,
            translationMode = translationMode,
            style = style,
            speakerAccent = speakerAccent,
            textColor = textColor,
            subTextColor = subTextColor,
            isSpecialNonSpoken = isSpecialNonSpoken,
            isMangaStyle = isManga,
            isCinemaSubtle = isCinemaSubtle,
            isEditMode = isEditMode,
            isSilentTsubuyaki = isSilentTsubuyaki,
            isHighEnergyShout = isHighEnergyShout,
            maxBubbleWidthDp = maxBubbleWidthDp,
        )
    }
}

/**
 * Computes the shortest signed angular delta from [fromRad] to [toRad] in [-PI, PI]
 * so rotating curved tails glide smoothly across the +/-PI seam.
 */
internal fun shortestAngularDeltaRad(fromRad: Float, toRad: Float): Float {
    val twoPi = (2.0 * PI).toFloat()
    var diff = (toRad - fromRad) % twoPi
    if (diff > PI.toFloat()) diff -= twoPi
    if (diff < -PI.toFloat()) diff += twoPi
    return diff
}

internal data class PerimeterSample(
    val point: Offset,
    val outwardNormal: Offset,
    val clockwiseTangent: Offset,
)

internal data class PerimeterTailGeometry(
    val perimeterLength: Float,
    val cornerRadius: Float,
    val exitArcLength: Float,
    val throatStartArcLength: Float,
    val throatEndArcLength: Float,
    val baseCenter: Offset,
    val throatStartPoint: Offset,
    val throatEndPoint: Offset,
    val tipPoint: Offset,
    val outerCtrl1: Offset,
    val outerCtrl2: Offset,
    val innerCtrl1: Offset,
    val innerCtrl2: Offset,
    val effectiveHalfThroatPx: Float = 8f,
    val tailLengthPx: Float = 20f,
)

internal fun sampleRoundedRectPerimeter(
    bodyRect: Rect,
    cornerRadius: Float,
    arcLengthRaw: Float,
): PerimeterSample {
    val l = bodyRect.left
    val t = bodyRect.top
    val r = bodyRect.right
    val b = bodyRect.bottom
    val w = bodyRect.width.coerceAtLeast(2f)
    val h = bodyRect.height.coerceAtLeast(2f)
    val cr = cornerRadius.coerceIn(2f, (minOf(w, h) * 0.48f).coerceAtLeast(2f))
    val topLen = (w - 2f * cr).coerceAtLeast(0f)
    val sideLen = (h - 2f * cr).coerceAtLeast(0f)
    val arcLen = (PI * 0.5 * cr).toFloat()
    val totalP = (2f * topLen + 2f * sideLen + 4f * arcLen).coerceAtLeast(1f)
    var s = ((arcLengthRaw % totalP) + totalP) % totalP

    if (s <= topLen) {
        return PerimeterSample(
            point = Offset(l + cr + s, t),
            outwardNormal = Offset(0f, -1f),
            clockwiseTangent = Offset(1f, 0f),
        )
    }
    s -= topLen

    if (s <= arcLen) {
        val ang = (-PI * 0.5 + (s / cr)).toFloat()
        val ca = cos(ang)
        val sa = sin(ang)
        return PerimeterSample(
            point = Offset((r - cr) + cr * ca, (t + cr) + cr * sa),
            outwardNormal = Offset(ca, sa),
            clockwiseTangent = Offset(-sa, ca),
        )
    }
    s -= arcLen

    if (s <= sideLen) {
        return PerimeterSample(
            point = Offset(r, t + cr + s),
            outwardNormal = Offset(1f, 0f),
            clockwiseTangent = Offset(0f, 1f),
        )
    }
    s -= sideLen

    if (s <= arcLen) {
        val ang = (s / cr)
        val ca = cos(ang)
        val sa = sin(ang)
        return PerimeterSample(
            point = Offset((r - cr) + cr * ca, (b - cr) + cr * sa),
            outwardNormal = Offset(ca, sa),
            clockwiseTangent = Offset(-sa, ca),
        )
    }
    s -= arcLen

    if (s <= topLen) {
        return PerimeterSample(
            point = Offset(r - cr - s, b),
            outwardNormal = Offset(0f, 1f),
            clockwiseTangent = Offset(-1f, 0f),
        )
    }
    s -= topLen

    if (s <= arcLen) {
        val ang = (PI * 0.5 + (s / cr)).toFloat()
        val ca = cos(ang)
        val sa = sin(ang)
        return PerimeterSample(
            point = Offset((l + cr) + cr * ca, (b - cr) + cr * sa),
            outwardNormal = Offset(ca, sa),
            clockwiseTangent = Offset(-sa, ca),
        )
    }
    s -= arcLen

    if (s <= sideLen) {
        return PerimeterSample(
            point = Offset(l, b - cr - s),
            outwardNormal = Offset(-1f, 0f),
            clockwiseTangent = Offset(0f, -1f),
        )
    }
    s -= sideLen

    val ang = (PI + (s / cr).coerceIn(0f, (PI * 0.5).toFloat())).toFloat()
    val ca = cos(ang)
    val sa = sin(ang)
    return PerimeterSample(
        point = Offset((l + cr) + cr * ca, (t + cr) + cr * sa),
        outwardNormal = Offset(ca, sa),
        clockwiseTangent = Offset(-sa, ca),
    )
}

/**
 * Computes continuous 360-degree rounded-perimeter exit geometry for an open-throat curved comic tail.
 * Smoothly traverses all 4 flat edges AND all 4 rounded corners with zero discrete edge jumps.
 */
internal fun computePerimeterTailGeometry(
    bodyRect: Rect,
    cornerRadius: Float,
    aimAngleRad: Float,
    targetDistanceFromCenterPx: Float,
    halfThroatPx: Float,
    mouthClearancePx: Float,
    minTailReachPx: Float,
    maxTailReachPx: Float,
): PerimeterTailGeometry {
    val l = bodyRect.left
    val t = bodyRect.top
    val r = bodyRect.right
    val b = bodyRect.bottom
    val w = bodyRect.width.coerceAtLeast(2f)
    val h = bodyRect.height.coerceAtLeast(2f)
    val cx = bodyRect.center.x
    val cy = bodyRect.center.y
    val cr = cornerRadius.coerceIn(2f, (minOf(w, h) * 0.48f).coerceAtLeast(2f))
    val topLen = (w - 2f * cr).coerceAtLeast(0f)
    val sideLen = (h - 2f * cr).coerceAtLeast(0f)
    val arcLen = (PI * 0.5 * cr).toFloat()
    val totalP = (2f * topLen + 2f * sideLen + 4f * arcLen).coerceAtLeast(1f)

    val dirX = cos(aimAngleRad)
    val dirY = sin(aimAngleRad)
    val tX = if (abs(dirX) > 1e-5f) {
        if (dirX > 0f) (r - cx) / dirX else (l - cx) / dirX
    } else {
        Float.MAX_VALUE
    }
    val tY = if (abs(dirY) > 1e-5f) {
        if (dirY > 0f) (b - cy) / dirY else (t - cy) / dirY
    } else {
        Float.MAX_VALUE
    }
    val tHit = minOf(tX, tY).coerceAtLeast(0f)
    val hitX = (cx + dirX * tHit).coerceIn(l, r)
    val hitY = (cy + dirY * tHit).coerceIn(t, b)

    val halfPi = (PI * 0.5).toFloat()
    val pi = PI.toFloat()
    val exitS = when {
        hitX > r - cr && hitY < t + cr -> {
            val ang = atan2((hitY - (t + cr)).toDouble(), (hitX - (r - cr)).toDouble()).toFloat()
                .coerceIn(-halfPi, 0f)
            topLen + (ang + halfPi) * cr
        }
        hitX > r - cr && hitY > b - cr -> {
            val ang = atan2((hitY - (b - cr)).toDouble(), (hitX - (r - cr)).toDouble()).toFloat()
                .coerceIn(0f, halfPi)
            topLen + arcLen + sideLen + ang * cr
        }
        hitX < l + cr && hitY > b - cr -> {
            val ang = atan2((hitY - (b - cr)).toDouble(), (hitX - (l + cr)).toDouble()).toFloat()
                .coerceIn(halfPi, pi)
            2f * topLen + 2f * arcLen + sideLen + (ang - halfPi) * cr
        }
        hitX < l + cr && hitY < t + cr -> {
            var ang = atan2((hitY - (t + cr)).toDouble(), (hitX - (l + cr)).toDouble()).toFloat()
            if (ang < 0f) ang += 2f * pi
            ang = ang.coerceIn(pi, pi + halfPi)
            2f * topLen + 3f * arcLen + 2f * sideLen + (ang - pi) * cr
        }
        hitY <= t + 0.5f -> (hitX - (l + cr)).coerceIn(0f, topLen)
        hitX >= r - 0.5f -> topLen + arcLen + (hitY - (t + cr)).coerceIn(0f, sideLen)
        hitY >= b - 0.5f -> topLen + 2f * arcLen + sideLen + ((r - cr) - hitX).coerceIn(0f, topLen)
        else -> 2f * topLen + 3f * arcLen + sideLen + ((b - cr) - hitY).coerceIn(0f, sideLen)
    }

    val baseSample = sampleRoundedRectPerimeter(bodyRect, cr, exitS)
    val baseRadius = hypot((baseSample.point.x - cx).toDouble(), (baseSample.point.y - cy).toDouble()).toFloat()
    val distOutsideBorder = (targetDistanceFromCenterPx - baseRadius).coerceAtLeast(0f)
    val reach = (distOutsideBorder - mouthClearancePx).coerceIn(minTailReachPx, maxTailReachPx)

    // Proportionally tapered elastic throat: crisp and generous at short reaches, gracefully sleek across long
    // dead-space reaches (up to 58% of stage width) so stretched tails never look bloated OR frayed.
    val stretchFactor = ((reach - 24f) / 145f).coerceIn(0f, 1f)
    val throatTaper = 1.0f - stretchFactor * 0.42f
    val safeHalfThroat = (halfThroatPx * throatTaper).coerceIn(4.8f, (totalP * 0.115f).coerceAtLeast(6f))
    val throatStartS = ((exitS - safeHalfThroat) % totalP + totalP) % totalP
    val throatEndS = ((exitS + safeHalfThroat) % totalP + totalP) % totalP
    val startSample = sampleRoundedRectPerimeter(bodyRect, cr, throatStartS)
    val endSample = sampleRoundedRectPerimeter(bodyRect, cr, throatEndS)

    val axis = Offset(dirX, dirY)
    val perp = Offset(-dirY, dirX)
    val tip = Offset(
        x = baseSample.point.x + axis.x * reach,
        y = baseSample.point.y + axis.y * reach,
    )

    // Distance-damped comic sweep: bends organically toward oblique mouths while softening bow on long reaches
    // so stretched tails stay sleek, unobtrusive, and never malformed or kinky.
    val normalCross = baseSample.outwardNormal.x * axis.y - baseSample.outwardNormal.y * axis.x
    val curveDamp = 1.0f - stretchFactor * 0.56f
    val hook = ((normalCross * 0.80f).coerceIn(-0.42f, 0.42f) +
        if (abs(normalCross) < 0.09f) 0.13f else 0f) * curveDamp
    val throatFlare = (reach * 0.19f).coerceIn(4.5f, 26f)
    val tipApproach = (reach * 0.24f).coerceIn(5f, 34f)
    val maxBowOffset = (reach * 0.13f).coerceAtMost(18f)

    // Flared concave throat fillets so the bubble's inner empty fill bleeds organically into the rotating tail
    val outerCtrl1 = Offset(
        x = startSample.point.x + startSample.outwardNormal.x * throatFlare + perp.x * (maxBowOffset * hook),
        y = startSample.point.y + startSample.outwardNormal.y * throatFlare + perp.y * (maxBowOffset * hook),
    )
    val outerCtrl2 = Offset(
        x = tip.x - axis.x * tipApproach + perp.x * (maxBowOffset * 0.86f * hook),
        y = tip.y - axis.y * tipApproach + perp.y * (maxBowOffset * 0.86f * hook),
    )
    val innerCtrl1 = Offset(
        x = tip.x - axis.x * (tipApproach * 0.88f) - perp.x * (maxBowOffset * 0.16f * hook),
        y = tip.y - axis.y * (tipApproach * 0.88f) - perp.y * (maxBowOffset * 0.16f * hook),
    )
    val innerCtrl2 = Offset(
        x = endSample.point.x + endSample.outwardNormal.x * (throatFlare * 0.86f) - perp.x * (maxBowOffset * 0.24f * hook),
        y = endSample.point.y + endSample.outwardNormal.y * (throatFlare * 0.86f) - perp.y * (maxBowOffset * 0.24f * hook),
    )

    return PerimeterTailGeometry(
        perimeterLength = totalP,
        cornerRadius = cr,
        exitArcLength = exitS,
        throatStartArcLength = throatStartS,
        throatEndArcLength = throatEndS,
        baseCenter = baseSample.point,
        throatStartPoint = startSample.point,
        throatEndPoint = endSample.point,
        tipPoint = tip,
        outerCtrl1 = outerCtrl1,
        outerCtrl2 = outerCtrl2,
        innerCtrl1 = innerCtrl1,
        innerCtrl2 = innerCtrl2,
        effectiveHalfThroatPx = safeHalfThroat,
        tailLengthPx = reach,
    )
}

/**
 * Builds a unified editorial speech balloon path with a continuous 360-degree border-gliding
 * curved comic tail. Because the tail throat is carved open directly inside the single contour,
 * the bubble's interior space bleeds seamlessly into the rotating tail with zero dividing seam.
 */
private fun buildPointedSpeechPath(
    bodyRect: Rect,
    showPointerTail: Boolean,
    aimAngleRad: Float,
    target: Offset?,
    cornerFraction: Float,
    isManga: Boolean,
    isCinemaSubtle: Boolean,
    mouthClearancePx: Float,
    maxTailReachPx: Float,
    minTailReachPx: Float,
): Path {
    val w = bodyRect.width
    val h = bodyRect.height
    val cornerR = (minOf(w, h) * cornerFraction).coerceIn(10f, 24f)

    if (!showPointerTail || target == null || aimAngleRad.isNaN()) {
        return Path().apply {
            addRoundRect(RoundRect(bodyRect, CornerRadius(cornerR, cornerR)))
        }
    }

    val targetDistFromCenter = hypot(
        (target.x - bodyRect.center.x).toDouble(),
        (target.y - bodyRect.center.y).toDouble(),
    ).toFloat().coerceAtLeast(1f)
    val halfThroatPx = when {
        isManga -> (minOf(w, h) * 0.19f).coerceIn(7.5f, 13.5f)
        isCinemaSubtle -> (minOf(w, h) * 0.16f).coerceIn(6.5f, 11.5f)
        else -> (minOf(w, h) * 0.15f).coerceIn(6.0f, 10.5f)
    }

    val geom = computePerimeterTailGeometry(
        bodyRect = bodyRect,
        cornerRadius = cornerR,
        aimAngleRad = aimAngleRad,
        targetDistanceFromCenterPx = targetDistFromCenter,
        halfThroatPx = halfThroatPx,
        mouthClearancePx = mouthClearancePx,
        minTailReachPx = minTailReachPx,
        maxTailReachPx = maxTailReachPx,
    )

    val bodySpan = (geom.perimeterLength - 2f * geom.effectiveHalfThroatPx).coerceAtLeast(1f)
    val steps = 64

    return Path().apply {
        moveTo(geom.throatEndPoint.x, geom.throatEndPoint.y)
        for (i in 1..steps) {
            val s = geom.throatEndArcLength + bodySpan * (i.toFloat() / steps.toFloat())
            val pt = sampleRoundedRectPerimeter(bodyRect, geom.cornerRadius, s).point
            lineTo(pt.x, pt.y)
        }
        // Open-throat curved comic tail sweeping smoothly toward the speaker's mouth
        cubicTo(
            geom.outerCtrl1.x, geom.outerCtrl1.y,
            geom.outerCtrl2.x, geom.outerCtrl2.y,
            geom.tipPoint.x, geom.tipPoint.y,
        )
        cubicTo(
            geom.innerCtrl1.x, geom.innerCtrl1.y,
            geom.innerCtrl2.x, geom.innerCtrl2.y,
            geom.throatEndPoint.x, geom.throatEndPoint.y,
        )
        close()
    }
}

/**
 * Builds a high-energy manga burst / jittered speech outline for shouted or high-emphasis lines
 * while keeping the interior readable and gliding the tail continuously toward [target].
 */
private fun buildJitteredShoutPath(
    bodyRect: Rect,
    showPointerTail: Boolean,
    aimAngleRad: Float,
    target: Offset?,
    spikeAmplitudePx: Float,
    jitterPhase: Float,
    mouthClearancePx: Float,
    maxTailReachPx: Float,
    minTailReachPx: Float,
): Path {
    val cx = bodyRect.center.x
    val cy = bodyRect.center.y
    val rx = (bodyRect.width * 0.54f).coerceAtLeast(24f)
    val ry = (bodyRect.height * 0.58f).coerceAtLeast(16f)
    val steps = 32

    val tailAngle = if (showPointerTail && target != null && !aimAngleRad.isNaN()) {
        aimAngleRad
    } else {
        Float.NaN
    }

    val tailTip = if (showPointerTail && target != null && !tailAngle.isNaN()) {
        val dx = target.x - cx
        val dy = target.y - cy
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
        val edgeR = hypot((rx * cos(tailAngle)).toDouble(), (ry * sin(tailAngle)).toDouble()).toFloat()
        val reach = (dist - mouthClearancePx).coerceIn(edgeR + minTailReachPx, edgeR + maxTailReachPx)
        Offset(cx + cos(tailAngle) * reach, cy + sin(tailAngle) * reach)
    } else {
        null
    }

    return Path().apply {
        for (i in 0 until steps) {
            val angle = (i.toFloat() / steps.toFloat()) * (2f * PI).toFloat()
            var angDiff = if (!tailAngle.isNaN()) abs(shortestAngularDeltaRad(angle, tailAngle)) else Float.MAX_VALUE

            if (tailTip != null && angDiff < 0.20f) {
                if (angDiff < 0.08f) {
                    lineTo(tailTip.x, tailTip.y)
                    continue
                }
            }

            val isSpike = i % 2 == 0
            val phasePulse = 0.72f + 0.28f * sin(jitterPhase + i * 1.9f)
            val radialDelta = if (isSpike) spikeAmplitudePx * phasePulse else -spikeAmplitudePx * 0.38f * phasePulse
            val px = cx + (rx + radialDelta) * cos(angle)
            val py = cy + (ry + radialDelta * 0.85f) * sin(angle)
            if (i == 0) moveTo(px, py) else lineTo(px, py)
        }
        close()
    }
}

private fun buildThoughtCloudPath(rect: Rect): Path {
    val r = minOf(rect.width, rect.height) * 0.46f
    return Path().apply {
        addRoundRect(RoundRect(rect, CornerRadius(r, r)))
    }
}

private fun DrawScope.drawMangaHalftoneCorner(bodyRect: Rect, dotColor: Color) {
    val spacing = 5.dp.toPx()
    val startX = bodyRect.right - spacing * 4.5f
    val startY = bodyRect.top + 3.5.dp.toPx()
    for (row in 0..2) {
        for (col in 0..3) {
            val radius = (1.5f - row * 0.35f).dp.toPx().coerceAtLeast(0.6f)
            drawCircle(
                color = dotColor,
                radius = radius,
                center = Offset(
                    x = startX + col * spacing + (if (row % 2 == 1) spacing * 0.45f else 0f),
                    y = startY + row * spacing,
                ),
            )
        }
    }
}

@Composable
private fun BalloonContentColumn(
    placed: PlacedBalloon,
    translationMode: TranslationMode,
    style: BalloonStyleSettings,
    speakerAccent: Color,
    textColor: Color,
    subTextColor: Color,
    isSpecialNonSpoken: Boolean,
    isMangaStyle: Boolean,
    isCinemaSubtle: Boolean,
    isEditMode: Boolean,
    isSilentTsubuyaki: Boolean,
    isHighEnergyShout: Boolean,
    maxBubbleWidthDp: Float,
) {
    val event = placed.event
    val isUnknownSpeaker = !isNonSpeakerSpecificEvent(event) &&
        (placed.isUnknownSpeaker || isBubbleSpeakerUnknown(event, placed.speaker))
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val resolvedOffScreenSide = if (placed.isOffScreenEdge) (placed.edgeSide ?: event.dialogueType) else null
        // Inspector / Edit metadata is strictly separated from pure story playback
        val showEditBadges = isEditMode && (isUnknownSpeaker || placed.isAmbiguousFallback || event.lockedByUser || style.showConfidenceIndicators)
        val showAutoSpeakerLabel = placed.speaker != null && !isSpecialNonSpoken && when (style.labelMode) {
            SpeakerLabelMode.AUTO_FIRST_10 -> placed.speakerLineOrdinal in 1..10
            SpeakerLabelMode.ALWAYS -> true
            SpeakerLabelMode.OFF -> false
        }
        val showSpeakerHeader = !isSilentTsubuyaki && (
            showEditBadges ||
                showAutoSpeakerLabel ||
                (event.dialogueType == DialogueType.NARRATION && isEditMode) ||
                event.dialogueType == DialogueType.INTERNAL_MONOLOGUE
            )

        if (showSpeakerHeader) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (isEditMode && style.speakerSpecificColors && placed.speaker != null && !isSpecialNonSpoken && !isUnknownSpeaker) {
                    Box(
                        modifier = Modifier
                            .size(width = 8.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(speakerAccent)
                    )
                }

                when {
                    isUnknownSpeaker && isEditMode -> {
                        Icon(
                            imageVector = Icons.Filled.HelpOutline,
                            contentDescription = "Unknown speaker",
                            tint = UnknownSpeakerGray,
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            text = "Unknown · Tap",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                            color = UnknownSpeakerGray,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    (event.dialogueType == DialogueType.NARRATION || event.dialogueType == DialogueType.VOICE_OVER) && isEditMode -> {
                        Icon(
                            imageVector = Icons.Filled.RecordVoiceOver,
                            contentDescription = event.dialogueType.label,
                            tint = if (isMangaStyle) MangaSumiInk else NonSpeakerBubbleWhite,
                            modifier = Modifier.size(11.dp),
                        )
                        Text(
                            text = event.dialogueType.label.uppercase(),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                            color = if (isMangaStyle) MangaSumiInk else NonSpeakerBubbleWhite,
                        )
                    }
                    event.dialogueType == DialogueType.INTERNAL_MONOLOGUE -> {
                        Icon(
                            imageVector = Icons.Filled.Psychology,
                            contentDescription = "Internal monologue",
                            tint = subTextColor,
                            modifier = Modifier.size(11.dp),
                        )
                    }
                    placed.speaker != null && showAutoSpeakerLabel -> {
                        Text(
                            text = if (isMangaStyle) placed.speaker.displayName.uppercase() else placed.speaker.displayName,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = (9.5f * style.fontScale).sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = if (isMangaStyle) 0.5.sp else 0.2.sp,
                            ),
                            color = when {
                                isUnknownSpeaker -> UnknownSpeakerGray
                                isCinemaSubtle -> speakerAccent.copy(alpha = 0.92f)
                                else -> subTextColor
                            },
                            maxLines = 1,
                        )
                    }
                }

                if (isEditMode && style.showConfidenceIndicators && !isUnknownSpeaker && !isSpecialNonSpoken) {
                    val confColor = when (event.confidenceTier) {
                        ConfidenceTier.HIGH -> ConfidenceHighColor
                        ConfidenceTier.MEDIUM -> ConfidenceMediumColor
                        ConfidenceTier.LOW -> ConfidenceLowColor
                    }
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(confColor)
                    )
                }

                if (isEditMode && event.lockedByUser) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = "Locked by user",
                        tint = subTextColor,
                        modifier = Modifier.size(10.dp),
                    )
                }
            }
        }

        val effectiveFontScale = when {
            isSilentTsubuyaki -> style.fontScale * 0.90f
            isHighEnergyShout -> style.fontScale * 1.07f
            else -> style.fontScale
        }
        val estimatedCharsPerLine = ((maxBubbleWidthDp - 26f) / (8.4f * effectiveFontScale))
            .toInt()
            .coerceAtLeast(12)
        val rawDisplayText = event.activeText(translationMode)
        val balancedText = remember(rawDisplayText, style.maxLines, estimatedCharsPerLine) {
            balanceSubtitleLines(
                text = rawDisplayText,
                maxLines = style.maxLines,
                charsPerLine = estimatedCharsPerLine,
            )
        }
        val finalDisplayText = balancedText

        val activeFontFamily = if (isMangaStyle) NunitoFamily else LexendFamily
        val legibilityShadow = if (textColor.red > 0.7f && !style.highContrastMode) {
            Shadow(
                color = Color.Black.copy(alpha = 0.58f),
                offset = Offset(0f, 1.2f),
                blurRadius = 3.2f,
            )
        } else {
            null
        }

        val sourceContainsCjk = remember(event.sourceText) { containsCjkScript(event.sourceText) }
        val displayContainsCjk = remember(finalDisplayText) { containsCjkScript(finalDisplayText) }
        val renderTategakiMain = style.tategakiVerticalJapanese && displayContainsCjk && !isSilentTsubuyaki

        val isSoftWhisper = !isHighEnergyShout &&
            !isSpecialNonSpoken &&
            (placed.event.audioFeatures?.let { it.speechEnergy in 0.05f..0.28f } == true)

        if (resolvedOffScreenSide == DialogueType.OFF_SCREEN_BEHIND) {
            OffScreenDirectionBadge(
                arrowGlyph = "▲",
                accent = if (isUnknownSpeaker) UnknownSpeakerGray else speakerAccent,
                isMangaStyle = isMangaStyle,
                tag = "offscreen_arrow_behind_${event.id}",
            )
        }

        val mainTextComposable: @Composable () -> Unit = {
            if (renderTategakiMain) {
                TategakiVerticalText(
                    text = finalDisplayText,
                    fontSizeSp = 13.5f * effectiveFontScale,
                    color = textColor,
                    isBold = isHighEnergyShout || isMangaStyle,
                    shadow = legibilityShadow,
                )
            } else {
                Text(
                    text = finalDisplayText,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = activeFontFamily,
                        fontSize = (14.5f * effectiveFontScale).sp,
                        lineHeight = (19.0f * effectiveFontScale).sp,
                        letterSpacing = if (isHighEnergyShout) 0.35.sp else 0.15.sp,
                        shadow = legibilityShadow,
                        fontWeight = when {
                            isSilentTsubuyaki || isSoftWhisper -> FontWeight.Normal
                            isHighEnergyShout -> FontWeight.Bold
                            isMangaStyle -> FontWeight.Bold
                            else -> FontWeight.Medium
                        },
                        fontStyle = if (isSilentTsubuyaki ||
                            isSoftWhisper ||
                            event.dialogueType == DialogueType.INTERNAL_MONOLOGUE ||
                            event.dialogueType == DialogueType.NARRATION
                        ) {
                            FontStyle.Italic
                        } else {
                            FontStyle.Normal
                        },
                    ),
                    color = textColor,
                    textAlign = when (resolvedOffScreenSide) {
                        DialogueType.OFF_SCREEN_LEFT -> TextAlign.Start
                        DialogueType.OFF_SCREEN_RIGHT -> TextAlign.End
                        else -> TextAlign.Center
                    },
                    softWrap = true,
                    maxLines = Int.MAX_VALUE,
                )
            }
        }

        if (resolvedOffScreenSide == DialogueType.OFF_SCREEN_LEFT || resolvedOffScreenSide == DialogueType.OFF_SCREEN_RIGHT) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                if (resolvedOffScreenSide == DialogueType.OFF_SCREEN_LEFT) {
                    OffScreenDirectionBadge(
                        arrowGlyph = "◀",
                        accent = if (isUnknownSpeaker) UnknownSpeakerGray else speakerAccent,
                        isMangaStyle = isMangaStyle,
                        tag = "offscreen_arrow_left_${event.id}",
                    )
                }
                Box(modifier = Modifier.weight(1f, fill = false)) {
                    mainTextComposable()
                }
                if (resolvedOffScreenSide == DialogueType.OFF_SCREEN_RIGHT) {
                    OffScreenDirectionBadge(
                        arrowGlyph = "▶",
                        accent = if (isUnknownSpeaker) UnknownSpeakerGray else speakerAccent,
                        isMangaStyle = isMangaStyle,
                        tag = "offscreen_arrow_right_${event.id}",
                    )
                }
            }
        } else {
            mainTextComposable()
        }

        if (translationMode == TranslationMode.LEARNING && event.sourceText.isNotBlank()) {
            if (style.tategakiVerticalJapanese && sourceContainsCjk) {
                TategakiVerticalText(
                    text = event.sourceText,
                    fontSizeSp = 10.5f * style.fontScale,
                    color = subTextColor,
                    isBold = false,
                    shadow = null,
                )
            } else {
                Text(
                    text = event.sourceText,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = (10.5f * style.fontScale).sp,
                        lineHeight = (13.5f * style.fontScale).sp,
                    ),
                    color = subTextColor,
                    textAlign = TextAlign.Center,
                    softWrap = true,
                    maxLines = Int.MAX_VALUE,
                )
            }
            if (event.romanization.isNotBlank()) {
                Text(
                    text = event.romanization,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = (9.5f * style.fontScale).sp,
                    ),
                    color = subTextColor.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                    softWrap = true,
                    maxLines = Int.MAX_VALUE,
                )
            }
        }

        if (event.culturalNote.isNotBlank() &&
            (isEditMode || translationMode == TranslationMode.LEARNING)
        ) {
            Surface(
                color = speakerAccent.copy(alpha = 0.16f),
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    text = "文化 Note · Tap",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp),
                    color = if (isMangaStyle) MangaSumiInk else speakerAccent,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun OffScreenDirectionBadge(
    arrowGlyph: String,
    accent: Color,
    isMangaStyle: Boolean,
    tag: String,
) {
    Surface(
        color = accent.copy(alpha = if (isMangaStyle) 0.24f else 0.22f),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier
            .testTag(tag)
            .border(
                width = 1.dp,
                color = accent.copy(alpha = 0.78f),
                shape = RoundedCornerShape(6.dp),
            ),
    ) {
        Text(
            text = arrowGlyph,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
            ),
            color = if (isMangaStyle) MangaSumiInk else accent,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}

private fun containsCjkScript(text: String): Boolean =
    text.any { ch ->
        val code = ch.code
        (code in 0x3040..0x30FF) || (code in 0x4E00..0x9FFF) || (code in 0xAC00..0xD7AF)
    }

/**
 * Renders vertical manga-style Japanese/CJK columns flowing top-to-bottom, right-to-left.
 */
@Composable
private fun TategakiVerticalText(
    text: String,
    fontSizeSp: Float,
    color: Color,
    isBold: Boolean,
    shadow: Shadow?,
) {
    val columns = remember(text) {
        val rawSegments = text.split('\n')
            .flatMap { line -> line.chunked(10) }
            .filter { it.isNotBlank() }
            .take(8)
        rawSegments.reversed() // Right-to-left traditional manga column order
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        columns.forEach { col ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy((-1).dp),
            ) {
                col.forEach { ch ->
                    val mapped = when (ch) {
                        'ー', '―', '—' -> '丨'
                        '…' -> '⋮'
                        '(', '（' -> '︵'
                        ')', '）' -> '︶'
                        else -> ch
                    }
                    Text(
                        text = mapped.toString(),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = fontSizeSp.sp,
                            lineHeight = (fontSizeSp + 1.5f).sp,
                            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Medium,
                            shadow = shadow,
                        ),
                        color = color,
                    )
                }
            }
        }
    }
}

/**
 * Splits multi-word dialogue into two balanced editorial lines only when both halves fit cleanly
 * inside [charsPerLine], avoiding jagged orphan-word double-wrapping.
 */
private fun balanceSubtitleLines(text: String, maxLines: Int, charsPerLine: Int): String {
    val clean = text.trim()
    if (maxLines <= 1 || clean.contains('\n') || clean.length <= 22 || clean.length > (charsPerLine * 2 - 2)) {
        return clean
    }
    val words = clean.split(Regex("\\s+"))
    if (words.size < 4) return clean

    val halfLen = clean.length / 2
    var runningLen = 0
    var bestSplitIdx = -1
    var bestScore = Int.MAX_VALUE

    for (i in 1 until words.size) {
        runningLen += words[i - 1].length + 1
        val lineA = runningLen - 1
        val lineB = clean.length - runningLen
        if (lineA < 6 || lineB < 6) continue
        if (lineA > charsPerLine || lineB > charsPerLine) continue

        val prevWord = words[i - 1]
        val pauseBonus = if (prevWord.endsWith(",") || prevWord.endsWith(".") ||
            prevWord.endsWith("?") || prevWord.endsWith("!") || prevWord.endsWith("—")
        ) -12 else 0
        val score = abs(runningLen - halfLen) + pauseBonus
        if (score < bestScore) {
            bestScore = score
            bestSplitIdx = i
        }
    }

    if (bestSplitIdx <= 0) return clean
    return words.take(bestSplitIdx).joinToString(" ") + "\n" + words.drop(bestSplitIdx).joinToString(" ")
}

private fun resolveFaceSafePixelPosition(
    baseLeftPx: Float,
    baseTopPx: Float,
    actualW: Float,
    actualH: Float,
    safeMarginX: Float,
    safeMarginY: Float,
    maxLeftPx: Float,
    maxTopPx: Float,
    containerWidthPx: Float,
    containerHeightPx: Float,
    faceExclusionRects: List<NormalizedRect>,
    occupiedBubbleRects: List<Rect> = emptyList(),
    density: Float,
    allowBelowFace: Boolean = true,
): Pair<Float, Float> {
    val facePixels = faceExclusionRects.map { f ->
        Rect(
            left = f.left * containerWidthPx,
            top = f.top * containerHeightPx,
            right = f.right * containerWidthPx,
            bottom = f.bottom * containerHeightPx,
        )
    }
    val bubblePadPx = 6f * density
    val siblingPixels = occupiedBubbleRects.map { b ->
        Rect(
            left = b.left - bubblePadPx,
            top = b.top - bubblePadPx,
            right = b.right + bubblePadPx,
            bottom = b.bottom + bubblePadPx,
        )
    }

    fun sumOverlap(rects: List<Rect>, l: Float, t: Float): Float {
        val r = l + actualW
        val b = t + actualH
        var total = 0f
        for (item in rects) {
            val ix = (minOf(r, item.right) - maxOf(l, item.left)).coerceAtLeast(0f)
            val iy = (minOf(b, item.bottom) - maxOf(t, item.top)).coerceAtLeast(0f)
            total += ix * iy
        }
        return total
    }

    // Emergency-only gate (DialogueLens 2.0 Section 21C):
    // BalloonLayoutEngine is the single authoritative layout engine; only escape here if a
    // measured bubble severely overlaps a live face (>14% bubble area) or sibling (>18% bubble area).
    val bubbleArea = (actualW * actualH).coerceAtLeast(1f)
    val baseFaceOverlapFrac = sumOverlap(facePixels, baseLeftPx, baseTopPx) / bubbleArea
    val baseSiblingOverlapFrac = sumOverlap(siblingPixels, baseLeftPx, baseTopPx) / bubbleArea
    if (baseFaceOverlapFrac <= 0.14f && baseSiblingOverlapFrac <= 0.18f) {
        return baseLeftPx to baseTopPx
    }

    val gap = 6f * density
    val candidates = buildList {
        add(baseLeftPx to baseTopPx)
        if (facePixels.isNotEmpty()) {
            val minFaceTop = facePixels.minOf { it.top }
            val maxFaceBottom = facePixels.maxOf { it.bottom }
            val minFaceLeft = facePixels.minOf { it.left }
            val maxFaceRight = facePixels.maxOf { it.right }
            add(baseLeftPx to minFaceTop - actualH - gap)
            if (allowBelowFace) {
                add(baseLeftPx to maxFaceBottom + gap)
            }
            add(minFaceLeft - actualW - gap to baseTopPx)
            add(maxFaceRight + gap to baseTopPx)
            for (face in facePixels) {
                add((face.center.x - actualW * 0.5f) to (face.top - actualH - gap))
                if (allowBelowFace) {
                    add((face.center.x - actualW * 0.5f) to (face.bottom + gap))
                }
                add((face.left - actualW - gap) to (face.center.y - actualH * 0.5f))
                add((face.right + gap) to (face.center.y - actualH * 0.5f))
            }
        }
        for (sib in siblingPixels) {
            add((sib.center.x - actualW * 0.5f) to (sib.top - actualH - gap))
            add((sib.center.x - actualW * 0.5f) to (sib.bottom + gap))
            add((sib.left - actualW - gap) to sib.top)
            add((sib.right + gap) to sib.top)
        }
        // Full 2D screen-wide roaming grid so bubbles glide cleanly into ANY open dead space on screen
        for (fx in listOf(0.08f, 0.25f, 0.42f, 0.58f, 0.75f, 0.92f)) {
            for (fy in listOf(0.09f, 0.26f, 0.46f, 0.66f, 0.85f)) {
                add((containerWidthPx * fx - actualW * 0.5f) to (containerHeightPx * fy - actualH * 0.5f))
            }
        }
    }.map { (cx, cy) ->
        cx.coerceIn(safeMarginX, maxLeftPx) to cy.coerceIn(safeMarginY, maxTopPx)
    }

    val areaScale = (containerWidthPx * containerHeightPx).coerceAtLeast(1f)
    val widthNorm = containerWidthPx.coerceAtLeast(1f)
    val heightNorm = containerHeightPx.coerceAtLeast(1f)
    return candidates.minByOrNull { (cx, cy) ->
        val faceNorm = sumOverlap(facePixels, cx, cy) / areaScale
        val siblingNorm = sumOverlap(siblingPixels, cx, cy) / areaScale
        val distNorm = hypot(
            ((cx - baseLeftPx) / widthNorm).toDouble(),
            ((cy - baseTopPx) / heightNorm).toDouble(),
        ).toFloat()
        val facePenalty = (if (faceNorm > 0f) 500f else 0f) + faceNorm * 14_000f
        val siblingPenalty = (if (siblingNorm > 0f) 420f else 0f) + siblingNorm * 10_000f
        facePenalty + siblingPenalty + distNorm * 1.8f
    } ?: (baseLeftPx to baseTopPx)
}

@Composable
fun ClassicSubtitleBar(
    placedBalloons: List<PlacedBalloon>,
    translationMode: TranslationMode,
    style: BalloonStyleSettings,
    onTapEvent: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (placedBalloons.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        placedBalloons.forEach { placed ->
            val isSfx = placed.event.dialogueType == DialogueType.SOUND_DESCRIPTION
            val rawText = placed.event.activeText(translationMode).trim()
            val formattedText = if (isSfx) {
                val lower = rawText.lowercase()
                val core = if (rawText.startsWith("[") && rawText.endsWith("]")) rawText else "[$rawText]"
                if ((lower.contains("music") || lower.contains("strings") || lower.contains("piano") ||
                        lower.contains("song") || lower.contains("hum")) && !core.contains("♪")
                ) {
                    "♪ $core"
                } else {
                    core
                }
            } else {
                val showClassicName = placed.speaker != null && when (style.labelMode) {
                    SpeakerLabelMode.AUTO_FIRST_10 -> placed.speakerLineOrdinal in 1..10
                    SpeakerLabelMode.ALWAYS -> true
                    SpeakerLabelMode.OFF -> false
                }
                val spkPrefix = if (showClassicName) {
                    "${placed.speaker!!.displayName}: "
                } else {
                    ""
                }
                "$spkPrefix$rawText"
            }
            val isUnknown = placed.isUnknownSpeaker || isBubbleSpeakerUnknown(placed.event, placed.speaker)
            val isNonSpeaker = isBubbleNonSpeakerSpecific(placed.event, style) && !isUnknown
            val classicOutlineColor = when {
                style.highContrastMode -> BalloonHighContrastBorder
                isUnknown -> UnknownSpeakerGray.copy(alpha = 0.88f)
                isNonSpeaker -> NonSpeakerBubbleWhite.copy(alpha = 0.72f)
                else -> placed.speaker?.let {
                    SpeakerAccentPalette[it.accentIndex % SpeakerAccentPalette.size].copy(alpha = 0.85f)
                } ?: UnknownSpeakerGray.copy(alpha = 0.88f)
            }
            val classicRadius = if (isSfx) 12.dp else 6.dp
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(classicRadius))
                    .background(CinemaObsidian.copy(alpha = if (isSfx) 0.56f else 0.78f))
                    .drawBehind {
                        val cornerPx = classicRadius.toPx()
                        val strokeW = if (style.highContrastMode) 1.5.dp.toPx() else if (isUnknown) 1.6.dp.toPx() else 1.dp.toPx()
                        val dash = if (isUnknown && !style.highContrastMode) {
                            PathEffect.dashPathEffect(floatArrayOf(1.5.dp.toPx(), strokeW + 4.dp.toPx()), 0f)
                        } else {
                            null
                        }
                        drawRoundRect(
                            color = classicOutlineColor,
                            cornerRadius = CornerRadius(cornerPx, cornerPx),
                            style = Stroke(width = strokeW, cap = StrokeCap.Round, pathEffect = dash),
                        )
                    }
                    .clickable { onTapEvent(placed.event.id) }
                    .padding(horizontal = if (isSfx) 12.dp else 10.dp, vertical = if (isSfx) 3.dp else 5.dp)
                    .testTag("subtitle_${placed.event.id}"),
            ) {
                Text(
                    text = formattedText,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = LexendFamily,
                        fontSize = ((if (isSfx) 13.0f else 14.5f) * style.fontScale).sp,
                        lineHeight = ((if (isSfx) 17.0f else 19.0f) * style.fontScale).sp,
                        letterSpacing = if (isSfx) 0.25.sp else 0.15.sp,
                        fontWeight = if (isSfx) FontWeight.Normal else FontWeight.Medium,
                        fontStyle = if (isSfx) FontStyle.Italic else FontStyle.Normal,
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.72f),
                            offset = Offset(0f, 1.2f),
                            blurRadius = 3.5f,
                        ),
                    ),
                    color = if (isSfx) Color(0xFFE2E8F0).copy(alpha = 0.92f) else Color.White,
                    textAlign = TextAlign.Center,
                    softWrap = true,
                    maxLines = Int.MAX_VALUE,
                )
            }
        }
    }
}

@Composable
fun SpatialSfxNoteView(
    placed: PlacedBalloon,
    translationMode: TranslationMode,
    style: BalloonStyleSettings,
    containerWidthPx: Float,
    containerHeightPx: Float,
    faceExclusionRects: List<NormalizedRect>,
    occupiedBubbleRects: List<Rect>,
    onPixelBoundsResolved: (Rect) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val event = placed.event
    val rawText = event.activeText(translationMode).trim()
    val formattedText = remember(rawText) {
        val lower = rawText.lowercase()
        val core = if (rawText.startsWith("[") && rawText.endsWith("]")) rawText else "[$rawText]"
        if ((lower.contains("music") || lower.contains("strings") || lower.contains("piano") ||
                lower.contains("song") || lower.contains("hum")) && !core.contains("♪")
        ) {
            "♪ $core"
        } else {
            core
        }
    }

    var measuredSize by remember(event.id) { mutableStateOf(IntSize.Zero) }
    val enterAlpha = remember(event.id) { Animatable(if (style.reducedMotion) 1f else 0f) }
    LaunchedEffect(event.id, measuredSize != IntSize.Zero, style.reducedMotion) {
        if (measuredSize == IntSize.Zero && !style.reducedMotion) return@LaunchedEffect
        if (style.reducedMotion) {
            enterAlpha.snapTo(1f)
        } else {
            enterAlpha.animateTo(1f, animationSpec = tween(durationMillis = 120))
        }
    }

    val density = LocalDensity.current.density
    val stageWidthDp = (containerWidthPx / density).coerceAtLeast(180f)
    val maxWidthDp = (stageWidthDp * 0.82f).coerceIn(180f, 360f).dp
    val actualW = if (measuredSize.width > 0) measuredSize.width.toFloat() else (placed.rect.width * containerWidthPx)
    val actualH = if (measuredSize.height > 0) measuredSize.height.toFloat() else (placed.rect.height * containerHeightPx)

    val safeMarginX = 12f * density
    val safeMarginY = 8f * density
    val maxLeftPx = (containerWidthPx - actualW - safeMarginX).coerceAtLeast(safeMarginX)
    val maxTopPx = (containerHeightPx - actualH - safeMarginY).coerceAtLeast(safeMarginY)
    val baseLeftPx = (placed.rect.centerX * containerWidthPx - actualW * 0.5f).coerceIn(safeMarginX, maxLeftPx)
    val baseTopPx = (placed.rect.centerY * containerHeightPx - actualH * 0.5f).coerceIn(safeMarginY, maxTopPx)

    val (sfxLeftPx, sfxTopPx) = if (measuredSize != IntSize.Zero) {
        resolveSfxSafePixelPosition(
            baseLeftPx = baseLeftPx,
            baseTopPx = baseTopPx,
            actualW = actualW,
            actualH = actualH,
            safeMarginX = safeMarginX,
            safeMarginY = safeMarginY,
            maxLeftPx = maxLeftPx,
            maxTopPx = maxTopPx,
            containerWidthPx = containerWidthPx,
            containerHeightPx = containerHeightPx,
            faceExclusionRects = faceExclusionRects,
            occupiedBubbleRects = occupiedBubbleRects,
            density = density,
        )
    } else {
        baseLeftPx to baseTopPx
    }

    LaunchedEffect(event.id, measuredSize, sfxLeftPx, sfxTopPx) {
        if (measuredSize != IntSize.Zero) {
            onPixelBoundsResolved(
                Rect(
                    left = sfxLeftPx,
                    top = sfxTopPx,
                    right = sfxLeftPx + actualW,
                    bottom = sfxTopPx + actualH,
                )
            )
        }
    }

    Box(
        modifier = modifier
            .offset { IntOffset(sfxLeftPx.roundToInt(), sfxTopPx.roundToInt()) }
            .graphicsLayer { alpha = if (measuredSize == IntSize.Zero) 0f else enterAlpha.value }
            .widthIn(max = maxWidthDp)
            .onSizeChanged { measuredSize = it }
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (style.highContrastMode) {
                    BalloonHighContrastSurface.copy(alpha = 0.96f)
                } else {
                    CinemaObsidian.copy(alpha = 0.58f)
                }
            )
            .then(
                if (style.highContrastMode) {
                    Modifier.border(1.5.dp, BalloonHighContrastBorder, RoundedCornerShape(12.dp))
                } else {
                    Modifier.border(0.8.dp, NonSpeakerBubbleWhite.copy(alpha = 0.50f), RoundedCornerShape(12.dp))
                }
            )
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag("subtitle_${event.id}"),
    ) {
        Text(
            text = formattedText,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = LexendFamily,
                fontSize = (13.0f * style.fontScale).sp,
                lineHeight = (17.0f * style.fontScale).sp,
                letterSpacing = 0.25.sp,
                fontWeight = FontWeight.Normal,
                fontStyle = FontStyle.Italic,
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.72f),
                    offset = Offset(0f, 1.2f),
                    blurRadius = 3.5f,
                ),
            ),
            color = if (style.highContrastMode) BalloonHighContrastInk else Color(0xFFE2E8F0).copy(alpha = 0.94f),
            textAlign = TextAlign.Center,
            softWrap = true,
            maxLines = Int.MAX_VALUE,
        )
    }
}

private fun resolveSfxSafePixelPosition(
    baseLeftPx: Float,
    baseTopPx: Float,
    actualW: Float,
    actualH: Float,
    safeMarginX: Float,
    safeMarginY: Float,
    maxLeftPx: Float,
    maxTopPx: Float,
    containerWidthPx: Float,
    containerHeightPx: Float,
    faceExclusionRects: List<NormalizedRect>,
    occupiedBubbleRects: List<Rect>,
    density: Float,
): Pair<Float, Float> {
    val facePixels = faceExclusionRects.map { f ->
        Rect(
            left = f.left * containerWidthPx,
            top = f.top * containerHeightPx,
            right = f.right * containerWidthPx,
            bottom = f.bottom * containerHeightPx,
        )
    }
    val padPx = 8f * density
    val paddedBubbles = occupiedBubbleRects.map { b ->
        Rect(
            left = b.left - padPx,
            top = b.top - padPx,
            right = b.right + padPx,
            bottom = b.bottom + padPx,
        )
    }

    fun sumOverlap(rectList: List<Rect>, l: Float, t: Float): Float {
        val r = l + actualW
        val b = t + actualH
        var total = 0f
        for (item in rectList) {
            val ix = (minOf(r, item.right) - maxOf(l, item.left)).coerceAtLeast(0f)
            val iy = (minOf(b, item.bottom) - maxOf(t, item.top)).coerceAtLeast(0f)
            total += ix * iy
        }
        return total
    }

    if (sumOverlap(facePixels, baseLeftPx, baseTopPx) <= 1f &&
        sumOverlap(paddedBubbles, baseLeftPx, baseTopPx) <= 1f
    ) {
        return baseLeftPx to baseTopPx
    }

    val centerX = (containerWidthPx - actualW) * 0.5f
    val bottomY = maxTopPx
    val midY = (containerHeightPx - actualH) * 0.5f
    val upperY = (safeMarginY + (containerHeightPx - actualH) * 0.16f)
    val lowerY = (maxTopPx - (containerHeightPx - actualH) * 0.16f)

    val candidates = buildList {
        add(baseLeftPx to baseTopPx)
        // Bottom strip
        add(centerX to bottomY)
        add(safeMarginX to bottomY)
        add(maxLeftPx to bottomY)
        add((centerX * 0.5f) to bottomY)
        add((centerX + (maxLeftPx - centerX) * 0.5f) to bottomY)
        // Top strip
        add(centerX to safeMarginY)
        add(safeMarginX to safeMarginY)
        add(maxLeftPx to safeMarginY)
        add(centerX to upperY)
        // Side gutters
        add(safeMarginX to midY)
        add(maxLeftPx to midY)
        add(safeMarginX to upperY)
        add(maxLeftPx to upperY)
        add(safeMarginX to lowerY)
        add(maxLeftPx to lowerY)
        // Above/below/beside each blocking bubble
        for (bubble in paddedBubbles) {
            add((bubble.center.x - actualW * 0.5f) to (bubble.top - actualH - 4f * density))
            add((bubble.center.x - actualW * 0.5f) to (bubble.bottom + 4f * density))
            add((bubble.left - actualW - 4f * density) to bubble.center.y - actualH * 0.5f)
            add((bubble.right + 4f * density) to bubble.center.y - actualH * 0.5f)
        }
        // Coarse interior scan grid
        for (fx in listOf(0.15f, 0.38f, 0.50f, 0.62f, 0.85f)) {
            for (fy in listOf(0.18f, 0.42f, 0.66f, 0.88f)) {
                add((containerWidthPx * fx - actualW * 0.5f) to (containerHeightPx * fy - actualH * 0.5f))
            }
        }
    }.map { (cx, cy) ->
        cx.coerceIn(safeMarginX, maxLeftPx) to cy.coerceIn(safeMarginY, maxTopPx)
    }

    val areaScale = (containerWidthPx * containerHeightPx).coerceAtLeast(1f)
    return candidates.minByOrNull { (cx, cy) ->
        val faceOverlapNorm = sumOverlap(facePixels, cx, cy) / areaScale
        val bubbleOverlapNorm = sumOverlap(paddedBubbles, cx, cy) / areaScale
        val distToBottomNorm = hypot(((cx - centerX) / containerWidthPx).toDouble(), ((cy - bottomY) / containerHeightPx).toDouble()).toFloat()
        val facePenalty = (if (faceOverlapNorm > 0f) 500f else 0f) + faceOverlapNorm * 12_000f
        val bubblePenalty = (if (bubbleOverlapNorm > 0f) 420f else 0f) + bubbleOverlapNorm * 10_000f
        facePenalty + bubblePenalty + distToBottomNorm * 1.4f
    } ?: (baseLeftPx to baseTopPx)
}
