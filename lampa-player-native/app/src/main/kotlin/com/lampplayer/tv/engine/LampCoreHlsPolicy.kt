package com.lampplayer.tv.engine

import java.io.IOException
import kotlin.math.min

/** Retry temporary failures for two minutes; permanent HTTP/parser/DRM errors stay visible. */
internal class HlsRetryPolicy(private val budgetMs: Long = 120_000, private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) }) {
    fun <T> run(active: () -> Boolean, onRetry: () -> Unit = {}, task: () -> T): T {
        val began = clock()
        var attempt = 0
        while (active()) {
            try { return task() } catch (e: IOException) {
                if (e is HlsException || !active() || clock() - began >= budgetMs) throw e
                onRetry()
                var wait = min(2000L, 100L shl min(attempt++, 5))
                while (active() && wait > 0) { val n = min(50L, wait); sleep(n); wait -= n }
            }
        }
        throw HlsException("HLS закрыт")
    }
}

internal class HlsAbr(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var fast = 0.0
    private var slow = 0.0
    private var candidate: String? = null
    private var votes = 0
    private var lastSwitch = Long.MIN_VALUE
    @Synchronized fun sample(bytes: Long, elapsedNs: Long) {
        if (bytes < 4096 || elapsedNs < 5_000_000) return
        val bps = (bytes * 8.0 * 1_000_000_000 / elapsedNs).coerceAtMost(100_000_000.0)
        fast = if (fast == 0.0) bps else fast * 0.5 + bps * 0.5
        slow = if (slow == 0.0) bps else slow * 0.8 + bps * 0.2
    }
    @Synchronized fun choose(variants: List<HlsVariant>, current: HlsVariant, bufferUs: Long): HlsVariant {
        if (fast == 0.0 || variants.isEmpty()) return current
        val budget = min(fast, slow) * 0.72
        val wanted = variants.filter { it.bandwidth <= budget }.maxByOrNull { it.bandwidth } ?: variants.minByOrNull { it.bandwidth }!!
        if (wanted.uri == current.uri) { votes = 0; candidate = null; return current }
        if (wanted.bandwidth < current.bandwidth) { lastSwitch = clock(); votes = 0; return wanted }
        if (candidate == wanted.uri) votes++ else { candidate = wanted.uri; votes = 1 }
        if (votes >= 3 && bufferUs >= 3_000_000 && (lastSwitch == Long.MIN_VALUE || clock() - lastSwitch >= 10_000)) {
            lastSwitch = clock(); votes = 0; return wanted
        }
        return current
    }
    @Synchronized fun estimate(): Long = min(fast, slow).toLong()
}

internal class HlsControl {
    @Volatile var variantUri: String? = null
    @Volatile var audioId = -1
    @Volatile var videoMime: String? = null
    @Volatile var adaptive = false
    @Volatile var ready = false
    @Volatile var supportedSize: (HlsVariant) -> Boolean = { it.height <= 1080 && it.width <= 1920 }
    var positionUs: () -> Long = { 0L }
    var bufferedUs: () -> Long = { 0L }
    val abr = HlsAbr()
    val timestampOffsets = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    @Volatile var recoveries = 0L
    @Volatile var switches = 0L
    @Volatile var restartReason = ""
}

/** Variant media sequence numbers need not agree. Align by PDT or the VOD timeline. */
internal object HlsAlignment {
    fun switchAt(old: HlsPlaylist, boundaryUs: Long, next: HlsPlaylist): HlsPlaylist? {
        val oldBoundary = old.segments.firstOrNull { kotlin.math.abs(it.startUs - boundaryUs) <= 100_000 }
        val target = if (oldBoundary?.programTimeUs != null) next.segments.firstOrNull {
            it.programTimeUs != null && kotlin.math.abs(it.programTimeUs - oldBoundary.programTimeUs) <= 100_000
        } else if (old.endList && next.endList) next.segments.firstOrNull { kotlin.math.abs(it.startUs - boundaryUs) <= 100_000 }
        else next.segments.firstOrNull { it.uri == oldBoundary?.uri }
        target ?: return null
        val offset = boundaryUs - target.startUs
        return next.copy(segments = next.segments.map { it.copy(startUs = it.startUs + offset) })
    }
}
