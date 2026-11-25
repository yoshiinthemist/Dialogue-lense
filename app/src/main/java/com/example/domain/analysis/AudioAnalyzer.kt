package com.example.domain.analysis

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.example.data.model.AudioVoiceFeatures
import com.example.data.model.DialogueEvent
import com.example.data.subtitle.RawSubtitleCue
import com.example.data.subtitle.SubtitleParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val TAG = "AudioAnalyzer"

data class AudioWavChunk(
    val startMs: Long,
    val endMs: Long,
    val wavBytes: ByteArray,
)

/**
 * Decodes local video audio tracks with Android MediaExtractor + MediaCodec to extract
 * per-dialogue acoustic voice signatures (RMS speech energy, peak burst amplitude, spectral
 * brightness via ZCR, autocorrelation pitch Hz, shout emphasis, waveform envelopes, and
 * 16kHz WAV chunks for Gemini multimodal speech transcription).
 */
class AudioAnalyzer(private val context: Context) {

    internal data class HlsSegmentEntry(
        val url: String,
        val startMs: Long,
        val endMs: Long,
        val initMapUrl: String? = null,
    )

    internal data class HlsMediaIndex(
        val playlistUrl: String,
        val segments: List<HlsSegmentEntry>,
        val totalDurationMs: Long,
        val encrypted: Boolean = false,
    )

    private val hlsIndexCache = ConcurrentHashMap<String, HlsMediaIndex>()

    /**
     * Attempts to pull text-based soft subtitle tracks embedded inside MP4/MKV containers.
     * Bitmap formats (PGS/VobSub) or raw CEA-608 NAL bitstreams are skipped safely.
     */
    suspend fun extractEmbeddedSubtitles(uri: Uri): List<RawSubtitleCue> = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        val cues = mutableListOf<RawSubtitleCue>()
        try {
            extractor.setDataSource(context, uri, null)
            var subTrackIdx = -1
            var subMime = ""
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty().lowercase()
                if (mime in listOf("text/x-srt", "application/x-subrip", "text/vtt", "text/plain", "text/ssa", "text/ass")) {
                    subTrackIdx = i
                    subMime = mime
                    break
                }
            }
            if (subTrackIdx == -1) return@withContext emptyList()

            extractor.selectTrack(subTrackIdx)
            val buffer = ByteBuffer.allocate(64 * 1024)
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size <= 0) break
                val startMs = (extractor.sampleTime / 1000L).coerceAtLeast(0L)
                val bytes = ByteArray(size)
                buffer.get(bytes)
                val raw = String(bytes, Charsets.UTF_8).trim()
                if (raw.isNotBlank()) {
                    val parsed = SubtitleParser.parse(raw, if (subMime.contains("ass") || subMime.contains("ssa")) "track.ass" else "track.srt")
                    if (parsed.isNotEmpty()) {
                        cues.addAll(parsed)
                    } else {
                        val cleaned = raw.replace(Regex("<[^>]+>"), "").trim()
                        if (cleaned.isNotBlank()) {
                            cues.add(
                                RawSubtitleCue(
                                    startTimeMs = startMs,
                                    endTimeMs = startMs + (1400L + cleaned.length * 60L).coerceIn(1400L, 4800L),
                                    text = cleaned,
                                )
                            )
                        }
                    }
                }
                if (!extractor.advance()) break
            }
        } catch (e: Exception) {
            Log.w(TAG, "Embedded subtitle extraction skipped", e)
        } finally {
            runCatching { extractor.release() }
        }
        cues.distinctBy { "${it.startTimeMs}_${it.text}" }.sortedBy { it.startTimeMs }
    }

    /**
     * Extracts normalized speech-energy waveform peaks across the video duration for scrubber rendering.
     */
    suspend fun extractWaveformPeaks(
        uri: Uri,
        durationMs: Long,
        bucketCount: Int = 72,
    ): List<Float> = withContext(Dispatchers.Default) {
        if (durationMs <= 0L || bucketCount <= 0) return@withContext emptyList()
        val bucketSumSq = DoubleArray(bucketCount)
        val bucketCounts = IntArray(bucketCount)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var audioIdx = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioIdx = i
                    audioFormat = fmt
                    break
                }
            }
            if (audioIdx == -1 || audioFormat == null) return@withContext emptyList()

            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return@withContext emptyList()
            val channels = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 8)
            } else 1
            val maxAnalyzeMs = durationMs.coerceAtMost(240_000L)

            extractor.selectTrack(audioIdx)
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(audioFormat, null, null, 0)
                start()
            }
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            while (!outDone) {
                if (!inDone) {
                    val inIdx = codec.dequeueInputBuffer(4_000L)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val sz = extractor.readSampleData(inBuf, 0)
                            if (sz < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inDone = true
                            } else {
                                val ptsUs = extractor.sampleTime
                                codec.queueInputBuffer(inIdx, 0, sz, ptsUs, 0)
                                extractor.advance()
                                if (ptsUs > maxAnalyzeMs * 1000L) inDone = true
                            }
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 4_000L)
                if (outIdx >= 0) {
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true
                    val outBuf = codec.getOutputBuffer(outIdx)
                    val ptsMs = (info.presentationTimeUs / 1000L).coerceIn(0L, durationMs)
                    if (outBuf != null && info.size > 0) {
                        val bucket = ((ptsMs.toFloat() / durationMs.toFloat()) * (bucketCount - 1))
                            .roundToInt()
                            .coerceIn(0, bucketCount - 1)
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        val shorts = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        var sampleStep = 0
                        while (shorts.remaining() >= channels) {
                            val s = shorts.get().toFloat() / 32768f
                            for (c in 1 until channels) shorts.get()
                            if (sampleStep % 4 == 0) {
                                bucketSumSq[bucket] += (s * s).toDouble()
                                bucketCounts[bucket]++
                            }
                            sampleStep++
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && inDone) {
                    break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Waveform peak extraction skipped", e)
            return@withContext emptyList()
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }

        val rawRms = List(bucketCount) { i ->
            if (bucketCounts[i] > 0) sqrt(bucketSumSq[i] / bucketCounts[i]).toFloat() else 0f
        }
        val peak = (rawRms.maxOrNull() ?: 0f).coerceAtLeast(0.02f)
        rawRms.map { (it / peak).coerceIn(0.08f, 1f) }
    }

    /**
     * Decodes local video audio into compact 16kHz mono 16-bit WAV chunks (<2MB per chunk)
     * ready for Gemini multimodal speech-to-subtitle transcription.
     */
    suspend fun extractChunkedWavForAsr(
        uri: Uri,
        chunkWindowMs: Long = 50_000L,
        maxTotalMs: Long = 180_000L,
    ): List<AudioWavChunk> = withContext(Dispatchers.Default) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val chunks = mutableListOf<AudioWavChunk>()
        try {
            extractor.setDataSource(context, uri, null)
            var audioIdx = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioIdx = i
                    audioFormat = fmt
                    break
                }
            }
            if (audioIdx == -1 || audioFormat == null) return@withContext emptyList()
            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return@withContext emptyList()
            val srcRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceIn(8000, 96000)
            } else 44100
            val channels = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 8)
            } else 1
            val targetRate = 16000
            val stepRatio = (srcRate.toDouble() / targetRate.toDouble()).coerceAtLeast(1.0)

            extractor.selectTrack(audioIdx)
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(audioFormat, null, null, 0)
                start()
            }

            var currentChunkStartMs = 0L
            var pcmOut = ByteArrayOutputStream()
            var sampleAccumPos = 0.0
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false

            fun flushChunk(endMs: Long) {
                val pcm = pcmOut.toByteArray()
                if (pcm.size > 3200) {
                    chunks.add(
                        AudioWavChunk(
                            startMs = currentChunkStartMs,
                            endMs = endMs.coerceAtLeast(currentChunkStartMs + 500L),
                            wavBytes = wrapPcmAsWav(pcm, targetRate),
                        )
                    )
                }
                pcmOut = ByteArrayOutputStream()
                currentChunkStartMs = endMs
            }

            while (!outDone) {
                if (!inDone) {
                    val inIdx = codec.dequeueInputBuffer(5_000L)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val sz = extractor.readSampleData(inBuf, 0)
                            if (sz < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inDone = true
                            } else {
                                val ptsUs = extractor.sampleTime
                                codec.queueInputBuffer(inIdx, 0, sz, ptsUs, 0)
                                extractor.advance()
                                if (ptsUs > maxTotalMs * 1000L) inDone = true
                            }
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 5_000L)
                if (outIdx >= 0) {
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true
                    val outBuf = codec.getOutputBuffer(outIdx)
                    val ptsMs = (info.presentationTimeUs / 1000L).coerceAtLeast(0L)
                    if (outBuf != null && info.size > 0) {
                        if (ptsMs - currentChunkStartMs >= chunkWindowMs && pcmOut.size() > 3200) {
                            flushChunk(ptsMs)
                        }
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        val shorts = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val frameCount = shorts.remaining() / channels
                        var frameIdx = 0
                        while (frameIdx < frameCount) {
                            var monoSum = 0
                            for (c in 0 until channels) monoSum += shorts.get().toInt()
                            if (sampleAccumPos <= 0.0) {
                                val monoSample = (monoSum / channels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                                pcmOut.write(monoSample.toInt() and 0xFF)
                                pcmOut.write((monoSample.toInt() shr 8) and 0xFF)
                                sampleAccumPos += stepRatio
                            }
                            sampleAccumPos -= 1.0
                            frameIdx++
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (ptsMs > maxTotalMs && inDone) outDone = true
                } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && inDone) {
                    break
                }
            }
            flushChunk(maxTotalMs)
        } catch (e: Exception) {
            Log.e(TAG, "WAV export for ASR failed", e)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
        chunks
    }

    /**
     * Decodes a targeted [windowStartMs..windowEndMs] slice (with slight overlap padding)
     * into a compact 16kHz mono 16-bit WAV chunk for real-time 7-second lookahead transcription.
     * Supports local content Uris, header-authenticated HTTP streams, and HLS .m3u8 playlists.
     */
    suspend fun extractWavWindowForAsr(
        uri: Uri?,
        httpUrl: String? = null,
        httpHeaders: Map<String, String> = emptyMap(),
        windowStartMs: Long,
        windowEndMs: Long,
    ): AudioWavChunk? = withContext(Dispatchers.Default) {
        if (uri == null && httpUrl.isNullOrBlank()) return@withContext null

        val padStartMs = (windowStartMs - 250L).coerceAtLeast(0L)
        val padEndMs = (windowEndMs + 450L).coerceAtLeast(padStartMs + 1000L)

        // If HTTP URL is an HLS playlist (.m3u8), download only overlapping segments to temp file cleanly
        if (!httpUrl.isNullOrBlank() && WebNavigationGuard.isHlsPlaylistUrl(httpUrl)) {
            return@withContext extractWavWindowFromHls(
                playlistUrl = httpUrl,
                headers = httpHeaders,
                windowStartMs = windowStartMs,
                windowEndMs = windowEndMs,
                padStartMs = padStartMs,
                padEndMs = padEndMs,
            )
        }

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            if (!httpUrl.isNullOrBlank()) {
                extractor.setDataSource(httpUrl, httpHeaders)
            } else if (uri != null) {
                extractor.setDataSource(context, uri, null)
            }
            return@withContext decodeExtractorWindowToWav(
                extractor = extractor,
                windowStartMs = windowStartMs,
                windowEndMs = windowEndMs,
                padStartMs = padStartMs,
                padEndMs = padEndMs,
                timeOffsetBaseMs = 0L,
            )
        } catch (e: Exception) {
            if (!httpUrl.isNullOrBlank()) {
                // Retry once as HLS playlist in case extensionless URL served #EXTM3U
                val hlsFallback = runCatching {
                    extractWavWindowFromHls(
                        playlistUrl = httpUrl,
                        headers = httpHeaders,
                        windowStartMs = windowStartMs,
                        windowEndMs = windowEndMs,
                        padStartMs = padStartMs,
                        padEndMs = padEndMs,
                    )
                }.getOrNull()
                if (hlsFallback != null) return@withContext hlsFallback
            }
            Log.w(TAG, "Window WAV extract failed for $windowStartMs..$windowEndMs", e)
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private suspend fun extractWavWindowFromHls(
        playlistUrl: String,
        headers: Map<String, String>,
        windowStartMs: Long,
        windowEndMs: Long,
        padStartMs: Long,
        padEndMs: Long,
    ): AudioWavChunk? = withContext(Dispatchers.IO) {
        val index = resolveHlsMediaIndex(playlistUrl, headers) ?: return@withContext null
        if (index.encrypted || index.segments.isEmpty()) return@withContext null

        val overlapping = index.segments.filter { seg ->
            seg.endMs > padStartMs && seg.startMs < padEndMs
        }.take(4)
        if (overlapping.isEmpty()) return@withContext null

        val sliceFirstSegStartMs = overlapping.first().startMs
        val localPadStartMs = (padStartMs - sliceFirstSegStartMs).coerceAtLeast(0L)
        val localPadEndMs = (padEndMs - sliceFirstSegStartMs).coerceAtLeast(localPadStartMs + 1000L)

        val tempFile = File(context.cacheDir, "dl_hls_${System.nanoTime()}.tmp")
        val extractor = MediaExtractor()
        try {
            FileOutputStream(tempFile).use { out ->
                val initUrl = overlapping.first().initMapUrl
                if (!initUrl.isNullOrBlank()) {
                    val initBytes = httpDownloadBytes(initUrl, headers) ?: return@withContext null
                    out.write(initBytes)
                }
                for (seg in overlapping) {
                    val segBytes = httpDownloadBytes(seg.url, headers) ?: continue
                    out.write(segBytes)
                }
            }
            if (tempFile.length() < 1024L) return@withContext null

            extractor.setDataSource(tempFile.absolutePath)
            decodeExtractorWindowToWav(
                extractor = extractor,
                windowStartMs = windowStartMs,
                windowEndMs = windowEndMs,
                padStartMs = localPadStartMs,
                padEndMs = localPadEndMs,
                timeOffsetBaseMs = sliceFirstSegStartMs,
            )
        } catch (e: Exception) {
            Log.w(TAG, "HLS segment WAV extract failed for $windowStartMs..$windowEndMs", e)
            null
        } finally {
            runCatching { extractor.release() }
            runCatching { tempFile.delete() }
        }
    }

    private fun decodeExtractorWindowToWav(
        extractor: MediaExtractor,
        windowStartMs: Long,
        windowEndMs: Long,
        padStartMs: Long,
        padEndMs: Long,
        timeOffsetBaseMs: Long,
    ): AudioWavChunk? {
        var codec: MediaCodec? = null
        return try {
            var audioIdx = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioIdx = i
                    audioFormat = fmt
                    break
                }
            }
            if (audioIdx == -1 || audioFormat == null) return null
            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return null
            val srcRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceIn(8000, 96000)
            } else 44100
            val channels = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 8)
            } else 1
            val targetRate = 16000
            val stepRatio = (srcRate.toDouble() / targetRate.toDouble()).coerceAtLeast(1.0)

            extractor.selectTrack(audioIdx)
            if (padStartMs > 250L) {
                extractor.seekTo(padStartMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }

            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(audioFormat, null, null, 0)
                start()
            }

            val pcmOut = ByteArrayOutputStream()
            var sampleAccumPos = 0.0
            var actualStartMs: Long? = null
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false

            while (!outDone) {
                if (!inDone) {
                    val inIdx = codec.dequeueInputBuffer(5_000L)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val sz = extractor.readSampleData(inBuf, 0)
                            if (sz < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inDone = true
                            } else {
                                val ptsUs = extractor.sampleTime
                                codec.queueInputBuffer(inIdx, 0, sz, ptsUs, 0)
                                extractor.advance()
                                if (ptsUs > padEndMs * 1000L) {
                                    inDone = true
                                    val eosIdx = codec.dequeueInputBuffer(2_000L)
                                    if (eosIdx >= 0) {
                                        codec.queueInputBuffer(eosIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    }
                                }
                            }
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 5_000L)
                if (outIdx >= 0) {
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true
                    val outBuf = codec.getOutputBuffer(outIdx)
                    val ptsMs = (info.presentationTimeUs / 1000L).coerceAtLeast(0L)
                    if (outBuf != null && info.size > 0 && ptsMs in padStartMs..padEndMs) {
                        if (actualStartMs == null) actualStartMs = timeOffsetBaseMs + ptsMs
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        val shorts = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val frameCount = shorts.remaining() / channels
                        var frameIdx = 0
                        while (frameIdx < frameCount) {
                            var monoSum = 0
                            for (c in 0 until channels) monoSum += shorts.get().toInt()
                            if (sampleAccumPos <= 0.0) {
                                val monoSample = (monoSum / channels)
                                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                                    .toShort()
                                pcmOut.write(monoSample.toInt() and 0xFF)
                                pcmOut.write((monoSample.toInt() shr 8) and 0xFF)
                                sampleAccumPos += stepRatio
                            }
                            sampleAccumPos -= 1.0
                            frameIdx++
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (ptsMs > padEndMs && inDone) outDone = true
                } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && inDone) {
                    break
                }
            }
            val pcm = pcmOut.toByteArray()
            if (pcm.size <= 3200) return null
            val resolvedStart = actualStartMs ?: windowStartMs
            AudioWavChunk(
                startMs = resolvedStart,
                endMs = windowEndMs.coerceAtLeast(resolvedStart + 500L),
                wavBytes = wrapPcmAsWav(pcm, targetRate),
            )
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }
    }

    private fun resolveHlsMediaIndex(
        playlistUrl: String,
        headers: Map<String, String>,
    ): HlsMediaIndex? {
        hlsIndexCache[playlistUrl]?.let { return it }
        val text = httpDownloadText(playlistUrl, headers) ?: return null
        if (!text.contains("#EXTM3U", ignoreCase = true)) return null

        val variantUrl = pickBestHlsMasterVariantUri(text, playlistUrl)
        val mediaPlaylistUrl = variantUrl ?: playlistUrl
        val mediaText = if (variantUrl != null) {
            httpDownloadText(variantUrl, headers) ?: return null
        } else {
            text
        }
        val parsed = parseHlsMediaPlaylist(mediaText, mediaPlaylistUrl) ?: return null
        if (hlsIndexCache.size > 16) hlsIndexCache.clear()
        hlsIndexCache[playlistUrl] = parsed
        return parsed
    }

    private fun httpDownloadText(urlStr: String, headers: Map<String, String>): String? {
        val bytes = httpDownloadBytes(urlStr, headers, maxBytes = 2 * 1024 * 1024) ?: return null
        return String(bytes, Charsets.UTF_8)
    }

    private fun httpDownloadBytes(
        urlStr: String,
        headers: Map<String, String>,
        maxBytes: Int = 12 * 1024 * 1024,
    ): ByteArray? {
        val conn = (URL(urlStr).openConnection() as? HttpURLConnection) ?: return null
        return try {
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 7_500
            conn.readTimeout = 10_000
            headers.forEach { (k, v) ->
                if (k.isNotBlank() && v.isNotBlank()) conn.setRequestProperty(k, v)
            }
            if (!headers.containsKey("User-Agent")) {
                conn.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                )
            }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    total += read
                    if (total > maxBytes) break
                    out.write(buf, 0, read)
                }
                out.toByteArray()
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun wrapPcmAsWav(pcmData: ByteArray, sampleRate: Int): ByteArray {
        val totalDataLen = pcmData.size + 36
        val byteRate = sampleRate * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(totalDataLen)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // PCM subchunk size
            putShort(1) // PCM format
            putShort(1) // Mono
            putInt(sampleRate)
            putInt(byteRate)
            putShort(2) // Block align
            putShort(16) // Bits per sample
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcmData.size)
        }.array()
        return header + pcmData
    }

    suspend fun extractVoiceFeatures(
        uri: Uri?,
        events: List<DialogueEvent>,
        httpUrl: String? = null,
        httpHeaders: Map<String, String> = emptyMap(),
    ): Map<String, AudioVoiceFeatures> = withContext(Dispatchers.Default) {
        if (events.isEmpty() || (uri == null && httpUrl.isNullOrBlank())) return@withContext emptyMap()
        if (!httpUrl.isNullOrBlank() && WebNavigationGuard.isHlsPlaylistUrl(httpUrl)) {
            return@withContext emptyMap()
        }

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val rawStats = mutableMapOf<String, MutableCueAudioAccumulator>()

        try {
            if (!httpUrl.isNullOrBlank()) {
                extractor.setDataSource(httpUrl, httpHeaders)
            } else if (uri != null) {
                extractor.setDataSource(context, uri, null)
            }
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = fmt
                    break
                }
            }
            if (audioTrackIndex == -1 || audioFormat == null) {
                return@withContext emptyMap()
            }

            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return@withContext emptyMap()
            val sampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceIn(8000, 96000)
            } else {
                44100
            }
            val channelCount = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 8)
            } else {
                1
            }

            val sortedEvents = events.sortedBy { it.startTimeMs }
            val minTimeMs = (sortedEvents.first().startTimeMs - 250L).coerceAtLeast(0L)
            val maxTimeMs = (sortedEvents.last().endTimeMs + 500L)

            extractor.selectTrack(audioTrackIndex)
            if (minTimeMs > 1500L) {
                extractor.seekTo(minTimeMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(audioFormat, null, null, 0)
                start()
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var nextEventCursor = 0

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(5_000L)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inIdx,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                val ptsUs = extractor.sampleTime
                                codec.queueInputBuffer(inIdx, 0, sampleSize, ptsUs, 0)
                                extractor.advance()
                                if (ptsUs > maxTimeMs * 1000L) {
                                    inputDone = true
                                }
                            }
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 5_000L)
                if (outIdx >= 0) {
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                    val outBuf = codec.getOutputBuffer(outIdx)
                    val ptsMs = bufferInfo.presentationTimeUs / 1000L
                    if (outBuf != null && bufferInfo.size > 0) {
                        while (nextEventCursor < sortedEvents.size && sortedEvents[nextEventCursor].endTimeMs < ptsMs - 200L) {
                            nextEventCursor++
                        }
                        val matchingEvent = sortedEvents.getOrNull(nextEventCursor)
                            ?.takeIf { ptsMs in (it.startTimeMs - 80L)..(it.endTimeMs + 80L) }

                        if (matchingEvent != null) {
                            val acc = rawStats.getOrPut(matchingEvent.id) {
                                MutableCueAudioAccumulator(sampleRate)
                            }
                            if (acc.collectedSamples < sampleRate * 2) {
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                val shortBuf = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                                val mono = FloatArray(shortBuf.remaining() / channelCount)
                                for (i in mono.indices) {
                                    var sum = 0f
                                    for (c in 0 until channelCount) {
                                        sum += shortBuf.get().toFloat() / 32768f
                                    }
                                    mono[i] = sum / channelCount
                                }
                                acc.appendPcm(mono)
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (ptsMs > maxTimeMs && nextEventCursor >= sortedEvents.size) {
                        outputDone = true
                    }
                } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio feature extraction skipped/partial", e)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }

        val initialFeatures = rawStats.mapValues { (_, acc) -> acc.summarize() }
        val clustered = clusterVoiceProfiles(initialFeatures)
        detectVocalEmphasis(clustered)
    }

    /**
     * Groups voiced dialogue windows into lightweight acoustic clusters (Session-local voice_1..4)
     * based on fundamental pitch (Hz) and spectral brightness without storing persistent biometrics.
     */
    fun clusterVoiceProfiles(
        raw: Map<String, AudioVoiceFeatures>,
    ): Map<String, AudioVoiceFeatures> {
        if (raw.isEmpty()) return emptyMap()
        val centroids = mutableListOf<Pair<Float, Float>>() // pitchHz to brightness
        val result = mutableMapOf<String, AudioVoiceFeatures>()

        for ((eventId, feat) in raw) {
            if (feat.pitchHz <= 0f || feat.speechEnergy < 0.04f) {
                result[eventId] = feat.copy(voiceClusterId = null)
                continue
            }
            var bestIdx = -1
            var bestDist = Float.MAX_VALUE
            centroids.forEachIndexed { idx, (cPitch, cBright) ->
                val pitchDiff = abs(cPitch - feat.pitchHz) / 140f
                val brightDiff = abs(cBright - feat.spectralBrightness)
                val dist = pitchDiff * 0.78f + brightDiff * 0.22f
                if (dist < bestDist) {
                    bestDist = dist
                    bestIdx = idx
                }
            }
            val clusterIdx = if (bestIdx != -1 && bestDist < 0.22f) {
                val old = centroids[bestIdx]
                centroids[bestIdx] = Pair(
                    old.first * 0.65f + feat.pitchHz * 0.35f,
                    old.second * 0.65f + feat.spectralBrightness * 0.35f,
                )
                bestIdx
            } else if (centroids.size < 4) {
                centroids.add(feat.pitchHz to feat.spectralBrightness)
                centroids.lastIndex
            } else {
                bestIdx.coerceAtLeast(0)
            }
            result[eventId] = feat.copy(voiceClusterId = "voice_${clusterIdx + 1}")
        }
        return result
    }

    /**
     * Calibrates each cue's vocal intensity against the clip's own conversational median
     * (RMS energy, peak burst amplitude, and vocal brightness) to detect shouted or
     * high-emphasis dialogue reliably across both quiet and loudly mastered videos.
     */
    fun detectVocalEmphasis(
        raw: Map<String, AudioVoiceFeatures>,
    ): Map<String, AudioVoiceFeatures> = Companion.detectVocalEmphasis(raw)

    companion object {
        /**
         * Calibrates each cue's vocal intensity against the clip's own conversational median
         * (RMS energy, peak burst amplitude, and vocal brightness) to detect shouted or
         * high-emphasis dialogue reliably across both quiet and loudly mastered videos.
         */
        fun detectVocalEmphasis(
            raw: Map<String, AudioVoiceFeatures>,
        ): Map<String, AudioVoiceFeatures> {
            if (raw.isEmpty()) return emptyMap()
            val voiced = raw.values.filter { it.speechEnergy > 0.05f }
            val sortedEnergy = voiced.map { it.speechEnergy }.sorted()
            val sortedBright = voiced.map { it.spectralBrightness }.sorted()
            val medianEnergy = sortedEnergy.getOrNull(sortedEnergy.size / 2) ?: 0.45f
            val medianBright = sortedBright.getOrNull(sortedBright.size / 2) ?: 0.48f

            return raw.mapValues { (_, feat) ->
                if (feat.speechEnergy < 0.06f) {
                    feat.copy(emphasisScore = 0f, isShouted = false)
                } else {
                    val effectivePeak = maxOf(feat.peakAmplitude, feat.speechEnergy)
                    val energyRatio = ((feat.speechEnergy - medianEnergy) / medianEnergy.coerceAtLeast(0.18f))
                        .coerceIn(0f, 1.5f) / 1.5f
                    val brightLift = ((feat.spectralBrightness - medianBright) / 0.35f).coerceIn(0f, 1f)
                    val absEnergyScore = ((feat.speechEnergy - 0.32f) / 0.58f).coerceIn(0f, 1f)
                    val peakBurstScore = ((effectivePeak - 0.42f) / 0.50f).coerceIn(0f, 1f)

                    val score = (
                        absEnergyScore * 0.40f +
                            peakBurstScore * 0.28f +
                            energyRatio * 0.20f +
                            brightLift * 0.12f
                        ).coerceIn(0f, 1f)

                    val isShout = (score >= 0.66f && feat.speechEnergy >= 0.62f) ||
                        (effectivePeak >= 0.88f && feat.speechEnergy >= 0.56f && energyRatio >= 0.22f)

                    feat.copy(
                        peakAmplitude = effectivePeak,
                        emphasisScore = score,
                        isShouted = isShout,
                    )
                }
            }
        }

        internal fun pickBestHlsMasterVariantUri(playlistText: String, baseUrl: String): String? {
            val lines = playlistText.replace("\r\n", "\n").lines().map { it.trim() }
            // Prefer dedicated AUDIO rendition URI if present in master playlist
            val audioUriMatch = lines.firstOrNull { line ->
                line.startsWith("#EXT-X-MEDIA:", ignoreCase = true) &&
                    line.contains("TYPE=AUDIO", ignoreCase = true) &&
                    line.contains("URI=", ignoreCase = true)
            }?.let { line ->
                Regex("""URI="([^"]+)"""").find(line)?.groupValues?.getOrNull(1)
            }
            if (!audioUriMatch.isNullOrBlank()) {
                return resolveRelativeUrl(baseUrl, audioUriMatch)
            }

            var bestBandwidth = Long.MAX_VALUE
            var bestUrl: String? = null
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                if (line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true)) {
                    val bw = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: Long.MAX_VALUE
                    var j = i + 1
                    while (j < lines.size && (lines[j].isBlank() || lines[j].startsWith("#"))) {
                        j++
                    }
                    if (j < lines.size) {
                        val cand = resolveRelativeUrl(baseUrl, lines[j])
                        if (bw < bestBandwidth || bestUrl == null) {
                            bestBandwidth = bw
                            bestUrl = cand
                        }
                    }
                    i = j
                } else {
                    i++
                }
            }
            return bestUrl
        }

        internal fun parseHlsMediaPlaylist(playlistText: String, baseUrl: String): HlsMediaIndex? {
            val lines = playlistText.replace("\r\n", "\n").lines().map { it.trim() }
            if (lines.none { it.startsWith("#EXTM3U", ignoreCase = true) }) return null
            var currentInitMap: String? = null
            var nextDurationMs = 0L
            var cursorMs = 0L
            var hasUnsupportedEncryption = false
            val segments = mutableListOf<HlsSegmentEntry>()

            for (line in lines) {
                if (line.isBlank()) continue
                when {
                    line.startsWith("#EXT-X-KEY:", ignoreCase = true) -> {
                        if (!line.contains("METHOD=NONE", ignoreCase = true)) {
                            hasUnsupportedEncryption = true
                        }
                    }
                    line.startsWith("#EXT-X-MAP:", ignoreCase = true) -> {
                        val mapUri = Regex("""URI="([^"]+)"""").find(line)?.groupValues?.getOrNull(1)
                        if (!mapUri.isNullOrBlank()) {
                            currentInitMap = resolveRelativeUrl(baseUrl, mapUri)
                        }
                    }
                    line.startsWith("#EXTINF:", ignoreCase = true) -> {
                        val durSec = line.removePrefix("#EXTINF:").substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                        nextDurationMs = (durSec * 1000.0).toLong().coerceAtLeast(100L)
                    }
                    line.startsWith("#") -> Unit
                    else -> {
                        val resolved = resolveRelativeUrl(baseUrl, line)
                        val segDur = if (nextDurationMs > 0L) nextDurationMs else 4_000L
                        segments.add(
                            HlsSegmentEntry(
                                url = resolved,
                                startMs = cursorMs,
                                endMs = cursorMs + segDur,
                                initMapUrl = currentInitMap,
                            )
                        )
                        cursorMs += segDur
                        nextDurationMs = 0L
                    }
                }
            }
            return HlsMediaIndex(
                playlistUrl = baseUrl,
                segments = segments,
                totalDurationMs = cursorMs,
                encrypted = hasUnsupportedEncryption,
            )
        }

        internal fun resolveRelativeUrl(baseUrl: String, relativeOrAbsolute: String): String {
            val trimmed = relativeOrAbsolute.trim()
            if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
            ) {
                return trimmed
            }
            return runCatching {
                URI(baseUrl).resolve(trimmed).toString()
            }.getOrDefault(trimmed)
        }
    }

    private class MutableCueAudioAccumulator(private val sampleRate: Int) {
        var collectedSamples = 0
        private var sumSq = 0.0
        private var zeroCrossings = 0
        private var prevSign = 0
        private var peakSample = 0f
        private var subFrameSq = 0.0
        private var subFrameCount = 0
        private var maxSubFrameRms = 0f
        private val pitchBuffer = FloatArray(2048)
        private var pitchBufferFill = 0

        fun appendPcm(mono: FloatArray) {
            for (sample in mono) {
                collectedSamples++
                val absSample = abs(sample)
                if (absSample > peakSample) peakSample = absSample
                sumSq += (sample * sample).toDouble()
                subFrameSq += (sample * sample).toDouble()
                subFrameCount++
                if (subFrameCount >= 512) {
                    val frameRms = sqrt(subFrameSq / subFrameCount).toFloat()
                    if (frameRms > maxSubFrameRms) maxSubFrameRms = frameRms
                    subFrameSq = 0.0
                    subFrameCount = 0
                }
                val sign = if (sample >= 0f) 1 else -1
                if (prevSign != 0 && sign != prevSign) zeroCrossings++
                prevSign = sign
                if (pitchBufferFill < pitchBuffer.size) {
                    pitchBuffer[pitchBufferFill++] = sample
                }
            }
        }

        fun summarize(): AudioVoiceFeatures {
            if (collectedSamples == 0) return AudioVoiceFeatures()
            if (subFrameCount > 64) {
                val tailRms = sqrt(subFrameSq / subFrameCount).toFloat()
                if (tailRms > maxSubFrameRms) maxSubFrameRms = tailRms
            }
            val rms = sqrt(sumSq / collectedSamples).toFloat()
            val energy = (rms * 5.2f).coerceIn(0f, 1f)
            val burstPeak = maxOf((maxSubFrameRms * 4.4f).coerceIn(0f, 1f), (peakSample * 1.05f).coerceIn(0f, 1f))
            val zcrRate = zeroCrossings.toFloat() / collectedSamples.toFloat()
            val brightness = (zcrRate * 3.8f).coerceIn(0f, 1f)
            val pitchHz = if (energy > 0.06f && pitchBufferFill >= 512) {
                estimatePitchHz(pitchBuffer, pitchBufferFill, sampleRate)
            } else {
                0f
            }
            return AudioVoiceFeatures(
                pitchHz = pitchHz,
                spectralBrightness = brightness,
                speechEnergy = energy,
                peakAmplitude = burstPeak,
            )
        }

        private fun estimatePitchHz(samples: FloatArray, len: Int, sr: Int): Float {
            val minLag = (sr / 340).coerceAtLeast(16)
            val maxLag = (sr / 75).coerceAtMost(len / 2 - 1)
            if (maxLag <= minLag) return 0f

            var bestLag = -1
            var bestCorr = 0f
            for (lag in minLag..maxLag) {
                var num = 0f
                var denomA = 0f
                var denomB = 0f
                val limit = len - lag
                for (i in 0 until limit) {
                    val a = samples[i]
                    val b = samples[i + lag]
                    num += a * b
                    denomA += a * a
                    denomB += b * b
                }
                val denom = sqrt((denomA * denomB).coerceAtLeast(1e-8f))
                val normCorr = num / denom
                if (normCorr > bestCorr) {
                    bestCorr = normCorr
                    bestLag = lag
                }
            }
            return if (bestLag > 0 && bestCorr > 0.35f) {
                sr.toFloat() / bestLag.toFloat()
            } else {
                0f
            }
        }
    }
}
