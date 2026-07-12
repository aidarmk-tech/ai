package com.lampplayer.tv.domain.model

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
    // Explicit resume position in milliseconds (MX/VLC-style `position` extra).
    // Takes priority over timelineTime when present; `fromStart` forces start at 0.
    val startPositionMs: Long? = null,
    val fromStart: Boolean = false,
    // External subtitle tracks passed via intent (MX `subs` / VLC `subtitles_location`).
    val subtitles: List<ExternalSubtitle> = emptyList(),
    // Episodes list for serials (from Lampa JSON)
    val episodes: List<EpisodeItem> = emptyList(),
    val currentEpisodeIndex: Int = 0,
    // EPG data for IPTV
    val epgTitle: String? = null,
    val epgStart: String? = null,
    val epgEnd: String? = null,
    // IPTV channel list (vs series episodes) + diagnostic dump for EPG bring-up.
    val iptv: Boolean = false,
    // Запуск сразу в архив конкретной передачи (из каталога lmtv): границы в секундах.
    val archiveStartSec: Long? = null,
    val archiveEndSec: Long? = null,
    val archiveTitle: String? = null,
    val debugInfo: String? = null,
    // IPTV m3u source URL (carries url-tvg/XMLTV + tvg-id) for EPG.
    val iptvSource: String? = null,
    // Current-channel programme scraped from Lampa's rendered EPG (ready to show).
    val iptvEpg: String? = null,
    // Programme map for ALL channels rendered in Lampa's list at launch
    // (normalized name → text) — used when switching channels in-player.
    val iptvEpgMap: Map<String, String> = emptyMap(),
    // When the map was scraped (epoch ms): entries go stale as programmes end.
    val iptvEpgTs: Long = 0L,
)

/** External subtitle track supplied through the launch intent. */
data class ExternalSubtitle(
    val uri: String,
    val name: String? = null,
    val enabled: Boolean = false,
)

val CardMeta.isSerial: Boolean get() = seasonNumber != null || episodes.isNotEmpty()
val CardMeta.isIptv: Boolean get() = !isSerial && episodes.isEmpty() && epgTitle != null
