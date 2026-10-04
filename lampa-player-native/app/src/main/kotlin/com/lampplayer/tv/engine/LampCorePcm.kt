package com.lampplayer.tv.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Android PCM channel order: FL, FR, FC, LFE, BL/BR or SL/SR; 7.1 adds sides. */
internal class LampCorePcm(private val channels: Int, private val floating: Boolean, channelMask: Int = 0) {
    init { require(channels in 1..8) }
    val inputFrameBytes = channels * if (floating) 4 else 2
    val outputChannels = if (channels == 1) 1 else 2
    val outputFrameBytes = outputChannels * 2
    private val positions = if (channelMask != 0 && Integer.bitCount(channelMask) == channels)
        (0..30).map { 1 shl it }.filter { channelMask and it != 0 }
        else when (channels) {
            1 -> listOf(4)
            2 -> listOf(4, 8)
            3 -> listOf(4, 8, 16)
            4 -> listOf(4, 8, 64, 128)
            5 -> listOf(4, 8, 16, 64, 128)
            6 -> listOf(4, 8, 16, 32, 64, 128)
            7 -> listOf(4, 8, 16, 32, 256, 512, 1024)
            8 -> listOf(4, 8, 16, 32, 64, 128, 512, 1024)
            else -> throw IllegalArgumentException("PCM поддерживает 1–8 каналов")
        }
    private val left = positions.map { when (it) { 4 -> 1.0; 8 -> 0.0; 16 -> 0.707; 32 -> 0.25; 64, 512 -> 0.707; 128, 1024 -> 0.0; else -> 0.5 } }
    private val right = positions.map { when (it) { 8 -> 1.0; 4 -> 0.0; 16 -> 0.707; 32 -> 0.25; 128, 1024 -> 0.707; 64, 512 -> 0.0; else -> 0.5 } }
    private val gain = maxOf(1.0, left.sum(), right.sum())
    fun convert(source: ByteBuffer): ByteBuffer {
        if (channels <= 2 && !floating) return source
        require(source.remaining() % inputFrameBytes == 0) { "Неполный PCM-фрейм" }
        source.order(ByteOrder.LITTLE_ENDIAN)
        val output = ByteBuffer.allocate(source.remaining() / inputFrameBytes * outputFrameBytes).order(ByteOrder.LITTLE_ENDIAN)
        while (source.hasRemaining()) {
            var l = 0.0; var r = 0.0
            for (c in 0 until channels) {
                val value = if (floating) source.float.toDouble().let { if (it.isFinite()) it.coerceIn(-1.0, 1.0) * 32767 else 0.0 } else source.short.toDouble()
                l += value * left[c]; r += value * right[c]
            }
            output.putShort((l / gain).roundToInt().coerceIn(-32768, 32767).toShort())
            if (outputChannels == 2) output.putShort((r / gain).roundToInt().coerceIn(-32768, 32767).toShort())
        }
        output.flip()
        return output
    }
}
