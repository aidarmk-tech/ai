package com.lampplayer.tv.center

import android.app.Application
import com.lampplayer.tv.data.datastore.PositionDataStore
import com.lampplayer.tv.domain.model.CardMeta
import com.lampplayer.tv.domain.model.PlaybackPosition
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[28])
class CenterNumAndResumeTest {
    @Test fun returningToOldStreamUsesLatestEpisodePositionIncludingIntentionalReset() = runBlocking {
        val store=PositionDataStore(RuntimeEnvironment.getApplication())
        val card=CardMeta("Resume regression",tmdbId=989891,contentKey="tmdb:tv:989891",seasonNumber=1,episodeNumber=3)
        store.savePosition("https://resume.test/a",card,PlaybackPosition(time=300.0,updated=100))
        store.savePosition("https://resume.test/b",card,PlaybackPosition(time=1200.0,updated=200))
        assertEquals(1200.0,store.getPosition("https://resume.test/a",card)!!.time,0.0)
        store.savePosition("https://resume.test/b",card,PlaybackPosition(time=0.0,updated=300))
        assertEquals(0.0,store.getPosition("https://resume.test/a",card)!!.time,0.0)
        assertNull(store.getPosition("https://resume.test/unseen",card.copy(episodeNumber=4)))
    }
    @Test fun movieAndSeriesWithSameIdKeepPreferencesAndProgressSeparate() = runBlocking {
        val store=PositionDataStore(RuntimeEnvironment.getApplication())
        val movie=CardMeta("Movie",tmdbId=989892,contentKey="tmdb:movie:989892")
        val tv=movie.copy(title="Series",contentKey="tmdb:tv:989892",seasonNumber=1,episodeNumber=1)
        assertNotEquals(store.showKey(movie),store.showKey(tv))
        assertNotEquals(store.episodeKey(movie),store.episodeKey(tv))
        store.savePosition("https://resume.test/movie",movie,PlaybackPosition(time=700.0))
        assertNull(store.getPosition("https://resume.test/tv",tv))
    }
    @Test fun numRoutesAndPaginationPreserveTmdbIdentity() {
        val section=CenterCatalog.sections("num").first()
        val url=CenterCatalog.route("num","https://num.test/old?token=unused",section,2)
        assertEquals("/movies_new",url.encodedPath)
        assertEquals("2",url.queryParameter("page"));assertEquals("ru",url.queryParameter("language"))
        assertNull(url.queryParameter("token"))
        val page=CenterCatalog.numPage("""{"total_pages":3,"results":[
            {"id":17,"media_type":"movie","title":"Фильм","poster_path":"/poster.jpg","release_date":"2026-01-01"},
            {"id":18,"media_type":"tv","name":"Сериал","first_air_date":"2025-01-01"},
            {"id":-1,"title":"Invalid"},{"id":19,"media_type":"person","title":"Invalid"},
            {"id":20,"title":""}]}""",section,2)
        assertTrue(page.hasMore);assertEquals(2,page.size)
        assertEquals("tmdb:movie:17",page[0].key);assertNull(page[0].provider)
        assertEquals("tmdb:tv:18",page[1].key)
        assertEquals("https://image.tmdb.org/t/p/w500/poster.jpg",page[0].poster)
        assertFalse(CenterCatalog.numPage("""{"total_pages":1,"results":[]}""",section,1).hasMore)
        assertTrue(runCatching { CenterCatalog.numPage("{}",section,1) }.isFailure)
    }
    @Test fun numCatalogUsesExplicitTmdbSearchFallback() {
        val store=CenterStore(RuntimeEnvironment.getApplication())
        store.catalog="num"
        assertEquals("tmdb",store.searchCatalog)
        assertFalse(CenterCatalog.isSiteIdentity("num"))
        assertTrue(CenterCatalog.sections("num").any { it.type=="tv" })
    }
}
