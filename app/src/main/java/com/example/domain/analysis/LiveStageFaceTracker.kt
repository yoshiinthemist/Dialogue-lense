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
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.roundToInt

data class LiveFaceObservation(
    val trackingId: Int?,
    val boundingBox: NormalizedRect,
    val mouthPoint: NormalizedPoint,
    val headYawDeg: Float,
    val capturedAtMs: Long,
)

data class LiveStageFrameSnapshot(
    val faces: List<LiveFaceObservation>,
    val visualCutDetected: Boolean,
    val cutDistance: Float,
    val frameLumaAverage: Float,
    val capturedAtMs: Long,
    val hasLiveVisionSignal: Boolean = true,
)

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

    private val detector by lazy { FaceDetection.getClient(options) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val captureBusy = AtomicBoolean(false)

    @Volatile
    private var previousLumaGrid: FloatArray? = null

    @Volatile
    private var previousFaceSignature: List<Pair<Float, Float>> = emptyList()

    @Volatile
    private var previousVideoTimeMs: Long = -1L

    fun resetSceneHistory() {
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
    ): LiveStageFrameSnapshot? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        if (!hostView.isAttachedToWindow) return null
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

            val targetWidth = 640
            val aspect = srcRect.height().toFloat() / srcRect.width().toFloat().coerceAtLeast(1f)
            val targetHeight = (targetWidth * aspect).roundToInt().coerceIn(180, 540)
            val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            capturedBitmap = bitmap

            val copySuccess = requestWindowPixelCopy(window, srcRect, bitmap)
            if (!copySuccess) return null

            val lumaGrid = computeLumaGrid(bitmap)
            val lumaAverage = if (lumaGrid.isNotEmpty()) lumaGrid.average().toFloat() else 0.5f

            val faces = detectFacesOnBitmap(bitmap)
            val w = bitmap.width.toFloat().coerceAtLeast(1f)
            val h = bitmap.height.toFloat().coerceAtLeast(1f)
            val nowMs = System.currentTimeMillis()
            val observations = faces.map { face ->
                val normBox = NormalizedRect(
                    left = (face.boundingBox.left / w).coerceIn(0f, 1f),
                    top = (face.boundingBox.top / h).coerceIn(0f, 1f),
                    right = (face.boundingBox.right / w).coerceIn(0f, 1f),
                    bottom = (face.boundingBox.bottom / h).coerceIn(0f, 1f),
                )
                LiveFaceObservation(
                    trackingId = face.trackingId,
                    boundingBox = normBox,
                    mouthPoint = extractLiveMouthPoint(face, w, h, normBox),
                    headYawDeg = face.headEulerAngleY,
                    capturedAtMs = nowMs.coerceAtLeast(videoTimeMs),
                )
            }

            val currentFaceSig = observations
                .sortedBy { it.boundingBox.centerX }
                .map { it.boundingBox.centerX to it.boundingBox.centerY }

            val prevGrid = previousLumaGrid
            val lumaDiff = if (prevGrid != null) computeGridDistance(prevGrid, lumaGrid) else 0f
            val timeDiscontinuity = previousVideoTimeMs >= 0L &&
                kotlin.math.abs(videoTimeMs - previousVideoTimeMs) > 2_500L
            val faceLayoutJump = detectFaceLayoutCut(previousFaceSignature, currentFaceSig)

            // Ignore completely dark transition frames as noise unless paired with big luma jump
            val cutDetected = timeDiscontinuity ||
                lumaDiff >= VISUAL_CUT_LUMA_THRESHOLD ||
                (lumaDiff >= SOFT_CUT_LUMA_THRESHOLD && faceLayoutJump)

            previousLumaGrid = lumaGrid
            previousFaceSignature = currentFaceSig
            previousVideoTimeMs = videoTimeMs

            LiveStageFrameSnapshot(
                faces = observations,
                visualCutDetected = cutDetected,
                cutDistance = lumaDiff,
                frameLumaAverage = lumaAverage,
                capturedAtMs = nowMs.coerceAtLeast(videoTimeMs),
                hasLiveVisionSignal = true,
            )
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { capturedBitmap?.recycle() }
            captureBusy.set(false)
        }
    }

    private fun computeLumaGrid(bitmap: Bitmap): FloatArray {
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
        const val VISUAL_CUT_LUMA_THRESHOLD = 0.165f
        const val SOFT_CUT_LUMA_THRESHOLD = 0.105f

        fun computeGridDistance(a: FloatArray, b: FloatArray): Float {
            if (a.isEmpty() || a.size != b.size) return 0f
            var sum = 0f
            for (i in a.indices) {
                sum += kotlin.math.abs(a[i] - b[i])
            }
            return sum / a.size
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

    private suspend fun requestWindowPixelCopy(
        window: Window,
        srcRect: Rect,
        dest: Bitmap,
    ): Boolean = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            if (cont.isActive) cont.resume(false)
            return@suspendCancellableCoroutine
        }
        try {
            PixelCopy.request(
                window,
                srcRect,
                dest,
                { copyResult ->
                    if (cont.isActive) {
                        cont.resume(copyResult == PixelCopy.SUCCESS)
                    }
                },
                mainHandler,
            )
        } catch (_: Throwable) {
            if (cont.isActive) cont.resume(false)
        }
    }

    private suspend fun detectFacesOnBitmap(bitmap: Bitmap): List<Face> =
        suspendCancellableCoroutine { cont ->
            val input = InputImage.fromBitmap(bitmap, 0)
            detector.process(input)
                .addOnSuccessListener { faces ->
                    if (cont.isActive) cont.resume(faces)
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(emptyList())
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
            x = (normBox.centerX + yawShift).coerceIn(normBox.left + 0.01f, normBox.right - 0.01f),
            y = (normBox.top + normBox.height * 0.72f).coerceIn(normBox.top, normBox.bottom),
        )
    }

    private tailrec fun findWindow(ctx: Context?): Window? = when (ctx) {
        is Activity -> ctx.window
        is ContextWrapper -> findWindow(ctx.baseContext)
        else -> null
    }
}
