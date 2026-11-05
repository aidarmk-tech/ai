package com.lampplayer.tv.center

import android.app.Application
import com.lampplayer.tv.domain.model.CardMeta
import kotlinx.coroutines.*
import com.google.gson.JsonParser
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.TimeUnit
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
    @Test fun genericTorznabUrlDoesNotNeedTorznabInItsPath() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<rss><channel/></rss>"))
            CenterBackend().searchReleases(server.url("/1/api").toString(), "secret", CenterTitle(1,"movie","Фильм","Movie"), null, "torznab")
            val request = server.takeRequest().requestUrl!!
            assertEquals("/1/api", request.encodedPath)
            assertEquals("search", request.queryParameter("t"))
            assertEquals("Movie", request.queryParameter("q"))
            assertNull(request.queryParameter("search"))
        }
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
    @Test fun cacheChangePreservesOtherSettingsAndUsesTheDocumentedPostEnvelope() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"CacheSize":67108864,"PreloadCache":50,"ConnectionsLimit":25,"Custom":{"enabled":true}}"""))
            server.enqueue(MockResponse().setBody(""))
            CenterBackend().setEngineCache(server.url("/").toString().trimEnd('/'),128)
            val get = server.takeRequest()
            assertEquals("/settings",get.path)
            assertEquals("get",JsonParser.parseString(get.body.readUtf8()).asJsonObject.get("action").asString)
            val set = server.takeRequest()
            assertEquals("POST",set.method)
            val payload=JsonParser.parseString(set.body.readUtf8()).asJsonObject
            assertEquals("set",payload.get("action").asString)
            val values=payload.getAsJsonObject("sets")
            assertEquals(134217728L,values.get("CacheSize").asLong)
            assertEquals(50,values.get("PreloadCache").asInt)
            assertTrue(values.getAsJsonObject("Custom").get("enabled").asBoolean)
        }
    }
    @Test fun returningFromSlowSearchCancelsTheUnderlyingHttpCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val request=async(Dispatchers.Default) { CenterBackend().searchReleases(server.url("/").toString(),"",CenterTitle(1,"movie","Test"),null) }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2,TimeUnit.SECONDS) })
            withTimeout(1500) { request.cancelAndJoin() }
            assertTrue(request.isCancelled)
        }
    }
    @Test fun sourceCheckRejectsHtmlAndUnrelatedJsonInsteadOfReportingSuccess() {
        for (response in listOf("<html><body>Login</body></html>","{\"status\":\"ok\"}","{\"error\":\"bad key\"}")) {
            try { CenterBackend.parseReleases(response); fail("Accepted $response") } catch (_: IllegalStateException) {}
        }
        assertTrue(CenterBackend.parseReleases("<rss><channel/></rss>").isEmpty())
    }
    @Test fun removingHistoryDoesNotRemoveAFilmWithTheSameNumericIdOrFavorites() {
        val context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("media_center",0).edit().clear().commit()
        val store=CenterStore(context)
        val movie=CardMeta("Movie",tmdbId=3)
        val series=CardMeta("Series",tmdbId=3,seasonNumber=1,episodeNumber=1)
        store.remember("movie",movie);store.remember("series",series)
        store.toggle(CenterTitle(3,"tv","Series"))
        store.removeHistory(series)
        assertEquals(listOf("Movie"),store.history().map { it.card.title })
        store.clearHistory();assertTrue(store.history().isEmpty());assertEquals(1,store.favorites().size)
    }


    @Test fun torrentPreloadReportsRealBytesAndUsesARange() = runBlocking {
        val server=MockWebServer();server.start()
        try {
            val progress=java.util.Collections.synchronizedList(mutableListOf<Pair<Long,Long>>())
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Type","video/mp4")
                .setBody(okio.Buffer().write(ByteArray(256*1024))))
            CenterBackend().prepareStream(server.url("/stream/movie.mp4?play").toString()) { bytes,total -> progress.add(bytes to total) }
            assertEquals("bytes=0-262143",server.takeRequest().getHeader("Range"))
            assertEquals(0L,progress.first().first)
            assertEquals(262144L to 262144L,progress.last())
            assertTrue(progress.zipWithNext().all { it.first.first <= it.second.first })
        } finally { server.shutdown() }
    }
    @Test fun torrentPreloadRejectsEmptyResponsesAndHttpErrors() = runBlocking {
        val server=MockWebServer();server.start()
        try {
            for(response in listOf(MockResponse().setResponseCode(404),MockResponse().setBody(""))) {
                server.enqueue(response)
                assertTrue(runCatching { CenterBackend().prepareStream(server.url("/stream/file").toString()) { _,_ -> } }.isFailure)
            }
        } finally { server.shutdown() }
    }
    @Test fun torrentPreloadCanBeCancelledWhileWaitingForTheEngine() = runBlocking {
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val pending=launch(Dispatchers.IO) { CenterBackend().prepareStream(server.url("/stream/file").toString()) { _,_ -> } }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5,TimeUnit.SECONDS)) }
            withTimeout(3000) { pending.cancelAndJoin() }
            assertTrue(pending.isCancelled)
        } finally { server.shutdown() }
    }

    @Test fun torrentMetricsUseEngineFieldsAndShowUnknownForMissingValues() = runBlocking {
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"download_speed":2097152,"active_peers":7,"preloaded_bytes":4194304}"""))
            val metrics=CenterBackend().torrentMetrics(server.url("/").toString().trimEnd('/'),"a".repeat(40))
            assertTrue(metrics.contains("2.0 МБ/с"));assertTrue(metrics.contains("Пиры: 7"));assertTrue(metrics.contains("4.0 МБ"))
            assertTrue(server.takeRequest().body.readUtf8().contains("\"action\":\"get\""))
            server.enqueue(MockResponse().setBody("{}"))
            assertTrue(CenterBackend().torrentMetrics(server.url("/").toString().trimEnd('/'),"a".repeat(40)).contains("Скорость: —"))
        } finally { server.shutdown() }
    }

    @Test fun releaseSortingKeepsUnknownSizesLastAndFiltersSeedsAndQuality() {
        val releases=listOf(
            CenterRelease("Film 1080p","a",0,100,"A"),
            CenterRelease("Film 720p","b",20,0,"B"),
            CenterRelease("Film 1080p","c",10,300,"C"),
            CenterRelease("Film 1080p","d",2,50,"D"),
            CenterRelease("ID 17201 10800","e",40,10,"E"))
        assertEquals(listOf("d","a","c","b"),CenterBackend.selectReleases(releases.take(4),"Все","small",false).map { it.link })
        assertEquals(listOf("c","a","d","b"),CenterBackend.selectReleases(releases.take(4),"Все","large",false).map { it.link })
        assertEquals(listOf("c","d"),CenterBackend.selectReleases(releases,"1080","seeds",true).map { it.link })
        assertEquals(listOf("b"),CenterBackend.selectReleases(releases,"720","seeds",false).map { it.link })
        assertEquals(5,releases.size)
        assertTrue(CenterBackend.selectReleases(releases,"480","seeds",false).isEmpty())
    }

    @Test fun nativeJacredSidPirAndBitrateAreNotLostOrReplacedByZero() {
        val list=CenterBackend.parseReleases("""[
          {"title":"Known","magnet":"magnet:?xt=urn:btih:known","sid":42,"pir":9,"bit_rate":8000000,"trackerName":"rutracker"},
          {"title":"Zero","magnet":"magnet:?xt=urn:btih:zero","sid":0,"pir":0},
          {"title":"Unknown","magnet":"magnet:?xt=urn:btih:unknown"},
          {"title":"Video","magnet":"magnet:?xt=urn:btih:video","ffprobe":[{"codec_type":"audio","bit_rate":"192000"},{"codec_type":"video","bit_rate":"4500000"}]}
        ]""").associateBy { it.title }
        assertEquals(42,list.getValue("Known").seeds);assertEquals(9,list.getValue("Known").leechers)
        assertEquals("8.0 Мбит/с",list.getValue("Known").bitrate);assertEquals("rutracker",list.getValue("Known").tracker)
        assertTrue(list.getValue("Zero").seedsKnown);assertEquals(0,list.getValue("Zero").leechers)
        assertFalse(list.getValue("Unknown").seedsKnown);assertNull(list.getValue("Unknown").leechers)
        assertEquals("Видео 4.5 Мбит/с",list.getValue("Video").bitrate)
        assertEquals(listOf("Known"),CenterBackend.selectReleases(list.values.toList(),"Все","seeds",true).map { it.title })
    }
    @Test fun torznabCountsRetainUnknownAndExplicitZeroSeparately() {
        val list=CenterBackend.parseReleases("""<rss xmlns:torznab="http://torznab.com/schemas/2015/feed"><channel>
        <item><title>Known</title><link>https://source.test/known.torrent</link><torznab:attr name="seeders" value="12"/><torznab:attr name="leechers" value="3"/></item>
        <item><title>Zero</title><link>https://source.test/zero.torrent</link><torznab:attr name="seeders" value="0"/></item>
        <item><title>Unknown</title><link>https://source.test/unknown.torrent</link></item>
        </channel></rss>""").associateBy { it.title }
        assertEquals(12,list.getValue("Known").seeds);assertEquals(3,list.getValue("Known").leechers)
        assertTrue(list.getValue("Zero").seedsKnown);assertFalse(list.getValue("Unknown").seedsKnown)
    }

    @Test fun exactReleaseMatchingUsesTitlesYearAndRequestedEpisode() {
        fun release(title:String)=CenterRelease(title,"magnet:?xt=urn:btih:x",1,100,"A")
        val movie=CenterTitle(1,"movie","Источник",original="The Source",year="2016")
        assertTrue(CenterBackend.matchesRelease(release("Источник [2016] WEB-DL 1080p"),movie))
        assertTrue(CenterBackend.matchesRelease(release("The.Source.2016.HEVC"),movie))
        assertFalse(CenterBackend.matchesRelease(release("Надежда Румянцева [2021]"),movie))
        assertFalse(CenterBackend.matchesRelease(release("Источник [2006]"),movie))
        assertTrue(CenterBackend.matchesRelease(release("IT 2017"),CenterTitle(2,"movie","Оно",original="It",year="2017")))
        val show=CenterTitle(3,"tv","Мой сериал")
        val episode=CenterEpisode(2,5,"","")
        assertTrue(CenterBackend.matchesRelease(release("Мой сериал S02E05 1080p"),show,episode))
        assertTrue(CenterBackend.matchesRelease(release("Мой сериал S02 сезон целиком"),show,episode))
        assertFalse(CenterBackend.matchesRelease(release("Мой сериал S01E05"),show,episode))
        assertFalse(CenterBackend.matchesRelease(release("Мой сериал S02E06"),show,episode))
    }
    @Test fun sourceVoicesAndExplicitCodecTagsAreAvailableBeforePlayback() {
        val result=CenterBackend.parseReleases("""[{"title":"Sorting 1080p HEVC HDR10","magnet":"magnet:?xt=urn:btih:x","voices":["Дубляж","Original","Дубляж"]}]""").single()
        assertEquals(listOf("Дубляж","Original"),result.voices)
        assertTrue(CenterBackend.releaseFormat(result).contains("HEVC"))
        assertTrue(CenterBackend.releaseFormat(result).contains("HDR10"))
        assertEquals("Не указан",CenterBackend.releaseFormat(result.copy(title="Unknown movie")))
    }

    @Test fun libraryFiltersAndSearchKeepTheirParametersAcrossPages() {
        val movies=CenterBrowse(type="movie",mode="new",country="KZ",year=2024,genre=18)
        val p=CenterBackend.browseParameters(movies,3)
        assertEquals("3",p["page"]);assertEquals("KZ",p["with_origin_country"]);assertEquals("2024",p["primary_release_year"])
        assertEquals("18",p["with_genres"]);assertEquals("primary_release_date.desc",p["sort_by"])
        val tv=CenterBackend.browseParameters(movies.copy(type="tv"),2)
        assertEquals("2024",tv["first_air_date_year"]);assertFalse(tv.containsKey("primary_release_year"))
        val search=CenterBackend.browseParameters(CenterBrowse(type="all",query="Источники"),2)
        assertEquals("Источники",search["query"]);assertEquals("2",search["page"])
        assertFalse(search.containsKey("with_genres"))
        assertTrue(runCatching { CenterBackend.browseParameters(movies,501) }.isFailure)
    }
    @Test fun mySeriesCombinesFollowingAndViewingHistoryWithoutMixingProviders() {
        val store=CenterStore(RuntimeEnvironment.getApplication())
        val first=CenterTitle(17,"tv","First",provider="kinovod",sourceUrl="https://source.test/serial/17-first")
        store.toggleFollow(first)
        store.remember("stream",CardMeta("First",contentKey=first.key,seasonNumber=1,episodeNumber=2))
        store.remember("other",CardMeta("Other",contentKey="filmix:tv:17",seasonNumber=2,episodeNumber=1))
        store.remember("movie",CardMeta("Movie",tmdbId=99))
        assertEquals(setOf("kinovod:tv:17","filmix:tv:17"),store.mySeries().map { it.key }.toSet())
        store.toggleFollow(first);assertFalse(store.isFollowed(first))
        assertEquals(2,store.mySeries().size)
    }
}
