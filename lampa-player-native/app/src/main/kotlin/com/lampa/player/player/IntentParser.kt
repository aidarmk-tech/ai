package com.lampa.player.player

import android.content.Intent
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lampa.player.domain.model.CardMeta
import com.lampa.player.domain.model.EpisodeItem

object IntentParser {

    private val gson = Gson()

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
        val jsonStr = uri.getQueryParameter("d") ?: return Pair(url, CardMeta(title = extractTitleFromUrl(url)))
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

        val episodes = str("episodes")?.let { parseEpisodes(it) } ?: emptyList()
        val headers = str("headers")?.let { parseHeaders(it) } ?: emptyMap()

        return CardMeta(
            title = str("title") ?: extractTitleFromUrl(url),
            originalTitle = str("original_title"),
            tmdbId = int("tmdb_id"),
            imdbId = str("imdb_id"),
            seasonNumber = int("season_number"),
            episodeNumber = int("episode_number"),
            posterUrl = str("poster_url"),
            backdropUrl = str("backdrop_url"),
            overview = str("overview"),
            releaseYear = int("release_year"),
            rating = float("rating"),
            quality = str("quality"),
            translator = str("translator"),
            headers = headers,
            timelineTime = double("timeline_time"),
            timelineDuration = double("timeline_duration"),
            episodes = episodes,
            currentEpisodeIndex = int("episode_index") ?: 0,
            epgTitle = str("epg_title"),
            epgStart = str("epg_start"),
            epgEnd = str("epg_end"),
        )
    }

    // ─── Standard ACTION_VIEW with intent extras (backward compat) ─

    private fun parseStandardIntent(intent: Intent): Pair<String, CardMeta>? {
        val url = intent.dataString ?: return null
        val extras = intent.extras

        // Single JSON extra from lmnp.js intent:// launch — preferred path
        val lampaDataJson = extras?.getString("lampa_data")
        if (!lampaDataJson.isNullOrEmpty()) {
            return try {
                val obj = JsonParser.parseString(lampaDataJson).asJsonObject
                Pair(url, cardFromJson(obj, url))
            } catch (e: Exception) {
                Pair(url, CardMeta(title = extractTitleFromUrl(url)))
            }
        }

        val headersBundle = extras?.getBundle("headers")
        val headersJson = extras?.getString("headers_json")
        val headers: Map<String, String> = when {
            headersBundle != null -> headersBundle.keySet()
                ?.associateWith { headersBundle.getString(it, "") ?: "" } ?: emptyMap()
            headersJson != null -> parseHeaders(headersJson)
            else -> emptyMap()
        }

        val timelineBundle = extras?.getBundle("timeline")
        val timelineTime = timelineBundle?.getDouble("time")
            ?: extras?.getDouble("timeline_time", -1.0).takeIf { it != null && it > 0 }
        val timelineDuration = timelineBundle?.getDouble("duration")
            ?: extras?.getDouble("timeline_duration", -1.0).takeIf { it != null && it > 0 }

        val tmdbId = extras?.getInt("tmdb_id", -1)?.takeIf { it > 0 }
            ?: extras?.getString("tmdb_id")?.toIntOrNull()?.takeIf { it > 0 }

        val title = extras?.getString("title")
            ?: extras?.getString("android.intent.extra.TITLE")
            ?: extractTitleFromUrl(url)

        val card = CardMeta(
            title = title,
            originalTitle = extras?.getString("original_title"),
            tmdbId = tmdbId,
            imdbId = extras?.getString("imdb_id"),
            seasonNumber = extras?.getInt("season_number", -1)?.takeIf { it > 0 },
            episodeNumber = extras?.getInt("episode_number", -1)?.takeIf { it > 0 },
            posterUrl = extras?.getString("poster_url"),
            backdropUrl = extras?.getString("backdrop_url"),
            overview = extras?.getString("overview"),
            releaseYear = extras?.getInt("release_year", -1)?.takeIf { it > 0 },
            rating = extras?.getFloat("rating", -1f)?.takeIf { it > 0 },
            quality = extras?.getString("quality"),
            translator = extras?.getString("translator"),
            headers = headers,
            timelineTime = timelineTime,
            timelineDuration = timelineDuration,
            episodes = parseEpisodes(extras?.getString("episodes")),
            currentEpisodeIndex = extras?.getInt("episode_index", 0) ?: 0,
            epgTitle = extras?.getString("epg_title"),
            epgStart = extras?.getString("epg_start"),
            epgEnd = extras?.getString("epg_end"),
        )
        return Pair(url, card)
    }

    // ─── Helpers ───────────────────────────────────────────────────

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

    private fun extractTitleFromUrl(url: String): String =
        url.substringAfterLast("/").substringBeforeLast(".")
            .replace(Regex("[_\\-]+"), " ").trim().ifEmpty { "" }
}
