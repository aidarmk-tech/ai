package com.lampplayer.tv.center

import android.app.Application
import com.lampplayer.tv.data.datastore.PositionDataStore
import com.lampplayer.tv.domain.model.CardMeta
import com.lampplayer.tv.domain.model.PlaybackPosition
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
    @Test fun numRecoversFromAnEmptyFirstPageWithoutLosingItsPagination() = runBlocking {
        MockWebServer().use { server ->
            val empty = """{"page":1,"total_pages":1,"total_results":0,"results":[]}"""
            server.enqueue(MockResponse().setBody(empty))
            server.enqueue(MockResponse().setBody(
                """{"page":2,"total_pages":3,"results":[{"id":17,"title":"Фильм"}]}"""
            ))
            val http = OkHttpClient()
            val catalog = CenterCatalog(Call.Factory { request ->
                val local = server.url(request.url.encodedPath + "?" + request.url.encodedQuery)
                http.newCall(request.newBuilder().url(local).build())
            })
            val base = CenterCatalog.defaultHost("num")
            val section = CenterCatalog.sections("num").first()
            val page = catalog.browse("num", base, section) as CenterTitlePage
            assertEquals(listOf("tmdb:movie:17"), page.map { it.key })
            assertEquals(2, page.sourcePage)
            assertTrue(page.hasMore)
            assertEquals("/movies_new?page=1&language=ru", server.takeRequest().path)
            assertEquals("/movies_new?page=2&language=ru", server.takeRequest().path)
            server.enqueue(MockResponse().setBody(
                """{"page":3,"total_pages":3,"results":[{"id":19,"title":"Следующий фильм"}]}"""
            ))
            val next = catalog.browse("num", base, section, requireNotNull(page.sourcePage) + 1) as CenterTitlePage
            assertEquals("tmdb:movie:19", next.single().key)
            assertFalse(next.hasMore)
            assertEquals("/movies_new?page=3&language=ru", server.takeRequest().path)

            catalog.clearCache()
            server.enqueue(MockResponse().setBody(empty))
            server.enqueue(MockResponse().setBody(empty))
            val unavailable = runCatching { catalog.browse("num", base, section) }
            assertTrue(unavailable.exceptionOrNull()?.message.orEmpty().startsWith("NUM сейчас не отдаёт"))
            server.enqueue(MockResponse().setBody(
                """{"page":1,"total_pages":1,"results":[{"id":18,"title":"Новинка"}]}"""
            ))
            assertEquals("tmdb:movie:18", catalog.browse("num", base, section).single().key)
            assertEquals(6, server.requestCount)
        }
    }

    @Test fun numCatalogUsesExplicitTmdbSearchFallback() {
        val store=CenterStore(RuntimeEnvironment.getApplication())
        store.catalog="num"
        assertEquals("tmdb",store.searchCatalog)
        assertFalse(CenterCatalog.isSiteIdentity("num"))
        assertTrue(CenterCatalog.sections("num").any { it.type=="tv" })
    }
}
