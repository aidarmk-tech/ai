package com.lampplayer.tv.player

import com.lampplayer.tv.domain.model.CardMeta
import com.lampplayer.tv.domain.model.EpisodeItem
import org.junit.Assert.*
import org.junit.Test

class EpisodeNavigationTest {
    private fun episode(index: Int, url: String) = EpisodeItem(index, "Серия ${index + 1}", url)
    private val carrie = listOf(episode(0, "https://carrie.test/one"), episode(1, "https://carrie.test/two"))
    private val marvel = listOf(episode(0, "https://marvel.test/one"), episode(1, "https://marvel.test/two"))

    @Test fun stalePlaylistDoesNotOfferTheNextMarvelVideoForCarrie() {
        assertNull(EpisodeNavigation.next(marvel, carrie[0].url))
        val incoming = CardMeta("Кэрри", episodes = marvel, currentEpisodeIndex = 0)
        assertTrue(EpisodeNavigation.validateCard(incoming, carrie[0].url).episodes.isEmpty())
    }

    @Test fun realNextEpisodeStillWorksAndStalePositionIsCorrected() {
        assertEquals(carrie[1], EpisodeNavigation.next(carrie, carrie[0].url))
        val incoming = CardMeta("Кэрри", episodes = carrie, currentEpisodeIndex = 1)
        assertEquals(0, EpisodeNavigation.validateCard(incoming, carrie[0].url).currentEpisodeIndex)
    }

    @Test fun unresolvedSecondEpisodeIsNotSkippedInFavourOfAnotherVideo() {
        val list = listOf(carrie[0], episode(1, ""), episode(2, "https://carrie.test/three"))
        assertNull(EpisodeNavigation.next(list, carrie[0].url))
    }

    @Test fun filteredWindowUsesUrlInsteadOfGlobalIndices() {
        val list = listOf(episode(25, "https://carrie.test/26"), episode(27, "https://carrie.test/28"))
        assertNull(EpisodeNavigation.next(list, list[0].url)) // index 26 is unresolved, not episode 28
        val complete = listOf(list[0], episode(26, "https://carrie.test/27"))
        assertEquals(complete[1], EpisodeNavigation.next(complete, complete[0].url))
        assertNull(EpisodeNavigation.next(list, list[1].url))
        assertNull(EpisodeNavigation.next(list, ""))
    }

    @Test fun iptvArchiveKeepsLiveChannelPlaylist() {
        val incoming = CardMeta("IPTV", iptv = true, episodes = carrie, currentEpisodeIndex = 1)
        assertEquals(incoming, EpisodeNavigation.validateCard(incoming, "https://archive.test/programme"))
    }
}
