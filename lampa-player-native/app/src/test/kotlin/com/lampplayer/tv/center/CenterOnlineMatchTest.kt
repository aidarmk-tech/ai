package com.lampplayer.tv.center

import android.app.Application
import com.lampplayer.tv.domain.model.CardMeta
import com.lampplayer.tv.data.datastore.PositionDataStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[28])
class CenterOnlineMatchTest {
    private val tmdb=CenterTitle(153312,"tv","Король Талсы",original="Tulsa King",year="2022")
    private val source=CenterTitle(240279,"tv","Король Талсы",year="2022",provider="kinovod",sourceUrl="https://kinovod.pro/serial/240279-korol-talsy")
    @Test fun matchingUsesTheYearTypeAndNamesInsteadOfNativeIds() {
        assertEquals(source,CenterOnlineMatch.automatic(tmdb,listOf(source,source)))
        assertNull(CenterOnlineMatch.automatic(tmdb,listOf(source.copy(year="2023"))))
        assertNull(CenterOnlineMatch.automatic(tmdb,listOf(source.copy(type="movie",sourceUrl="https://kinovod.pro/film/240279-title"))))
        assertNull(CenterOnlineMatch.automatic(tmdb,listOf(source.copy(title="Король Талсы 2"))))
        assertNull(CenterOnlineMatch.automatic(tmdb.copy(year=""),listOf(source)))
        assertNull(CenterOnlineMatch.automatic(tmdb,listOf(source.copy(year=""))))
        assertEquals(source.copy(title="Tulsa King (2022)"),CenterOnlineMatch.automatic(tmdb,listOf(source.copy(title="Tulsa King (2022)"))))
    }
    @Test fun duplicateTitlesAndWrongOrUnsupportedUrlsDoNotAutoSelect() {
        val other=source.copy(id=240280,sourceUrl="https://kinovod.pro/serial/240280-another")
        assertNull(CenterOnlineMatch.automatic(tmdb,listOf(source,other)))
        assertEquals(2,CenterOnlineMatch.candidates(tmdb,listOf(source,other)).size)
        assertTrue(CenterOnlineMatch.candidates(tmdb,listOf(source.copy(provider="kinogo"),source.copy(sourceUrl="https://kinovod.pro/serial/999-wrong"),source.copy(sourceUrl="javascript:bad"))).isEmpty())
    }
    @Test fun historyUsesTheSavedSourcePageWithoutReplacingTheTmdbIdentity() {
        val origin=tmdb.copy(sourceUrl=source.sourceUrl)
        val restored=CenterOnlineMatch.fromPage(origin)!!
        assertEquals(source.key,restored.key);assertEquals(tmdb.key,origin.key)
        assertNull(CenterOnlineMatch.fromPage(origin.copy(type="movie")))
        assertNull(CenterOnlineMatch.fromPage(origin.copy(sourceUrl="https://site.test/login")))
        assertNull(CenterOnlineMatch.fromPage(source))
        val video=OnlineVideo(source,emptyList(),emptyMap(),catalogItem=origin)
        assertEquals(tmdb.key,video.identity.key);assertEquals(source.key,video.item.key)
    }
    @Test fun explicitSourceChoiceSurvivesReopeningAndIsIsolatedByTmdbTypeAndId() {
        val app=RuntimeEnvironment.getApplication()
        val store=CenterStore(app);store.setOnlineSource(tmdb,source)
        assertEquals(source,CenterStore(app).onlineSource(tmdb))
        assertNull(store.onlineSource(tmdb.copy(id=999)))
        assertNull(store.onlineSource(tmdb.copy(type="movie")))
        assertTrue(runCatching { store.setOnlineSource(tmdb,source.copy(type="movie")) }.isFailure)
    }
    @Test fun tmdbProgressAndHistoryKeepTheirExistingKeysWhileProviderIdsStaySeparate() {
        val app=RuntimeEnvironment.getApplication()
        val legacy=CardMeta("Title",tmdbId=153312,seasonNumber=2,episodeNumber=5)
        val linked=legacy.copy(contentKey="tmdb:tv:153312",sourcePage=source.sourceUrl)
        val positions=PositionDataStore(app)
        assertEquals(positions.episodeKey(legacy),positions.episodeKey(linked))
        assertEquals(positions.showKey(legacy),positions.showKey(linked))
        assertNotEquals(positions.episodeKey(linked),positions.episodeKey(linked.copy(contentKey="kinovod:tv:153312")))
        val store=CenterStore(app);store.remember("https://old.test/video",legacy);store.remember("https://new.test/video",linked)
        assertEquals(1,store.history().size);assertEquals("https://new.test/video",store.history().single().url)
        assertEquals("tmdb:tv:153312",store.mySeries().single().key)
    }
}
