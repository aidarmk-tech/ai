package com.lampplayer.tv.engine

import android.media.MediaDataSource
import android.os.Build
import androidx.annotation.RequiresApi
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit

/** Bounded, seekable HTTP input. Called only by the demux thread, never the UI/renderer. */
@RequiresApi(Build.VERSION_CODES.M)
internal class LampCoreHttpSource(url: String, headers: Map<String, String>) : MediaDataSource() {
    private val reader = LampCoreRangeReader(url, headers)
    val downloadedBytes: Long get() = reader.downloadedBytes
    val lastError: String? get() = reader.lastError
    override fun getSize(): Long = reader.size()
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int = reader.readAt(position, buffer, offset, size)
    override fun close() = reader.close()
}

/** Pure HTTP reader, kept independent of Android for protocol/seek tests. */
internal class LampCoreRangeReader(
    private val url: String,
    private val headers: Map<String, String>,
) : java.io.Closeable {
    companion object {
        private const val BLOCK_BYTES = 128 * 1024
        private const val CACHE_BLOCKS = 16 // 2 MiB; no whole-film downloads
        private val contentRange = Regex("bytes (\\d+)-(\\d+)/(\\d+)")
        private val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val cache = object : LinkedHashMap<Long, ByteArray>(CACHE_BLOCKS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean =
            size > CACHE_BLOCKS
    }
    @Volatile private var closed = false
    @Volatile private var activeCall: Call? = null
    @Volatile private var length = -1L
    @Volatile var lastError: String? = null
        private set
    @Volatile var downloadedBytes = 0L
        private set
    @Volatile var networkReads = 0L
        private set

    fun size(): Long {
        if (length < 0) block(0)
        return length
    }

    @Synchronized
    fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (closed) throw IOException("Источник закрыт")
        require(position >= 0 && offset >= 0 && size >= 0 && offset <= buffer.size - size)
        if (size == 0) return 0
        if (length >= 0 && position >= length) return -1
        val start = position / BLOCK_BYTES * BLOCK_BYTES
        val bytes = block(start)
        val within = (position - start).toInt()
        if (within >= bytes.size) return -1
        val count = minOf(size, bytes.size - within)
        System.arraycopy(bytes, within, buffer, offset, count)
        return count
    }

    private fun block(start: Long): ByteArray {
        if (closed) throw IOException("Источник закрыт")
        cache[start]?.let { return it }
        val end = if (length >= 0) minOf(start + BLOCK_BYTES - 1, length - 1) else start + BLOCK_BYTES - 1
        if (end < start) return ByteArray(0)
        val builder = Request.Builder().url(url)
        headers.forEach { (name, value) ->
            if (!name.equals("Range", true) && !name.equals("Accept-Encoding", true)) builder.header(name, value)
        }
        if (headers.keys.none { it.equals("User-Agent", true) }) builder.header("User-Agent", "LampPlayer/LampCore")
        builder.header("Accept-Encoding", "identity").header("Range", "bytes=$start-$end")
        val call = client.newCall(builder.build())
        activeCall = call
        // close() can race between construction and assignment; never start a cancelled session.
        if (closed) { call.cancel(); throw IOException("Источник закрыт") }
        try {
            call.execute().use { response ->
                if (response.code != 206) throw IOException(
                    if (response.code == 200) "Сервер не поддерживает HTTP Range; выберите ExoPlayer/libVLC"
                    else "HTTP ${response.code} при чтении видео",
                )
                val match = contentRange.matchEntire(response.header("Content-Range").orEmpty())
                    ?: throw IOException("Некорректный Content-Range")
                val first = match.groupValues[1].toLong()
                val last = match.groupValues[2].toLong()
                val total = match.groupValues[3].toLong()
                if (first != start || last < first || last > end || total <= last || (length >= 0 && length != total))
                    throw IOException("Сервер вернул неверный диапазон видео")
                length = total
                val bytes = ByteArray((last - first + 1).toInt())
                val body = response.body ?: throw IOException("Пустой ответ сервера")
                body.byteStream().use { input ->
                    var read = 0
                    while (read < bytes.size) {
                        if (closed) throw IOException("Источник закрыт")
                        val count = input.read(bytes, read, bytes.size - read)
                        if (count < 0) throw IOException("Оборванный диапазон видео")
                        read += count
                        downloadedBytes += count
                    }
                }
                networkReads++
                cache[start] = bytes
                return bytes
            }
        } catch (e: IOException) {
            // Only our own protocol messages are exposed; transport errors can contain URLs.
            lastError = if (e.message?.startsWith("Сервер") == true || e.message?.startsWith("HTTP ") == true || e.message?.startsWith("Некорректный") == true || e.message?.startsWith("Оборванный") == true) e.message else "Соединение прервано или истёк таймаут"
            throw e
        } finally {
            activeCall = null
        }
    }

    override fun close() {
        // No synchronized lock: interrupt an in-progress readAt immediately.
        closed = true
        activeCall?.cancel()
    }
}
