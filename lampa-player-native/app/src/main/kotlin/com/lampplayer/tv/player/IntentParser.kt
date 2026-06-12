package com.lampplayer.tv.player

import android.content.Intent
import android.net.Uri
import android.os.Parcelable
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lampplayer.tv.domain.model.CardMeta
import com.lampplayer.tv.domain.model.EpisodeItem
import com.lampplayer.tv.domain.model.ExternalSubtitle

object IntentParser {

    private val gson = Gson()

    /** Title envelope used by the companion plugin when the core only forwards `title`. */
    private const val META_PREFIX = "lmpmeta://"

    fun parse(intent: Intent): Pair<String, CardMeta>? {
        val uri = intent.data ?: return null

        return if (uri.scheme == "lmnp") {
            parseLmnpUri(uri)
        } else {
            parseStandardIntent(intent)
        }
    }

    // ─── lmnp://play?url=VIDEO_URL&d=JSON ─────────────────────────

    private fun parseLmnpUri(uri: Uri): Pair<String, CardMeta>? {
        val url = uri.getQueryParameter("url")?.ifEmpty { null } ?: return null
        val jsonStr = uri.getQueryParameter("d")?.let { decodeMaybeBase64(it) }
            ?: return Pair(url, CardMeta(title = extractTitleFromUrl(url)))
        return try {
            val obj = JsonParser.parseString(jsonStr).asJsonObject
            Pair(url, cardFromJson(obj, url))
        } catch (e: Exception) {
            Pair(url, CardMeta(title = extractTitleFromUrl(url)))
        }
    }

    private fun cardFromJson(obj: JsonObject, url: String): CardMeta {
        fun str(key: String) = obj.get(key)?.takeIf { !it.isJsonNull }?.asString
        fun int(key: String) = obj.get(key)?.takeIf { !it.isJsonNull }?.asInt?.takeIf { it > 0 }
        fun float(key: String) = obj.get(key)?.takeIf { !it.isJsonNull }?.asFloat?.takeIf { it > 0 }
        fun double(key: String) = obj.get(key)?.takeIf { !it.isJsonNull }?.asDouble
        fun long(key: String) = obj.get(key)?.takeIf { !it.isJsonNull }?.asLong

        // Playlist window (compact): { items:[{u,e,s,t}], pi } — playable episode URLs.
        val plObj = obj.get("pl")?.takeIf { it.isJsonObject }?.asJsonObject
        val plEpisodes = plObj?.let { parseCompactPlaylist(it) } ?: emptyList()
        val plIndex = plObj?.get("pi")?.takeIf { !it.isJsonNull }?.asInt ?: 0

        val episodes = plEpisodes.ifEmpty { str("episodes")?.let { parseEpisodes(it) } ?: emptyList() }
        val headers = str("headers")?.let { parseHeaders(it) } ?: emptyMap()
        // Subtitles may arrive as a JSON array [{url,name,enable}] or a single string.
        val subtitles = subtitlesFromJson(obj)

        return CardMeta(
            title = cleanTitle(str("title")).ifEmpty { extractTitleFromUrl(url) },
            originalTitle = str("original_title"),
            tmdbId = int("tmdb_id"),
            imdbId = str("imdb_id"),
            seasonNumber = int("season_number") ?: int("season"),
            episodeNumber = int("episode_number") ?: int("episode"),
            posterUrl = str("poster_url") ?: str("poster"),
            backdropUrl = str("backdrop_url") ?: str("backdrop"),
            overview = str("overview"),
            releaseYear = int("release_year") ?: int("year"),
            rating = float("rating"),
            quality = str("quality"),
            translator = str("translator"),
            headers = headers,
            timelineTime = double("timeline_time"),
            timelineDuration = double("timeline_duration"),
            startPositionMs = long("position")?.takeIf { it > 0 },
            fromStart = obj.get("from_start")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
            subtitles = subtitles,
            episodes = episodes,
            currentEpisodeIndex = if (plEpisodes.isNotEmpty()) plIndex else (int("episode_index") ?: 0),
            epgTitle = str("epg_title"),
            epgStart = str("epg_start"),
            epgEnd = str("epg_end"),
            iptv = obj.get("iptv")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
        )
    }

    /** Pull the full playlist + IPTV diagnostic out of the forwarded headers (X-Lmnp-*). */
    private data class HeaderExtras(
        val headers: Map<String, String>,
        val episodes: List<EpisodeItem>,
        val playlistIndex: Int,
        val debugInfo: String?,
        val source: String?,
        val epg: String?,
        val epgMap: Map<String, String> = emptyMap(),
        val epgTs: Long = 0L,
    )

    private fun extractHeaderExtras(headers: Map<String, String>): HeaderExtras {
        if (headers.isEmpty()) return HeaderExtras(headers, emptyList(), 0, null, null, null)
        val plKey = headers.keys.firstOrNull { it.equals("X-Lmnp-Pl", true) }
        val dbgKey = headers.keys.firstOrNull { it.equals("X-Lmnp-Dbg", true) }
        val srcKey = headers.keys.firstOrNull { it.equals("X-Lmnp-Src", true) }
        val epgKey = headers.keys.firstOrNull { it.equals("X-Lmnp-Epg", true) }
        val epgMapKey = headers.keys.firstOrNull { it.equals("X-Lmnp-EpgMap", true) }
        if (plKey == null && dbgKey == null && srcKey == null && epgKey == null && epgMapKey == null)
            return HeaderExtras(headers, emptyList(), 0, null, null, null)

        val clean = headers.filterKeys { it != plKey && it != dbgKey && it != srcKey && it != epgKey && it != epgMapKey }
        var episodes: List<EpisodeItem> = emptyList()
        var pi = 0
        plKey?.let { k ->
            runCatching {
                val obj = JsonParser.parseString(decodeMaybeBase64(headers.getValue(k))).asJsonObject
                episodes = parseCompactPlaylist(obj)
                pi = obj.get("pi")?.takeIf { !it.isJsonNull }?.asInt ?: 0
            }
        }
        val dbg = dbgKey?.let { runCatching { decodeMaybeBase64(headers.getValue(it)) }.getOrNull() }
        // src is a plain URL (not JSON) — always base64-decode it.
        val src = srcKey?.let {
            runCatching { String(Base64.decode(headers.getValue(it), Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()
        }
        val epg = epgKey?.let {
            runCatching { String(Base64.decode(headers.getValue(it), Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()
        }
        // Programme map: base64 → { ts: epochMs, ch: { normName: text } }.
        var epgMap: Map<String, String> = emptyMap()
        var epgTs = 0L
        epgMapKey?.let { k ->
            runCatching {
                val o = JsonParser.parseString(
                    String(Base64.decode(headers.getValue(k), Base64.DEFAULT), Charsets.UTF_8)
                ).asJsonObject
                epgTs = o.get("ts")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                epgMap = o.get("ch")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.entrySet()
                    ?.mapNotNull { (name, v) -> v.takeIf { it.isJsonPrimitive }?.asString?.let { name to it } }
                    ?.toMap()
                    ?: emptyMap()
            }
        }
        return HeaderExtras(clean, episodes, pi, dbg, src, epg, epgMap, epgTs)
    }

    // ─── Standard ACTION_VIEW with intent extras (backward compat) ─

    private fun parseStandardIntent(intent: Intent): Pair<String, CardMeta>? {
        val url = intent.dataString ?: return null
        val extras = intent.extras

        // ── Rich-metadata channels ──────────────────────────────────
        // 1. Single JSON extra from lmnp.js intent:// launch (preferred).
        // 2. `lampa_meta` extra carrying base64(UTF-8 JSON) — spec "на вырост".
        // 3. `title` carrying the `lmpmeta://<base64 JSON>` envelope — the
        //    primary byLampa channel, since the core reliably forwards `title`.
        val metaJson = extras?.getString("lampa_data")
            ?: extras?.getString("lampa_meta")?.let { decodeMaybeBase64(it) }
            ?: extras?.getString("title")?.takeIf { it.startsWith(META_PREFIX) }
                ?.removePrefix(META_PREFIX)?.let { decodeMaybeBase64(it) }
            ?: extras?.getString("android.intent.extra.TITLE")?.takeIf { it.startsWith(META_PREFIX) }
                ?.removePrefix(META_PREFIX)?.let { decodeMaybeBase64(it) }
        if (!metaJson.isNullOrEmpty()) {
            val card = runCatching { cardFromJson(JsonParser.parseString(metaJson).asJsonObject, url) }
                .getOrElse { CardMeta(title = extractTitleFromUrl(url)) }
            // Merge in MX/VLC-style extras (subs, position, headers) that may also be present.
            return Pair(url, mergeStandardExtras(card, intent, extras))
        }

        val hx = extractHeaderExtras(parseHeadersFromExtras(extras))
        val headers = hx.headers

        val timelineBundle = extras?.getBundle("timeline")
        val timelineTime = timelineBundle?.getDouble("time")
            ?: extras?.getDouble("timeline_time", -1.0).takeIf { it != null && it > 0 }
        val timelineDuration = timelineBundle?.getDouble("duration")
            ?: extras?.getDouble("timeline_duration", -1.0).takeIf { it != null && it > 0 }

        val tmdbId = extras?.getInt("tmdb_id", -1)?.takeIf { it > 0 }
            ?: extras?.getString("tmdb_id")?.toIntOrNull()?.takeIf { it > 0 }

        val title = cleanTitle(extras?.getString("title") ?: extras?.getString("android.intent.extra.TITLE"))
            .ifEmpty { extractTitleFromUrl(url) }

        val card = CardMeta(
            title = title,
            originalTitle = extras?.getString("original_title"),
            tmdbId = tmdbId,
            imdbId = extras?.getString("imdb_id"),
            seasonNumber = extras?.getInt("season_number", -1)?.takeIf { it > 0 },
            episodeNumber = extras?.getInt("episode_number", -1)?.takeIf { it > 0 },
            // Lampa torrent/standard launches send a plain `poster` (not `poster_url`).
            posterUrl = imageUrl(extras?.getString("poster_url") ?: extras?.getString("poster")
                ?: extras?.getString("img")),
            backdropUrl = imageUrl(extras?.getString("backdrop_url") ?: extras?.getString("background")
                ?: extras?.getString("backdrop")),
            overview = extras?.getString("overview") ?: extras?.getString("description"),
            releaseYear = extras?.getInt("release_year", -1)?.takeIf { it > 0 },
            rating = extras?.getFloat("rating", -1f)?.takeIf { it > 0 },
            quality = extras?.getString("quality"),
            translator = extras?.getString("translator"),
            headers = headers,
            timelineTime = timelineTime,
            timelineDuration = timelineDuration,
            startPositionMs = parsePositionMs(extras),
            fromStart = extras?.getBoolean("from_start", false) ?: false,
            subtitles = parseSubtitlesFromExtras(intent, extras),
            episodes = hx.episodes.ifEmpty { parseEpisodes(extras?.getString("episodes")) },
            currentEpisodeIndex = if (hx.episodes.isNotEmpty()) hx.playlistIndex else (extras?.getInt("episode_index", 0) ?: 0),
            epgTitle = extras?.getString("epg_title"),
            epgStart = extras?.getString("epg_start"),
            epgEnd = extras?.getString("epg_end"),
            debugInfo = hx.debugInfo,
            iptvSource = hx.source,
            iptvEpg = hx.epg,
            iptvEpgMap = hx.epgMap,
            iptvEpgTs = hx.epgTs,
        )
        return Pair(url, card)
    }

    /** Overlay MX/VLC-style extras onto a card already built from a metadata envelope. */
    private fun mergeStandardExtras(card: CardMeta, intent: Intent, extras: android.os.Bundle?): CardMeta {
        val hx = extractHeaderExtras(parseHeadersFromExtras(extras))
        val extraSubs = parseSubtitlesFromExtras(intent, extras)
        val pos = parsePositionMs(extras)
        return card.copy(
            headers = if (card.headers.isNotEmpty()) card.headers else hx.headers,
            // Full playlist from the header wins over the small title-envelope window.
            episodes = if (hx.episodes.isNotEmpty()) hx.episodes else card.episodes,
            currentEpisodeIndex = if (hx.episodes.isNotEmpty()) hx.playlistIndex else card.currentEpisodeIndex,
            debugInfo = hx.debugInfo ?: card.debugInfo,
            iptvSource = hx.source ?: card.iptvSource,
            iptvEpg = hx.epg ?: card.iptvEpg,
            iptvEpgMap = if (hx.epgMap.isNotEmpty()) hx.epgMap else card.iptvEpgMap,
            iptvEpgTs = if (hx.epgMap.isNotEmpty()) hx.epgTs else card.iptvEpgTs,
            subtitles = if (extraSubs.isNotEmpty()) card.subtitles + extraSubs else card.subtitles,
            startPositionMs = card.startPositionMs ?: pos,
            fromStart = card.fromStart || (extras?.getBoolean("from_start", false) ?: false),
        )
    }

    // ─── Resume position (MX/VLC `position` in milliseconds) ───────

    private fun parsePositionMs(extras: android.os.Bundle?): Long? {
        if (extras == null || !extras.containsKey("position")) return null
        // MX Player stores `position` as int ms; others as long. Try both.
        val asLong = runCatching { extras.getLong("position", -1L) }.getOrDefault(-1L)
        if (asLong > 0) return asLong
        val asInt = runCatching { extras.getInt("position", -1) }.getOrDefault(-1)
        return asInt.toLong().takeIf { it > 0 }
    }

    // ─── Headers ───────────────────────────────────────────────────
    // Supports: Bundle `headers`, JSON `headers_json`, and MX-style
    // `headers` String[] of alternating key/value pairs.

    private fun parseHeadersFromExtras(extras: android.os.Bundle?): Map<String, String> {
        if (extras == null) return emptyMap()
        extras.getBundle("headers")?.let { b ->
            return b.keySet()?.associateWith { b.getString(it, "") ?: "" } ?: emptyMap()
        }
        extras.getStringArray("headers")?.let { arr ->
            val map = LinkedHashMap<String, String>()
            var i = 0
            while (i + 1 < arr.size) { map[arr[i]] = arr[i + 1]; i += 2 }
            if (map.isNotEmpty()) return map
        }
        extras.getString("headers_json")?.let { return parseHeaders(it) }
        return emptyMap()
    }

    // ─── Subtitles (MX Player `subs` / VLC `subtitles_location`) ───

    private fun parseSubtitlesFromExtras(intent: Intent, extras: android.os.Bundle?): List<ExternalSubtitle> {
        if (extras == null) return emptyList()
        val result = mutableListOf<ExternalSubtitle>()

        // MX Player style: subs (Uri[]), subs.name (String[]), subs.enable (int index)
        val subUris: Array<Uri> = runCatching {
            @Suppress("DEPRECATION")
            (intent.getParcelableArrayExtra("subs"))?.filterIsInstance<Uri>()?.toTypedArray()
        }.getOrNull() ?: emptyArray()
        if (subUris.isNotEmpty()) {
            val names = extras.getStringArray("subs.name")
            val enableIdx = if (extras.containsKey("subs.enable")) extras.getInt("subs.enable", 0) else 0
            subUris.forEachIndexed { i, u ->
                result += ExternalSubtitle(
                    uri = u.toString(),
                    name = names?.getOrNull(i),
                    enabled = i == enableIdx,
                )
            }
        }

        // VLC style: subtitles_location (single string path/uri)
        extras.getString("subtitles_location")?.takeIf { it.isNotBlank() }?.let {
            result += ExternalSubtitle(uri = it, name = null, enabled = result.isEmpty())
        }
        return result
    }

    private fun subtitlesFromJson(obj: JsonObject): List<ExternalSubtitle> {
        val el = obj.get("subtitles") ?: obj.get("subs") ?: return emptyList()
        if (el.isJsonNull) return emptyList()
        return try {
            when {
                el.isJsonArray -> el.asJsonArray.mapNotNull { item ->
                    when {
                        item.isJsonObject -> {
                            val o = item.asJsonObject
                            val uri = (o.get("url") ?: o.get("uri"))?.asString ?: return@mapNotNull null
                            ExternalSubtitle(
                                uri = uri,
                                name = (o.get("label") ?: o.get("name"))?.takeIf { !it.isJsonNull }?.asString,
                                enabled = o.get("enable")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
                            )
                        }
                        item.isJsonPrimitive -> ExternalSubtitle(uri = item.asString)
                        else -> null
                    }
                }
                el.isJsonPrimitive -> listOf(ExternalSubtitle(uri = el.asString))
                else -> emptyList()
            }
        } catch (e: Exception) { emptyList() }
    }

    // ─── Helpers ───────────────────────────────────────────────────

    /** Compact playlist window from the title envelope: { items:[{u,e,s,t}], pi }. */
    private fun parseCompactPlaylist(obj: JsonObject): List<EpisodeItem> {
        val arr = obj.get("items")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return arr.mapIndexedNotNull { i, el ->
            val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapIndexedNotNull null
            val u = o.get("u")?.takeIf { !it.isJsonNull }?.asString ?: return@mapIndexedNotNull null
            if (u.isBlank()) return@mapIndexedNotNull null
            val ep = o.get("e")?.takeIf { !it.isJsonNull }?.asInt
            EpisodeItem(
                index = i,
                title = o.get("t")?.takeIf { !it.isJsonNull }?.asString?.ifBlank { null } ?: "Серия ${ep ?: i + 1}",
                url = u,
                season = o.get("s")?.takeIf { !it.isJsonNull }?.asInt,
                episode = ep,
                logoUrl = o.get("l")?.takeIf { !it.isJsonNull }?.asString?.ifBlank { null },
            )
        }
    }

    private fun parseEpisodes(json: String?): List<EpisodeItem> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JsonParser.parseString(json).asJsonArray
            arr.mapIndexed { i, el ->
                val obj = el.asJsonObject
                EpisodeItem(
                    index = i,
                    title = obj.get("title")?.asString
                        ?: obj.get("name")?.asString
                        ?: "Серия ${i + 1}",
                    url = obj.get("url")?.asString ?: "",
                    season = obj.get("season")?.asInt,
                    episode = obj.get("episode")?.asInt,
                )
            }.filter { it.url.isNotEmpty() }
        } catch (e: Exception) { emptyList() }
    }

    private fun parseHeaders(json: String): Map<String, String> = try {
        val obj = JsonParser.parseString(json).asJsonObject
        obj.entrySet().associate { it.key to it.value.asString }
    } catch (e: Exception) { emptyMap() }

    /** Decode a base64(UTF-8) payload; returns the input unchanged if it isn't valid base64. */
    private fun decodeMaybeBase64(value: String): String = try {
        val bytes = Base64.decode(value, Base64.DEFAULT)
        val decoded = String(bytes, Charsets.UTF_8)
        // Heuristic: a metadata envelope decodes to JSON. If not, assume it was plain text.
        if (decoded.trimStart().startsWith("{") || decoded.trimStart().startsWith("[")) decoded else value
    } catch (e: Exception) { value }

    private fun extractTitleFromUrl(url: String): String =
        url.substringAfterLast("/").substringBeforeLast(".")
            .replace(Regex("[_\\-]+"), " ").trim().ifEmpty { "" }

    /** Normalize an image value: pass full URLs through, expand bare TMDB paths ("/x.jpg"). */
    private fun imageUrl(v: String?): String? {
        val s = v?.trim()?.ifEmpty { null } ?: return null
        return when {
            s.startsWith("http", true) -> s
            s.startsWith("/") -> "https://image.tmdb.org/t/p/w500$s"
            else -> s
        }
    }

    /** Strip noise tags Lampa/TorrServe prepend to torrent titles, e.g. "[LAMPA] Фильм". */
    private fun cleanTitle(raw: String?): String {
        if (raw == null) return ""
        return raw.replace(Regex("""^\s*\[(?:lampa|torrent|ts|torrserve|торрент)]\s*""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    /**
     * Human-readable dump of exactly what arrived in the launch intent — used by the
     * in-player diagnostic overlay to see which metadata channel (if any) survived.
     */
    fun debugDump(intent: Intent): String = buildString {
        append("action: ").append(intent.action ?: "—").append('\n')
        append("scheme: ").append(intent.data?.scheme ?: "—").append('\n')
        append("type:   ").append(intent.type ?: "—").append('\n')
        val data = intent.dataString
        append("data:   ").append(if (data != null) data.take(90) else "—").append('\n')
        val ex = intent.extras
        if (ex == null) {
            append("extras: <NONE>")
            return@buildString
        }
        val keys = ex.keySet()
        append("extras (").append(keys.size).append("): ").append(keys.joinToString(", ")).append('\n')
        // Highlight the channels we care about
        listOf("title", "lampa_data", "lampa_meta").forEach { key ->
            if (ex.containsKey(key)) {
                val v = runCatching { ex.getString(key) }.getOrNull() ?: ex.get(key)?.toString()
                append("  • ").append(key).append(" = ").append(v?.take(120) ?: "—").append('\n')
            } else {
                append("  • ").append(key).append(": <absent>\n")
            }
        }
    }
}
