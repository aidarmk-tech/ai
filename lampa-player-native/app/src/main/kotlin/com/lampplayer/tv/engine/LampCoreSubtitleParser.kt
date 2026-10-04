package com.lampplayer.tv.engine

internal data class CoreTextCue(val startUs: Long, val endUs: Long, val text: String)
internal data class CoreVttMap(val localUs: Long, val mpegTs: Long)

internal object LampCoreSubtitleParser {
    fun timeUs(value: String): Long {
        val pieces = value.trim().replace(',', '.').split(':')
        if (pieces.size !in 2..3) throw IllegalArgumentException("Некорректное время субтитров")
        val seconds = pieces.last().toDouble()
        val minutes = pieces[pieces.size - 2].toLong()
        val hours = if (pieces.size == 3) pieces[0].toLong() else 0L
        require(seconds.isFinite() && seconds >= 0 && seconds < 60 && minutes >= 0 && (pieces.size == 2 || minutes < 60) && hours in 0..10000)
        return ((hours * 3600 + minutes * 60 + seconds) * 1_000_000).toLong()
    }
    fun timestampMap(text: String): CoreVttMap? {
        val line = text.lineSequence().firstOrNull { it.startsWith("X-TIMESTAMP-MAP=") } ?: return null
        val local = Regex("LOCAL:([^,]+)").find(line)?.groupValues?.get(1) ?: return null
        val mpeg = Regex("MPEGTS:(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        return CoreVttMap(timeUs(local), mpeg and 0x1ffffffffL)
    }
    fun hlsOffset(map: CoreVttMap?, mediaOffsetUs: Long, nearUs: Long): Long {
        val raw = (map?.mpegTs ?: 0) * 1_000_000 / 90_000 + mediaOffsetUs
        val wrap = 0x200000000L * 1_000_000 / 90_000
        val epoch = kotlin.math.round((nearUs - raw).toDouble() / wrap).toLong()
        return raw + epoch * wrap - (map?.localUs ?: 0)
    }
    fun parse(input: String, offsetUs: Long = 0): List<CoreTextCue> {
        require(input.length <= 2 * 1024 * 1024) { "Слишком большие субтитры" }
        val text = input.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val cues = mutableListOf<CoreTextCue>()
        if (text.contains("[Events]", true)) {
            var columns = listOf("Layer", "Start", "End", "Style", "Name", "MarginL", "MarginR", "MarginV", "Effect", "Text")
            for (line in text.lineSequence()) {
                if (line.startsWith("Format:", true)) columns = line.substringAfter(':').split(',').map { it.trim() }
                if (!line.startsWith("Dialogue:", true)) continue
                val fields = line.substringAfter(':').trimStart().split(',', limit = columns.size)
                val start = columns.indexOfFirst { it.equals("Start", true) }; val end = columns.indexOfFirst { it.equals("End", true) }
                val body = columns.indexOfFirst { it.equals("Text", true) }
                if (start < 0 || end < 0 || body < 0 || fields.size != columns.size) continue
                runCatching {
                    val rendered = fields[body].replace(Regex("\\{[^}]*}"), "").replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
                    cues += CoreTextCue(timeUs(fields[start]) + offsetUs, timeUs(fields[end]) + offsetUs, rendered)
                }
            }
        } else {
            for (block in text.split(Regex("\n[ \t]*\n"))) {
                val lines = block.lines()
                if (lines.firstOrNull()?.let { it.startsWith("NOTE") || it == "STYLE" || it == "REGION" } == true) continue
                val index = lines.indexOfFirst { it.contains("-->") }
                if (index < 0) continue
                val times = lines[index].split("-->", limit = 2)
                runCatching {
                    val body = lines.drop(index + 1).joinToString("\n")
                        .replace(Regex("</?(?:c(?:\\.[^ >]+)?|v|lang|ruby|rt)(?:[ \t]+[^>]*)?>"), "")
                        .replace(Regex("<\\d{2}:\\d{2}(?::\\d{2})?\\.\\d+>"), "")
                    cues += CoreTextCue(timeUs(times[0].trim()) + offsetUs, timeUs(times[1].trim().substringBefore(' ')) + offsetUs, body)
                }
            }
        }
        return cues.filter { it.endUs > it.startUs && it.text.isNotBlank() }.sortedBy { it.startUs }.take(50000)
    }
    fun textAt(cues: List<CoreTextCue>, timeUs: Long): String = cues.asSequence().filter { timeUs >= it.startUs && timeUs < it.endUs }
        .map { it.text }.distinct().take(8).joinToString("\n")
}

/** Prefix maxima retain long overlapping cues while avoiding a full scan every UI tick. */
internal class CoreCueIndex(private val cues: List<CoreTextCue>) {
    private val maxEnds = LongArray(cues.size)
    init { for (i in cues.indices) maxEnds[i] = maxOf(if (i == 0) Long.MIN_VALUE else maxEnds[i - 1], cues[i].endUs) }
    fun textAt(timeUs: Long): String {
        var lo = 0; var hi = cues.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (cues[mid].startUs <= timeUs) lo = mid + 1 else hi = mid }
        val texts = linkedSetOf<String>()
        var i = lo - 1
        while (i >= 0 && maxEnds[i] > timeUs && texts.size < 8) {
            if (cues[i].endUs > timeUs) texts += cues[i].text
            i--
        }
        return texts.toList().asReversed().joinToString("\n")
    }
}
