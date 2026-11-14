package com.lampplayer.tv.engine

import com.lampplayer.tv.data.BrowserMediaSession
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.net.CookieHandler

class LampCoreSessionTest {
    @Test fun browserSessionReachesBothHlsAndSeekableMp4Readers() {
        val previous=CookieHandler.getDefault()
        try {
            BrowserMediaSession.refresh()
            BrowserMediaSession.install { "media_session=valid" }
            MockWebServer().use { server ->
                server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request:RecordedRequest):MockResponse {
                        if(request.getHeader("Cookie")!="media_session=valid")return MockResponse().setResponseCode(404)
                        return if(request.path=="/movie.mp4")
                            MockResponse().setResponseCode(206).setHeader("Content-Range","bytes 0-11/12").setBody("0000ftypisom")
                        else MockResponse().setBody("#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1,\nsegment.ts\n#EXT-X-ENDLIST")
                    }
                }
                LampCoreHlsHttp(emptyMap()).use { assertNotNull(it.probe(server.url("/movie.m3u8").toString())) }
                LampCoreRangeReader(server.url("/movie.mp4").toString(),emptyMap()).use { assertEquals(12L,it.size()) }
                BrowserMediaSession.install { "media_session=browser" }
                val explicit=mapOf("Cookie" to "media_session=valid")
                LampCoreHlsHttp(explicit).use { assertNotNull(it.probe(server.url("/movie.m3u8").toString())) }
                LampCoreRangeReader(server.url("/movie.mp4").toString(),explicit).use { assertEquals(12L,it.size()) }
                repeat(4) { assertEquals("media_session=valid",server.takeRequest().getHeader("Cookie")) }
            }
        } finally { BrowserMediaSession.refresh();BrowserMediaSession.install { null };CookieHandler.setDefault(previous) }
    }
}
