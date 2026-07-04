package com.lampplayer.tv.player

import com.lampplayer.tv.data.epg.CatchupInfo
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds candidate archive (catch-up) URLs for a programme and probes which one
 * the provider actually serves. Covers the conventions used by IPTV playlists:
 *  - catchup="default"   → catchup-source template with ${start}/{utc}/… placeholders
 *  - catchup="append"    → live URL + filled template
 *  - catchup="shift"     → live URL + ?utc=<start>&lutc=<now>
 *  - catchup="flussonic" → …/<name>-<start>-<dur>.m3u8 / timeshift_abs / archive variants
 *  - catchup="xc"        → Xtream-Codes streaming/timeshift.php
 * When the m3u declares nothing we still try flussonic + shift — many servers
 * support them undeclared, and a cheap probe settles it.
 */
object CatchupUrlBuilder {

    fun candidates(liveUrl: String, info: CatchupInfo?, startSec: Long, durSec: Long): List<String> {
        val now = System.currentTimeMillis() / 1000
        val out = LinkedHashSet<String>()
        when (info?.type) {
            "default" -> fill(info.source, startSec, durSec, now)?.let {
                // A relative template resolves against the live URL.
                out.add(if (it.startsWith("http", true)) it
                        else runCatching { URL(URL(liveUrl), it).toString() }.getOrNull() ?: return@let)
            }
            "append" -> fill(info.source, startSec, durSec, now)?.let { out.add(liveUrl + it) }
            "shift", "timeshift" -> out.add(addQuery(liveUrl, "utc=$startSec&lutc=$now"))
            "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> out.addAll(flussonic(liveUrl, startSec, durSec))
            "xc", "xtream" -> xtream(liveUrl, startSec, durSec)?.let { out.add(it) }
        }
        // Undeclared archive or a mislabeled type: probe the common conventions too.
        out.addAll(flussonic(liveUrl, startSec, durSec))
        out.add(addQuery(liveUrl, "utc=$startSec&lutc=$now"))
        xtream(liveUrl, startSec, durSec)?.let { out.add(it) }
        return out.toList()
    }

    /** First candidate the server actually serves (HTTP 200 + playlist sanity), or null. */
    fun firstWorking(candidates: List<String>, headers: Map<String, String>): String? =
        candidates.firstOrNull { probe(it, headers) }

    // ── template placeholders ──────────────────────────────────────
    /** Substituted template, or null when it's blank or keeps unsupported tokens. */
    private fun fill(template: String, start: Long, dur: Long, now: Long): String? {
        if (template.isBlank()) return null
        var t = template
        val end = start + dur
        mapOf(
            "{utc}" to "$start", "\${start}" to "$start", "{start}" to "$start",
            "{lutc}" to "$now", "\${timestamp}" to "$now", "{now}" to "$now", "{current_utc}" to "$now",
            "{utcend}" to "$end", "\${end}" to "$end", "{end}" to "$end",
            "{duration}" to "$dur", "\${duration}" to "$dur",
            "\${offset}" to "${now - start}", "{offset}" to "${now - start}",
        ).forEach { (k, v) -> t = t.replace(k, v, ignoreCase = true) }
        return t.takeIf { !it.contains('{') }       // unsupported tokens → a broken URL, skip
    }

    // ── flussonic ──────────────────────────────────────────────────
    // http://host/ch/video.m3u8?tok → video-<utc>-<dur>.m3u8, index-…, mono-…,
    // archive-<utc>-<dur>.m3u8, timeshift_abs-<utc>.(m3u8|ts) — same query kept.
    private fun flussonic(liveUrl: String, start: Long, dur: Long): List<String> {
        val q = liveUrl.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
        val base = liveUrl.substringBefore('?')
        val slash = base.lastIndexOf('/')
        if (slash <= "https://".length) return emptyList()
        val dir = base.substring(0, slash)
        val file = base.substring(slash + 1)
        val out = ArrayList<String>(5)
        if (file.endsWith(".m3u8", true)) {
            val name = file.removeSuffix(".m3u8")
            out.add("$dir/$name-$start-$dur.m3u8$q")
            if (name != "index") out.add("$dir/index-$start-$dur.m3u8$q")
            if (name != "video") out.add("$dir/video-$start-$dur.m3u8$q")
            out.add("$dir/archive-$start-$dur.m3u8$q")
            out.add("$dir/timeshift_abs-$start.m3u8$q")
        } else {
            out.add("$dir/timeshift_abs-$start.ts$q")
        }
        return out
    }

    // ── Xtream Codes ───────────────────────────────────────────────
    // http://host[:port]/live/USER/PASS/12345.m3u8 → streaming/timeshift.php
    private val XC = Regex("""^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\d+)(?:\.\w+)?(?:\?.*)?$""")
    private fun xtream(liveUrl: String, start: Long, dur: Long): String? {
        val m = XC.find(liveUrl) ?: return null
        val (host, user, pass, id) = m.destructured
        if (user.startsWith("live", true) && user.contains('.')) return null   // path, not creds
        val fmt = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).format(Date(start * 1000))
        return "$host/streaming/timeshift.php?username=$user&password=$pass&stream=$id&start=$fmt&duration=${(dur / 60).coerceAtLeast(1)}"
    }

    private fun addQuery(url: String, q: String) = url + (if (url.contains('?')) "&" else "?") + q

    // ── probe ──────────────────────────────────────────────────────
    private fun probe(url: String, headers: Map<String, String>): Boolean = runCatching {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000; readTimeout = 5000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", headers["User-Agent"] ?: "Mozilla/5.0")
            headers.forEach { (k, v) -> if (!k.equals("User-Agent", true)) setRequestProperty(k, v) }
        }
        if (c.responseCode != 200) { c.disconnect(); return@runCatching false }
        // Playlist URLs must actually return a playlist (some servers 200 an error page).
        val ok = if (url.substringBefore('?').endsWith(".m3u8", true)) {
            val head = c.inputStream.use { String(it.readNBytesCompat(512), Charsets.UTF_8) }
            head.contains("#EXTM3U")
        } else true
        c.disconnect()
        ok
    }.getOrDefault(false)

    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = read(buf, off, n - off)
            if (r < 0) break
            off += r
        }
        return buf.copyOf(off)
    }
}
