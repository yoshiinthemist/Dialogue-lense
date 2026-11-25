package com.example.domain.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.annotation.OptIn
import androidx.core.graphics.withSave
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import android.graphics.DashPathEffect
import com.example.data.model.BubbleVisualStyle
import com.example.data.model.DialogueLensProject
import com.example.data.model.DialogueType
import com.example.data.model.SpeakerColorOrder
import com.example.data.model.isBubbleNonSpeakerSpecific
import com.example.data.model.isBubbleSpeakerUnknown
import com.example.domain.layout.BalloonLayoutEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.hypot

private val SPEAKER_HEX_COLORS: IntArray =
    SpeakerColorOrder.PALETTE_HEX_COLORS.map { it.toInt() }.toIntArray()
private const val UNKNOWN_SPEAKER_GRAY_ARGB: Int = SpeakerColorOrder.UNKNOWN_SPEAKER_GRAY_HEX.toInt()

/**
 * Burns DialogueLens spatial speech balloons, pointed tails, character outline hues,
 * shout accents, and bottom SFX captions directly onto local MP4 exports via Media3 Transformer.
 */
@OptIn(UnstableApi::class)
class VideoExporter(private val context: Context) {

    suspend fun exportBurnedMp4(
        project: DialogueLensProject,
        destinationUri: Uri,
        onProgress: (Float) -> Unit,
    ): Result<Unit> = runCatching {
        val srcUri = Uri.parse(project.videoSource.uriString)
        val tempOut = File(context.cacheDir, "dlens_export_${System.currentTimeMillis()}.mp4")
        if (tempOut.exists()) tempOut.delete()

        val renderWidth = project.videoSource.width.coerceIn(640, 1280)
        val renderHeight = project.videoSource.height.coerceIn(360, 720)
        val balloonOverlay = DialogueBalloonBitmapOverlay(project, renderWidth, renderHeight)

        try {
            val effects = Effects(
                /* audioProcessors = */ emptyList(),
                /* videoEffects = */ listOf(OverlayEffect(listOf(balloonOverlay))),
            )
            val editedItem = EditedMediaItem.Builder(MediaItem.fromUri(srcUri))
                .setEffects(effects)
                .build()

            runTransformerOnMainThread(editedItem, tempOut.absolutePath, onProgress)

            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(destinationUri)?.use { outStream ->
                    FileInputStream(tempOut).use { inStream ->
                        inStream.copyTo(outStream)
                    }
                } ?: error("Could not open destination output stream for MP4 export.")
            }
        } finally {
            runCatching { balloonOverlay.releaseBitmap() }
            runCatching { tempOut.delete() }
        }
    }

    private suspend fun runTransformerOnMainThread(
        editedMediaItem: EditedMediaItem,
        outputPath: String,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Unit> { cont ->
            var finished = false
            val transformer = Transformer.Builder(context)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        finished = true
                        onProgress(1f)
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        finished = true
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()

            transformer.start(editedMediaItem, outputPath)
            cont.invokeOnCancellation {
                runCatching { transformer.cancel() }
            }

            // Poll export progress cleanly until Transformer invokes onCompleted/onError
            val holder = ProgressHolder()
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val pollRunnable = object : Runnable {
                override fun run() {
                    if (finished || !cont.isActive) return
                    val state = transformer.getProgress(holder)
                    if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress((holder.progress / 100f).coerceIn(0f, 0.98f))
                    }
                    handler.postDelayed(this, 180L)
                }
            }
            handler.postDelayed(pollRunnable, 120L)
        }
    }

    private class DialogueBalloonBitmapOverlay(
        private val project: DialogueLensProject,
        private val width: Int,
        private val height: Int,
    ) : BitmapOverlay() {

        private val layoutEngine = BalloonLayoutEngine()
        private val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        private val canvas = Canvas(bitmap)
        private var lastRenderedBucketMs = -1L

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        private val sfxPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E2E8F0")
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC)
            textAlign = Paint.Align.CENTER
            textSize = (height * 0.036f).coerceIn(18f, 38f)
            setShadowLayer(4f, 0f, 1.5f, Color.BLACK)
        }

        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            val timeMs = (presentationTimeUs / 1000L).coerceAtLeast(0L)
            val bucketMs = (timeMs / 100L) * 100L
            if (bucketMs == lastRenderedBucketMs) return bitmap
            lastRenderedBucketMs = bucketMs

            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val placedList = layoutEngine.computeLayout(project, timeMs, project.styleSettings)
            if (placedList.isEmpty()) return bitmap

            val isManga = project.styleSettings.visualStyle == BubbleVisualStyle.MANGA_INK &&
                !project.styleSettings.highContrastMode
            val isCinema = project.styleSettings.visualStyle == BubbleVisualStyle.CINEMA_SUBTLE &&
                !project.styleSettings.highContrastMode

            val bottomSfx = placedList.filter { it.event.dialogueType == DialogueType.SOUND_DESCRIPTION }
            val spatial = placedList.filter { it.event.dialogueType != DialogueType.SOUND_DESCRIPTION }
            val drawnBubbleRects = mutableListOf<RectF>()

            val activeShotId = project.shots.firstOrNull { timeMs in it.startMs..it.endMs }?.id
            val faceObstacleRects = project.faceTracks
                .filter { activeShotId == null || it.shotId == activeShotId }
                .mapNotNull { it.sampleAt(timeMs)?.boundingBox }
                .map { box ->
                    val bottomNorm = if (project.styleSettings.lipReadingSafeZone) {
                        (box.bottom + box.height * 0.45f).coerceAtMost(1f)
                    } else {
                        (box.bottom + 0.036f).coerceAtMost(1f)
                    }
                    RectF(
                        (box.left - 0.04f).coerceAtLeast(0f) * width,
                        (box.top - 0.036f).coerceAtLeast(0f) * height,
                        (box.right + 0.04f).coerceAtMost(1f) * width,
                        bottomNorm * height,
                    )
                }

            for (placed in spatial) {
                val text = placed.event.activeText(project.translationMode).trim()
                if (text.isEmpty()) continue

                val isUnknownSpeaker = placed.isUnknownSpeaker || isBubbleSpeakerUnknown(placed.event, placed.speaker)
                val isNonSpeakerBubble = isBubbleNonSpeakerSpecific(placed.event, project.styleSettings) && !isUnknownSpeaker
                val accentColor = when {
                    isUnknownSpeaker -> UNKNOWN_SPEAKER_GRAY_ARGB
                    isNonSpeakerBubble -> Color.WHITE
                    else -> placed.speaker?.let {
                        SPEAKER_HEX_COLORS[it.accentIndex % SPEAKER_HEX_COLORS.size]
                    } ?: UNKNOWN_SPEAKER_GRAY_ARGB
                }

                val maxTextWidth = (width * 0.48f).toInt().coerceIn(220, 560)
                val fontPx = (height * 0.036f * project.styleSettings.fontScale).coerceIn(16f, 44f)
                textPaint.textSize = fontPx
                textPaint.color = when {
                    project.styleSettings.highContrastMode -> Color.WHITE
                    isManga -> Color.parseColor("#0B0F17")
                    isCinema -> Color.parseColor("#F8FAFC")
                    else -> Color.parseColor("#0F172A")
                }

                val staticLayout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, maxTextWidth)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .build()

                var actualTextW = 0f
                for (line in 0 until staticLayout.lineCount) {
                    actualTextW = maxOf(actualTextW, staticLayout.getLineWidth(line))
                }
                val padH = 20f
                val padV = 12f
                val boxW = (actualTextW + padH * 2f).coerceAtLeast(64f)
                val boxH = (staticLayout.height + padV * 2f).coerceAtLeast(36f)

                val cx = (placed.rect.centerX * width).coerceIn(boxW * 0.5f + 12f, width - boxW * 0.5f - 12f)
                val cy = (placed.rect.centerY * height).coerceIn(boxH * 0.5f + 12f, height - boxH * 0.5f - 12f)
                val boxRect = RectF(cx - boxW * 0.5f, cy - boxH * 0.5f, cx + boxW * 0.5f, cy + boxH * 0.5f)
                drawnBubbleRects.add(
                    RectF(boxRect.left - 12f, boxRect.top - 12f, boxRect.right + 12f, boxRect.bottom + 12f)
                )

                val alphaInt = (project.styleSettings.balloonOpacity * 255f).toInt().coerceIn(155, 250)
                fillPaint.color = when {
                    project.styleSettings.highContrastMode -> Color.argb(248, 8, 8, 10)
                    isManga -> Color.argb(alphaInt.coerceAtLeast(220), 251, 248, 241)
                    isCinema -> Color.argb(alphaInt, 10, 15, 26)
                    else -> Color.argb(alphaInt, 248, 250, 252)
                }

                val contourPath = Path().apply {
                    addRoundRect(boxRect, 22f, 22f, Path.Direction.CW)
                }

                // Append pointed mouth tail when target exists & showTail is enabled
                if (project.styleSettings.showTail && placed.tailTarget != null && !placed.isAmbiguousFallback) {
                    val tx = placed.tailTarget.x * width
                    val ty = placed.tailTarget.y * height
                    val dx = tx - cx
                    val dy = ty - cy
                    val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
                    val reach = (dist - 16f).coerceIn(12f, 44f)
                    val tipX = cx + (dx / dist) * (reach + boxH * 0.45f)
                    val tipY = cy + (dy / dist) * (reach + boxH * 0.45f)
                    val tailBaseY = if (dy >= 0f) boxRect.bottom - 2f else boxRect.top + 2f
                    val baseX = cx.coerceIn(boxRect.left + 20f, boxRect.right - 20f)
                    val tailPath = Path().apply {
                        moveTo(baseX - 9f, tailBaseY)
                        lineTo(tipX, tipY)
                        lineTo(baseX + 9f, tailBaseY)
                        close()
                    }
                    contourPath.op(tailPath, Path.Op.UNION)
                }

                canvas.drawPath(contourPath, fillPaint)

                val baseStroke = if (isUnknownSpeaker) 3.4f else if (isManga) 3.2f else 2.2f
                strokePaint.strokeWidth = if (placed.isShouted) baseStroke * 1.9f else baseStroke
                strokePaint.pathEffect = if (isUnknownSpeaker) {
                    DashPathEffect(floatArrayOf(3f, strokePaint.strokeWidth + 9f), 0f)
                } else {
                    null
                }
                strokePaint.color = when {
                    isUnknownSpeaker -> UNKNOWN_SPEAKER_GRAY_ARGB
                    project.styleSettings.highContrastMode -> Color.YELLOW
                    isNonSpeakerBubble -> Color.WHITE
                    else -> accentColor
                }
                canvas.drawPath(contourPath, strokePaint)

                canvas.withSave {
                    translate(boxRect.centerX() - staticLayout.width * 0.5f, boxRect.top + padV)
                    staticLayout.draw(this)
                }
            }

            if (bottomSfx.isNotEmpty()) {
                val sfxLine = bottomSfx.joinToString(" · ") { it.event.activeText(project.translationMode) }
                val sfxW = sfxPaint.measureText(sfxLine).coerceAtMost(width * 0.86f)
                val fm = sfxPaint.fontMetrics
                val sfxH = (fm.descent - fm.ascent).coerceAtLeast(20f)
                val marginX = 24f
                val marginY = 24f
                val defaultCx = width * 0.5f
                val defaultCy = height - 28f - sfxH * 0.5f
                val layoutHint = bottomSfx.first().rect

                val sfxCandidates = listOf(
                    defaultCx to defaultCy,
                    (layoutHint.centerX * width) to (layoutHint.centerY * height),
                    (sfxW * 0.5f + marginX) to defaultCy,
                    (width - sfxW * 0.5f - marginX) to defaultCy,
                    defaultCx to (sfxH * 0.5f + marginY),
                    (sfxW * 0.5f + marginX) to (sfxH * 0.5f + marginY),
                    (width - sfxW * 0.5f - marginX) to (sfxH * 0.5f + marginY),
                    (sfxW * 0.5f + marginX) to (height * 0.5f),
                    (width - sfxW * 0.5f - marginX) to (height * 0.5f),
                ).map { (cx, cy) ->
                    cx.coerceIn(sfxW * 0.5f + marginX, (width - sfxW * 0.5f - marginX).coerceAtLeast(sfxW * 0.5f + marginX)) to
                        cy.coerceIn(sfxH * 0.5f + marginY, (height - sfxH * 0.5f - marginY).coerceAtLeast(sfxH * 0.5f + marginY))
                }

                fun rectOverlap(a: RectF, b: RectF): Float {
                    val ix = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f)
                    val iy = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0f)
                    return ix * iy
                }

                val bestSfxCenter = sfxCandidates.minByOrNull { (cx, cy) ->
                    val sfxBox = RectF(cx - sfxW * 0.5f, cy - sfxH * 0.5f, cx + sfxW * 0.5f, cy + sfxH * 0.5f)
                    val bubbleHit = drawnBubbleRects.sumOf { rectOverlap(sfxBox, it).toDouble() }.toFloat()
                    val faceHit = faceObstacleRects.sumOf { rectOverlap(sfxBox, it).toDouble() }.toFloat()
                    val dist = hypot((cx - defaultCx).toDouble(), (cy - defaultCy).toDouble()).toFloat()
                    faceHit * 12f + bubbleHit * 10f + dist * 0.05f
                } ?: (defaultCx to defaultCy)

                val baselineY = bestSfxCenter.second - (fm.ascent + fm.descent) * 0.5f
                canvas.drawText(sfxLine, bestSfxCenter.first, baselineY, sfxPaint)
            }

            return bitmap
        }

        fun releaseBitmap() {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }
}
