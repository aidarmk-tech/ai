package com.lampplayer.tv.player

import com.google.gson.JsonParser
import com.lampplayer.tv.data.tmdb.TmdbMetadata
import com.lampplayer.tv.data.tmdb.TmdbSearch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ExternalMetadataResolverTest {
    private val hash = "a".repeat(40)
    private val url = "http://127.0.0.1:43181/stream/Ilnga.S01.E02.2026.mkv?link=$hash&index=2&play"
    private val json = """{"hash":"$hash","data":{"movie":{"title":"Инга","overview":"Описание сериала","poster_path":"/poster.jpg","tmdb_id":12}}}"""

    @Test fun cleansFluxEpisodeTitleWithoutUsingTheReleaseFileNameAsTheShowName() {
        val identity = ExternalMetadataResolver.identity(url, "Инга · S1 · E2")!!
        assertEquals("Инга", identity.title)
        assertEquals(1, identity.season)
        assertEquals(2, identity.episode)
        assertNull(ExternalMetadataResolver.identity("https://cdn.test/video.mp4", "Инга"))
    }

    @Test fun acceptsOnlyCurrentTorrentAndMatchingTitle() {
        val meta = ExternalMetadataResolver.parse(json, hash, "Инга")!!
        assertEquals("Описание сериала", meta.overview)
        assertEquals(12, meta.tmdbId)
        assertEquals("https://image.tmdb.org/t/p/w500/poster.jpg", meta.posterUrl)
        assertNull(ExternalMetadataResolver.parse(json, "b".repeat(40), "Инга"))
        assertNull(ExternalMetadataResolver.parse(json, hash, "Другой сериал"))
    }

    @Test fun acceptsJsonStringDataAndIgnoresGenericDatabaseIds() {
        val encoded = com.google.gson.JsonObject().apply {
            addProperty("hash", hash)
            addProperty("data", """{"title":"Инга","id":777,"description":"Из Flux","poster":"file:///private/poster"}""")
        }
        val meta = ExternalMetadataResolver.parse(encoded.toString(), hash, "Инга")!!
        assertEquals("Из Flux", meta.overview)
        assertNull(meta.tmdbId)
        assertNull(meta.posterUrl)
        assertNull(ExternalMetadataResolver.parse("<html>404</html>", hash, "Инга"))
    }

    @Test fun exactSearchRefusesFuzzyMatchesAndMultipleShowsWithTheSameName() {
        val one = TmdbMetadata(id = 1, name = "Инга", first_air_date = "2026-01-01")
        val two = TmdbMetadata(id = 2, name = "Инга", first_air_date = "2001-01-01")
        assertEquals(1, ExternalMetadataResolver.chooseTv(TmdbSearch(listOf(one), 1), "Инга", null))
        assertNull(ExternalMetadataResolver.chooseTv(TmdbSearch(listOf(one, two), 1), "Инга", null))
        assertEquals(1, ExternalMetadataResolver.chooseTv(TmdbSearch(listOf(one, two), 1), "Инга", 2026))
        assertNull(ExternalMetadataResolver.chooseTv(TmdbSearch(listOf(one), 2), "Инга", null))
        assertNull(ExternalMetadataResolver.chooseTv(TmdbSearch(listOf(one), 1), "Инга 2", null))
    }

    @Test fun getRequestIsReadOnlyBoundToCurrentHashAndPreservesAuthorization() = runBlocking {
        MockWebServer().use { server ->
            val source = server.url("/stream/Ilnga.S01E02.mkv?link=$hash&index=2&play").toString()
            server.enqueue(MockResponse().setBody(json))
            val result = ExternalMetadataResolver().resolve(source, "Инга", mapOf("Authorization" to "Basic abc"))!!
            assertEquals("Описание сериала", result.overview)
            val request = server.takeRequest()
            assertEquals("/torrents", request.requestUrl!!.encodedPath)
            assertEquals("POST", request.method)
            val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
            assertEquals(setOf("action", "hash"), body.keySet())
            assertEquals("get", body.get("action").asString)
            assertEquals(hash, body.get("hash").asString)
            assertEquals("Basic abc", request.getHeader("Authorization"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun unsupportedOrRedirectedEndpointReturnsNoMetadata() = runBlocking {
        MockWebServer().use { server ->
            val source = server.url("/stream/Ilnga.S01E02.mkv?link=$hash&play").toString()
            server.enqueue(MockResponse().setResponseCode(404))
            assertNull(ExternalMetadataResolver().resolve(source, "Инга", emptyMap()))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://foreign.test/"))
            assertNull(ExternalMetadataResolver().resolve(source, "Инга", emptyMap()))
            assertEquals(2, server.requestCount)
        }
    }
}
