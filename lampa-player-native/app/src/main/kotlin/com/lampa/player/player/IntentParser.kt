package com.lampa.player.player

import android.content.Intent
import android.os.Build
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.lampa.player.domain.model.CardMeta
import com.lampa.player.domain.model.EpisodeItem

object IntentParser {

    private val gson = Gson()

    fun parse(intent: Intent): Pair<String, CardMeta>? {
        val url = intent.dataString ?: return null
        val extras = intent.extras

        // Headers: Bundle or JSON string (from companion plugin)
        val headersBundle = extras?.getBundle("headers")
        val headersJson = extras?.getString("headers_json")
        val headers: Map<String, String> = when {
            headersBundle != null -> headersBundle.keySet()
                ?.associateWith { headersBundle.getString(it, "") ?: "" } ?: emptyMap()
            headersJson != null -> try {
                val obj = JsonParser.parseString(headersJson).asJsonObject
                obj.entrySet().associate { it.key to it.value.asString }
            } catch (e: Exception) { emptyMap() }
            else -> emptyMap()
        }

        // Timeline: Bundle or flat extras (from companion plugin)
        val timelineBundle = extras?.getBundle("timeline")
        val timelineTime: Double? = timelineBundle?.getDouble("time")
            ?: extras?.getDouble("timeline_time", -1.0).takeIf { it != null && it > 0 }
        val timelineDuration: Double? = timelineBundle?.getDouble("duration")
            ?: extras?.getDouble("timeline_duration", -1.0).takeIf { it != null && it > 0 }

        val genres: List<String> = extras?.getStringArray("genres")?.toList() ?: emptyList()

        val episodes = parseEpisodes(extras?.getString("episodes"))
        val episodeIndex = extras?.getInt("episode_index", 0) ?: 0

        // tmdb_id can come as int or string from the intent scheme
        val tmdbId: Int? = try {
            extras?.getInt("tmdb_id", -1).takeIf { it != null && it > 0 }
                ?: extras?.getString("tmdb_id")?.toIntOrNull()?.takeIf { it > 0 }
        } catch (e: Exception) { null }

        val card = CardMeta(
            title = extras?.getString("title") ?: extractTitleFromUrl(url),
            originalTitle = extras?.getString("original_title"),
            tmdbId = tmdbId,
            imdbId = extras?.getString("imdb_id"),
            seasonNumber = extras?.getInt("season_number", -1).takeIf { it != null && it > 0 }
                ?: extras?.getString("season_number")?.toIntOrNull()?.takeIf { it > 0 },
            episodeNumber = extras?.getInt("episode_number", -1).takeIf { it != null && it > 0 }
                ?: extras?.getString("episode_number")?.toIntOrNull()?.takeIf { it > 0 },
            posterUrl = extras?.getString("poster_url"),
            backdropUrl = extras?.getString("backdrop_url"),
            overview = extras?.getString("overview"),
            releaseYear = extras?.getInt("release_year", -1).takeIf { it != null && it > 0 }
                ?: extras?.getString("release_year")?.toIntOrNull()?.takeIf { it > 0 },
            genres = genres,
            rating = extras?.getFloat("rating", -1f).takeIf { it != null && it > 0 }
                ?: extras?.getString("rating")?.toFloatOrNull()?.takeIf { it > 0 },
            ageRating = extras?.getString("age_rating"),
            quality = extras?.getString("quality"),
            translator = extras?.getString("translator"),
            headers = headers,
            timelineTime = timelineTime,
            timelineDuration = timelineDuration,
            episodes = episodes,
            currentEpisodeIndex = episodeIndex,
            epgTitle = extras?.getString("epg_title"),
            epgStart = extras?.getString("epg_start"),
            epgEnd = extras?.getString("epg_end"),
        )

        return Pair(url, card)
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
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractTitleFromUrl(url: String): String =
        url.substringAfterLast("/").substringBeforeLast(".")
            .replace("_", " ").replace("-", " ").ifEmpty { "Unknown" }
}
