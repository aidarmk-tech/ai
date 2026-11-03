package com.lampplayer.tv.center

import android.app.Application
import com.lampplayer.tv.domain.model.CardMeta
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class CenterBackendTest {
    private val magnet = "magnet:?xt=urn:btih:" + "a".repeat(40)
    @Test fun nativeJacredResultsUseMagnetsAndSortBySeeders() {
        val list = CenterBackend.parseReleases("""[
            {"title":"First","magnet":"$magnet","seeders":3,"size":4000000},
            {"title":"Second","magnet":"${magnet}b","seeders":20},
            {"title":"Info page only","url":"https://tracker.test/topic/1"}]""")
        assertEquals(2, list.size)
        assertEquals("Second", list[0].title)
        assertEquals(4000000, list[1].size)
    }
    @Test fun torznabUsesMagnetAndTorrentEnclosureInsteadOfInfoPage() {
        val xml = """<rss xmlns:torznab="http://torznab.com/schemas/2015/feed"><channel>
            <item><title>Release</title><link>https://tracker.test/info</link>
            <enclosure url="https://tracker.test/file.torrent"/>
            <torznab:attr name="magneturl" value="$magnet"/>
            <torznab:attr name="seeders" value="12"/></item></channel></rss>"""
        val one = CenterBackend.parseReleases(xml).single()
        assertEquals(magnet, one.link); assertEquals(12, one.seeds)
        val withoutMagnet = xml.replace("<torznab:attr name=\"magneturl\" value=\"$magnet\"/>", "")
        assertEquals("https://tracker.test/file.torrent", CenterBackend.parseReleases(withoutMagnet).single().link)
    }
    @Test fun parserRequestUsesConfiguredHostKeyAndSeriesSeason() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[]"))
            CenterBackend().searchReleases(server.url("/").toString(), "secret", CenterTitle(1,"tv","Сериал","Show"), CenterEpisode(2,3,"", ""))
            val request = server.takeRequest().requestUrl!!
            assertEquals("/api/v1.0/torrents", request.encodedPath)
            assertEquals("secret", request.queryParameter("apikey"))
            assertEquals("Сериал", request.queryParameter("search"))
            assertEquals("2", request.queryParameter("season"))
            assertEquals("serial", request.queryParameter("type"))
        }
    }
    @Test fun sourceValidationAndFileFiltering() {
        assertFalse(CenterBackend.playableSource("javascript:alert(1)"))
        assertTrue(CenterBackend.playableSource(magnet))
        assertFalse(CenterBackend.video("Show.S01E02.sample.mkv"))
        assertFalse(CenterBackend.video("Show.S01E02.srt"))
        assertTrue(CenterBackend.video("Show.S01E02.mkv"))
        assertEquals(1 to 2, CenterFile(7,"folder/Show.S01.E02.mkv",0).episode())
        assertEquals(2 to 12, CenterFile(4,"Show.2x12.mp4",0).episode())
    }
    @Test fun streamUsesRealFileIdAndEncodesName() {
        val url = CenterBackend().stream("http://127.0.0.1:9999", "a".repeat(40), CenterFile(17,"folder/Серия 2.mkv",2)).toHttpUrl()
        assertEquals("17",url.queryParameter("index"))
        assertEquals("Серия 2.mkv",url.pathSegments.last())
        assertTrue("play" in url.queryParameterNames)
    }
    @Test fun historyAndFavoritesStaySeparateAcrossMovieAndSeriesIds() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("media_center",0).edit().clear().commit()
        val store = CenterStore(context)
        val movie = CenterTitle(1,"movie","Movie"); val series = CenterTitle(1,"tv","Series")
        store.toggle(movie); store.toggle(series)
        assertEquals(2,store.favorites().size)
        store.toggle(movie); assertFalse(store.isFavorite(movie)); assertTrue(store.isFavorite(series))
        store.remember("http://127.0.0.1:1111/stream/a?link=abc&index=1",CardMeta("Series",tmdbId=1,seasonNumber=1,episodeNumber=1))
        store.remember("http://127.0.0.1:1111/stream/b?link=abc&index=2",CardMeta("Series",tmdbId=1,seasonNumber=1,episodeNumber=2))
        assertEquals(1,store.history().size); assertEquals(2,store.history().single().card.episodeNumber)
        val resumed=CenterStore.rebase(store.history().single().url,"http://127.0.0.1:2222").toHttpUrl()
        assertEquals(2222,resumed.port); assertEquals("2",resumed.queryParameter("index")); assertEquals("abc",resumed.queryParameter("link"))
    }
}
