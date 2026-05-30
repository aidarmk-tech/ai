package com.lampa.player.player

import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.lampa.player.domain.model.CardMeta

object IntentParser {

    fun parse(intent: Intent): Pair<String, CardMeta>? {
        val url = intent.dataString ?: return null

        val extras = intent.extras
        val title = extras?.getString("title") ?: extractTitleFromUrl(url)

        val headersBundle = extras?.getBundle("headers")
        val headers = headersBundle?.keySet()?.associateWith { headersBundle.getString(it, "") ?: "" }
            ?: emptyMap()

        val timelineBundle = extras?.getBundle("timeline")

        val genres: List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras?.getStringArray("genres")?.toList() ?: emptyList()
        } else {
            @Suppress("DEPRECATION")
            extras?.getStringArray("genres")?.toList() ?: emptyList()
        }

        val card = CardMeta(
            title = title,
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
        )

        return Pair(url, card)
    }

    private fun extractTitleFromUrl(url: String): String {
        return url.substringAfterLast("/").substringBeforeLast(".").replace("_", " ").replace("-", " ")
            .ifEmpty { "Unknown" }
    }
}
