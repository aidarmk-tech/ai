package com.lampplayer.tv.engine

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi

/** Android adaptive decode requires new CSD and the first keyframe in one input buffer. */
@UnstableApi
internal class HlsVideoTransition {
    private var configuration: ByteArray? = null
    fun changed(format: Format) { configuration = format.initializationData.fold(ByteArray(0)) { a, b -> a + b } }
    fun packet(bytes: ByteArray, flags: Int): ByteArray? {
        val csd = configuration ?: return bytes
        if (flags and 1 == 0) return null
        configuration = null
        if (csd.size + bytes.size > 4 * 1024 * 1024) throw HlsException("Слишком большой ключевой кадр")
        return csd + bytes
    }
    companion object {
        fun sameFormat(a: Format, b: Format): Boolean = a.sampleMimeType == b.sampleMimeType &&
            a.width == b.width && a.height == b.height && a.channelCount == b.channelCount && a.sampleRate == b.sampleRate &&
            a.initializationData.size == b.initializationData.size && a.initializationData.indices.all { a.initializationData[it].contentEquals(b.initializationData[it]) }
    }
}
