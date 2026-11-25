package com.example

import android.content.res.AssetManager
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** A real, device-local HTTP origin for both Media3 and WebView. Supports range requests for seeks. */
internal class FixtureMediaServer(assets: AssetManager) : Closeable {
    private val video = assets.open("playback/two-lines.mp4").use { it.readBytes() }
    private val vtt = assets.open("playback/two-lines.srt").bufferedReader().use { reader ->
        ("WEBVTT\n\n" + reader.readText().replace(Regex("(\\d{2}:\\d{2}:\\d{2}),(\\d{3})"), "$1.$2")).toByteArray()
    }
    private val html = """
        <!doctype html><html lang="en"><title>Two spoken lines</title>
        <video controls muted playsinline preload="auto" width="320" height="180" src="/clip.mp4">
        <track src="/lines.vtt" kind="captions" srclang="en" label="English" default>
        </video></html>
    """.trimIndent().toByteArray()

    private val listeners = Executors.newCachedThreadPool()
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val origin = "http://127.0.0.1:${server.localPort}"
    val requested = ConcurrentHashMap.newKeySet<String>()

    init {
        listeners.submit {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (_: java.io.IOException) { break }
                listeners.submit { client.use { runCatching { serve(it) } } }
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 5_000
        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
        val request = reader.readLine()?.split(" ") ?: return
        val path = request.getOrNull(1)?.substringBefore('?') ?: return
        val headers = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }
            .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        val out = socket.getOutputStream()
        val (body, mime) = when (path) {
            "/clip.mp4" -> video to "video/mp4"
            "/lines.vtt" -> vtt to "text/vtt"
            "/watch" -> html to "text/html; charset=utf-8"
            else -> {
                out.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray())
                return
            }
        }
        requested.add(path)
        val range = headers["range"]?.removePrefix("bytes=")
        val start = range?.substringBefore('-')?.toIntOrNull() ?: 0
        val end = range?.substringAfter('-')?.toIntOrNull()?.coerceAtMost(body.lastIndex) ?: body.lastIndex
        if (start !in 0..end) {
            out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */${body.size}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            return
        }
        val response = buildString {
            append(if (range == null) "HTTP/1.1 200 OK\r\n" else "HTTP/1.1 206 Partial Content\r\n")
            if (range != null) append("Content-Range: bytes $start-$end/${body.size}\r\n")
            append("Content-Type: $mime\r\nAccept-Ranges: bytes\r\nContent-Length: ${end - start + 1}\r\nConnection: close\r\n\r\n")
        }
        out.write(response.toByteArray(Charsets.US_ASCII))
        if (request.first() != "HEAD") out.write(body, start, end - start + 1)
    }

    override fun close() {
        server.close()
        listeners.shutdownNow()
    }
}
