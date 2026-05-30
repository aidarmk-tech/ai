package com.lampa.player.domain.model

data class CardMeta(
    val title: String,
    val originalTitle: String? = null,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val overview: String? = null,
    val releaseYear: Int? = null,
    val genres: List<String> = emptyList(),
    val rating: Float? = null,
    val ageRating: String? = null,
    val quality: String? = null,
    val translator: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val timelineTime: Double? = null,
    val timelineDuration: Double? = null,
    // Episodes list for serials (from Lampa JSON)
    val episodes: List<EpisodeItem> = emptyList(),
    val currentEpisodeIndex: Int = 0,
    // EPG data for IPTV
    val epgTitle: String? = null,
    val epgStart: String? = null,
    val epgEnd: String? = null,
)

val CardMeta.isSerial: Boolean get() = seasonNumber != null || episodes.isNotEmpty()
val CardMeta.isIptv: Boolean get() = !isSerial && episodes.isEmpty() && epgTitle != null
