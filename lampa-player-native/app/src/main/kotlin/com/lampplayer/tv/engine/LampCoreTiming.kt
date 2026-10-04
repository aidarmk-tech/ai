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
    const val LATE_FRAME_US = 100_000L
    const val EARLY_FRAME_US = 12_000L

    /** Audio is the master: never speed up video to catch a network stall. */
    fun frameAction(frameUs: Long, clockUs: Long): Int = when {
        frameUs < clockUs - LATE_FRAME_US -> -1 // discard late frame
        frameUs > clockUs + EARLY_FRAME_US -> 1 // retain until due
        else -> 0 // render
    }
}
