package com.lampplayer.tv.center

import android.app.Application
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[28])
class CenterOnlineTest {
    private val show=CenterTitle(17,"tv","Мой сериал",provider="kinovod")
    @Test fun seasonAndVoiceFoldersKeepTheExactEpisodeAndQualityUrls() {
        val snapshot=JsonParser.parseString("""{"lists":[[{"title":"Сезон 2","folder":[{"title":"Дубляж","folder":[
            {"title":"Серия 3","file":"[720p]https://cdn.test/3.m3u8,[1080p]https://cdn.test/3-high.m3u8"},
            {"title":"Серия 4","file":"[720p]https://cdn.test/4.m3u8"}]}]}]]}""").asJsonObject
        val result=CenterOnline.decode(show,snapshot)
        assertEquals(listOf(3,4),result.map { it.number });assertEquals(listOf(2,2),result.map { it.season })
        assertEquals("Дубляж",result[0].voice);assertEquals("https://cdn.test/3-high.m3u8",result[0].sources[1080])
    }
    @Test fun repeatedObservationsAreDeduplicatedButConflictingEncodesStayExplicit() {
        val row="""{"title":"S01E02","file":"https://cdn.test/two.mp4"}"""
        val snapshot=JsonParser.parseString("""{"lists":[[$row],[$row,{"title":"S01E02","file":"https://cdn.test/other.mp4"}]]}""").asJsonObject
        assertEquals(2,CenterOnline.decode(show,snapshot).size)
    }
    @Test fun unlabeledTvFilesAndMetadataUrlsDoNotBecomeEpisodes() {
        val snapshot=JsonParser.parseString("""{"lists":[[{"title":"Реклама","file":"https://cdn.test/ad.mp4"},{"title":"Серия 1","file":"https://cdn.test/list.txt"}]],"files":["https://cdn.test/movie.mp4"]}""").asJsonObject
        assertTrue(CenterOnline.decode(show,snapshot).isEmpty())
        assertTrue(CenterOnline.sources("javascript:alert(1)").isEmpty())
    }
    @Test fun filmixGuestQualityLimitIsHonoredWithoutInventingHigherUrls() {
        val movie=CenterTitle(1,"movie","Movie",provider="filmix")
        val snapshot=JsonParser.parseString("""{"files":["[480p]https://cdn.test/low.mp4,[1080p]https://cdn.test/high.mp4"],"cap":480}""").asJsonObject
        assertEquals(setOf(480),CenterOnline.decode(movie,snapshot).single().sources.keys)
        snapshot.addProperty("cap",2160)
        assertEquals(setOf(480,1080),CenterOnline.decode(movie,snapshot).single().sources.keys)
    }
    @Test fun aMissingSeasonIsNotInventedAsSeasonOne() {
        val snapshot=JsonParser.parseString("""{"lists":[[{"title":"7 серия","file":"https://cdn.test/seven.m3u8"}]]}""").asJsonObject
        val episode=CenterOnline.decode(show,snapshot).single()
        assertEquals(0,episode.season);assertEquals(7,episode.number)
    }
    @Test fun aDifferentTitleIdCannotBeResolvedUnderTheSelectedCard() {
        try { CenterOnline.source(show.copy(sourceUrl="https://site.test/serial/999-other"));fail("Accepted a foreign title") }
        catch(_:IllegalStateException) {}
        assertEquals("/serial/17-test",CenterOnline.source(show.copy(sourceUrl="https://site.test/serial/17-test")).encodedPath)
    }
    @Test fun canonicalPageNavigationKeepsTheOriginAndContentIdentity() {
        val movie=CenterTitle(17,"movie","Movie",provider="filmix",sourceUrl="https://site.test/film/drama/17-old.html")
        assertTrue(CenterOnline.sameTitleNavigation(movie,"https://site.test/film/drama/17-canonical.html"))
        assertFalse(CenterOnline.sameTitleNavigation(movie,"https://site.test/film/drama/999-other.html"))
        assertFalse(CenterOnline.sameTitleNavigation(movie,"https://site.test/seria/drama/17-other.html"))
        assertFalse(CenterOnline.sameTitleNavigation(movie,"https://ads.test/film/drama/17-canonical.html"))
        assertFalse(CenterOnline.sameTitleNavigation(movie,"http://site.test/film/drama/17-canonical.html"))
        assertFalse(CenterOnline.sameTitleNavigation(movie,"https://site.test:8443/film/drama/17-canonical.html"))
        assertFalse(CenterOnline.sameTitleNavigation(movie,"https://site.test/login"))
        assertTrue(CenterOnline.sameTitleNavigation(show.copy(sourceUrl="https://site.test/serial/17-old"),"https://site.test/serial/17-canonical"))
    }


    @Test fun missingManifestFallsBackOnlyToVerifiedVideo() = kotlinx.coroutines.runBlocking {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(404))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type","video/mp4").setBody("0000ftypisom"))
            val hls=server.url("/video.mp4/master-v1-a1.m3u8?token=test").toString()
            val resolved=CenterOnline.playableUrl(hls,mapOf("Referer" to "https://source.test/film/1"))
            assertEquals(server.url("/video.mp4?token=test").toString(),resolved)
            assertEquals("/video.mp4/master-v1-a1.m3u8?token=test",server.takeRequest().path)
            assertEquals("/video.mp4?token=test",server.takeRequest().path)
        }
    }
    @Test fun validHlsRemainsUnchangedAndAccessDeniedNeverTriggersMp4Fallback() = kotlinx.coroutines.runBlocking {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            val url=server.url("/video.mp4/master.m3u8").toString()
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("#EXTM3U\n#EXTINF:1,\nsegment.ts\n#EXT-X-ENDLIST"))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type","video/mp2t").setBody("G"))
            assertEquals(url,CenterOnline.playableUrl(url,emptyMap()))
            server.takeRequest();assertEquals("/video.mp4/segment.ts",server.takeRequest().path)
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(403))
            assertTrue(runCatching { CenterOnline.playableUrl(url,emptyMap()) }.isFailure)
            assertEquals(3,server.requestCount)
        }
    }
    @Test fun htmlIsNotAcceptedAsTheDirectVideoFallback() = kotlinx.coroutines.runBlocking {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(404))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type","text/html").setBody("<html>Login</html>"))
            assertTrue(runCatching { CenterOnline.playableUrl(server.url("/video.mp4/master.m3u8").toString(),emptyMap()) }.isFailure)
        }
    }
    @Test fun kinogoIdentityAndOnlineScopeStayExplicit() {
        val item=CenterTitle(6873,"tv","Гангстерленд",provider="kinogo",sourceUrl="https://kz.kinogo.moi/6873-gangsterlend-2025.html")
        assertEquals(6873,CenterOnline.source(item).encodedPath.substringAfter('/').substringBefore('-').toInt())
        assertTrue(CenterOnline.sameTitleNavigation(item,"https://kz.kinogo.moi/6873-canonical.html"))
        assertFalse(CenterOnline.sameTitleNavigation(item,"https://kz.kinogo.moi/6874-other.html"))
        assertFalse(CenterOnline.sameTitleNavigation(item,"https://foreign.test/6873-other.html"))
        assertTrue(CenterOnline.onlineSupported("kinogo"));assertTrue(CenterOnline.onlineSupported("kinovod"))
        assertFalse(CenterOnline.onlineSupported("filmix"));assertFalse(CenterOnline.onlineSupported("hdrezka"))
        try { CenterOnline.source(item.copy(type="all"));fail("Accepted an unknown media type") } catch(_:IllegalStateException) {}
    }

    @Test fun successfulManifestWithMissingSegmentFailsBeforePlayerLaunch() = kotlinx.coroutines.runBlocking {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("\uFEFF#EXTM3U\n#EXTINF:4,\nmissing.ts\n"))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(404))
            val error=runCatching { CenterOnline.playableUrl(server.url("/stream.m3u8").toString(),emptyMap()) }.exceptionOrNull()
            assertTrue(error is OnlineMediaUnavailable)
            assertEquals(404,(error as OnlineMediaUnavailable).status)
            assertFalse(error.message!!.contains(server.hostName))
        }
    }
    @Test fun sitePlayerStaysOnTheSelectedCardAndAllowsCanonicalTitlesOnly() {
        val source="https://kz.kinogo.moi/62389-gangsterlend.html"
        assertTrue(CenterWebActivity.sameCardPage(source,"https://kz.kinogo.moi/62389-canonical.html#player"))
        assertFalse(CenterWebActivity.sameCardPage(source,"https://kz.kinogo.moi/9-another.html"))
        assertFalse(CenterWebActivity.sameCardPage(source,"https://ads.test/62389-gangsterlend.html"))
        assertFalse(CenterWebActivity.sameCardPage(source,"https://kz.kinogo.moi/login"))
    }


    @Test fun playerjsMirrorsAreSeparateUrlsWithinTheSameEpisodeAndQuality() {
        val raw="[720p]https://one.test/video.m3u8 or https://two.test/video.mp4,[1080p]//three.test/high.m3u8 and //four.test/high.m3u8"
        val choices=CenterOnline.sourceCandidates(raw)
        assertEquals(listOf("https://one.test/video.m3u8","https://two.test/video.mp4"),choices[720])
        assertEquals(listOf("https://three.test/high.m3u8","https://four.test/high.m3u8"),choices[1080])
        assertEquals("https://one.test/video.m3u8",CenterOnline.sources(raw)[720])
        val snapshot=JsonParser.parseString("""{"lists":[[{"title":"Сезон 2","folder":[{"title":"Серия 5","file":"[720p]https://one.test/five.m3u8 or https://two.test/five.m3u8"}]}]]}""").asJsonObject
        val episode=CenterOnline.decode(show,snapshot).single()
        assertEquals(2,episode.season);assertEquals(5,episode.number)
        assertEquals(listOf("https://one.test/five.m3u8","https://two.test/five.m3u8"),episode.alternatives[720])
        assertTrue(CenterOnline.sourceCandidates("[720p]https://one.test/five.m3u8 or javascript:bad").isEmpty())
        assertTrue(CenterOnline.sourceCandidates("[720p]https://one.test/a.m3u8,[720p]https://two.test/b.m3u8").isEmpty())
    }
    @Test fun hlsUsesNormalGetAndDeclaredMirrorRecovers404WithoutChangingQuality() = kotlinx.coroutines.runBlocking {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(404).setBody("<html>Missing</html>"))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("#EXTM3U\n#EXTINF:1,\nsegment.ts\n#EXT-X-ENDLIST"))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type","video/mp2t").setBody("G"))
            val first=server.url("/missing.m3u8").toString()
            val mirror=server.url("/available.m3u8").toString()
            assertEquals(mirror,CenterOnline.firstPlayable(listOf(first,mirror),mapOf("Referer" to "https://source.test/")))
            repeat(3) { assertNull(server.takeRequest().getHeader("Range")) }
        }
    }
    @Test fun declaredMirrorsDoNotRetryAnAccessDenial() = kotlinx.coroutines.runBlocking {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(403))
            val result=runCatching { CenterOnline.firstPlayable(listOf(server.url("/denied.m3u8").toString(),server.url("/other.m3u8").toString()),emptyMap()) }
            assertTrue(result.exceptionOrNull() is OnlineMediaUnavailable)
            assertEquals(1,server.requestCount)
        }
    }

    @Test fun mediaReferrerMatchesBrowserOriginPolicyWithoutSharingPageQuery() {
        val headers=mapOf("Referer" to "https://source.test/film/17-title?private=value#part","Origin" to "https://source.test","User-Agent" to "test")
        assertEquals("https://source.test/",CenterOnline.mediaHeaders("https://cdn.test/movie.m3u8",headers)["Referer"])
        assertEquals("https://source.test/film/17-title?private=value",CenterOnline.mediaHeaders("https://source.test/movie.m3u8",headers)["Referer"])
        assertFalse(CenterOnline.mediaHeaders("http://cdn.test/movie.mp4",headers).containsKey("Referer"))
        assertEquals("test",CenterOnline.mediaHeaders("https://cdn.test/movie.m3u8",headers)["User-Agent"])
    }

    @Test fun kinokongOnlineIsBoundToTheSelectedCardAndKeepsSeriesIdentity() {
        val item=CenterTitle(61709,"tv","Док",provider="kinokong",sourceUrl="https://kinokong.es/61709-serial-doc.html")
        assertTrue(CenterOnline.onlineSupported(item.provider))
        assertEquals("/61709-serial-doc.html",CenterOnline.source(item).encodedPath)
        assertTrue(CenterOnline.sameTitleNavigation(item,"https://kinokong.es/61709-canonical.html"))
        assertFalse(CenterOnline.sameTitleNavigation(item,"https://kinokong.es/61710-another.html"))
        assertFalse(CenterOnline.sameTitleNavigation(item,"https://other.test/61709-canonical.html"))
        assertTrue(runCatching { CenterOnline.source(item.copy(type="all")) }.isFailure)
        assertFalse(CenterOnline.onlineSupported("zona"));assertFalse(CenterOnline.onlineSupported("kinotac"))
        val snapshot=JsonParser.parseString("""{"lists":[[{"title":"Сезон 4","folder":[{"title":"Дубляж","folder":[{"title":"12 серия","file":"[720p]https://cdn.test/12.m3u8"}]}]}]]}""").asJsonObject
        val entry=CenterOnline.decode(item,snapshot).single()
        assertEquals(4,entry.season);assertEquals(12,entry.number);assertEquals("Дубляж",entry.voice)
    }
}
