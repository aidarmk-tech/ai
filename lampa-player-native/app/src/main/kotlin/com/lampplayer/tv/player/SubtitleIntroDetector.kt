package com.lampplayer.tv.player

import android.content.Context
import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL

/**
 * Heuristic intro detector from a subtitle track (.srt/.vtt): the opening
 * (title sequence / song) is a long gap with no dialogue near the start.
 *  - If the first line appears late (45–180s in), the intro is 0..firstLine.
 *  - Otherwise the largest 45–150s gap within the first ~5.5 min is the intro.
 * Returns the intro END in ms (where to skip to), or 0 if nothing convincing.
 * Best-effort: only as good as the subtitle sync; conservative thresholds.
 */
object SubtitleIntroDetector {

    private val TS = Regex("""(\d{1,2}):(\d{2}):(\d{2})[.,](\d{1,3})""")

    fun detectIntroEndMs(context: Context, subtitleUri: String): Long {
        val text = readText(context, subtitleUri) ?: return 0L
        val cues = parse(text)
        if (cues.size < 5) return 0L
        return findIntroEnd(cues)
    }

    private fun parse(text: String): List<Pair<Long, Long>> {
        val out = ArrayList<Pair<Long, Long>>()
        text.lineSequence().forEach { line ->
            if (line.contains("-->")) {
                val ms = TS.findAll(line).map { toMs(it) }.toList()
                if (ms.size >= 2) out.add(ms[0] to ms[1])
            }
        }
        return out.sortedBy { it.first }
    }

    private fun findIntroEnd(cues: List<Pair<Long, Long>>): Long {
        val first = cues.first().first
        if (first in 45_000L..180_000L) return first   // cold start → opening before any line
        var bestEnd = 0L
        var bestGap = 0L
        for (i in 0 until cues.size - 1) {
            val nextStart = cues[i + 1].first
            if (nextStart > 330_000L) break            // only look in the first ~5.5 min
            val gap = nextStart - cues[i].second
            if (gap in 45_000L..150_000L && gap > bestGap) { bestGap = gap; bestEnd = nextStart }
        }
        return bestEnd
    }

    private fun toMs(m: MatchResult): Long {
        val (h, mm, s, ms) = m.destructured
        val frac = ms.padEnd(3, '0').take(3).toLong()
        return h.toLong() * 3_600_000 + mm.toLong() * 60_000 + s.toLong() * 1000 + frac
    }

    private fun readText(context: Context, uriStr: String): String? = runCatching {
        if (uriStr.startsWith("http", true)) {
            var u = URL(uriStr); var hops = 0
            while (true) {
                val c = (u.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000; readTimeout = 12000
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }
                val code = c.responseCode
                if (code in 300..399 && hops++ < 4) { u = URL(u, c.getHeaderField("Location")); c.disconnect(); continue }
                if (code != 200) { c.disconnect(); return null }
                return c.inputStream.use { it.readBytes().take(2_000_000).toByteArray().toString(Charsets.UTF_8) }
            }
            @Suppress("UNREACHABLE_CODE") null
        } else {
            context.contentResolver.openInputStream(Uri.parse(uriStr))
                ?.use { it.readBytes().take(2_000_000).toByteArray().toString(Charsets.UTF_8) }
        }
    }.getOrNull()
}
