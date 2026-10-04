package com.lampplayer.tv.engine

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LampCoreRangeReaderTest {
    private val file = ByteArray(300_001) { (it % 251).toByte() }

    @Test fun readsRandomOffsetsAcrossBlocksAndReusesCachedData() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = Regex("bytes=(\\d+)-(\\d+)").matchEntire(request.getHeader("Range")!!)!!
                val start = range.groupValues[1].toInt()
                val end = minOf(range.groupValues[2].toInt(), file.lastIndex)
                return MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-$end/${file.size}")
                    .setBody(Buffer().write(file, start, end - start + 1))
            }
        }
        server.start()
        try {
            LampCoreRangeReader(server.url("/movie.mp4").toString(), mapOf("Referer" to "https://example.org", "X-Test" to "header")).use { reader ->
                assertEquals(file.size.toLong(), reader.size())
                for (offset in listOf(299_970, 131_060, 7, 262_140, 140_000)) {
                    val expected = minOf(300, file.size - offset)
                    val result = ByteArray(expected)
                    var done = 0
                    while (done < expected) {
                        val count = reader.readAt((offset + done).toLong(), result, done, expected - done)
                        assertTrue(count > 0); done += count
                    }
                    assertArrayEquals(file.copyOfRange(offset, offset + expected), result)
                }
                assertEquals(-1, reader.readAt(file.size.toLong(), ByteArray(1), 0, 1))
                assertEquals(0, reader.readAt(file.size.toLong(), ByteArray(0), 0, 0))
                assertEquals(3, server.requestCount)
                val request = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("https://example.org", request.getHeader("Referer"))
                assertEquals("header", request.getHeader("X-Test"))
                assertEquals("identity", request.getHeader("Accept-Encoding"))
            }
        } finally { server.shutdown() }
    }

    @Test fun refusesServerIgnoringRangeInsteadOfReturningWrongSeekBytes() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("not a range")); server.start()
        try {
            LampCoreRangeReader(server.url("/signed?token=private").toString(), emptyMap()).use { reader ->
                try { reader.readAt(500, ByteArray(4), 0, 4); fail("Expected Range error") }
                catch (_: IOException) { assertTrue(reader.lastError!!.contains("HTTP Range")); assertFalse(reader.lastError!!.contains("private")) }
            }
        } finally { server.shutdown() }
    }

    @Test fun rejectsMismatchedRangeStart() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 1-3/100").setBody("abc")); server.start()
        try {
            LampCoreRangeReader(server.url("/").toString(), emptyMap()).use { reader ->
                try { reader.size(); fail("Expected Content-Range error") }
                catch (_: IOException) { assertTrue(reader.lastError!!.contains("неверный диапазон")) }
            }
        } finally { server.shutdown() }
    }

    @Test fun closeCancelsBlockedNetworkRead() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)); server.start()
        val executor = Executors.newSingleThreadExecutor()
        val reader = LampCoreRangeReader(server.url("/").toString(), emptyMap())
        try {
            val pending = executor.submit<Boolean> { try { reader.size(); false } catch (_: IOException) { true } }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            reader.close()
            assertTrue(pending.get(2, TimeUnit.SECONDS))
        } finally { reader.close(); executor.shutdownNow(); server.shutdown() }
    }
}
