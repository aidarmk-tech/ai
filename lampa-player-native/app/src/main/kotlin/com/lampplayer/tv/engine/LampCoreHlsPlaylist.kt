package com.lampplayer.tv.engine

import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer

internal open class HlsException(message: String) : IOException(message)
internal class HlsWindowException(message: String) : HlsException(message)
internal class HlsMissingException(val status: Int) : HlsException("HLS-сегмент недоступен (HTTP $status)")
internal data class HlsRange(val offset: Long, val length: Long)
internal data class HlsKey(val uri: String, val ivHex: String?) {
    fun iv(sequence: Long): ByteArray {
        if (ivHex == null) return ByteBuffer.allocate(16).putLong(0).putLong(sequence).array()
        val padded = ivHex.removePrefix("0x").removePrefix("0X").padStart(32, '0')
        if (padded.length != 32 || !padded.all { it in "0123456789abcdefABCDEF" }) throw HlsException("Некорректный HLS IV")
        return ByteArray(16) { padded.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
internal data class HlsInit(val uri: String, val range: HlsRange?, val key: HlsKey?)
internal data class HlsSegment(
    val uri: String, val durationUs: Long, val startUs: Long, val sequence: Long,
    val discontinuity: Long, val range: HlsRange?, val key: HlsKey?, val init: HlsInit?, val programTimeUs: Long? = null,
)
internal data class HlsVariant(val uri: String, val bandwidth: Long, val height: Int, val audioGroup: String?,
    val width: Int = 0, val codecs: String? = null, val subtitleGroup: String? = null) {
    val videoMime: String? get() = codecs?.split(',')?.firstNotNullOfOrNull { codec -> when {
        codec.trim().startsWith("avc") -> "video/avc"
        codec.trim().startsWith("hvc") || codec.trim().startsWith("hev") -> "video/hevc"
        codec.trim().startsWith("vp09") -> "video/x-vnd.on2.vp9"
        codec.trim().startsWith("av01") -> "video/av01"
        else -> null
    } }
}
internal data class HlsAudio(val uri: String?, val group: String, val name: String, val language: String?, val default: Boolean)
internal data class HlsSubtitle(val uri: String, val group: String, val name: String, val language: String?)
internal data class HlsPlaylist(
    val uri: String, val variants: List<HlsVariant>, val audios: List<HlsAudio>,
    val segments: List<HlsSegment>, val endList: Boolean, val targetUs: Long, val subtitles: List<HlsSubtitle> = emptyList(),
) {
    val durationUs: Long get() = segments.sumOf { it.durationUs }
    fun initialVariant(): HlsVariant = variants.filter { it.height <= 1080 && it.bandwidth <= 8_000_000 }
        .maxByOrNull { it.bandwidth } ?: variants.minByOrNull { it.bandwidth }
        ?: throw HlsException("Нет варианта HLS")
}

/** RFC 8216 core tags; unsupported encryption/delta playlists fail explicitly. */
internal object LampCoreHlsPlaylist {
    fun parse(uri: String, text: String): HlsPlaylist {
        val lines = text.removePrefix("\uFEFF").lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.firstOrNull() != "#EXTM3U") throw HlsException("Ответ не является HLS-плейлистом")
        val variants = mutableListOf<HlsVariant>()
        val audios = mutableListOf<HlsAudio>()
        val subtitles = mutableListOf<HlsSubtitle>()
        val segments = mutableListOf<HlsSegment>()
        var pendingVariant: Map<String, String>? = null
        var duration: Long? = null
        var pendingRange: String? = null
        var previousRangeUri: String? = null
        var previousRangeEnd = -1L
        var sequence = 0L
        var discontinuity = 0L
        var start = 0L
        var key: HlsKey? = null
        var init: HlsInit? = null
        var endList = false
        var target = 6_000_000L
        var programTimeUs: Long? = null
        for (line in lines.drop(1)) {
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> pendingVariant = attributes(line.substringAfter(':'))
                line.startsWith("#EXT-X-MEDIA:") -> {
                    val a = attributes(line.substringAfter(':'))
                    if (a["TYPE"] == "AUDIO") audios += HlsAudio(a["URI"]?.let { resolve(uri, it) },
                        a["GROUP-ID"] ?: throw HlsException("Нет AUDIO GROUP-ID"), a["NAME"] ?: "Audio", a["LANGUAGE"], a["DEFAULT"] == "YES")
                    if (a["TYPE"] == "SUBTITLES") subtitles += HlsSubtitle(resolve(uri, a["URI"] ?: throw HlsException("Нет URI субтитров")),
                        a["GROUP-ID"] ?: throw HlsException("Нет SUBTITLES GROUP-ID"), a["NAME"] ?: "Subtitles", a["LANGUAGE"])
                }
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                    if (segments.isNotEmpty()) throw HlsException("MEDIA-SEQUENCE после сегментов")
                    sequence = number(line.substringAfter(':'))
                }
                line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE:") -> {
                    if (segments.isNotEmpty()) throw HlsException("DISCONTINUITY-SEQUENCE после сегментов")
                    discontinuity = number(line.substringAfter(':'))
                }
                line == "#EXT-X-DISCONTINUITY" -> discontinuity = add(discontinuity, 1)
                line.startsWith("#EXTINF:") -> {
                    val seconds = line.substringAfter(':').substringBefore(',').toDoubleOrNull()
                        ?: throw HlsException("Некорректный EXTINF")
                    if (!seconds.isFinite() || seconds <= 0 || seconds > 3600) throw HlsException("Некорректная длительность HLS-сегмента")
                    duration = (seconds * 1_000_000).toLong()
                }
                line.startsWith("#EXT-X-TARGETDURATION:") -> target = number(line.substringAfter(':')).coerceIn(1, 3600) * 1_000_000
                line.startsWith("#EXT-X-PROGRAM-DATE-TIME:") -> programTimeUs = dateUs(line.substringAfter(':'))
                line.startsWith("#EXT-X-BYTERANGE:") -> pendingRange = line.substringAfter(':')
                line.startsWith("#EXT-X-KEY:") -> key = parseKey(uri, attributes(line.substringAfter(':')))
                line.startsWith("#EXT-X-SESSION-KEY:") -> parseKey(uri, attributes(line.substringAfter(':')))
                line.startsWith("#EXT-X-MAP:") -> {
                    val a = attributes(line.substringAfter(':'))
                    if (key != null && key.ivHex == null) throw HlsException("Для зашифрованного EXT-X-MAP нужен явный IV")
                    init = HlsInit(resolve(uri, a["URI"] ?: throw HlsException("Нет URI в EXT-X-MAP")),
                        a["BYTERANGE"]?.let { parseRange(it, 0) }, key)
                }
                line == "#EXT-X-ENDLIST" -> endList = true
                line == "#EXT-X-GAP" -> throw HlsException("HLS GAP пока не поддерживается")
                line.startsWith("#EXT-X-SKIP:") -> throw HlsException("Нужен полный HLS-плейлист, delta/SKIP не поддерживается")
                line == "#EXT-X-I-FRAMES-ONLY" -> throw HlsException("Плейлист содержит только I-кадры")
                line.startsWith('#') -> Unit
                else -> {
                    val resolved = resolve(uri, line)
                    val variant = pendingVariant
                    if (variant != null) {
                        val height = variant["RESOLUTION"]?.substringAfter('x')?.toIntOrNull() ?: 0
                        variants += HlsVariant(resolved, number(variant["BANDWIDTH"] ?: "0"), height, variant["AUDIO"],
                            variant["RESOLUTION"]?.substringBefore('x')?.toIntOrNull() ?: 0, variant["CODECS"], variant["SUBTITLES"])
                        pendingVariant = null
                    } else {
                        val d = duration ?: throw HlsException("URI сегмента без EXTINF")
                        val range = pendingRange?.let { raw ->
                            if (!raw.contains('@') && previousRangeUri != resolved) throw HlsException("Неявный BYTERANGE относится к другому ресурсу")
                            parseRange(raw, previousRangeEnd)
                        }
                        segments += HlsSegment(resolved, d, start, sequence, discontinuity, range, key, init, programTimeUs)
                        programTimeUs = programTimeUs?.let { add(it, d) }
                        if (segments.size > 100_000) throw HlsException("Слишком большой HLS-плейлист")
                        start = add(start, d); sequence = add(sequence, 1)
                        previousRangeUri = if (range == null) null else resolved
                        previousRangeEnd = range?.let { add(it.offset, it.length) } ?: -1
                        pendingRange = null; duration = null
                    }
                }
            }
        }
        if (pendingVariant != null || duration != null) throw HlsException("Оборванный HLS-плейлист")
        if (variants.isEmpty() && segments.isEmpty()) throw HlsException("Нет полных HLS-сегментов; LL-HLS-only пока не поддерживается")
        if (variants.isNotEmpty() && segments.isNotEmpty()) throw HlsException("Смешанный master/media плейлист")
        return HlsPlaylist(uri, variants, audios, segments, endList, target, subtitles)
    }

    fun attributes(value: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var i = 0
        while (i < value.length) {
            val end = value.indexOf('=', i)
            if (end < 0) throw HlsException("Некорректные атрибуты HLS")
            val name = value.substring(i, end).trim()
            i = end + 1
            val content: String
            if (i < value.length && value[i] == '"') {
                val close = value.indexOf('"', i + 1)
                if (close < 0) throw HlsException("Оборванная строка атрибута HLS")
                content = value.substring(i + 1, close); i = close + 1
            } else {
                val comma = value.indexOf(',', i).takeIf { it >= 0 } ?: value.length
                content = value.substring(i, comma).trim(); i = comma
            }
            if (name.isEmpty() || result.put(name, content) != null) throw HlsException("Повторный атрибут HLS")
            if (i < value.length) { if (value[i] != ',') throw HlsException("Некорректный разделитель HLS"); i++ }
        }
        return result
    }

    private fun parseKey(base: String, a: Map<String, String>): HlsKey? = when (a["METHOD"]) {
        "NONE" -> null
        "AES-128" -> {
            if (a["KEYFORMAT"] != null && a["KEYFORMAT"] != "identity") throw HlsException("DRM KEYFORMAT не поддерживается")
            HlsKey(resolve(base, a["URI"] ?: throw HlsException("Нет URI ключа AES-128")), a["IV"]).also { it.iv(0) }
        }
        else -> throw HlsException("HLS SAMPLE-AES/DRM пока не поддерживается")
    }

    private fun parseRange(value: String, implicitOffset: Long): HlsRange {
        val length = number(value.substringBefore('@'))
        val offset = if (value.contains('@')) number(value.substringAfter('@')) else implicitOffset
        if (length <= 0 || offset < 0 || offset > Long.MAX_VALUE - length) throw HlsException("Некорректный BYTERANGE")
        return HlsRange(offset, length)
    }
    private fun number(value: String): Long = value.toLongOrNull()?.takeIf { it >= 0 } ?: throw HlsException("Некорректное число HLS")
    private fun add(a: Long, b: Long): Long {
        if (a > Long.MAX_VALUE - b) throw HlsException("Переполнение числового поля HLS")
        return a + b
    }
    private fun dateUs(text: String): Long {
        val normalized = Regex("(\\.\\d+)(Z|[+-]\\d\\d:\\d\\d)$").replace(text) { m -> m.groupValues[1].take(4).padEnd(4, '0') + m.groupValues[2] }
        // ISO X patterns require API 24. Normalize to RFC822 Z for Android 6.
        val compatible = normalized.replace(Regex("Z$"), "+0000")
            .replace(Regex("([+-]\\d\\d):(\\d\\d)$"), "$1$2")
        val pattern = if (compatible.contains('.')) "yyyy-MM-dd'T'HH:mm:ss.SSSZ" else "yyyy-MM-dd'T'HH:mm:ssZ"
        val format = java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply { isLenient = false }
        return try { (format.parse(compatible)?.time ?: throw HlsException("Некорректный PROGRAM-DATE-TIME")) * 1000 }
        catch (_: Exception) { throw HlsException("Некорректный PROGRAM-DATE-TIME") }
    }
    private fun resolve(base: String, relative: String): String {
        val result = try { URI(base).resolve(relative) } catch (_: Exception) { throw HlsException("Некорректный URI HLS") }
        if (result.scheme !in listOf("http", "https")) throw HlsException("HLS поддерживает только HTTP/HTTPS")
        return result.toString()
    }
}

/** Keep a stable timeline while live MEDIA-SEQUENCE slides forward. */
internal class HlsTimeline(initial: HlsPlaylist) {
    private var previous = initial
    private var offsetUs = 0L
    fun update(next: HlsPlaylist): HlsPlaylist {
        val overlap = next.segments.firstOrNull { fresh -> previous.segments.any { it.sequence == fresh.sequence } }
        offsetUs = if (overlap != null) previous.segments.first { it.sequence == overlap.sequence }.startUs - overlap.startUs
        else if (next.segments.first().sequence == previous.segments.last().sequence + 1) previous.segments.last().let { it.startUs + it.durationUs }
        else throw HlsWindowException("Live-плейлист потерял непрерывность")
        val adjusted = next.copy(segments = next.segments.map { it.copy(startUs = it.startUs + offsetUs) })
        previous = adjusted
        return adjusted
    }
}
