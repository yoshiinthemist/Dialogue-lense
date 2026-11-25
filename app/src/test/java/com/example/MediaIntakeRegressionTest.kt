package com.example

import com.example.data.ai.withModelDeadline
import com.example.data.subtitle.SubtitleParser
import com.example.domain.analysis.AudioAnalyzer
import com.example.domain.media.YouTubeMediaAdapter
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MediaIntakeRegressionTest {
    @Test fun onlyYouTubeLinksRouteToTheYouTubePlayer() {
        val adapter = YouTubeMediaAdapter()
        val id = "RzagPoxaolQ"
        for (url in listOf(id, "https://youtu.be/$id?is=Q4GpsxfsZt7h3S1q",
            "https://m.youtube.com/watch?feature=share&v=$id", "https://youtube.com/shorts/$id",
            "https://www.youtube-nocookie.com/embed/$id")) {
            assertEquals(url, id, adapter.extractVideoId(url))
        }
        for (url in listOf("https://example.org/$id", "https://example.org/watch?v=$id",
            "https://example.org/?next=https://youtu.be/$id", "https://youtu.be/${id}extra")) {
            assertNull(url, adapter.extractVideoId(url))
        }
    }

    @Test fun authoredRapidCuesRetainTheirBoundaries() {
        val cues = SubtitleParser.parse("1\n00:00:01,000 --> 00:00:01,140\nWait!\n\n2\n00:00:01,150 --> 00:00:01,280\nGo!\n", "rapid.srt")
        assertEquals(listOf(1000L to 1140L, 1150L to 1280L), cues.map { it.startTimeMs to it.endTimeMs })
    }

    @Test fun modelRequestTimeoutAllowsRecoveryButSessionCancellationPropagates() = runTest {
        val error = runCatching { withModelDeadline(10) { delay(30); "late" } }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals("retry", withModelDeadline(10) { "retry" })
        val cancellation = CancellationException("session replaced")
        val thrown = runCatching { withModelDeadline<String>(10) { throw cancellation } }.exceptionOrNull()
        assertSame(cancellation, thrown)
    }

    @Test fun playlistChildrenCarryCredentialsOnlyOnTheSameOrigin() {
        val url = "https://media.example/video/index.m3u8"
        val headers = mapOf("User-Agent" to "test", "Cookie" to "cookie", "Referer" to url)
        assertEquals(headers, AudioAnalyzer.headersForMediaRequest(url, "https://media.example:443/audio/chunk.ts", headers))
        for (other in listOf("https://cdn.example/chunk", "https://media.example:8080/chunk", "http://media.example/chunk")) {
            assertEquals(mapOf("User-Agent" to "test"), AudioAnalyzer.headersForMediaRequest(url, other, headers))
        }
    }
}
