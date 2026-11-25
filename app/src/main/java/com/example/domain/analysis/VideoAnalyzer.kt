package com.example.domain.analysis

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.example.data.model.FaceSample
import com.example.data.model.FaceTrack
import com.example.data.model.MediaTrackInfo
import com.example.data.model.NormalizedPoint
import com.example.data.model.NormalizedRect
import com.example.data.model.Shot
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

private const val TAG = "VideoAnalyzer"

data class VideoMetadataResult(
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val audioTracks: List<MediaTrackInfo>,
    val subtitleTracks: List<MediaTrackInfo>,
)

data class WindowAnalysisResult(
    val shots: List<Shot>,
    val faceTracks: List<FaceTrack>,
)

class VideoAnalyzer(private val context: Context) {

    private val detectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
        .enableTracking()
        .setMinFaceSize(0.08f)
        .build()

    private val faceDetector by lazy { FaceDetection.getClient(detectorOptions) }
    private val textRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun extractMetadata(uri: Uri): VideoMetadataResult = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        var width = 1920
        var height = 1080
        var durationMs = 0L
        try {
            retriever.setDataSource(context, uri)
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()?.coerceAtLeast(1) ?: 1920
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()?.coerceAtLeast(1) ?: 1080
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "Retriever metadata extraction failed", e)
        } finally {
            runCatching { retriever.release() }
        }

        val audioTracks = mutableListOf<MediaTrackInfo>()
        val subtitleTracks = mutableListOf<MediaTrackInfo>()
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                val lang = format.getString(MediaFormat.KEY_LANGUAGE) ?: "und"
                when {
                    mime.startsWith("audio/") -> audioTracks.add(
                        MediaTrackInfo(i, mime, lang, "Audio ${audioTracks.size + 1} ($lang)")
                    )
                    mime.startsWith("text/") || mime.contains("subtitle") || mime.contains("cea") ->
                        subtitleTracks.add(
                            MediaTrackInfo(i, mime, lang, "Embedded Track ${subtitleTracks.size + 1} ($lang)")
                        )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaExtractor track scan partial/failed", e)
        } finally {
            runCatching { extractor.release() }
        }

        VideoMetadataResult(width, height, durationMs, audioTracks, subtitleTracks)
    }

    suspend fun analyzeWindow(
        uri: Uri,
        windowStartMs: Long,
        windowEndMs: Long,
        sampleIntervalMs: Long,
        existingShotCount: Int = 0,
    ): WindowAnalysisResult = withContext(Dispatchers.Default) {
        val retriever = MediaMetadataRetriever()
        val sampledDetections = mutableListOf<FrameObservation>()
        try {
            retriever.setDataSource(context, uri)
            val safeEnd = windowEndMs.coerceAtLeast(windowStartMs + 500L)
            var t = windowStartMs
            var prevSignature: IntArray? = null
            var frameIndex = 0

            while (t <= safeEnd) {
                // OPTION_CLOSEST ensures smooth motion & mouth movement tracking instead of snapping to keyframes
                val bitmap = retriever.getFrameAtTime(t * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: retriever.getFrameAtTime(t * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (bitmap != null) {
                    val signature = computeFrameSignature(bitmap)
                    val cutScore = prevSignature?.let { signatureDistance(it, signature) } ?: 0f
                    prevSignature = signature
                    val isCut = cutScore > 0.24f

                    val faces = runCatching { detectFaces(bitmap) }.getOrElse {
                        Log.w(TAG, "ML Kit face detection skipped at ${t}ms", it)
                        emptyList()
                    }
                    // Run on-device OCR on shot starts or every 3rd sample to protect burnt-in signage/titles
                    val ocrZones = if (isCut || frameIndex % 3 == 0) {
                        runCatching { detectTextExclusionZones(bitmap) }.getOrElse { emptyList() }
                    } else {
                        emptyList()
                    }
                    val frameLuma = estimateBitmapLuminance(signature)

                    sampledDetections.add(
                        FrameObservation(
                            timeMs = t,
                            frameWidth = bitmap.width.toFloat().coerceAtLeast(1f),
                            frameHeight = bitmap.height.toFloat().coerceAtLeast(1f),
                            isShotCut = isCut,
                            luminance = frameLuma,
                            faces = faces,
                            textZones = ocrZones,
                        )
                    )
                    bitmap.recycle()
                }
                t += sampleIntervalMs
                frameIndex++
            }
        } catch (e: Exception) {
            Log.e(TAG, "Window analysis failed for $windowStartMs..$windowEndMs", e)
        } finally {
            runCatching { retriever.release() }
        }

        buildShotsAndTracks(sampledDetections, windowStartMs, windowEndMs, existingShotCount)
    }

    /**
     * Extracts compact representative keyframes (<= 480px wide) for multimodal Gemini scene reasoning.
     */
    suspend fun extractKeyframesForShots(
        uri: Uri,
        shots: List<Shot>,
        maxFrames: Int = 4,
    ): List<Pair<String, Bitmap>> = withContext(Dispatchers.IO) {
        if (shots.isEmpty()) return@withContext emptyList()
        val selectedShots = if (shots.size <= maxFrames) {
            shots
        } else {
            val step = shots.size.toFloat() / maxFrames.toFloat()
            List(maxFrames) { i -> shots[(i * step).toInt().coerceIn(0, shots.lastIndex)] }
        }
        val retriever = MediaMetadataRetriever()
        val frames = mutableListOf<Pair<String, Bitmap>>()
        try {
            retriever.setDataSource(context, uri)
            for (shot in selectedShots) {
                val midMs = (shot.startMs + shot.endMs) / 2L
                val raw = retriever.getFrameAtTime(midMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (raw != null) {
                    val targetW = 480
                    val targetH = ((raw.height.toFloat() / raw.width.coerceAtLeast(1)) * targetW)
                        .toInt().coerceIn(180, 480)
                    val scaled = Bitmap.createScaledBitmap(raw, targetW, targetH, true)
                    if (scaled !== raw) raw.recycle()
                    frames.add(shot.id to scaled)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Keyframe extraction for multimodal Gemini skipped", e)
        } finally {
            runCatching { retriever.release() }
        }
        frames
    }

    private suspend fun detectFaces(bitmap: Bitmap): List<Face> =
        suspendCancellableCoroutine { cont ->
            val input = InputImage.fromBitmap(bitmap, 0)
            faceDetector.process(input)
                .addOnSuccessListener { faces ->
                    if (cont.isActive) cont.resume(faces)
                }
                .addOnFailureListener { exc ->
                    if (cont.isActive) cont.resumeWithException(exc)
                }
        }

    private suspend fun detectTextExclusionZones(bitmap: Bitmap): List<NormalizedRect> =
        suspendCancellableCoroutine { cont ->
            val w = bitmap.width.toFloat().coerceAtLeast(1f)
            val h = bitmap.height.toFloat().coerceAtLeast(1f)
            val input = InputImage.fromBitmap(bitmap, 0)
            textRecognizer.process(input)
                .addOnSuccessListener { result ->
                    val zones = result.textBlocks.mapNotNull { block ->
                        val box = block.boundingBox ?: return@mapNotNull null
                        if (box.width() < w * 0.04f || box.height() < h * 0.02f) return@mapNotNull null
                        NormalizedRect(
                            left = (box.left / w - 0.015f).coerceIn(0f, 1f),
                            top = (box.top / h - 0.01f).coerceIn(0f, 1f),
                            right = (box.right / w + 0.015f).coerceIn(0f, 1f),
                            bottom = (box.bottom / h + 0.01f).coerceIn(0f, 1f),
                        )
                    }.take(5)
                    if (cont.isActive) cont.resume(zones)
                }
                .addOnFailureListener { exc ->
                    if (cont.isActive) cont.resumeWithException(exc)
                }
        }

    private fun estimateBitmapLuminance(pixels: IntArray): Float {
        if (pixels.isEmpty()) return 0.25f
        var sum = 0f
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            sum += (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        }
        return (sum / pixels.size).coerceIn(0f, 1f)
    }

    private fun buildShotsAndTracks(
        observations: List<FrameObservation>,
        windowStartMs: Long,
        windowEndMs: Long,
        existingShotCount: Int,
    ): WindowAnalysisResult {
        if (observations.isEmpty()) {
            val fallbackShot = Shot(
                id = "shot_${existingShotCount + 1}",
                startMs = windowStartMs,
                endMs = windowEndMs,
                cutType = "CONTINUOUS",
            )
            return WindowAnalysisResult(listOf(fallbackShot), emptyList())
        }

        val shots = mutableListOf<Shot>()
        val tracks = mutableListOf<FaceTrack>()

        val shotGroups = mutableListOf<MutableList<FrameObservation>>()
        var currentGroup = mutableListOf<FrameObservation>()
        for (obs in observations) {
            if (obs.isShotCut && currentGroup.isNotEmpty()) {
                shotGroups.add(currentGroup)
                currentGroup = mutableListOf()
            }
            currentGroup.add(obs)
        }
        if (currentGroup.isNotEmpty()) shotGroups.add(currentGroup)

        shotGroups.forEachIndexed { idx, group ->
            val shotId = "shot_${existingShotCount + idx + 1}"
            val startMs = if (idx == 0) windowStartMs else group.first().timeMs
            val endMs = if (idx == shotGroups.lastIndex) windowEndMs else shotGroups[idx + 1].first().timeMs
            val avgLuma = group.map { it.luminance }.average().toFloat().coerceIn(0f, 1f)
            val ocrExclusions = group.flatMap { it.textZones }.take(6)
            shots.add(
                Shot(
                    id = shotId,
                    startMs = startMs,
                    endMs = endMs,
                    focalExclusionZones = ocrExclusions,
                    sceneLuminance = avgLuma,
                )
            )

            // Group faces within shot using ML Kit trackingId or spatial proximity
            val trackBuckets = mutableMapOf<Int, MutableList<FaceSample>>()
            val prevApertureByKey = mutableMapOf<Int, Float>()
            var syntheticIdCounter = 1000

            for (obs in group) {
                for (face in obs.faces) {
                    val normBox = NormalizedRect(
                        left = (face.boundingBox.left / obs.frameWidth).coerceIn(0f, 1f),
                        top = (face.boundingBox.top / obs.frameHeight).coerceIn(0f, 1f),
                        right = (face.boundingBox.right / obs.frameWidth).coerceIn(0f, 1f),
                        bottom = (face.boundingBox.bottom / obs.frameHeight).coerceIn(0f, 1f),
                    )
                    val key = face.trackingId ?: findNearestBucket(trackBuckets, normBox) ?: syntheticIdCounter++
                    val rawAperture = estimateMouthAperture(face)
                    val prevAperture = prevApertureByKey[key] ?: rawAperture
                    prevApertureByKey[key] = rawAperture

                    // Combine openness + frame-to-frame lip movement energy
                    val mouthEnergy = (rawAperture * 0.55f + abs(rawAperture - prevAperture) * 4.5f)
                        .coerceIn(0f, 1f)

                    trackBuckets.getOrPut(key) { mutableListOf() }.add(
                        FaceSample(
                            timeMs = obs.timeMs,
                            boundingBox = normBox,
                            mouthActivity = mouthEnergy,
                            headYawDeg = face.headEulerAngleY,
                            mouthPoint = extractNormalizedMouthPoint(face, obs.frameWidth, obs.frameHeight, normBox),
                        )
                    )
                }
            }

            // Keep up to 4 most prominent face tracks per shot
            trackBuckets.entries
                .sortedByDescending { it.value.size }
                .take(4)
                .forEachIndexed { trackIndex, (key, samples) ->
                    tracks.add(
                        FaceTrack(
                            id = "ft_${shotId}_${trackIndex + 1}",
                            shotId = shotId,
                            speakerId = null,
                            trackingTag = key,
                            samples = samples,
                        )
                    )
                }
        }

        return WindowAnalysisResult(shots, tracks)
    }

    private fun findNearestBucket(
        buckets: Map<Int, List<FaceSample>>,
        box: NormalizedRect,
    ): Int? {
        var bestKey: Int? = null
        var bestDist = 0.16f
        for ((key, samples) in buckets) {
            val last = samples.lastOrNull() ?: continue
            val dx = abs(last.boundingBox.centerX - box.centerX)
            val dy = abs(last.boundingBox.centerY - box.centerY)
            val dist = dx + dy
            if (dist < bestDist) {
                bestDist = dist
                bestKey = key
            }
        }
        return bestKey
    }

    private fun estimateMouthAperture(face: Face): Float {
        val upperLip = face.getContour(FaceContour.UPPER_LIP_BOTTOM)?.points
        val lowerLip = face.getContour(FaceContour.LOWER_LIP_TOP)?.points
        val faceH = face.boundingBox.height().toFloat().coerceAtLeast(1f)
        if (!upperLip.isNullOrEmpty() && !lowerLip.isNullOrEmpty()) {
            val upperY = upperLip.map { it.y }.average().toFloat()
            val lowerY = lowerLip.map { it.y }.average().toFloat()
            val gap = (lowerY - upperY).coerceAtLeast(0f) / faceH
            return (gap * 6.5f).coerceIn(0f, 1f)
        }
        val smile = face.smilingProbability ?: 0.2f
        return (smile * 0.45f).coerceIn(0f, 1f)
    }

    private fun extractNormalizedMouthPoint(
        face: Face,
        frameW: Float,
        frameH: Float,
        normBox: NormalizedRect,
    ): NormalizedPoint {
        val upperLip = face.getContour(FaceContour.UPPER_LIP_BOTTOM)?.points
        val lowerLip = face.getContour(FaceContour.LOWER_LIP_TOP)?.points
        if (!upperLip.isNullOrEmpty() && !lowerLip.isNullOrEmpty()) {
            val allPts = upperLip + lowerLip
            val mx = (allPts.map { it.x }.average().toFloat() / frameW).coerceIn(0.02f, 0.98f)
            val my = (allPts.map { it.y }.average().toFloat() / frameH).coerceIn(0.02f, 0.98f)
            return NormalizedPoint(mx, my)
        }
        val mLeft = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
        val mRight = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position
        val mBottom = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position
        val landmarkPts = listOfNotNull(mLeft, mRight, mBottom)
        if (landmarkPts.isNotEmpty()) {
            val mx = (landmarkPts.map { it.x }.average().toFloat() / frameW).coerceIn(0.02f, 0.98f)
            val my = (landmarkPts.map { it.y }.average().toFloat() / frameH).coerceIn(0.02f, 0.98f)
            return NormalizedPoint(mx, my)
        }
        val yawShift = (face.headEulerAngleY / 90f).coerceIn(-0.28f, 0.28f) * normBox.width
        return NormalizedPoint(
            x = (normBox.centerX + yawShift).coerceIn(normBox.left + 0.01f, normBox.right - 0.01f),
            y = (normBox.top + normBox.height * 0.72f).coerceIn(normBox.top, normBox.bottom),
        )
    }

    private fun computeFrameSignature(bitmap: Bitmap): IntArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, 16, 16, false)
        val pixels = IntArray(256)
        scaled.getPixels(pixels, 0, 16, 0, 0, 16, 16)
        if (scaled !== bitmap) scaled.recycle()
        return pixels
    }

    private fun signatureDistance(a: IntArray, b: IntArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var diffSum = 0L
        for (i in a.indices) {
            val pa = a[i]
            val pb = b[i]
            val dr = abs(((pa shr 16) and 0xFF) - ((pb shr 16) and 0xFF))
            val dg = abs(((pa shr 8) and 0xFF) - ((pb shr 8) and 0xFF))
            val db = abs((pa and 0xFF) - (pb and 0xFF))
            diffSum += (dr + dg + db)
        }
        return diffSum.toFloat() / (a.size * 3f * 255f)
    }

    private data class FrameObservation(
        val timeMs: Long,
        val frameWidth: Float,
        val frameHeight: Float,
        val isShotCut: Boolean,
        val luminance: Float,
        val faces: List<Face>,
        val textZones: List<NormalizedRect>,
    )
}
