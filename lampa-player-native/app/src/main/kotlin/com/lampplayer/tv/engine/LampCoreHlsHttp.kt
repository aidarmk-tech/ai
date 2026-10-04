package com.lampplayer.tv.engine

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class HlsResolved(val video: HlsPlaylist, val audio: HlsPlaylist?, val label: String,
    val master: HlsPlaylist? = null, val variant: HlsVariant? = null, val audios: List<HlsAudio> = emptyList(), val selectedAudioId: Int = -1)

/** HLS does not require a server to support Range except EXT-X-BYTERANGE resources. */
internal class LampCoreHlsHttp(private val headers: Map<String, String>, private val retryPolicy: HlsRetryPolicy = HlsRetryPolicy()) : java.io.Closeable {
    companion object {
        private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
        private const val PLAYLIST_LIMIT = 1024 * 1024
        private const val INIT_LIMIT = 2 * 1024 * 1024
        private const val SEGMENT_LIMIT = 16 * 1024 * 1024
    }
    private val calls = Collections.newSetFromMap(ConcurrentHashMap<Call, Boolean>())
    private val keys = object : LinkedHashMap<String, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = size > 8
    }
    private val inits = object : LinkedHashMap<HlsInit, ByteArray>(2, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<HlsInit, ByteArray>?): Boolean = size > 2
    }
    private val downloaded = AtomicLong()
    val downloadedBytes: Long get() = downloaded.get()
    @Volatile private var closed = false
    private val recovering = java.util.concurrent.atomic.AtomicInteger()
    val isRecovering: Boolean get() = recovering.get() > 0
    val recoveries = AtomicLong()
    private fun <T> retry(task: () -> T): T {
        var entered = false
        try { return retryPolicy.run({ !closed && !Thread.currentThread().isInterrupted }, {
            recoveries.incrementAndGet()
            if (!entered) { entered = true; recovering.incrementAndGet() }
        }, task) } finally { if (entered) recovering.decrementAndGet() }
    }

    /** Sniff extensionless balancer URLs as well as explicit .m3u8 links. */
    fun probe(url: String): HlsPlaylist? = retry { withResponse(url, null) { response ->
        val body = response.body ?: throw HlsException("Пустой ответ HLS")
        body.byteStream().use { input ->
            val prefix = ByteArray(64)
            var count = 0
            while (count < prefix.size) { val n = input.read(prefix, count, prefix.size - count); if (n < 0) break; count += n }
            downloaded.addAndGet(count.toLong())
            val header = String(prefix, 0, count, Charsets.UTF_8).removePrefix("\uFEFF").trimStart()
            val explicit = response.request.url.encodedPath.lowercase().endsWith(".m3u8") || response.header("Content-Type").orEmpty().contains("mpegurl", true)
            if (!explicit && !header.startsWith("#EXTM3U")) return@withResponse null
            val output = ByteArrayOutputStream().apply { write(prefix, 0, count) }
            copy(input, output, PLAYLIST_LIMIT - count)
            LampCoreHlsPlaylist.parse(response.request.url.toString(), output.toString("UTF-8"))
        }
    } }

    fun playlist(url: String): HlsPlaylist = retry { withResponse(url, null) { response ->
        val out = ByteArrayOutputStream()
        response.body?.byteStream()?.use { copy(it, out, PLAYLIST_LIMIT) } ?: throw HlsException("Пустой HLS-плейлист")
        LampCoreHlsPlaylist.parse(response.request.url.toString(), out.toString("UTF-8"))
    } }

    fun text(url: String, limit: Int = 2 * 1024 * 1024): String = retry { withResponse(url, null) { response ->
        val out = ByteArrayOutputStream()
        response.body?.byteStream()?.use { copy(it, out, limit) } ?: throw HlsException("Пустые субтитры")
        out.toString("UTF-8")
    } }

    fun resolve(initial: HlsPlaylist, preferredVariant: String? = null, audioId: Int = -1): HlsResolved {
        var current = initial
        var audio: HlsAudio? = null
        var label = "HLS"
        var master: HlsPlaylist? = null
        var chosen: HlsVariant? = null
        var choices = emptyList<HlsAudio>()
        var selectedId = -1
        val visited = mutableSetOf<String>()
        repeat(5) {
            if (!visited.add(current.uri)) throw HlsException("Циклический HLS master")
            if (current.variants.isEmpty()) return HlsResolved(current, audio?.uri?.let { playlist(it) }, label, master, chosen, choices, selectedId)
            master = current
            val variant = current.variants.firstOrNull { it.uri == preferredVariant } ?: current.initialVariant()
            chosen = variant
            label = "HLS ${if (variant.height > 0) "${variant.height}p" else ""} ${(variant.bandwidth / 1000)}kbps"
            choices = current.audios.filter { it.group == variant.audioGroup && it.uri != null }
            if (choices.isNotEmpty()) {
                audio = choices.getOrNull(audioId - 10_000) ?: choices.firstOrNull { it.language?.startsWith("ru", true) == true }
                    ?: choices.firstOrNull { it.default } ?: choices.first()
                selectedId = 10_000 + choices.indexOf(audio)
            }
            current = playlist(variant.uri)
        }
        throw HlsException("Слишком много вложенных HLS master")
    }

    fun segment(segment: HlsSegment, directory: File): File {
        if (closed) throw HlsException("HLS закрыт")
        val file = File.createTempFile("segment-", ".bin", directory)
        try {
            return retry {
                    FileOutputStream(file).use { out ->
                        segment.init?.let { init ->
                            val data = synchronized(inits) { inits[init] } ?: ByteArrayOutputStream().also {
                                resource(init.uri, init.range, init.key, 0, it, INIT_LIMIT)
                            }.toByteArray().also { synchronized(inits) { inits[init] = it } }
                            out.write(data)
                        }
                        resource(segment.uri, segment.range, segment.key, segment.sequence, out, SEGMENT_LIMIT)
                    }
                    file
            }
        } catch (e: Exception) { file.delete(); throw e }
    }

    private fun resource(url: String, range: HlsRange?, key: HlsKey?, sequence: Long, output: OutputStream, limit: Int) {
        val cipher = key?.let {
            val secret = synchronized(keys) { keys[it.uri] } ?: withResponse(it.uri, null) { response ->
                val out = ByteArrayOutputStream()
                response.body?.byteStream()?.use { stream -> copy(stream, out, 16) } ?: throw HlsException("Пустой ключ HLS")
                out.toByteArray().also { bytes -> if (bytes.size != 16) throw HlsException("Ключ AES-128 должен содержать 16 байт") }
            }.also { bytes -> synchronized(keys) { keys[it.uri] = bytes } }
            Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(secret, "AES"), IvParameterSpec(it.iv(sequence))) }
        }
        withResponse(url, range) { response ->
            val body = response.body ?: throw HlsException("Пустой HLS-сегмент")
            if (body.contentLength() > limit + 16L) throw HlsException("HLS-сегмент превышает лимит буфера")
            body.byteStream().use { raw ->
                val input = if (cipher == null) raw else CipherInputStream(raw, cipher)
                input.use { copy(it, output, limit) }
            }
        }
    }

    private fun copy(input: InputStream, output: OutputStream, limit: Int) {
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            if (closed || Thread.currentThread().isInterrupted) throw HlsException("HLS закрыт")
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > limit) throw HlsException("HLS-ресурс превышает лимит буфера")
            downloaded.addAndGet(n.toLong()); output.write(buffer, 0, n)
        }
    }

    private fun <T> withResponse(url: String, range: HlsRange?, action: (Response) -> T): T {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> if (!k.equals("Range", true) && !k.equals("Accept-Encoding", true)) builder.header(k, v) }
        if (headers.keys.none { it.equals("User-Agent", true) }) builder.header("User-Agent", "LampPlayer/LampCore")
        builder.header("Accept-Encoding", "identity")
        range?.let { builder.header("Range", "bytes=${it.offset}-${it.offset + it.length - 1}") }
        val call = client.newCall(builder.build())
        calls.add(call)
        if (closed) call.cancel()
        try {
            call.execute().use { response ->
                if (response.code in listOf(408, 429) || response.code >= 500) throw java.io.IOException("Временный сбой HTTP HLS")
                if (response.code == 404 || response.code == 410) throw HlsMissingException(response.code)
                if (range == null && response.code != 200) throw HlsException("HTTP ${response.code} при загрузке HLS")
                if (range != null) {
                    if (response.code != 206) throw HlsException("Сервер не выполнил HLS BYTERANGE")
                    val m = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.header("Content-Range").orEmpty())
                        ?: throw HlsException("Некорректный Content-Range HLS")
                    if (m.groupValues[1].toLong() != range.offset || m.groupValues[2].toLong() != range.offset + range.length - 1)
                        throw HlsException("Неверный диапазон HLS-сегмента")
                }
                return action(response)
            }
        } finally { calls.remove(call) }
    }

    override fun close() { closed = true; calls.forEach { it.cancel() } }
}
