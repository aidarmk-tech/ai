package com.lampplayer.tv.data.epg

import android.content.Context
import android.util.Xml
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

data class EpgProgramme(val start: Long, val stop: Long, val title: String, val desc: String)

/** Archive (catch-up) declaration from the m3u: type + optional URL template + depth. */
data class CatchupInfo(val type: String, val source: String, val days: Int)

private data class EpgCache(
    val ts: Long,
    val programmes: Map<String, List<EpgProgramme>>,
    val names: Map<String, String>,
    val urls: Map<String, String>,
    val catchupNames: Map<String, CatchupInfo>? = null,
    val catchupUrls: Map<String, CatchupInfo>? = null,
)

/**
 * IPTV EPG from the playlist's own data: fetch the m3u (it carries `url-tvg`
 * pointing at an XMLTV guide, plus per-channel `tvg-id`), then fetch+parse the
 * XMLTV and expose programmes per channel. Channels are matched by tvg-id (via
 * the m3u) or, failing that, by normalized display name.
 */
class EpgRepository {

    private var loadedSrc: String? = null
    private var programmesById: Map<String, List<EpgProgramme>> = emptyMap()
    private var nameToId: MutableMap<String, String> = mutableMapOf()   // m3u + xmltv display-names
    private var urlToId: MutableMap<String, String> = mutableMapOf()    // m3u stream url → tvg-id
    private var nameToCatchup: MutableMap<String, CatchupInfo> = mutableMapOf()
    private var urlToCatchup: MutableMap<String, CatchupInfo> = mutableMapOf()

    val isLoaded: Boolean get() = programmesById.isNotEmpty()
    var lastStatus: String = "не загружено"
        private set

    private val gson = Gson()
    private val cacheTtlMs = 12 * 3600_000L
    private var timedOut = false
    // Sources whose XMLTV is too big to parse in time — never retry (would hang every switch).
    private val deadSrcs = HashSet<String>()

    /** True once a source has been ruled out as too big — callers can skip it instantly. */
    fun isDead(srcUrl: String?): Boolean = srcUrl != null && deadSrcs.contains(srcUrl)

    /** Idempotent: load from disk cache (≤12h), else fetch+parse the m3u/XMLTV and cache it. */
    suspend fun ensureLoaded(context: Context, srcUrl: String): Boolean = withContext(Dispatchers.IO) {
        if (srcUrl == loadedSrc && programmesById.isNotEmpty()) return@withContext true
        if (deadSrcs.contains(srcUrl)) { lastStatus = "XMLTV слишком большой (пропущен)"; return@withContext false }
        // 1) disk cache
        val cacheFile = File(File(context.cacheDir, "epg").apply { mkdirs() }, "${srcUrl.hashCode()}-v2.json")
        if (cacheFile.exists() && System.currentTimeMillis() - cacheFile.lastModified() < cacheTtlMs) {
            runCatching {
                val c = gson.fromJson(cacheFile.readText(), EpgCache::class.java)
                if (c != null && c.programmes.isNotEmpty()) {
                    programmesById = c.programmes
                    nameToId = c.names.toMutableMap()
                    urlToId = c.urls.toMutableMap()
                    nameToCatchup = (c.catchupNames ?: emptyMap()).toMutableMap()
                    urlToCatchup = (c.catchupUrls ?: emptyMap()).toMutableMap()
                    loadedSrc = srcUrl
                    lastStatus = "из кэша: каналов-прог=${programmesById.size}"
                }
            }
            if (programmesById.isNotEmpty()) return@withContext true
        }
        // 2) network
        runCatching {
            nameToId = mutableMapOf(); urlToId = mutableMapOf()
            nameToCatchup = mutableMapOf(); urlToCatchup = mutableMapOf()
            lastStatus = "качаю m3u…"
            val m3u = httpText(srcUrl)
            if (m3u == null) { lastStatus = "m3u не загрузился"; return@runCatching false }
            val xmltvUrl = parseM3u(m3u)
            if (xmltvUrl.isNullOrBlank()) { lastStatus = "в m3u нет url-tvg (каналов: ${nameToId.size})"; return@runCatching false }
            lastStatus = "качаю XMLTV…"
            val ins = httpStream(absolute(xmltvUrl, srcUrl))
            if (ins == null) { lastStatus = "XMLTV не скачался: ${xmltvUrl.take(70)}"; return@runCatching false }
            val deadline = System.currentTimeMillis() + 40_000L      // hard cap: never hang
            ins.use { programmesById = parseXmltv(it, deadline) }
            loadedSrc = srcUrl
            lastStatus = (if (timedOut) "XMLTV таймаут (слишком большой), частично: " else "XMLTV: ") +
                "каналов-прог=${programmesById.size}, m3u-имён=${nameToId.size}"
            if (programmesById.isNotEmpty() && !timedOut) {   // cache only a complete parse
                runCatching {
                    cacheFile.writeText(gson.toJson(EpgCache(
                        System.currentTimeMillis(), programmesById, nameToId, urlToId,
                        nameToCatchup, urlToCatchup)))
                }
            }
            // Too big to finish in time → blacklist so we don't re-download it on every channel switch.
            if (timedOut) deadSrcs.add(srcUrl)
            programmesById.isNotEmpty()
        }.getOrElse { lastStatus = "ошибка: ${it.message}"; false }
    }

    /** Diagnostic: how the current channel maps to the guide. */
    fun matchDebug(title: String?, streamUrl: String?): String {
        val key = title?.let { normalize(it) } ?: ""
        val id = streamUrl?.let { urlToId[it] } ?: nameToId[key]
        return "ключ='$key' id=${id ?: "—"} прог=${id?.let { programmesById[it]?.size } ?: 0} xmltvIds=${programmesById.keys.take(3)}"
    }

    /** Programmes for a channel, newest-relevant first. Empty if unmatched. */
    fun programmes(title: String?, streamUrl: String?): List<EpgProgramme> {
        val id = streamUrl?.let { urlToId[it] }
            ?: title?.let { nameToId[normalize(it)] }
            ?: return emptyList()
        return programmesById[id].orEmpty()
    }

    fun current(title: String?, streamUrl: String?): EpgProgramme? {
        val now = System.currentTimeMillis()
        return programmes(title, streamUrl).firstOrNull { now in it.start until it.stop }
    }

    // ─── m3u ───────────────────────────────────────────────────────
    // Reads `url-tvg`/`x-tvg-url` and per-channel `tvg-id` + name; fills name/url maps.
    private fun parseM3u(text: String): String? {
        var xmltv: String? = null
        val headerTvg = Regex("""(?:url-tvg|x-tvg-url)\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(text.substringBefore('\n'))?.groupValues?.get(1)
            ?: Regex("""(?:url-tvg|x-tvg-url)\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1)
        xmltv = headerTvg?.substringBefore(',')?.trim()   // url-tvg may be comma-separated; take first

        val extinf = Regex("""tvg-id\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
        val catchupRe = Regex("""catchup(?:-type)?\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
        val catchupSrcRe = Regex("""catchup-source\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
        val catchupDaysRe = Regex("""catchup-days\s*=\s*"(\d+)"""", RegexOption.IGNORE_CASE)
        // A playlist-wide default in the #EXTM3U header applies to channels without their own.
        val head = text.substringBefore('\n')
        val headerCatchup = catchupRe.find(head)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let {
            CatchupInfo(it.lowercase(),
                catchupSrcRe.find(head)?.groupValues?.get(1).orEmpty(),
                catchupDaysRe.find(head)?.groupValues?.get(1)?.toIntOrNull() ?: 1)
        }
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXTINF", true)) {
                val tvgId = extinf.find(line)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
                val name = line.substringAfterLast(',').trim()
                // Archive declaration: on the EXTINF itself or in a following #EXTGRP-style tag block.
                var attrs = line
                var j = i + 1
                while (j < lines.size && lines[j].trimStart().startsWith("#")) { attrs += "\n" + lines[j]; j++ }
                val url = lines.getOrNull(j)?.trim()
                val cu = catchupRe.find(attrs)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let {
                    CatchupInfo(it.lowercase(),
                        catchupSrcRe.find(attrs)?.groupValues?.get(1).orEmpty(),
                        catchupDaysRe.find(attrs)?.groupValues?.get(1)?.toIntOrNull() ?: 1)
                } ?: headerCatchup
                if (tvgId != null) {
                    if (name.isNotBlank()) nameToId[normalize(name)] = tvgId
                    if (!url.isNullOrBlank()) urlToId[url] = tvgId
                }
                if (cu != null) {
                    if (name.isNotBlank()) nameToCatchup[normalize(name)] = cu
                    if (!url.isNullOrBlank()) urlToCatchup[url] = cu
                }
                i = j
            } else i++
        }
        return xmltv
    }

    /** The channel's archive declaration from the m3u, or null when none. */
    fun catchup(title: String?, streamUrl: String?): CatchupInfo? =
        streamUrl?.let { urlToCatchup[it] }
            ?: title?.let { nameToCatchup[normalize(it)] }

    // ─── XMLTV ─────────────────────────────────────────────────────
    // deadline bounds total download+parse time (it pulls from the stream), so a
    // huge guide can't hang forever — we keep whatever we parsed before the cutoff.
    private fun parseXmltv(input: InputStream, deadlineMs: Long): Map<String, List<EpgProgramme>> {
        val keepAfter = System.currentTimeMillis() - 9 * 3600_000L   // keep hours of past: archive navigation
        val result = HashMap<String, MutableList<EpgProgramme>>()
        val parser = Xml.newPullParser()
        parser.setInput(input, null)
        var event = parser.eventType
        var curId: String? = null
        var curStart = 0L; var curStop = 0L; var curChan = ""
        var title = ""; var desc = ""
        var text = ""
        var ticks = 0
        timedOut = false
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if ((++ticks and 0x1FFF) == 0 && System.currentTimeMillis() > deadlineMs) { timedOut = true; break }
            when (event) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> when (parser.name) {
                    "channel" -> curId = parser.getAttributeValue(null, "id")
                    "display-name" -> {} // captured via text on END_TAG
                    "programme" -> {
                        curChan = parser.getAttributeValue(null, "channel") ?: ""
                        curStart = parseXmltvTime(parser.getAttributeValue(null, "start"))
                        curStop = parseXmltvTime(parser.getAttributeValue(null, "stop"))
                        title = ""; desc = ""
                    }
                    "title", "desc" -> text = ""
                }
                org.xmlpull.v1.XmlPullParser.TEXT -> text = parser.text ?: ""
                org.xmlpull.v1.XmlPullParser.END_TAG -> when (parser.name) {
                    "display-name" -> {
                        val id = curId
                        val key = if (id != null && text.isNotBlank()) normalize(text) else null
                        if (id != null && key != null && !nameToId.containsKey(key)) nameToId[key] = id
                    }
                    "channel" -> curId = null
                    "title" -> title = text.trim()
                    "desc" -> desc = text.trim()
                    "programme" -> {
                        if (curChan.isNotBlank() && curStop >= keepAfter && curStart > 0) {
                            val list = result.getOrPut(curChan) { mutableListOf() }
                            if (list.size < 60) list.add(EpgProgramme(curStart, curStop, title, desc))
                        }
                    }
                }
            }
            event = parser.next()
        }
        result.values.forEach { it.sortBy { p -> p.start } }
        return result
    }

    /** XMLTV time: "yyyyMMddHHmmss" optionally " +HHMM". */
    private fun parseXmltvTime(s: String?): Long {
        if (s.isNullOrBlank()) return 0L
        return try {
            val m = Regex("""(\d{14})(?:\s*([+-]\d{4}))?""").find(s) ?: return 0L
            val dt = m.groupValues[1]
            val tz = m.groupValues[2].ifBlank { "+0000" }
            val fmt = java.text.SimpleDateFormat("yyyyMMddHHmmssZ", java.util.Locale.US)
            fmt.parse(dt + tz)?.time ?: 0L
        } catch (e: Exception) { 0L }
    }

    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("""\b(hd|fhd|uhd|4k|sd|\+|hevc|h265|h\.265)\b"""), "")
            .replace(Regex("[^a-zа-я0-9]"), "").trim()

    // ─── HTTP ──────────────────────────────────────────────────────
    private fun httpText(url: String): String? =
        httpStream(url)?.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun httpStream(url: String): InputStream? {
        return try {
            var u = URL(url); var redirects = 0
            while (true) {
                val c = (u.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12000; readTimeout = 20000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                    setRequestProperty("Accept-Encoding", "gzip")
                }
                val code = c.responseCode
                if (code in 300..399 && redirects++ < 5) {
                    u = URL(u, c.getHeaderField("Location")); c.disconnect(); continue
                }
                if (code != 200) { c.disconnect(); return null }
                var ins: InputStream = BufferedInputStream(c.inputStream)
                val gz = c.contentEncoding?.contains("gzip", true) == true ||
                    url.endsWith(".gz", true)
                if (gz) ins = GZIPInputStream(ins)
                else {
                    ins.mark(2)
                    val b0 = ins.read(); val b1 = ins.read(); ins.reset()
                    if (b0 == 0x1f && b1 == 0x8b) ins = GZIPInputStream(ins)
                }
                return ins
            }
            @Suppress("UNREACHABLE_CODE") null
        } catch (e: Exception) { null }
    }

    private fun absolute(url: String, base: String): String =
        try { if (url.startsWith("http")) url else URL(URL(base), url).toString() } catch (e: Exception) { url }
}
