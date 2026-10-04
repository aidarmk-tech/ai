package com.lampplayer.tv.engine

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.*
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.ts.AdtsExtractor
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import java.io.EOFException
import java.io.File
import java.io.FileInputStream

internal interface HlsPacketOutput {
    fun format(id: Int, type: Int, format: Format)
    fun packet(id: Int, type: Int, bytes: ByteArray, timeUs: Long, flags: Int)
    fun endTracks(audioPresent: Boolean)
}

/** Only Media3 container extractors are reused; no ExoPlayer playback/buffering machinery. */
@UnstableApi
internal class LampCoreHlsExtractor(
    private val adjuster: TimestampAdjuster,
    private val output: HlsPacketOutput,
    private val audioOnly: Boolean,
    private val running: () -> Boolean,
) : java.io.Closeable {
    private var extractor: Extractor? = null
    private val tracks = linkedMapOf<Int, PacketTrack>()
    private var packedAudio = false
    private val bridge = object : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput {
            if (type !in listOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO) || audioOnly && type == C.TRACK_TYPE_VIDEO) return DummyTrackOutput()
            if (tracks.values.any { it.type == type && it.id != id }) return DummyTrackOutput()
            return tracks.getOrPut(id) { PacketTrack(id, type) }
        }
        override fun endTracks() { output.endTracks(tracks.values.any { it.type == C.TRACK_TYPE_AUDIO }) }
        override fun seekMap(seekMap: SeekMap) { /* timeline/seek comes from EXTINF, not segment indexes */ }
    }

    fun read(file: File, segment: HlsSegment) {
        FileInputStream(file).use { stream ->
            val input = DefaultExtractorInput(DataReader { buffer, offset, length -> stream.read(buffer, offset, length) }, 0, file.length())
            if (extractor == null) {
                val mp4 = FragmentedMp4Extractor(0, adjuster)
                val adts = AdtsExtractor()
                extractor = when {
                    segment.init != null || mp4.sniff(input) -> mp4
                    else -> {
                        input.resetPeekPosition()
                        if (adts.sniff(input)) { packedAudio = true; adts }
                        else { input.resetPeekPosition(); TsExtractor(TsExtractor.MODE_HLS, adjuster, DefaultTsPayloadReaderFactory(0)) }
                    }
                }
                input.resetPeekPosition()
                extractor!!.init(bridge)
            }
            if (packedAudio) extractor!!.seek(0, segment.startUs)
            val holder = PositionHolder()
            while (running()) {
                when (extractor!!.read(input, holder)) {
                    Extractor.RESULT_END_OF_INPUT -> return
                    Extractor.RESULT_SEEK -> throw HlsException("Контейнер HLS-сегмента требует неподдерживаемый seek")
                }
            }
        }
    }

    override fun close() { extractor?.release() }

    private inner class PacketTrack(val id: Int, val type: Int) : TrackOutput {
        private var bytes = ByteArray(16 * 1024)
        private var used = 0
        override fun format(format: Format) = output.format(id, type, format)
        private fun reserve(length: Int) {
            if (length < 0 || used.toLong() + length > 4 * 1024 * 1024) throw HlsException("HLS-кадр превышает лимит 4 МиБ")
            if (used + length > bytes.size) bytes = bytes.copyOf(maxOf(used + length, bytes.size * 2).coerceAtMost(4 * 1024 * 1024))
        }
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            reserve(length)
            val count = input.read(bytes, used, length)
            if (count < 0 && !allowEndOfInput) throw EOFException()
            if (count > 0) used += count
            return count
        }
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            reserve(length); data.readBytes(bytes, used, length); used += length
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            if (cryptoData != null) throw HlsException("HLS SAMPLE-AES/DRM пока не поддерживается")
            val start = used - size - offset
            if (start < 0 || size < 0 || offset < 0) throw HlsException("Некорректные границы HLS-кадра")
            output.packet(id, type, bytes.copyOfRange(start, start + size), timeUs, flags)
            if (offset > 0) System.arraycopy(bytes, used - offset, bytes, 0, offset)
            used = offset
        }
    }
}
