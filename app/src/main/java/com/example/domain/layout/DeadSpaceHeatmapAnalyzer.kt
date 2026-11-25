package com.example.domain.layout

import com.example.data.model.NormalizedPoint
import com.example.data.model.NormalizedRect
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot

enum class ScreenEdge(val label: String) {
    TOP("TOP"),
    BOTTOM("BOTTOM"),
    LEFT("LEFT"),
    RIGHT("RIGHT"),
}

data class EdgeCoordinateBuffer(
    val edge: ScreenEdge,
    val meanDensity: Float,
    val anchorCenter: NormalizedPoint,
)

/**
 * 12x8 normalized screen occupancy heatmap built continuously from ML Kit face detections,
 * lip-reading corridors, upper-body silhouettes, OCR zones, and active speech balloons.
 */
class DeadSpaceHeatmap(
    val cols: Int,
    val rows: Int,
    val grid: FloatArray,
    val edgeBuffers: List<EdgeCoordinateBuffer>,
    val lowestDensityEdge: ScreenEdge,
    val speakerMoved: Boolean,
    val maxSpeakerDelta: Float,
) {
    fun cellAt(col: Int, row: Int): Float {
        val c = col.coerceIn(0, cols - 1)
        val r = row.coerceIn(0, rows - 1)
        return grid[r * cols + c]
    }

    /**
     * Computes mean normalized occupation density covered by [rect] across overlapping grid cells.
     */
    fun sampleRectDensity(rect: NormalizedRect): Float {
        if (cols <= 0 || rows <= 0 || grid.isEmpty()) return 0f
        val cStart = (rect.left * cols).toInt().coerceIn(0, cols - 1)
        val cEnd = (rect.right * cols).toInt().coerceIn(cStart, cols - 1)
        val rStart = (rect.top * rows).toInt().coerceIn(0, rows - 1)
        val rEnd = (rect.bottom * rows).toInt().coerceIn(rStart, rows - 1)

        var sum = 0f
        var count = 0
        for (r in rStart..rEnd) {
            val rowOffset = r * cols
            for (c in cStart..cEnd) {
                sum += grid[rowOffset + c]
                count++
            }
        }
        return if (count > 0) sum / count else 0f
    }

    /**
     * Checks whether [rect] sits predominantly inside the lowest-density screen edge buffer.
     */
    fun distanceToLowestDensityEdge(rect: NormalizedRect): Float = when (lowestDensityEdge) {
        ScreenEdge.TOP -> rect.centerY
        ScreenEdge.BOTTOM -> (1f - rect.centerY)
        ScreenEdge.LEFT -> rect.centerX
        ScreenEdge.RIGHT -> (1f - rect.centerX)
    }.coerceIn(0f, 1f)

    fun isAnchoredOnLowestDensityEdge(rect: NormalizedRect): Boolean = when (lowestDensityEdge) {
        ScreenEdge.TOP -> rect.centerY <= 0.28f || rect.top <= 0.14f
        ScreenEdge.BOTTOM -> rect.centerY >= 0.72f || rect.bottom >= 0.86f
        ScreenEdge.LEFT -> rect.centerX <= 0.30f || rect.left <= 0.14f
        ScreenEdge.RIGHT -> rect.centerX >= 0.70f || rect.right >= 0.86f
    }
}

object DeadSpaceHeatmapAnalyzer {
    const val GRID_COLS = 12
    const val GRID_ROWS = 8
    const val MOVEMENT_THRESHOLD = 0.024f

    fun detectSpeakerMovement(
        currentFaces: List<Pair<String, NormalizedRect>>,
        previousFacesByKey: Map<String, NormalizedRect>,
    ): Pair<Boolean, Float> {
        if (currentFaces.isEmpty() || previousFacesByKey.isEmpty()) return false to 0f
        var maxDelta = 0f
        for ((key, box) in currentFaces) {
            val prev = previousFacesByKey[key] ?: continue
            val centerDelta = hypot(
                (box.centerX - prev.centerX).toDouble(),
                (box.centerY - prev.centerY).toDouble(),
            ).toFloat()
            val scaleDelta = maxOf(
                abs(box.width - prev.width),
                abs(box.height - prev.height),
            )
            maxDelta = maxOf(maxDelta, centerDelta + scaleDelta * 0.5f)
        }
        return (maxDelta >= MOVEMENT_THRESHOLD) to maxDelta
    }

    /**
     * Evaluates a real-time thermal occupancy heatmap from ML Kit detections and active exclusions,
     * ranking the four screen edge buffers so moving-speaker bubbles lock cleanly onto the clearest edge.
     */
    fun computeHeatmap(
        faceZones: List<NormalizedRect>,
        lipZones: List<NormalizedRect> = emptyList(),
        bodyZones: List<NormalizedRect> = emptyList(),
        obstacleZones: List<NormalizedRect> = emptyList(),
        occupiedBubbles: List<NormalizedRect> = emptyList(),
        speakerMoved: Boolean = false,
        maxSpeakerDelta: Float = 0f,
    ): DeadSpaceHeatmap {
        val grid = FloatArray(GRID_COLS * GRID_ROWS)

        fun splatZone(rect: NormalizedRect, peakWeight: Float, haloMargin: Float) {
            val expRect = rect.expand(haloMargin, haloMargin)
            val rx = (expRect.width * 0.5f).coerceAtLeast(0.06f)
            val ry = (expRect.height * 0.5f).coerceAtLeast(0.06f)
            val rcx = expRect.centerX
            val rcy = expRect.centerY
            for (r in 0 until GRID_ROWS) {
                val cellCy = (r + 0.5f) / GRID_ROWS.toFloat()
                val dy = abs(cellCy - rcy) / ry
                if (dy > 1.75f) continue
                for (c in 0 until GRID_COLS) {
                    val cellCx = (c + 0.5f) / GRID_COLS.toFloat()
                    val dx = abs(cellCx - rcx) / rx
                    if (dx > 1.75f) continue
                    val normDistSq = dx * dx + dy * dy
                    val falloff = if (cellCx in rect.left..rect.right && cellCy in rect.top..rect.bottom) {
                        1.0f
                    } else {
                        exp((-normDistSq * 0.85f).toDouble()).toFloat()
                    }
                    grid[r * GRID_COLS + c] += peakWeight * falloff
                }
            }
        }

        faceZones.forEach { splatZone(it, peakWeight = 1.0f, haloMargin = 0.045f) }
        lipZones.forEach { splatZone(it, peakWeight = 0.85f, haloMargin = 0.03f) }
        obstacleZones.forEach { splatZone(it, peakWeight = 0.72f, haloMargin = 0.025f) }
        bodyZones.forEach { splatZone(it, peakWeight = 0.42f, haloMargin = 0.02f) }
        occupiedBubbles.forEach { splatZone(it, peakWeight = 0.65f, haloMargin = 0.02f) }

        // Evaluate perimeter edge density bands (priority coordinate buffers)
        val topBand = bandAverage(grid, cRange = 1 until (GRID_COLS - 1), rRange = 0..1)
        val bottomBand = bandAverage(grid, cRange = 1 until (GRID_COLS - 1), rRange = (GRID_ROWS - 2) until GRID_ROWS)
        val leftBand = bandAverage(grid, cRange = 0..2, rRange = 0 until (GRID_ROWS - 1))
        val rightBand = bandAverage(grid, cRange = (GRID_COLS - 3) until GRID_COLS, rRange = 0 until (GRID_ROWS - 1))

        val buffers = listOf(
            EdgeCoordinateBuffer(ScreenEdge.TOP, topBand, NormalizedPoint(0.50f, 0.11f)),
            EdgeCoordinateBuffer(ScreenEdge.LEFT, leftBand, NormalizedPoint(0.18f, 0.24f)),
            EdgeCoordinateBuffer(ScreenEdge.RIGHT, rightBand, NormalizedPoint(0.82f, 0.24f)),
            EdgeCoordinateBuffer(ScreenEdge.BOTTOM, bottomBand, NormalizedPoint(0.50f, 0.85f)),
        )
        // Lowest density edge wins; tie-breaker prefers TOP -> LEFT/RIGHT -> BOTTOM for clean cinema framing
        val lowestEdge = buffers.minByOrNull { it.meanDensity }?.edge ?: ScreenEdge.TOP

        return DeadSpaceHeatmap(
            cols = GRID_COLS,
            rows = GRID_ROWS,
            grid = grid,
            edgeBuffers = buffers,
            lowestDensityEdge = lowestEdge,
            speakerMoved = speakerMoved,
            maxSpeakerDelta = maxSpeakerDelta,
        )
    }

    /**
     * Generates high-priority edge anchor candidates ordered with the lowest-density screen edge first
     * so bubbles smoothly vacate moving speakers and lock to clear edge pockets.
     */
    fun buildPriorityEdgeCandidates(
        heatmap: DeadSpaceHeatmap,
        halfW: Float,
        halfH: Float,
    ): List<NormalizedPoint> {
        val leftX = (0.05f + halfW).coerceIn(0.08f, 0.48f)
        val rightX = (0.95f - halfW).coerceIn(0.52f, 0.92f)
        val topY = (0.045f + halfH).coerceIn(0.07f, 0.35f)
        val bottomY = (0.90f - halfH).coerceIn(0.65f, 0.90f)

        val byEdge = mapOf(
            ScreenEdge.TOP to listOf(
                NormalizedPoint(0.50f, topY),
                NormalizedPoint(leftX, topY),
                NormalizedPoint(rightX, topY),
            ),
            ScreenEdge.LEFT to listOf(
                NormalizedPoint(leftX, topY),
                NormalizedPoint(leftX, 0.42f),
                NormalizedPoint(leftX, bottomY),
            ),
            ScreenEdge.RIGHT to listOf(
                NormalizedPoint(rightX, topY),
                NormalizedPoint(rightX, 0.42f),
                NormalizedPoint(rightX, bottomY),
            ),
            ScreenEdge.BOTTOM to listOf(
                NormalizedPoint(0.50f, bottomY),
                NormalizedPoint(leftX, bottomY),
                NormalizedPoint(rightX, bottomY),
            ),
        )

        return buildList {
            heatmap.edgeBuffers
                .sortedBy { it.meanDensity }
                .forEach { buf ->
                    addAll(byEdge[buf.edge].orEmpty())
                }
        }
    }

    private fun bandAverage(grid: FloatArray, cRange: IntRange, rRange: IntRange): Float {
        var sum = 0f
        var count = 0
        for (r in rRange) {
            val rowOff = r * GRID_COLS
            for (c in cRange) {
                sum += grid[rowOff + c]
                count++
            }
        }
        return if (count > 0) sum / count else 0f
    }
}
