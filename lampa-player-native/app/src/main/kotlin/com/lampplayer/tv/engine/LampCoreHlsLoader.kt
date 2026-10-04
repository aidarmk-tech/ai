package com.lampplayer.tv.engine

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Own HLS timeline, live reload, seek and one-segment lookahead per rendition. */
@UnstableApi
internal class LampCoreHlsLoader(
    private val http: LampCoreHlsHttp,
    private val initial: HlsPlaylist,
    cacheDirectory: File,
    private val startUs: Long,
    private val output: HlsPacketOutput,
    private val metadata: (HlsResolved, Long) -> Unit,
    private val onError: (Exception) -> Unit,
    private val active: () -> Boolean,
) : Closeable {
    private val directory = File(cacheDirectory, "lampcore-hls-${java.util.UUID.randomUUID()}")
    private val downloads = Executors.newFixedThreadPool(2) { r -> Thread(r, "LampCore-HLS-fetch") }
    private val adjusters = ConcurrentHashMap<Long, TimestampAdjuster>()
    private val passedGroups = ConcurrentHashMap<Boolean, Long>()
    private val failure = AtomicReference<Exception?>()
    @Volatile private var closed = false
    @Volatile private var audioThread: Thread? = null
    val downloadedBytes: Long get() = http.downloadedBytes

    private fun running() = !closed && active() && failure.get() == null
    private fun fail(e: Exception) { if (!closed && failure.compareAndSet(null, e)) { onError(e); http.close() } }

    fun read() {
        try {
            if (!directory.mkdirs()) throw HlsException("Не удалось создать буфер HLS")
            val resolved = http.resolve(initial)
            val first = resolved.video.segments
            // Leave three target durations behind the live edge. VOD starts at the requested position.
            val begin = if (startUs > 0 || resolved.video.endList) startUs else
                (first.last().startUs + first.last().durationUs - resolved.video.targetUs * 3).coerceAtLeast(first.first().startUs)
            metadata(resolved, begin)
            resolved.audio?.let { playlist ->
                audioThread = Thread({
                    try { stream(playlist, begin, true, resolved.audio != null) }
                    catch (e: Exception) { if (running()) fail(e) }
                }, "LampCore-HLS-audio").also { it.start() }
            }
            stream(resolved.video, begin, false, resolved.audio != null)
            while (running() && audioThread?.isAlive == true) audioThread?.join(50)
            failure.get()?.let { throw it }
        } finally { close() }
    }

    private fun stream(initial: HlsPlaylist, begin: Long, audioOnly: Boolean, externalAudio: Boolean) {
        var playlist = initial
        val timeline = HlsTimeline(initial)
        var sequence = playlist.segments.firstOrNull { it.startUs + it.durationUs > begin }?.sequence
            ?: playlist.segments.last().sequence
        var extractor: LampCoreHlsExtractor? = null
        var group = Long.MIN_VALUE
        var init: HlsInit? = null
        var pending: Future<File>? = null
        val bridge = object : HlsPacketOutput {
            private val firstIds = mutableMapOf<Int, Int>()
            fun reset() { firstIds.clear() }
            private fun keep(type: Int) = if (audioOnly) type == C.TRACK_TYPE_AUDIO else type == C.TRACK_TYPE_VIDEO || !externalAudio
            private fun selected(nativeId: Int, type: Int): Boolean = firstIds.getOrPut(type) { nativeId } == nativeId
            private fun logicalId(type: Int) = if (type == C.TRACK_TYPE_VIDEO) 0 else 1
            override fun format(id: Int, type: Int, format: Format) { if (keep(type) && selected(id, type)) output.format(logicalId(type), type, format) }
            override fun packet(id: Int, type: Int, bytes: ByteArray, timeUs: Long, flags: Int) {
                if (keep(type) && selected(id, type)) output.packet(logicalId(type), type, bytes, timeUs, flags)
            }
            override fun endTracks(audioPresent: Boolean) {
                if (audioOnly && !audioPresent) throw HlsException("В AUDIO rendition нет аудиодорожки")
                if (!audioOnly) output.endTracks(externalAudio || audioPresent)
            }
        }
        try {
            while (running()) {
                val index = playlist.segments.indexOfFirst { it.sequence == sequence }
                if (index < 0) {
                    if (playlist.endList) return
                    if (sequence < playlist.segments.first().sequence) throw HlsException("Отставание от live-окна HLS; переподключитесь к эфиру")
                    var remaining = (playlist.targetUs / 2000).coerceIn(250, 5000)
                    while (running() && remaining > 0) { val sleep = minOf(remaining, 50); Thread.sleep(sleep); remaining -= sleep }
                    if (!running()) return
                    playlist = timeline.update(http.playlist(playlist.uri))
                    continue
                }
                val segment = playlist.segments[index]
                if (pending == null) pending = downloads.submit<File> { http.segment(segment, directory) }
                val file = try { pending!!.get() } catch (e: java.util.concurrent.ExecutionException) { throw (e.cause as? Exception ?: e) }
                pending = null
                try {
                    // Start the next network request while this segment is being demuxed/queued.
                    playlist.segments.getOrNull(index + 1)?.let { next ->
                        pending = downloads.submit<File> { http.segment(next, directory) }
                    }
                    if (group != segment.discontinuity || init != segment.init || extractor == null) {
                        extractor?.close()
                        bridge.reset()
                        val adjuster = if (!audioOnly) adjusters[segment.discontinuity] ?: TimestampAdjuster(segment.startUs).let {
                            adjusters.putIfAbsent(segment.discontinuity, it) ?: it
                        }
                        else {
                            while (running() && adjusters[segment.discontinuity]?.timestampOffsetUs.let { it == null || it == C.TIME_UNSET }) Thread.sleep(5)
                            if (!running()) return
                            adjusters[segment.discontinuity] ?: throw HlsException("Нет временной базы HLS-аудио")
                        }
                        extractor = LampCoreHlsExtractor(adjuster, bridge, audioOnly, ::running)
                        group = segment.discontinuity; init = segment.init
                        // Old discontinuity clocks are no longer needed after both readers passed them.
                        passedGroups[audioOnly] = group
                        val oldest = if (externalAudio) minOf(passedGroups[false] ?: Long.MIN_VALUE, passedGroups[true] ?: Long.MIN_VALUE) else group
                        adjusters.keys.filter { it < oldest }.forEach { adjusters.remove(it) }
                    }
                    extractor!!.read(file, segment)
                    sequence++
                } finally { file.delete() }
            }
        } finally {
            extractor?.close()
            // An in-flight fetch may finish after cancellation; delete its result on completion.
            pending?.let { future ->
                if (future.isDone && !future.isCancelled) runCatching { future.get().delete() }
                else future.cancel(true)
            }
        }
    }

    fun cancel() {
        closed = true; http.close(); audioThread?.interrupt(); downloads.shutdownNow()
    }

    override fun close() {
        cancel()
        // Called by the demux owner, never by the UI. Bound joins before deleting temporary files.
        if (Thread.currentThread() !== audioThread) runCatching { audioThread?.join(500) }
        runCatching { downloads.awaitTermination(500, TimeUnit.MILLISECONDS) }
        directory.deleteRecursively()
    }
}
