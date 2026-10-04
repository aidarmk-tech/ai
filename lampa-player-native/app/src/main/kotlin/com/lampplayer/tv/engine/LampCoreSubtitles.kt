package com.lampplayer.tv.engine

import android.content.Context
import android.net.Uri
import com.lampplayer.tv.domain.model.ExternalSubtitle
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

internal data class CoreSubtitleSource(val id: Int, val uri: String, val name: String, val hls: Boolean = false)

/** Subtitle I/O is independent of the AV queues; failure never stops the film. */
internal class LampCoreSubtitles(private val context: Context, private val control: () -> HlsControl,
    private val positionUs: () -> Long, private val tracksChanged: () -> Unit, private val notice: () -> Unit) : Closeable {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "LampCore-subtitles") }
    private val generation = AtomicInteger()
    @Volatile private var sources = emptyList<CoreSubtitleSource>()
    @Volatile private var external = emptyList<CoreSubtitleSource>()
    @Volatile private var headers = emptyMap<String, String>()
    @Volatile private var index = CoreCueIndex(emptyList())
    @Volatile private var transport: LampCoreHlsHttp? = null
    @Volatile private var future: Future<*>? = null
    @Volatile private var closed = false
    @Volatile var selectedId = -1
        private set
    @Volatile var delayMs = 0L
    fun tracks(): List<EngineTrack> = listOf(EngineTrack(-1, "Выкл")) + sources.map { EngineTrack(it.id, it.name) }
    fun text(): String = index.textAt(positionUs() - delayMs * 1000)

    fun configure(subtitles: List<ExternalSubtitle>, requestHeaders: Map<String, String>) {
        cancelLoad(); headers = requestHeaders.toMap()
        external = subtitles.filter { it.uri.isNotBlank() }.mapIndexed { i, s -> CoreSubtitleSource(i + 1, s.uri, s.name ?: "Субтитры ${i + 1}") }
        sources = external; selectedId = -1
        val enabledUri = subtitles.firstOrNull { it.enabled }?.uri
        tracksChanged()
        external.firstOrNull { it.uri == enabledUri }?.let { select(it.id) }
    }
    fun hlsTracks(resolved: HlsResolved) {
        val old = sources.firstOrNull { it.id == selectedId }?.uri
        val hls = resolved.master?.subtitles.orEmpty().filter { it.group == resolved.variant?.subtitleGroup }
            .mapIndexed { i, s -> CoreSubtitleSource(20_000 + i, s.uri, "${s.name}${s.language?.let { " · $it" } ?: ""}", true) }
        val next = external + hls
        if (next == sources) return
        sources = next; tracksChanged()
        if (old != null) {
            val selected = sources.firstOrNull { it.uri == old }
            if (selected == null) select(-1) else if (selected.id != selectedId) select(selected.id)
        }
    }
    fun refreshHls() { if (sources.any { it.id == selectedId && it.hls }) select(selectedId) }
    private fun cancelLoad() { generation.incrementAndGet(); transport?.close(); future?.cancel(true); index = CoreCueIndex(emptyList()) }
    fun select(id: Int) {
        cancelLoad(); selectedId = id
        val source = sources.firstOrNull { it.id == id } ?: return
        if (closed) return
        val token = generation.get()
        val http = LampCoreHlsHttp(headers)
        transport = http
        future = executor.submit {
            val directory = File(context.cacheDir, "lampcore-subs-${java.util.UUID.randomUUID()}")
            fun active() = !closed && token == generation.get() && !Thread.currentThread().isInterrupted
            try {
                if (!source.hls) {
                    val uri = Uri.parse(source.uri)
                    val text = if (uri.scheme in listOf("http", "https")) http.text(source.uri)
                    else context.contentResolver.openInputStream(uri)?.use { stream ->
                        val out = java.io.ByteArrayOutputStream()
                        val bytes = ByteArray(8192)
                        while (active()) { val n = stream.read(bytes); if (n < 0) break; if (out.size() + n > 2 * 1024 * 1024) throw HlsException("Слишком большие субтитры"); out.write(bytes, 0, n) }
                        out.toString("UTF-8")
                    } ?: throw HlsException("Не удалось открыть субтитры")
                    val parsed = LampCoreSubtitleParser.parse(text)
                    if (active()) index = CoreCueIndex(parsed)
                    return@submit
                }
                if (!directory.mkdirs()) throw HlsException("Не удалось создать кэш субтитров")
                val loaded = object : LinkedHashMap<String, List<CoreTextCue>>(8, 0.75f, true) {
                    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<CoreTextCue>>?): Boolean = size > 8
                }
                var playlist: HlsPlaylist? = null
                var reloadNs = 0L
                while (active()) {
                    if (playlist == null || !playlist.endList && System.nanoTime() >= reloadNs) {
                        playlist = http.playlist(source.uri)
                        reloadNs = System.nanoTime() + (playlist.targetUs / 2).coerceIn(500_000, 5_000_000) * 1000
                    }
                    val now = positionUs()
                    val window = if (!playlist.endList) playlist.segments.takeLast(6) else {
                        val at = playlist.segments.indexOfFirst { it.startUs + it.durationUs > now }.takeIf { it >= 0 } ?: playlist.segments.lastIndex
                        playlist.segments.subList((at - 1).coerceAtLeast(0), (at + 2).coerceAtMost(playlist.segments.size))
                    }
                    for (segment in window) {
                        if (!active()) break
                        val offset = control().timestampOffsets[segment.discontinuity] ?: continue
                        val key = "${segment.uri}|${segment.range}|${segment.sequence}|$offset"
                        if (loaded.containsKey(key)) continue
                        val file = http.segment(segment, directory)
                        val text = try { if (file.length() > 2 * 1024 * 1024) throw HlsException("Слишком большие субтитры"); file.readText() } finally { file.delete() }
                        val shift = LampCoreSubtitleParser.hlsOffset(LampCoreSubtitleParser.timestampMap(text), offset, now)
                        loaded[key] = LampCoreSubtitleParser.parse(text, shift)
                    }
                    val cues = loaded.values.flatten().distinct().sortedBy { it.startUs }
                    if (active()) index = CoreCueIndex(cues)
                    repeat(5) { if (active()) Thread.sleep(50) }
                }
            } catch (_: Exception) { if (active()) { index = CoreCueIndex(emptyList()); notice() } }
            finally { http.close(); directory.deleteRecursively() }
        }
    }
    override fun close() { closed = true; cancelLoad(); executor.shutdownNow() }
}
