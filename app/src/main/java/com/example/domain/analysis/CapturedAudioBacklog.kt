package com.example.domain.analysis

internal class CapturedAudioClip(
    val startMs: Long,
    val endMs: Long,
    val audio: ByteArray,
    val mimeType: String,
)

/** Owns pending bytes only. The VM's shared Scout reservations own running transcriptions. */
internal class CapturedAudioBacklog(
    private val maxClips: Int = 4,
    private val maxBytes: Int = 4 * 1024 * 1024,
) {
    private val pending = mutableListOf<CapturedAudioClip>()

    fun offer(clip: CapturedAudioClip, firstGap: Long, active: Collection<TimeWindowRange>) {
        if (clip.endMs <= clip.startMs || clip.audio.isEmpty() || clip.audio.size > maxBytes) return
        if (active.any { it.startMs <= clip.startMs && it.endMs >= clip.endMs } ||
            pending.any { it.startMs <= clip.startMs && it.endMs >= clip.endMs }) return
        pending.removeAll { it.startMs >= clip.startMs && it.endMs <= clip.endMs }
        pending.add(clip)
        val order = orderFrom(firstGap)
        while (pending.size > maxClips || pending.sumOf { it.audio.size } > maxBytes) {
            pending.remove(pending.maxWith(order))
        }
    }

    fun take(firstGap: Long, active: Collection<TimeWindowRange>, isVerified: (CapturedAudioClip) -> Boolean): CapturedAudioClip? {
        pending.removeAll(isVerified)
        val next = pending.asSequence()
            .filter { LookaheadWindowPlanner.canAnalyzeRecording(it.startMs, it.endMs, active) }
            .minWithOrNull(orderFrom(firstGap)) ?: return null
        pending.remove(next)
        return next
    }

    fun hasWaitingGap(gap: Long, active: Collection<TimeWindowRange>): Boolean = pending.any { clip ->
        gap in clip.startMs until clip.endMs && active.none { it.startMs < clip.endMs && it.endMs > clip.startMs }
    }

    fun clear() = pending.clear()

    private fun orderFrom(gap: Long): Comparator<CapturedAudioClip> =
        compareBy<CapturedAudioClip> {
            when {
                gap in it.startMs until it.endMs -> 0
                it.startMs >= gap -> 1
                else -> 2
            }
        }.thenBy { if (it.endMs <= gap) gap - it.endMs else (it.startMs - gap).coerceAtLeast(0L) }
            .thenByDescending { it.endMs }
}
