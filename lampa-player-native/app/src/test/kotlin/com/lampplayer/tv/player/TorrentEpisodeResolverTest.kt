package com.lampplayer.tv.player

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class TorrentEpisodeResolverTest {
    private val hash = "bf38bd0bb0123456789012345678901234567890"
    private val base = "http://127.0.0.1:43181"
    private fun stream(name: String, id: Int, origin: String = base, torrent: String = hash) =
        "$origin/stream/$name?link=$torrent&index=$id&play"
    private fun episode(n: Int, id: Int = n, origin: String = base) =
        stream("Ilnga.S01E${n.toString().padStart(2, '0')}.2026.WEB-DL.1080p.ExKinoRay.mkv", id, origin)
    private fun m3u(vararg links: String) = "#EXTM3U\n" + links.joinToString("\n") { "#EXTINF:0,video\n$it" }

    @Test fun requestTargetsWholeCurrentTorrentWithoutStartingAnotherFile() {
        val source = episode(2, 4) + "&preload&fromlast&token=secret"
        val request = TorrentEpisodeResolver.playlistUrl(source)!!
        assertEquals(hash, request.queryParameter("hash"))
        assertEquals("/playlist", request.encodedPath)
        assertEquals("secret", request.queryParameter("token"))
        assertFalse("m3u" in request.queryParameterNames)
        for (key in listOf("play", "preload", "fromlast", "index")) assertFalse(key in request.queryParameterNames)
        assertNull(TorrentEpisodeResolver.playlistUrl("https://cdn.test/movie.mp4"))
        assertNull(TorrentEpisodeResolver.playlistUrl("$base/stream/a.mkv?link=not-a-torrent-hash"))
    }

    @Test fun fluxShapeFindsCurrentEpisodeAndKeepsRealNonconsecutiveFileIds() {
        val source = episode(2, 4)
        val list = TorrentEpisodeResolver.parse(source, m3u(episode(10, 20), episode(3, 8), source,
            episode(1, 1), stream("Ilnga.S01E02.ru.srt", 5), stream("Ilnga.S01E03.sample.mkv", 9),
            stream("Marvel.S01E03.mkv", 30), stream("Ilnga.S02E01.mkv", 80)))!!
        assertEquals(listOf(1, 2, 3, 10), list.episodes.map { it.episode })
        assertEquals(1, list.currentIndex)
        assertEquals(source, list.episodes[list.currentIndex].url)
        val next = EpisodeNavigation.next(list.episodes, source)!!
        assertEquals(3, next.episode)
        assertEquals("8", next.url.toHttpUrl().queryParameter("index"))
        assertEquals(hash, next.url.toHttpUrl().queryParameter("link"))
    }

    @Test fun missingCurrentIndexCanBeMatchedByUniqueFileName() {
        val source = episode(2).toHttpUrl().newBuilder().removeAllQueryParameters("index").build().toString()
        val list = TorrentEpisodeResolver.parse(source, m3u(episode(1), episode(2), episode(3)))!!
        assertEquals(1, list.currentIndex)
        assertEquals(source, list.episodes[1].url)
    }

    @Test fun foreignOriginHashAndWrongCurrentFileAreRejected() {
        val source = episode(2)
        assertNull(TorrentEpisodeResolver.parse(source, m3u(episode(1), episode(3))))
        assertNull(TorrentEpisodeResolver.parse(source, m3u(source, episode(3, origin = "https://foreign.test"))))
        assertNull(TorrentEpisodeResolver.parse(source, m3u(source, episode(3).replace(hash, "a".repeat(40)))))
        assertNull(TorrentEpisodeResolver.parse(source, m3u(episode(1), episode(2, 99))))
    }

    @Test fun duplicateEpisodeEncodesAreNotChosenAutomatically() {
        val source = episode(2)
        assertNull(TorrentEpisodeResolver.parse(source,
            m3u(episode(1), source, stream("Ilnga.S01E02.720p.mkv", 22))))
    }

    @Test fun absentEpisodeDoesNotBecomeAnAutomaticJumpToEpisodeThree() {
        val source = episode(1)
        val list = TorrentEpisodeResolver.parse(source, m3u(source, episode(3)))!!
        assertNull(EpisodeNavigation.next(list.episodes, source))
    }

    @Test fun preservesAccessKeysAndCorrectsFluxTitleBeforeSwitchingEpisodes() {
        val source = episode(2) + "&token=secret"
        val list = TorrentEpisodeResolver.parse(source, m3u(episode(1), episode(2), episode(3)))!!
        assertEquals("secret", list.episodes[2].url.toHttpUrl().queryParameter("token"))
        assertEquals("Инга", TorrentEpisodeResolver.showTitle("Инга · S1 · E2", 1, 2))
        assertEquals("Инга", TorrentEpisodeResolver.showTitle("Инга S01E02", 1, 2))
    }

    @Test fun htmlHlsAndUnnumberedMovieListsAreNotEpisodePlaylists() {
        assertNull(TorrentEpisodeResolver.parse(episode(2), "<html>Not found</html>"))
        assertNull(TorrentEpisodeResolver.parse(episode(2), "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\npart.ts"))
        assertNull(TorrentEpisodeResolver.parse(stream("movie.mkv", 1), m3u(stream("movie.mkv", 1), episode(2))))
    }

    @Test fun fetchesOnlyM3uWithHeadersAndCanSelectNextAfterAnOrdinaryActionViewLaunch() = runBlocking {
        MockWebServer().use { server ->
            val origin = server.url("/").toString().trimEnd('/')
            val source = episode(2, 4, origin)
            server.enqueue(MockResponse().setBody(m3u(episode(1, 1, origin), source, episode(3, 8, origin))))
            val list = TorrentEpisodeResolver().resolve(source,
                mapOf("Authorization" to "Basic abc", "Range" to "bytes=0-", "X-Lmnp-Pl" to "stale"))!!
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/playlist", request.requestUrl!!.encodedPath)
            assertEquals(hash, request.requestUrl!!.queryParameter("hash"))
            assertFalse("play" in request.requestUrl!!.queryParameterNames)
            assertNull(request.getHeader("Range"))
            assertNull(request.getHeader("X-Lmnp-Pl"))
            assertEquals("Basic abc", request.getHeader("Authorization"))
            assertEquals(1, server.requestCount) // never probes/preloads every episode
            assertEquals(3, EpisodeNavigation.next(list.episodes, source)!!.episode)
        }
    }

    @Test fun unsupportedServerAndRedirectDoNotChangePlayback() = runBlocking {
        MockWebServer().use { server ->
            val source = episode(2, origin = server.url("/").toString().trimEnd('/'))
            server.enqueue(MockResponse().setResponseCode(404))
            assertNull(TorrentEpisodeResolver().resolve(source, emptyMap()))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://foreign.test/list"))
            assertNull(TorrentEpisodeResolver().resolve(source, emptyMap()))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun oversizedChunkedResponseIsBounded() = runBlocking {
        MockWebServer().use { server ->
            val source = episode(2, origin = server.url("/").toString().trimEnd('/'))
            server.enqueue(MockResponse().setChunkedBody("#EXTM3U\n" + "x".repeat(1024 * 1024 + 1), 4096))
            assertNull(TorrentEpisodeResolver().resolve(source, emptyMap()))
        }
    }
}
