package com.lampplayer.tv.player

import com.lampplayer.tv.domain.model.CardMeta
import com.lampplayer.tv.domain.model.EpisodeItem

/** An index alone cannot prove that a playlist belongs to the stream being played. */
internal object EpisodeNavigation {
    fun next(episodes: List<EpisodeItem>, currentUrl: String): EpisodeItem? {
        if (currentUrl.isBlank()) return null
        val current = episodes.indexOfFirst { it.url == currentUrl }
        if (current < 0) return null
        val playing = episodes[current]
        return episodes.getOrNull(current + 1)?.takeIf {
            it.url.isNotBlank() && it.url != currentUrl && it.index == playing.index + 1
        }
    }

    fun validateCard(card: CardMeta, currentUrl: String): CardMeta {
        // An IPTV archive URL legitimately differs from the channel's live URL.
        if (card.iptv || card.episodes.isEmpty()) return card
        val current = card.episodes.indexOfFirst { it.url == currentUrl }
        return if (current < 0) card.copy(episodes = emptyList(), currentEpisodeIndex = 0)
        else card.copy(currentEpisodeIndex = current)
    }
}
