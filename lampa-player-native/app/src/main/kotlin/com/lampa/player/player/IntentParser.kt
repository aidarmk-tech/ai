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

        val headersBundle = extras?.getBundle("headers")
        val headers = headersBundle?.keySet()
            ?.associateWith { headersBundle.getString(it, "") ?: "" }
            ?: emptyMap()

        val timelineBundle = extras?.getBundle("timeline")

        val genres: List<String> = extras?.getStringArray("genres")?.toList() ?: emptyList()

        // Parse episodes list (Lampa sends JSON string)
        val episodes = parseEpisodes(extras?.getString("episodes"))
        val episodeIndex = extras?.getInt("episode_index", 0) ?: 0

        val card = CardMeta(
            title = extras?.getString("title") ?: extractTitleFromUrl(url),
            originalTitle = extras?.getString("original_title"),
            tmdbId = extras?.getInt("tmdb_id", -1).takeIf { it != null && it > 0 },
            imdbId = extras?.getString("imdb_id"),
            seasonNumber = extras?.getInt("season_number", -1).takeIf { it != null && it > 0 },
            episodeNumber = extras?.getInt("episode_number", -1).takeIf { it != null && it > 0 },
            posterUrl = extras?.getString("poster_url"),
            backdropUrl = extras?.getString("backdrop_url"),
            overview = extras?.getString("overview"),
            releaseYear = extras?.getInt("release_year", -1).takeIf { it != null && it > 0 },
            genres = genres,
            rating = extras?.getFloat("rating", -1f).takeIf { it != null && it > 0 },
            ageRating = extras?.getString("age_rating"),
            quality = extras?.getString("quality"),
            translator = extras?.getString("translator"),
            headers = headers,
            timelineTime = timelineBundle?.getDouble("time"),
            timelineDuration = timelineBundle?.getDouble("duration"),
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
