package com.lampplayer.tv.engine

/** AudioTrack's unsigned 32-bit frame counter wraps during long playback. */
internal class AudioFrameCounter {
    private var previous = 0L
    private var wraps = 0L

    fun update(raw: Int): Long {
        val current = raw.toLong() and 0xffffffffL
        if (current < previous && previous - current > 0x80000000L) wraps++
        previous = current
        return current + (wraps shl 32)
    }
}

internal object LampCoreTiming {
    /** Refill a paused AudioTrack before restarting it; never wait beyond its capacity. */
    fun audioReady(queuedFrames: Long, sampleRate: Int, capacityFrames: Int, rate: Float, ended: Boolean): Boolean {
        if (ended) return true // short clips and the final PCM buffer must also drain
        val target = minOf((sampleRate * 0.12 * rate).toLong(), capacityFrames * 3L / 4).coerceAtLeast(1)
        return queuedFrames >= target
    }
}

/** Maps media PTS to the monotonic Surface clock, with a cadence guard after stalls. */
internal class LampCoreVideoTiming(fps: Float) {
    data class Release(val action: Int, val timeNs: Long = 0) // -1 drop, 0 render, 1 wait

    private var frameUs = if (fps > 0) (1_000_000 / fps).toLong() else 33_333L
    private var observedUs = Long.MIN_VALUE
    private var renderedUs = Long.MIN_VALUE
    private var renderedNs = Long.MIN_VALUE

    fun plan(ptsUs: Long, clockUs: Long, nowNs: Long, rate: Float): Release {
        if (observedUs != Long.MIN_VALUE && ptsUs != observedUs) {
            val interval = ptsUs - observedUs
            if (interval in 4_000..250_000) frameUs = interval
        }
        observedUs = ptsUs
        val lateUs = (frameUs * 3 / 4).coerceIn(20_000, 100_000)
        if (ptsUs <= renderedUs || ptsUs < clockUs - lateUs) return Release(-1)
        val dueNs = nowNs + ((ptsUs - clockUs) * 1000 / rate).toLong()
        // Dropped frames do not extend the next interval. Never replay a backlog in a burst.
        val cadenceNs = if (renderedNs == Long.MIN_VALUE) nowNs else renderedNs +
            (minOf(frameUs, ptsUs - renderedUs) * 900 / rate).toLong()
        val targetNs = maxOf(nowNs, dueNs, cadenceNs)
        return if (targetNs - nowNs > 12_000_000) Release(1) else Release(0, targetNs)
    }

    fun rendered(ptsUs: Long, timeNs: Long) { renderedUs = ptsUs; renderedNs = timeNs }
}
