package com.lampplayer.tv.engine

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.Collections
import java.util.Properties

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@UnstableApi
class LampCoreHlsExtractorTest {
    private val fixtures = Properties().apply {
        LampCoreHlsExtractorTest::class.java.getResourceAsStream("/hls-fixtures.properties")!!.use { load(it) }
    }
    private fun data(name: String) = Base64.getDecoder().decode(fixtures.getProperty(name))
    private data class Packet(val type: Int, val timeUs: Long, val size: Int)
    private class Capture : HlsPacketOutput {
        val formats = Collections.synchronizedMap(mutableMapOf<Int, Format>())
        val packets = Collections.synchronizedList(mutableListOf<Packet>())
        override fun format(id: Int, type: Int, format: Format) { formats[type] = format }
        override fun packet(id: Int, type: Int, bytes: ByteArray, timeUs: Long, flags: Int) { packets += Packet(type, timeUs, bytes.size) }
        override fun endTracks(audioPresent: Boolean) { }
    }

    @Test fun tsSegmentsKeepContinuousVideoAndAudioTimestamps() {
        extract(false)
    }

    @Test fun fragmentedMp4WithInitKeepsContinuousTimestamps() {
        extract(true)
    }

    private fun extract(mp4: Boolean) {
        val dir = Files.createTempDirectory("hls-extract").toFile()
        val capture = Capture()
        try {
            LampCoreHlsExtractor(TimestampAdjuster(0), capture, false) { true }.use { extractor ->
                repeat(2) { index ->
                    val init = if (mp4) HlsInit("http://example/init.mp4", null, null) else null
                    val segment = HlsSegment("http://example/$index", 1_000_000, index * 1_000_000L, index.toLong(), 0, null, null, init)
                    val file = File(dir, "$index.bin")
                    file.writeBytes(if (mp4) data("init.mp4") + data("mp4$index.m4s") else data("ts$index.ts"))
                    extractor.read(file, segment)
                }
            }
            verify(capture)
        } finally { dir.deleteRecursively() }
    }

    private fun verify(capture: Capture) {
        assertEquals("video/avc", capture.formats[C.TRACK_TYPE_VIDEO]?.sampleMimeType)
        assertEquals("audio/mp4a-latm", capture.formats[C.TRACK_TYPE_AUDIO]?.sampleMimeType)
        assertTrue(capture.formats[C.TRACK_TYPE_VIDEO]!!.initializationData.isNotEmpty())
        for (type in listOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO)) {
            val packets = capture.packets.filter { it.type == type }
            assertTrue("Missing packets for $type", packets.size >= 25)
            assertTrue(packets.all { it.size > 0 && it.timeUs != C.TIME_UNSET })
            assertTrue("Timestamp reset at segment boundary", packets.zipWithNext().all { (a, b) -> b.timeUs >= a.timeUs })
            assertTrue("Second segment missing", packets.last().timeUs > 1_800_000)
            assertTrue(packets.first().timeUs < 100_000)
        }
    }

    @Test(timeout = 15000) fun loaderSeeksBySegmentAndCleansBuffer() {
        MockWebServer().use { server ->
            val requests = Collections.synchronizedList(mutableListOf<String>())
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request.path.orEmpty()
                    return when (request.path) {
                        "/ts0.ts" -> MockResponse().setBody(Buffer().write(data("ts0.ts")))
                        "/ts1.ts" -> MockResponse().setBody(Buffer().write(data("ts1.ts")))
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            val dir = Files.createTempDirectory("hls-loader").toFile()
            try {
                val p = LampCoreHlsPlaylist.parse(server.url("/index.m3u8").toString(), "#EXTM3U\n#EXTINF:1,\nts0.ts\n#EXTINF:1,\nts1.ts\n#EXT-X-ENDLIST")
                val capture = Capture()
                LampCoreHlsLoader(LampCoreHlsHttp(emptyMap()), p, dir, 1_300_000, capture,
                    { resolved, _ -> assertEquals(2_000_000L, resolved.video.durationUs) }, { throw it }, { true }).read()
                assertEquals(listOf("/ts1.ts"), requests)
                assertTrue(capture.packets.any { it.type == C.TRACK_TYPE_VIDEO })
                assertTrue(capture.packets.all { it.timeUs >= 1_000_000 })
                assertTrue(dir.listFiles()!!.isEmpty())
            } finally { dir.deleteRecursively() }
        }
    }

    @Test(timeout = 15000) fun masterWithSeparateAudioUsesOneSharedClock() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/video.m3u8", "/audio.m3u8" -> MockResponse().setBody("#EXTM3U\n#EXTINF:1,\nts0.ts\n#EXTINF:1,\nts1.ts\n#EXT-X-ENDLIST")
                    "/ts0.ts" -> MockResponse().setBody(Buffer().write(data("ts0.ts")))
                    "/ts1.ts" -> MockResponse().setBody(Buffer().write(data("ts1.ts")))
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val dir = Files.createTempDirectory("hls-audio").toFile()
            try {
                val p = LampCoreHlsPlaylist.parse(server.url("/master.m3u8").toString(), "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"Russian\",LANGUAGE=\"ru\",DEFAULT=YES,URI=\"audio.m3u8\"\n#EXT-X-STREAM-INF:BANDWIDTH=2000000,AUDIO=\"a\"\nvideo.m3u8")
                val capture = Capture()
                LampCoreHlsLoader(LampCoreHlsHttp(emptyMap()), p, dir, 0, capture, { _, _ -> }, { throw it }, { true }).read()
                verify(capture)
                assertTrue(dir.listFiles()!!.isEmpty())
            } finally { dir.deleteRecursively() }
        }
    }
}
