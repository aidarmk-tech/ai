package com.lampplayer.tv.engine

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import com.lampplayer.tv.domain.model.ExternalSubtitle
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.Collections
import java.util.Properties
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@UnstableApi
class LampCoreHlsRecoveryTest {
    private fun data(name: String, fresh: Boolean = false): ByteArray {
        val props = Properties().apply { LampCoreHlsRecoveryTest::class.java.getResourceAsStream(if (fresh) "/hls-new-fixtures.properties" else "/hls-fixtures.properties")!!.use { load(it) } }
        return Base64.getDecoder().decode(props.getProperty(name))
    }
    private class Capture : HlsPacketOutput {
        val formats = Collections.synchronizedList(mutableListOf<Pair<Int, Format>>())
        val times = Collections.synchronizedList(mutableListOf<Long>())
        val flags = Collections.synchronizedList(mutableListOf<Int>())
        override fun format(id: Int, type: Int, format: Format) { formats += id to format }
        override fun packet(id: Int, type: Int, bytes: ByteArray, timeUs: Long, flags: Int) { if (type == C.TRACK_TYPE_VIDEO) { times += timeUs; this.flags += flags } }
        override fun endTracks(audioPresent: Boolean) {}
    }
    @Test(timeout = 15000) fun qualitySwitchDemuxesDifferentResolutionAtAlignedBoundary() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(r: RecordedRequest): MockResponse = when (r.path) {
                    "/high" -> MockResponse().setBody("#EXTM3U\n#EXTINF:1,\nhigh0.ts\n#EXTINF:1,\nhigh1.ts\n#EXT-X-ENDLIST")
                    "/low" -> MockResponse().setBody("#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:90\n#EXTINF:1,\nts0.ts\n#EXTINF:1,\nts1.ts\n#EXT-X-ENDLIST")
                    "/high0.ts", "/high1.ts" -> MockResponse().setBody(Buffer().write(data(r.path!!.drop(1), true))).setBodyDelay(300, TimeUnit.MILLISECONDS)
                    "/ts0.ts", "/ts1.ts" -> MockResponse().setBody(Buffer().write(data(r.path!!.drop(1))))
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val master = LampCoreHlsPlaylist.parse(server.url("/master").toString(), "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100000,RESOLUTION=160x96,CODECS=\"avc1.42e01e\"\nlow\n#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=320x192,CODECS=\"avc1.42e01e\"\nhigh")
            val dir = Files.createTempDirectory("abr-loader").toFile()
            val capture = Capture()
            val control = HlsControl().apply { ready = true; adaptive = true; videoMime = "video/avc" }
            try {
                LampCoreHlsLoader(LampCoreHlsHttp(emptyMap()), master, dir, 0, capture, { _, _ -> }, { throw it }, { true }, control).read()
                assertEquals(1L, control.switches)
                assertTrue(capture.formats.any { it.first == 0 && it.second.width == 320 })
                assertTrue(capture.formats.any { it.first == 0 && it.second.width == 160 })
                assertTrue(capture.times.zipWithNext().all { (a, b) -> b >= a })
                assertTrue(capture.times.last() > 1_800_000)
                assertTrue(capture.flags.any { it and 1 != 0 })
                assertTrue(dir.listFiles()!!.isEmpty())
            } finally { dir.deleteRecursively() }
        }
    }

    @Test(timeout = 15000) fun lostLiveOverlapRequestsAutomaticRestartAndCleansFiles() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(r: RecordedRequest): MockResponse = when (r.path) {
                    "/ts0.ts" -> MockResponse().setBody(Buffer().write(data("ts0.ts")))
                    "/live" -> MockResponse().setBody("#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:100\n#EXTINF:1,\nnew.ts\n")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val p = LampCoreHlsPlaylist.parse(server.url("/live").toString(), "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:10\n#EXTINF:1,\nts0.ts\n")
            val dir = Files.createTempDirectory("recover-live").toFile()
            val control = HlsControl()
            var restart: Long? = null
            try {
                LampCoreHlsLoader(LampCoreHlsHttp(emptyMap()), p, dir, 0, Capture(), { _, _ -> }, { throw it }, { true }, control, { restart = it }).read()
                assertEquals(0L, restart)
                assertEquals(1L, control.recoveries)
                assertTrue(dir.listFiles()!!.isEmpty())
            } finally { dir.deleteRecursively() }
        }
    }

    @Test fun twoInbandAacTracksKeepDifferentIdsAndLanguages() {
        val dir = Files.createTempDirectory("audio-tracks").toFile()
        val capture = Capture()
        try {
            val file = File(dir, "two.ts").apply { writeBytes(data("two-audio.ts", true)) }
            LampCoreHlsExtractor(TimestampAdjuster(0), capture, false) { true }.use {
                it.read(file, HlsSegment("http://x/a", 1_000_000, 0, 0, 0, null, null, null))
            }
            val audio = capture.formats.filter { it.second.sampleMimeType?.startsWith("audio/") == true }.distinctBy { it.first }
            assertEquals(2, audio.size)
            assertEquals(setOf("ru", "en"), audio.map { it.second.language }.toSet())
        } finally { dir.deleteRecursively() }
    }

    private fun awaitCaption(manager: LampCoreSubtitles, expected: String) {
        val until = System.nanoTime() + 3_000_000_000L
        while (manager.text() != expected && System.nanoTime() < until) Thread.sleep(10)
        assertEquals(expected, manager.text())
    }
    @Test(timeout = 10000) fun externalSubtitleSelectionDelayAndDisableWorkWithoutBlockingPlayback() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("1\n00:00:01,000 --> 00:00:02,000\nHello"))
            val control = HlsControl()
            var position = 1_500_000L
            LampCoreSubtitles(RuntimeEnvironment.getApplication(), { control }, { position }, {}, { fail("subtitle load failed") }).use { subs ->
                subs.configure(listOf(ExternalSubtitle(server.url("/srt").toString(), "English", true)), emptyMap())
                awaitCaption(subs, "Hello")
                subs.delayMs = 1000; assertEquals("", subs.text())
                position = 2_500_000; assertEquals("Hello", subs.text())
                subs.delayMs = -1000; assertEquals("", subs.text())
                subs.select(-1); assertEquals("", subs.text()); assertEquals(-1, subs.selectedId)
                assertEquals(2, subs.tracks().size)
            }
        }
    }

    @Test(timeout = 10000) fun hlsWebVttUsesVideoTimestampOffsetAndPublishesSelectableTrack() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(r: RecordedRequest): MockResponse = when (r.path) {
                    "/subs" -> MockResponse().setBody("#EXTM3U\n#EXTINF:3,\ntext.vtt\n#EXT-X-ENDLIST")
                    "/text.vtt" -> MockResponse().setBody("WEBVTT\nX-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:90000\n\n00:01.000 --> 00:02.000\nMapped")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val master = LampCoreHlsPlaylist.parse(server.url("/master").toString(), "#EXTM3U\n#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"s\",NAME=\"Russian\",URI=\"subs\"\n#EXT-X-STREAM-INF:BANDWIDTH=100000,SUBTITLES=\"s\"\nvideo")
            val video = LampCoreHlsPlaylist.parse(server.url("/video").toString(), "#EXTM3U\n#EXTINF:3,\na.ts\n#EXT-X-ENDLIST")
            val control = HlsControl().apply { timestampOffsets[0] = -1_000_000 }
            LampCoreSubtitles(RuntimeEnvironment.getApplication(), { control }, { 1_500_000L }, {}, { fail("VTT load failed") }).use { subs ->
                subs.configure(emptyList(), emptyMap())
                subs.hlsTracks(HlsResolved(video, null, "HLS", master, master.variants.single()))
                assertEquals(20000, subs.tracks()[1].id)
                subs.select(20000)
                awaitCaption(subs, "Mapped")
            }
        }
    }
}
