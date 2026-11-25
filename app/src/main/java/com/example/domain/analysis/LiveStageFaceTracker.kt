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

/**
 * High-frequency (~6fps) on-device ML Kit face + mouth landmark tracker that captures rendered
 * VideoPlayerStage frames via Android PixelCopy (zero extra permissions; works identically across
 * embedded YouTube WebView, Web Stream, and ExoPlayer MP4) so balloon tails track moving mouths
 * in real time rather than sticking to static cue coordinates.
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

    suspend fun captureAndDetect(
        hostView: View,
        stageBoundsInWindow: ComposeRect,
        videoTimeMs: Long,
    ): List<LiveFaceObservation>? {
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

            val faces = detectFacesOnBitmap(bitmap)
            val w = bitmap.width.toFloat().coerceAtLeast(1f)
            val h = bitmap.height.toFloat().coerceAtLeast(1f)
            val nowMs = System.currentTimeMillis()
            faces.map { face ->
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
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { capturedBitmap?.recycle() }
            captureBusy.set(false)
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
