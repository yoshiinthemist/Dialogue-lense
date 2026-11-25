package com.example.domain.layout

import com.example.data.model.DialogueType
import com.example.data.model.NormalizedPoint
import com.example.data.model.NormalizedRect
import com.example.data.model.PlacementMode
import kotlin.math.abs

/** Final positions in stage coordinates. Trackers provide proposals; only this pass places measured bodies. */
internal class MeasuredBalloonLayout {
    private data class Commitment(
        val scene: String,
        val speakerId: String?,
        val mode: PlacementMode,
        val rect: NormalizedRect,
    )
    private val positions = mutableMapOf<String, Commitment>()
    var relocations = 0
        private set

    fun place(
        proposals: List<PlacedBalloon>,
        sizes: Map<String, NormalizedPoint>,
        obstacles: List<NormalizedRect>,
        marginX: Float,
        marginY: Float,
    ): Map<String, NormalizedRect> {
        val active = proposals.mapTo(mutableSetOf()) { it.event.id }
        positions.keys.retainAll(active)
        val result = linkedMapOf<String, NormalizedRect>()
        val occupied = obstacles.toMutableList()
        val ordered = proposals.sortedWith(
            compareBy<PlacedBalloon> { it.event.placementMode != PlacementMode.MANUAL_OVERRIDE }
                .thenBy { it.event.dialogueType == DialogueType.SOUND_DESCRIPTION }
                .thenBy { it.event.startTimeMs }
                .thenBy { it.event.id }
        )
        for (balloon in ordered) {
            val id = balloon.event.id
            val size = sizes[id] ?: continue
            val w = size.x.coerceIn(0f, 1f)
            val h = size.y.coerceIn(0f, 1f)
            val insetX = minOf(marginX, (1f - w) / 2f)
            val insetY = minOf(marginY, (1f - h) / 2f)
            fun body(cx: Float, cy: Float): NormalizedRect {
                val left = (cx - w / 2).coerceIn(insetX, maxOf(insetX, 1f - insetX - w))
                val top = (cy - h / 2).coerceIn(insetY, maxOf(insetY, 1f - insetY - h))
                return NormalizedRect(left, top, left + w, top + h)
            }
            val desired = body(balloon.rect.centerX, balloon.rect.centerY)
            val previous = positions[id]?.takeIf {
                it.scene == balloon.layoutSceneKey && it.speakerId == balloon.event.speakerId &&
                    it.mode == balloon.event.placementMode
            }?.rect?.let {
                if (abs(it.width - w) < 0.000001f && abs(it.height - h) < 0.000001f) it
                else body(it.centerX, it.centerY)
            }
            fun overlap(rect: NormalizedRect): Float = occupied.sumOf { rect.intersectionArea(it).toDouble() }.toFloat() /
                (w * h).coerceAtLeast(0.00001f)

            val chosen = when {
                balloon.event.placementMode == PlacementMode.MANUAL_OVERRIDE -> desired
                previous != null && overlap(previous) <= 0.012f -> previous
                overlap(desired) <= 0.001f -> desired
                else -> {
                    val candidates = buildList {
                        add(desired)
                        previous?.let { add(it) }
                        for (zone in occupied) {
                            add(body(zone.left - insetX - w / 2, desired.centerY))
                            add(body(zone.right + insetX + w / 2, desired.centerY))
                            add(body(desired.centerX, zone.top - insetY - h / 2))
                            add(body(desired.centerX, zone.bottom + insetY + h / 2))
                        }
                        for (y in 0..4) for (x in 0..5) add(body(x / 5f, y / 4f))
                    }
                    val best = candidates.minBy { rect ->
                        30f * overlap(rect) + abs(rect.centerX - desired.centerX) + abs(rect.centerY - desired.centerY)
                    }
                    // If every location is obstructed, avoid trading nearly equal overlaps on every observation.
                    if (previous != null && overlap(previous) - overlap(best) < 0.012f) previous else best
                }
            }
            if (previous != null && (abs(chosen.centerX - previous.centerX) > 0.01f ||
                        abs(chosen.centerY - previous.centerY) > 0.01f)) relocations++
            positions[id] = Commitment(balloon.layoutSceneKey, balloon.event.speakerId, balloon.event.placementMode, chosen)
            result[id] = chosen
            occupied.add(chosen.expand(insetX * 0.6f, insetY * 0.6f))
        }
        return result
    }
}
