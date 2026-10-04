package com.lampplayer.tv.engine

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class LampCoreHlsHttpTest {
    @Test fun detectsExtensionlessRedirectAndPreservesHeaders() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/cdn/media"))
            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:2,\npart.ts\n#EXT-X-ENDLIST"))
            LampCoreHlsHttp(mapOf("Referer" to "https://lampa.example", "Cookie" to "auth=abc")).use { http ->
                val p = http.probe(server.url("/balancer").toString())!!
                assertEquals(server.url("/cdn/part.ts").toString(), p.segments.single().uri)
                server.takeRequest()
                val request = server.takeRequest()
                assertEquals("auth=abc", request.getHeader("Cookie"))
                assertEquals("https://lampa.example", request.getHeader("Referer"))
                assertNull(request.getHeader("Range"))
            }
        }
    }

    @Test fun decryptsAes128AndCachesKey() {
        MockWebServer().use { server ->
            val key = ByteArray(16) { it.toByte() }
            val plain = ByteArray(1003) { (it * 7).toByte() }
            val encryption = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(HlsKey("", null).iv(9)))
            }.doFinal(plain)
            server.enqueue(MockResponse().setBody(Buffer().write(key)))
            repeat(2) { server.enqueue(MockResponse().setBody(Buffer().write(encryption))) }
            val dir = Files.createTempDirectory("hls-aes").toFile()
            try {
                LampCoreHlsHttp(emptyMap()).use { http ->
                    val segment = HlsSegment(server.url("/segment").toString(), 1_000_000, 0, 9, 0, null, HlsKey(server.url("/key").toString(), null), null)
                    repeat(2) { val file = http.segment(segment, dir); assertArrayEquals(plain, file.readBytes()); file.delete() }
                    assertEquals(3, server.requestCount)
                }
            } finally { dir.deleteRecursively() }
        }
    }

    @Test fun prependsInitAndEnforcesByteRangeResponse() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("INIT"))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 4-6/10").setBody("abc"))
            server.enqueue(MockResponse().setBody("ignored range"))
            val dir = Files.createTempDirectory("hls-range").toFile()
            try {
                LampCoreHlsHttp(emptyMap()).use { http ->
                    val segment = HlsSegment(server.url("/blob").toString(), 1, 0, 0, 0, HlsRange(4, 3), null, HlsInit(server.url("/init").toString(), null, null))
                    val file = http.segment(segment, dir)
                    assertEquals("INITabc", file.readText()); file.delete()
                    server.takeRequest()
                    assertEquals("bytes=4-6", server.takeRequest().getHeader("Range"))
                    assertThrows(HlsException::class.java) { http.segment(segment, dir) }
                    assertTrue(dir.listFiles()!!.isEmpty())
                }
            } finally { dir.deleteRecursively() }
        }
    }

    @Test fun cancelWakesBlockedRequest() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val worker = Executors.newSingleThreadExecutor()
            val http = LampCoreHlsHttp(emptyMap())
            try {
                val future = worker.submit<Boolean> { try { http.playlist(server.url("/live").toString()); false } catch (_: Exception) { true } }
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                http.close()
                assertTrue(future.get(2, TimeUnit.SECONDS))
            } finally { http.close(); worker.shutdownNow() }
        }
    }
}
