package com.example.domain.layout

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

data class TailPoint2D(
    val x: Float,
    val y: Float,
)

/**
 * Shared pure-Kotlin speech balloon tail geometry math used by both [BalloonLayoutEngine]
 * collision evaluation and [com.example.ui.player.SpeechBalloonView] Compose rendering.
 *
 * Ensures density-consistent short pointer sizing (~12.dp nominal, capped at 18.dp max,
 * shortened proportionally for tight mouth clearance) and continuous 360-degree vertex
 * insertion for jagged shout balloons with zero blind angles.
 */
object TailGeometry {
    const val NOMINAL_TAIL_REACH_DP = 12f
    const val MAX_TAIL_REACH_DP = 18f
    const val MIN_TAIL_REACH_DP = 6f
    const val MOUTH_CLEARANCE_DP = 6f

    private const val TWO_PI = (2.0 * PI).toFloat()

    fun computeTailReachPx(
        distOutsideBorderPx: Float,
        density: Float,
        nominalReachDp: Float = NOMINAL_TAIL_REACH_DP,
        maxReachDp: Float = MAX_TAIL_REACH_DP,
        minReachDp: Float = MIN_TAIL_REACH_DP,
        mouthClearanceDp: Float = MOUTH_CLEARANCE_DP,
    ): Float {
        val safeDensity = density.coerceAtLeast(0.5f)
        return computeTailReachPxWithPxBounds(
            distOutsideBorderPx = distOutsideBorderPx,
            nominalReachPx = nominalReachDp * safeDensity,
            maxReachPx = maxReachDp * safeDensity,
            minReachPx = minReachDp * safeDensity,
            mouthClearancePx = mouthClearanceDp * safeDensity,
        )
    }

    fun computeTailReachPxWithPxBounds(
        distOutsideBorderPx: Float,
        nominalReachPx: Float,
        maxReachPx: Float,
        minReachPx: Float,
        mouthClearancePx: Float,
    ): Float {
        if (distOutsideBorderPx <= 1f) return 0f
        val safeMax = maxReachPx.coerceAtLeast(minReachPx)
        val safeNominal = nominalReachPx.coerceIn(minReachPx, safeMax)
        return when {
            distOutsideBorderPx <= mouthClearancePx ->
                (distOutsideBorderPx * 0.55f).coerceIn(0f, minReachPx)
            distOutsideBorderPx <= mouthClearancePx + safeNominal ->
                (distOutsideBorderPx - mouthClearancePx).coerceIn(minReachPx, safeNominal)
            else -> {
                val excess = (distOutsideBorderPx - (mouthClearancePx + safeNominal)) * 0.20f
                (safeNominal + excess).coerceAtMost(safeMax)
            }
        }
    }

    fun maxNormalizedTailReachForStage(
        ux: Float,
        uy: Float,
        stageWidthDp: Float = 360f,
        stageHeightDp: Float = 202.5f,
        maxReachDp: Float = MAX_TAIL_REACH_DP,
    ): Float {
        val safeW = stageWidthDp.coerceAtLeast(120f)
        val safeH = stageHeightDp.coerceAtLeast(90f)
        val dxNorm = ux * (maxReachDp / safeW)
        val dyNorm = uy * (maxReachDp / safeH)
        return hypot(dxNorm.toDouble(), dyNorm.toDouble()).toFloat().coerceIn(0.01f, 0.12f)
    }

    fun computeNormalizedTailReach(
        ux: Float,
        uy: Float,
        distOutsideNorm: Float,
        stageWidthDp: Float = 360f,
        stageHeightDp: Float = 202.5f,
    ): Float {
        if (distOutsideNorm <= 1e-4f) return 0f
        val safeW = stageWidthDp.coerceAtLeast(120f)
        val safeH = stageHeightDp.coerceAtLeast(90f)
        val dpPerNormX = safeW
        val dpPerNormY = safeH
        val dpPerNormAlongDir = hypot((ux * dpPerNormX).toDouble(), (uy * dpPerNormY).toDouble()).toFloat()
            .coerceAtLeast(1f)
        val distOutsideDp = distOutsideNorm * dpPerNormAlongDir
        val reachDp = computeTailReachPx(
            distOutsideBorderPx = distOutsideDp,
            density = 1f,
        )
        return (reachDp / dpPerNormAlongDir).coerceAtLeast(0f)
    }

    fun normalizeAngleRad(angleRad: Float): Float {
        if (angleRad.isNaN() || angleRad.isInfinite()) return 0f
        var a = angleRad % TWO_PI
        if (a < 0f) a += TWO_PI
        return a
    }

    fun shortestAngularDeltaRad(a: Float, b: Float): Float {
        var d = (a - b) % TWO_PI
        if (d > PI.toFloat()) d -= TWO_PI
        if (d < -PI.toFloat()) d += TWO_PI
        return d
    }

    /**
     * Generates a closed polygon for a high-energy shout balloon with explicit throat/tip
     * insertion sorted monotonically around the perimeter, eliminating blind midpoint angles
     * (such as 5.625 deg between 0 deg and 11.25 deg).
     */
    fun computeShoutBalloonVertices(
        centerX: Float,
        centerY: Float,
        radiusX: Float,
        radiusY: Float,
        spikeAmplitudePx: Float,
        jitterPhase: Float,
        showPointerTail: Boolean,
        aimAngleRad: Float,
        targetX: Float?,
        targetY: Float?,
        nominalReachPx: Float,
        maxReachPx: Float,
        minReachPx: Float,
        mouthClearancePx: Float,
        steps: Int = 32,
    ): List<TailPoint2D> {
        fun sampleBurstPoint(angle: Float, idxHint: Int): TailPoint2D {
            val isSpike = idxHint % 2 == 0
            val phasePulse = 0.72f + 0.28f * sin(jitterPhase + idxHint * 1.9f)
            val radialDelta = if (isSpike) spikeAmplitudePx * phasePulse else -spikeAmplitudePx * 0.38f * phasePulse
            return TailPoint2D(
                x = centerX + (radiusX + radialDelta) * cos(angle),
                y = centerY + (radiusY + radialDelta * 0.85f) * sin(angle),
            )
        }

        if (!showPointerTail || targetX == null || targetY == null || aimAngleRad.isNaN()) {
            return (0 until steps).map { i ->
                val angle = (i.toFloat() / steps.toFloat()) * TWO_PI
                sampleBurstPoint(angle, i)
            }
        }

        val tailAngle = normalizeAngleRad(aimAngleRad)
        val dx = targetX - centerX
        val dy = targetY - centerY
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
        val edgeR = hypot((radiusX * cos(tailAngle)).toDouble(), (radiusY * sin(tailAngle)).toDouble()).toFloat()
        val distOutsideBorder = (dist - edgeR).coerceAtLeast(0f)
        val reachPx = computeTailReachPxWithPxBounds(
            distOutsideBorderPx = distOutsideBorder,
            nominalReachPx = nominalReachPx,
            maxReachPx = maxReachPx,
            minReachPx = minReachPx,
            mouthClearancePx = mouthClearancePx,
        )

        if (reachPx <= 1f) {
            return (0 until steps).map { i ->
                val angle = (i.toFloat() / steps.toFloat()) * TWO_PI
                sampleBurstPoint(angle, i)
            }
        }

        val tip = TailPoint2D(
            x = centerX + cos(tailAngle) * (edgeR + reachPx),
            y = centerY + sin(tailAngle) * (edgeR + reachPx),
        )
        val throatHalfAngleRad = 0.13f
        val throatEndAngle = normalizeAngleRad(tailAngle + throatHalfAngleRad)
        val throatStartAngle = normalizeAngleRad(tailAngle - throatHalfAngleRad)
        val throatEnd = TailPoint2D(
            x = centerX + radiusX * cos(throatEndAngle),
            y = centerY + radiusY * sin(throatEndAngle),
        )
        val throatStart = TailPoint2D(
            x = centerX + radiusX * cos(throatStartAngle),
            y = centerY + radiusY * sin(throatStartAngle),
        )

        val remainingPerimeter = (0 until steps)
            .mapNotNull { i ->
                val angle = (i.toFloat() / steps.toFloat()) * TWO_PI
                if (abs(shortestAngularDeltaRad(angle, tailAngle)) < throatHalfAngleRad + 0.02f) {
                    null
                } else {
                    val forwardOffset = normalizeAngleRad(angle - throatEndAngle)
                    forwardOffset to sampleBurstPoint(angle, i)
                }
            }
            .sortedBy { it.first }
            .map { it.second }

        return buildList(remainingPerimeter.size + 3) {
            add(tip)
            add(throatEnd)
            addAll(remainingPerimeter)
            add(throatStart)
        }
    }
}
