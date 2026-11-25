package com.example.domain.analysis

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.Window
import androidx.compose.ui.geometry.Rect as ComposeRect
import com.example.data.model.NormalizedPoint
import com.example.data.model.NormalizedRect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * High-frequency (~6fps) on-device ML Kit face + mouth landmark tracker with lightweight 8x6
 * luma-grid visual cut detection. Captures rendered VideoPlayerStage frames via Android PixelCopy
 * (zero extra permissions; works across embedded YouTube WebView, Web Stream, and ExoPlayer MP4)
 * so balloon tails track moving mouths and immediately invalidate stale coordinates across real
 * visual camera cuts, reaction shots, and B-roll transitions.
 */
class LiveStageFaceTracker {

    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
        .enableTracking()
        .setMinFaceSize(0.032f)
        .build()

    private val detector = lazy { FaceDetection.getClient(options) }
    private var closed = false

    fun close() {
        closed = true
        if (!captureBusy.get() && detector.isInitialized()) detector.value.close()
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val captureBusy = AtomicBoolean(false)

    @Volatile
    private var previousLumaGrid: FloatArray? = null

    @Volatile
    private var previousFaceSignature: List<Pair<Float, Float>> = emptyList()

    @Volatile
    private var previousVideoTimeMs: Long = -1L

    private var consecutiveEmpty = 0
    private var consecutiveSoftCuts = 0

    fun resetSceneHistory() {
        consecutiveSoftCuts = 0
        consecutiveEmpty = 0
        previousLumaGrid = null
        previousFaceSignature = emptyList()
        previousVideoTimeMs = -1L
    }

    suspend fun captureAndDetect(
        hostView: View,
        stageBoundsInWindow: ComposeRect,
        videoTimeMs: Long,
    ): List<LiveFaceObservation>? =
        captureFrameSnapshot(hostView, stageBoundsInWindow, videoTimeMs)?.faces

    suspend fun captureFrameSnapshot(
        hostView: View,
        stageBoundsInWindow: ComposeRect,
        videoTimeMs: Long,
        videoSurface: SurfaceView? = null,
        overlayExclusionRects: List<NormalizedRect> = emptyList(),
        projectId: String = "",
        seekGeneration: Long = 0L,
    ): LiveStageFrameSnapshot? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        if (closed || !hostView.isAttachedToWindow) return null
        if (stageBoundsInWindow.width < 48f || stageBoundsInWindow.height < 48f) return null
        if (!captureBusy.compareAndSet(false, true)) return null

        var capturedBitmap: Bitmap? = null
        return try {
            val window = findWindow(hostView.context) ?: return null
            val srcRect = Rect(
                stageBoundsInWindow.left.roundToInt(),
                stageBoundsInWindow.top.roundToInt(),
                stageBoundsInWindow.right.roundToInt(),
                stageBoundsInWindow.bottom.roundToInt(),
            )
            if (srcRect.width() < 32 || srcRect.height() < 32) return null

            val surfaceBounds = if (videoSurface != null) {
                if (videoSurface.width <= 0 || videoSurface.height <= 0) return null
                val location = IntArray(2)
                videoSurface.getLocationInWindow(location)
                val left = (location[0] - srcRect.left).toFloat() / srcRect.width()
                val top = (location[1] - srcRect.top).toFloat() / srcRect.height()
                NormalizedRect(left, top, left + videoSurface.width.toFloat() / srcRect.width(),
                    top + videoSurface.height.toFloat() / srcRect.height())
            } else NormalizedRect(0f, 0f, 1f, 1f)
            val targetWidth = 640
            val aspect = if (videoSurface == null) srcRect.height().toFloat() / srcRect.width()
                else videoSurface.height.toFloat() / videoSurface.width
            val targetHeight = (targetWidth * aspect).roundToInt().coerceIn(180, 540)
            val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            capturedBitmap = bitmap

            // The producer can still be writing into the destination when a composable leaves.
            // Keep the bitmap alive through the callback, even if playback was paused meanwhile.
            val copySuccess = withContext(NonCancellable) {
                requestPixelCopy(window, srcRect, videoSurface, bitmap)
            }
            if (!copySuccess) return null

            val masks = if (videoSurface != null) emptyList() else overlayExclusionRects.map { it.expand(.018f, .018f) }
            val lumaGrid = computeLumaGrid(bitmap, masks)
            val lumaAverage = lumaGrid.filter { it.isFinite() }.average().toFloat()
            if (!lumaAverage.isFinite()) return null

            val faces = withContext(NonCancellable) { detectFacesOnBitmap(bitmap) } ?: return null
            val w = bitmap.width.toFloat().coerceAtLeast(1f)
            val h = bitmap.height.toFloat().coerceAtLeast(1f)
            val nowMs = System.currentTimeMillis()
            val observations = faces.mapNotNull { face ->
                val normBox = NormalizedRect(
                    left = (face.boundingBox.left / w).coerceIn(0f, 1f),
                    top = (face.boundingBox.top / h).coerceIn(0f, 1f),
                    right = (face.boundingBox.right / w).coerceIn(0f, 1f),
                    bottom = (face.boundingBox.bottom / h).coerceIn(0f, 1f),
                )
                if (masks.any { normBox.intersectionArea(it) > 0.5f * normBox.width * normBox.height }) {
                    return@mapNotNull null
                }
                // A fitted SurfaceView may occupy only part of a portrait or ultrawide stage.
                fun stageX(x: Float) = (surfaceBounds.left + x * surfaceBounds.width).coerceIn(0f, 1f)
                fun stageY(y: Float) = (surfaceBounds.top + y * surfaceBounds.height).coerceIn(0f, 1f)
                val mouth = extractLiveMouthPoint(face, w, h, normBox)
                LiveFaceObservation(
                    trackingId = face.trackingId,
                    boundingBox = NormalizedRect(stageX(normBox.left), stageY(normBox.top), stageX(normBox.right), stageY(normBox.bottom)),
                    mouthPoint = NormalizedPoint(stageX(mouth.x), stageY(mouth.y)),
                    headYawDeg = face.headEulerAngleY,
                    capturedAtMs = nowMs.coerceAtLeast(videoTimeMs),
                )
            }

            val currentFaceSig = observations
                .sortedBy { it.boundingBox.centerX }
                .map { it.boundingBox.centerX to it.boundingBox.centerY }

            val prevGrid = previousLumaGrid
            val (lumaDiff, cells) = if (prevGrid != null) computeMaskedGridDistance(prevGrid, lumaGrid) else 0f to lumaGrid.count { it.isFinite() }
            val timeDiscontinuity = previousVideoTimeMs >= 0L &&
                kotlin.math.abs(videoTimeMs - previousVideoTimeMs) > 2_500L
            val faceLayoutJump = detectFaceLayoutCut(previousFaceSignature, currentFaceSig)

            val reliable = cells >= MIN_UNMASKED_CELLS
            val soft = reliable && lumaDiff >= SOFT_CUT_LUMA_THRESHOLD && faceLayoutJump
            consecutiveSoftCuts = if (soft) consecutiveSoftCuts + 1 else 0
            val cutDetected = timeDiscontinuity || (reliable && lumaDiff >= VISUAL_CUT_LUMA_THRESHOLD) ||
                consecutiveSoftCuts >= 2
            if (cutDetected) consecutiveSoftCuts = 0
            consecutiveEmpty = if (observations.isEmpty()) consecutiveEmpty + 1 else 0
            previousLumaGrid = if (cutDetected) lumaGrid else mergeGridPreservingMasked(prevGrid, lumaGrid)
            previousFaceSignature = currentFaceSig
            previousVideoTimeMs = videoTimeMs

            LiveStageFrameSnapshot(
                faces = observations,
                projectId = projectId,
                playbackGeneration = seekGeneration.toInt(),
                seekGeneration = seekGeneration,
                emptyFrameConsecutiveCount = consecutiveEmpty,
                unmaskedSampleCount = cells,
                visualCutDetected = cutDetected,
                cutDistance = lumaDiff,
                frameLumaAverage = lumaAverage,
                capturedAtMs = nowMs.coerceAtLeast(videoTimeMs),
                // Occluded faces and detector failures are not evidence of an empty scene.
                hasLiveVisionSignal = observations.isNotEmpty() || faces.isEmpty() && masks.none {
                    it.width * it.height > 0.20f
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } finally {
            runCatching { capturedBitmap?.recycle() }
            captureBusy.set(false)
            if (closed && detector.isInitialized()) detector.value.close()
        }
    }

    private fun computeLumaGrid(bitmap: Bitmap, masks: List<NormalizedRect>): FloatArray {
        val cols = 8
        val rows = 6
        val grid = FloatArray(cols * rows)
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return grid
        // Sample inner 84% of frame to ignore top/bottom player chrome & letterbox bars
        val startX = (w * 0.08f).toInt().coerceIn(0, w - 1)
        val endX = (w * 0.92f).toInt().coerceIn(startX + 1, w)
        val startY = (h * 0.08f).toInt().coerceIn(0, h - 1)
        val endY = (h * 0.92f).toInt().coerceIn(startY + 1, h)
        val cellW = ((endX - startX) / cols).coerceAtLeast(1)
        val cellH = ((endY - startY) / rows).coerceAtLeast(1)

        var idx = 0
        for (r in 0 until rows) {
            val y = (startY + r * cellH + cellH / 2).coerceIn(0, h - 1)
            for (c in 0 until cols) {
                val x = (startX + c * cellW + cellW / 2).coerceIn(0, w - 1)
                if (masks.any { x / w.toFloat() in it.left..it.right && y / h.toFloat() in it.top..it.bottom }) {
                    grid[idx++] = Float.NaN
                    continue
                }
                val px = bitmap.getPixel(x, y)
                val red = (px shr 16) and 0xFF
                val green = (px shr 8) and 0xFF
                val blue = px and 0xFF
                grid[idx++] = (0.299f * red + 0.587f * green + 0.114f * blue) / 255f
            }
        }
        return grid
    }

    companion object {
        const val VISUAL_CUT_LUMA_THRESHOLD = 0.185f
        const val SOFT_CUT_LUMA_THRESHOLD = 0.115f
        const val MIN_UNMASKED_CELLS = 12
        const val CONFIRMED_EMPTY_FRAME_STREAK = LiveStageFrameSnapshot.CONFIRMED_EMPTY_FRAME_STREAK

        fun computeGridDistance(a: FloatArray, b: FloatArray): Float =
            computeMaskedGridDistance(a, b).first

        /**
         * Compares two luma grids while ignoring any cell masked by active UI overlays (`Float.NaN`).
         * Prevents appearing/moving speech balloons from causing false PixelCopy visual cuts.
         */
        fun computeMaskedGridDistance(a: FloatArray, b: FloatArray): Pair<Float, Int> {
            if (a.isEmpty() || a.size != b.size) return 0f to 0
            var sum = 0f
            var count = 0
            for (i in a.indices) {
                val va = a[i]
                val vb = b[i]
                if (!va.isNaN() && !vb.isNaN()) {
                    sum += abs(va - vb)
                    count++
                }
            }
            return if (count == 0) 0f to 0 else (sum / count) to count
        }

        fun mergeGridPreservingMasked(previous: FloatArray?, current: FloatArray): FloatArray {
            if (previous == null || previous.size != current.size) return current
            return FloatArray(current.size) { i ->
                if (current[i].isNaN()) previous[i] else current[i]
            }
        }

        fun detectFaceLayoutCut(
            previous: List<Pair<Float, Float>>,
            current: List<Pair<Float, Float>>,
        ): Boolean {
            if (previous.isEmpty() && current.isEmpty()) return false
            if (previous.isEmpty() != current.isEmpty()) return true
            if (kotlin.math.abs(previous.size - current.size) >= 2) return true
            val minSize = minOf(previous.size, current.size)
            var maxShift = 0f
            for (i in 0 until minSize) {
                val dx = previous[i].first - current[i].first
                val dy = previous[i].second - current[i].second
                val dist = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
                if (dist > maxShift) maxShift = dist
            }
            return maxShift >= 0.24f
        }
    }

    private suspend fun requestPixelCopy(
        window: Window,
        srcRect: Rect,
        videoSurface: SurfaceView?,
        dest: Bitmap,
    ): Boolean = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            if (cont.isActive) cont.resume(false)
            return@suspendCancellableCoroutine
        }
        try {
            val listener = PixelCopy.OnPixelCopyFinishedListener { result ->
                if (cont.isActive) cont.resume(result == PixelCopy.SUCCESS)
            }
            if (videoSurface != null) {
                PixelCopy.request(videoSurface, dest, listener, mainHandler)
            } else {
                PixelCopy.request(window, srcRect, dest, listener, mainHandler)
            }
        } catch (_: Throwable) {
            if (cont.isActive) cont.resume(false)
        }
    }

    private suspend fun detectFacesOnBitmap(bitmap: Bitmap): List<Face>? =
        suspendCancellableCoroutine { cont ->
            val input = InputImage.fromBitmap(bitmap, 0)
            detector.value.process(input)
                .addOnSuccessListener { faces ->
                    if (cont.isActive) cont.resume(faces)
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(null)
                }
        }

    private fun extractLiveMouthPoint(
        face: Face,
        w: Float,
        h: Float,
        normBox: NormalizedRect,
    ): NormalizedPoint {
        val mLeft = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
        val mRight = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position
        val mBottom = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position
        val pts = listOfNotNull(mLeft, mRight, mBottom)
        if (pts.isNotEmpty()) {
            val mx = (pts.map { it.x }.average().toFloat() / w).coerceIn(0.02f, 0.98f)
            val my = (pts.map { it.y }.average().toFloat() / h).coerceIn(0.02f, 0.98f)
            return NormalizedPoint(mx, my)
        }
        val yawShift = (face.headEulerAngleY / 90f).coerceIn(-0.28f, 0.28f) * normBox.width
        return NormalizedPoint(
            x = (normBox.centerX + yawShift).coerceIn(normBox.left, normBox.right),
            y = (normBox.top + normBox.height * 0.72f).coerceIn(normBox.top, normBox.bottom),
        )
    }

    private tailrec fun findWindow(ctx: Context?): Window? = when (ctx) {
        is Activity -> ctx.window
        is ContextWrapper -> findWindow(ctx.baseContext)
        else -> null
    }
}
