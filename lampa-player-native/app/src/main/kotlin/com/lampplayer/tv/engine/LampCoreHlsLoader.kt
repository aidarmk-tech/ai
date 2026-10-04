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

/** Own HLS retries, timeline, variant selection and bounded rendition lookahead. */
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
    private val control: HlsControl = HlsControl(),
    private val requestRestart: (Long) -> Unit = { throw HlsWindowException("Нужно восстановить live-окно") },
) : Closeable {
    private val directory = File(cacheDirectory, "lampcore-hls-${java.util.UUID.randomUUID()}")
    private val downloads = Executors.newFixedThreadPool(2) { r -> Thread(r, "LampCore-HLS-fetch") }
    private val adjusters = ConcurrentHashMap<Long, TimestampAdjuster>()
    private val passedGroups = ConcurrentHashMap<Boolean, Long>()
    private val failure = AtomicReference<Exception?>()
    @Volatile private var closed = false
    @Volatile private var restarting = false
    @Volatile private var videoFinished = false
    @Volatile private var audioThread: Thread? = null
    private lateinit var resolved: HlsResolved
    private val blockedVariants = mutableMapOf<String, Long>()
    val downloadedBytes: Long get() = http.downloadedBytes
    private fun running() = !closed && !restarting && active() && failure.get() == null
    private fun fail(e: Exception) { if (!closed && failure.compareAndSet(null, e)) { onError(e); http.close() } }
    private fun restart(positionUs: Long, reason: String) {
        control.restartReason = reason; control.recoveries++
        restarting = true; requestRestart(positionUs); http.close()
    }

    fun read() {
        try {
            if (!directory.mkdirs()) throw HlsException("Не удалось создать буфер HLS")
            resolved = http.resolve(initial, control.variantUri, control.audioId)
            resolved.variant?.let { control.variantUri = it.uri }
            val first = resolved.video.segments
            val begin = if (startUs > 0 || resolved.video.endList) startUs else
                (first.last().startUs + first.last().durationUs - resolved.video.targetUs * 3).coerceAtLeast(first.first().startUs)
            metadata(resolved, begin)
            resolved.audio?.let { playlist ->
                audioThread = Thread({
                    try { stream(playlist, begin, true, true) }
                    catch (e: Exception) { if (running()) fail(e) }
                }, "LampCore-HLS-audio").also { it.start() }
            }
            try { stream(resolved.video, begin, false, resolved.audio != null) }
            finally { videoFinished = true }
            while (running() && audioThread?.isAlive == true) audioThread?.join(50)
            failure.get()?.let { throw it }
        } finally { close() }
    }

    /** Cancellation owns the eventual file even when Future.cancel discards its return value. */
    private inner class Download(segment: HlsSegment, video: Boolean) {
        private var cancelled = false
        private var file: File? = null
        val future: Future<File> = downloads.submit<File> {
            val began = System.nanoTime()
            val result = http.segment(segment, directory)
            if (video) control.abr.sample(result.length(), System.nanoTime() - began)
            synchronized(this) { if (cancelled) result.delete() else file = result }
            result
        }
        fun take(): File {
            val result = try { future.get() } catch (e: java.util.concurrent.ExecutionException) { throw (e.cause as? Exception ?: e) }
            synchronized(this) { file = null }
            return result
        }
        fun cancel() { synchronized(this) { cancelled = true; future.cancel(true); file?.delete(); file = null } }
    }

    private fun switchVariant(playlist: HlsPlaylist, boundaryUs: Long, cachedAheadUs: Long): HlsPlaylist? {
        val current = resolved.variant ?: return null
        val master = resolved.master ?: return null
        if (!control.ready) return null
        val now = System.nanoTime() / 1_000_000
        val variants = master.variants.filter { it.audioGroup == current.audioGroup && it.height <= 1080 && it.bandwidth <= 8_000_000 &&
            control.supportedSize(it) && (it.videoMime == null || it.videoMime == control.videoMime) && (blockedVariants[it.uri] ?: 0) <= now }
        val next = control.abr.choose(variants, current, maxOf(control.bufferedUs(), cachedAheadUs))
        if (next.uri == current.uri) return null
        val aligned = try { HlsAlignment.switchAt(playlist, boundaryUs, http.candidatePlaylist(next.uri)) }
        catch (e: Exception) { if (!running()) throw e; null }
        if (aligned == null) { blockedVariants[next.uri] = now + 60_000; return null }
        control.variantUri = next.uri; control.switches++
        resolved = resolved.copy(video = aligned, variant = next, label = "HLS AUTO ${next.height}p ${next.bandwidth / 1000}kbps")
        metadata(resolved, boundaryUs)
        if (!control.adaptive) { restart(control.positionUs(), "Смена качества: перенастройка декодера"); return null }
        return aligned
    }

    private fun stream(initial: HlsPlaylist, begin: Long, audioOnly: Boolean, externalAudio: Boolean) {
        var playlist = initial
        var timeline = HlsTimeline(initial)
        var sequence = playlist.segments.firstOrNull { it.startUs + it.durationUs > begin }?.sequence ?: playlist.segments.last().sequence
        var extractor: LampCoreHlsExtractor? = null
        var group = Long.MIN_VALUE
        var init: HlsInit? = null
        var pending: Download? = null
        var missingSinceMs = 0L
        val bridge = object : HlsPacketOutput {
            private var firstVideo: Int? = null
            private var firstAudio: Int? = null
            fun reset() { firstVideo = null; firstAudio = null }
            private fun keep(id: Int, type: Int): Boolean {
                if (type == C.TRACK_TYPE_VIDEO) { if (firstVideo == null) firstVideo = id; return !audioOnly && firstVideo == id }
                if (type == C.TRACK_TYPE_AUDIO && audioOnly) { if (firstAudio == null) firstAudio = id; return id == firstAudio }
                return type == C.TRACK_TYPE_AUDIO && !externalAudio
            }
            private fun logicalId(id: Int, type: Int) = if (type == C.TRACK_TYPE_VIDEO) 0 else if (audioOnly) 1 else 100 + id
            override fun format(id: Int, type: Int, format: Format) { if (keep(id, type)) output.format(logicalId(id, type), type, format) }
            override fun packet(id: Int, type: Int, bytes: ByteArray, timeUs: Long, flags: Int) {
                if (!audioOnly && type == C.TRACK_TYPE_VIDEO) {
                    adjusters[group]?.timestampOffsetUs?.takeIf { it != C.TIME_UNSET }?.let { control.timestampOffsets[group] = it }
                    control.timestampOffsets.keys.filter { it < group - 8 }.forEach { control.timestampOffsets.remove(it) }
                }
                if (keep(id, type)) output.packet(logicalId(id, type), type, bytes, timeUs, flags)
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
                    if (sequence < playlist.segments.first().sequence) { restart(0, "Восстановлено live-окно"); return }
                    waitReload(playlist)
                    if (!running()) return
                    try { playlist = timeline.update(http.playlist(playlist.uri)) }
                    catch (_: HlsWindowException) { restart(0, "Восстановлена непрерывность live"); return }
                    continue
                }
                val segment = playlist.segments[index]
                if (pending == null) pending = Download(segment, !audioOnly)
                val file = try { pending!!.take() } catch (e: HlsMissingException) {
                    pending?.cancel(); pending = null
                    if (playlist.endList) throw e
                    val nowMs = System.nanoTime() / 1_000_000
                    if (missingSinceMs == 0L) missingSinceMs = nowMs
                    if (nowMs - missingSinceMs > maxOf(10_000, playlist.targetUs / 1000 * 3)) { restart(0, "Восстановлен недоступный live-сегмент"); return }
                    waitReload(playlist)
                    try { playlist = timeline.update(http.playlist(playlist.uri)) }
                    catch (_: HlsWindowException) { restart(0, "Сегмент вышел из live-окна"); return }
                    continue
                }
                pending = null; missingSinceMs = 0L
                try {
                    val next = playlist.segments.getOrNull(index + 1)
                    if (next != null) pending = Download(next, !audioOnly)
                    if (group != segment.discontinuity || init != segment.init || extractor == null) {
                        extractor?.close(); bridge.reset()
                        val adjuster = if (!audioOnly) adjusters[segment.discontinuity] ?: TimestampAdjuster(segment.startUs).let {
                            adjusters.putIfAbsent(segment.discontinuity, it) ?: it
                        } else {
                            while (running() && adjusters[segment.discontinuity]?.timestampOffsetUs.let { it == null || it == C.TIME_UNSET }) {
                                if (videoFinished) throw HlsException("HLS AUDIO discontinuity не совпадает с видео")
                                Thread.sleep(5)
                            }
                            if (!running()) return
                            adjusters[segment.discontinuity] ?: throw HlsException("Нет временной базы HLS-аудио")
                        }
                        extractor = LampCoreHlsExtractor(adjuster, bridge, audioOnly, ::running)
                        group = segment.discontinuity; init = segment.init
                        passedGroups[audioOnly] = group
                        val oldest = if (externalAudio) minOf(passedGroups[false] ?: Long.MIN_VALUE, passedGroups[true] ?: Long.MIN_VALUE) else group
                        adjusters.keys.filter { it < oldest }.forEach { adjusters.remove(it) }
                    }
                    extractor!!.read(file, segment)
                    sequence++
                    if (!audioOnly && running() && next != null) {
                        val cached = if (pending?.future?.isDone == true) (next.startUs + next.durationUs - control.positionUs()).coerceAtLeast(0) else 0
                        switchVariant(playlist, next.startUs, cached)?.let { switched ->
                            pending?.cancel(); pending = null
                            extractor?.close(); extractor = null; init = null
                            playlist = switched; timeline = HlsTimeline(switched)
                            sequence = switched.segments.first { kotlin.math.abs(it.startUs - next.startUs) <= 100_000 }.sequence
                        }
                    }
                } finally { file.delete() }
            }
        } finally { extractor?.close(); pending?.cancel() }
    }

    private fun waitReload(playlist: HlsPlaylist) {
        var remaining = (playlist.targetUs / 2000).coerceIn(250, 5000)
        while (running() && remaining > 0) { val sleep = minOf(remaining, 50); Thread.sleep(sleep); remaining -= sleep }
    }
    fun cancel() { closed = true; http.close(); audioThread?.interrupt(); downloads.shutdownNow() }
    override fun close() {
        cancel()
        if (Thread.currentThread() !== audioThread) runCatching { audioThread?.join(500) }
        runCatching { downloads.awaitTermination(500, TimeUnit.MILLISECONDS) }
        directory.deleteRecursively()
    }
}
