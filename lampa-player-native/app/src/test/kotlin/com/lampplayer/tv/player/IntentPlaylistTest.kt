package com.lampplayer.tv.player

import android.app.Application
import android.content.Intent
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class IntentPlaylistTest {
    private fun parse(json: String) = IntentParser.parse(Intent(Intent.ACTION_VIEW,
        Uri.parse("lmnp://play").buildUpon().appendQueryParameter("url", "https://carrie.test/one")
            .appendQueryParameter("d", json).build()))!!.second

    @Test fun pollutedTitleEnvelopeCannotInstallPreviousTitlesPlaylist() {
        val card = parse("""{"title":"Кэрри","pl":{"pi":0,"items":[
            {"u":"https://marvel.test/one","e":1},{"u":"https://marvel.test/two","e":2}]}}""")
        assertEquals("Кэрри", card.title)
        assertTrue(card.episodes.isEmpty())
    }

    @Test fun validEnvelopeCorrectsWrongPlaylistPosition() {
        val card = parse("""{"title":"Кэрри","pl":{"pi":1,"items":[
            {"u":"https://carrie.test/one","e":1},{"u":"https://carrie.test/two","e":2}]}}""")
        assertEquals(2, card.episodes.size)
        assertEquals(0, card.currentEpisodeIndex)
        assertEquals("https://carrie.test/two", EpisodeNavigation.next(card.episodes, "https://carrie.test/one")!!.url)
    }

    @Test fun unresolvedSecondEpisodeInFullHeaderCannotBeSkipped() {
        val card = parse("""{"title":"Кэрри","pl":{"pi":0,"items":[
            {"u":"https://carrie.test/one","e":1},{"u":"","e":2},
            {"u":"https://carrie.test/three","e":3}]}}""")
        assertEquals(2, card.episodes.size)
        assertNull(EpisodeNavigation.next(card.episodes, "https://carrie.test/one"))
    }
}
