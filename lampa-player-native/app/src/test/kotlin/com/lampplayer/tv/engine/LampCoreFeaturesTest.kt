package com.lampplayer.tv.engine

import androidx.media3.common.Format
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@UnstableApi
class LampCoreFeaturesTest {
    @Test fun retryBudgetAndCancellationAreBounded() {
        var now = 0L
        var attempts = 0
        val policy = HlsRetryPolicy(300, { now }, { now += it })
        assertThrows(IOException::class.java) { policy.run({ true }) { attempts++; throw IOException() } }
        assertEquals(3, attempts)
        var active = true
        attempts = 0
        val cancelled = HlsRetryPolicy(1000, { 0 }, { active = false })
        assertThrows(HlsException::class.java) { cancelled.run({ active }) { attempts++; throw IOException() } }
        assertEquals(1, attempts)
        attempts = 0
        assertThrows(HlsException::class.java) { policy.run({ true }) { attempts++; throw HlsException("permanent") } }
        assertEquals(1, attempts)
    }

    @Test fun temporaryPlaylistErrorsRecoverWithoutLosingHeaders() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setResponseCode(429))
            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:1,\npart.ts\n#EXT-X-ENDLIST"))
            LampCoreHlsHttp(mapOf("Cookie" to "test=1"), HlsRetryPolicy(1000, sleep = {})).use { http ->
                assertEquals(1, http.playlist(server.url("/live").toString()).segments.size)
                assertEquals(2L, http.recoveries.get())
                assertFalse(http.isRecovering)
                repeat(3) { assertEquals("test=1", server.takeRequest().getHeader("Cookie")) }
            }
        }
    }

    @Test fun interruptedSegmentRestartsFromEmptyFile() {
        MockWebServer().use { server ->
            val payload = ByteArray(32768) { (it % 113).toByte() }
            server.enqueue(MockResponse().setBody(Buffer().write(payload)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            server.enqueue(MockResponse().setBody(Buffer().write(payload)))
            val dir = Files.createTempDirectory("hls-retry").toFile()
            try {
                LampCoreHlsHttp(emptyMap(), HlsRetryPolicy(1000, sleep = {})).use { http ->
                    val s = HlsSegment(server.url("/seg").toString(), 1, 0, 0, 0, null, null, null)
                    val file = http.segment(s, dir)
                    assertArrayEquals(payload, file.readBytes())
                    assertEquals(2, server.requestCount)
                    file.delete()
                }
                assertTrue(dir.listFiles()!!.isEmpty())
            } finally { dir.deleteRecursively() }
        }
    }

    @Test fun permanentHttpErrorsDoNotRetry() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            LampCoreHlsHttp(emptyMap()).use { http ->
                assertThrows(HlsException::class.java) { http.playlist(server.url("/locked").toString()) }
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun abrDowngradesImmediatelyButRequiresBufferAndVotesForUpgrade() {
        var now = 0L
        val abr = HlsAbr { now }
        val low = HlsVariant("low", 200000, 360, null)
        val high = HlsVariant("high", 2000000, 720, null)
        val variants = listOf(low, high)
        abr.sample(100000, 1_000_000_000)
        assertEquals(low, abr.choose(variants, high, 0))
        repeat(10) { abr.sample(2_000_000, 1_000_000_000) }
        repeat(4) { assertEquals(low, abr.choose(variants, low, 0)) }
        now = 11000
        assertEquals(high, abr.choose(variants, low, 4_000_000))
        assertEquals(high, abr.choose(variants, high, 4_000_000))
    }

    @Test fun vodVariantsAlignWithoutAssumingEqualSequenceNumbers() {
        val old = LampCoreHlsPlaylist.parse("http://x/old", "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:7\n#EXTINF:1,\na\n#EXTINF:1,\nb\n#EXT-X-ENDLIST")
        val next = LampCoreHlsPlaylist.parse("http://x/new", "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:90\n#EXTINF:1,\nc\n#EXTINF:1,\nd\n#EXT-X-ENDLIST")
        val aligned = HlsAlignment.switchAt(old, 1_000_000, next)!!
        assertEquals(91L, aligned.segments.first { it.startUs == 1_000_000L }.sequence)
        assertNull(HlsAlignment.switchAt(old.copy(endList = false), 1_000_000, next.copy(endList = false)))
    }

    @Test fun liveVariantsAlignByProgramTimeAndKeepPlaybackTimeline() {
        val old = LampCoreHlsPlaylist.parse("http://x/old", "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:7\n#EXT-X-PROGRAM-DATE-TIME:2026-01-01T00:00:00Z\n#EXTINF:1,\na\n#EXTINF:1,\nb\n")
        val next = LampCoreHlsPlaylist.parse("http://x/new", "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:90\n#EXT-X-PROGRAM-DATE-TIME:2026-01-01T00:00:01.000+00:00\n#EXTINF:1,\nc\n")
        assertEquals(1_000_000L, HlsAlignment.switchAt(old, 1_000_000, next)!!.segments.single().startUs)
    }

    @Test fun audioRenditionSelectionAndSubtitleGroupAreRetained() {
        MockWebServer().use { server ->
            val body = "#EXTM3U\n#EXTINF:1,\npart\n#EXT-X-ENDLIST"
            repeat(2) { server.enqueue(MockResponse().setBody(body)) }
            val master = LampCoreHlsPlaylist.parse(server.url("/master").toString(), "#EXTM3U\n" +
                "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"Russian\",LANGUAGE=\"ru\",URI=\"ru\"\n" +
                "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"English\",LANGUAGE=\"en\",URI=\"en\"\n" +
                "#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"s\",NAME=\"Subs\",LANGUAGE=\"ru\",URI=\"subs\"\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=100000,RESOLUTION=640x360,CODECS=\"avc1.42e01e\",AUDIO=\"a\",SUBTITLES=\"s\"\nvideo")
            LampCoreHlsHttp(emptyMap()).use { http ->
                val resolved = http.resolve(master, audioId = 10001)
                assertEquals(10001, resolved.selectedAudioId)
                assertEquals("en", resolved.audios[1].language)
                assertEquals("/en", java.net.URI(resolved.audio!!.uri).path)
                assertEquals("s", resolved.variant!!.subtitleGroup)
                assertEquals("video/avc", resolved.variant.videoMime)
                assertEquals(640, resolved.variant.width)
            }
        }
    }

    @Test fun adaptiveCsdIsJoinedToKeyframeAndNonSyncPacketsWait() {
        val transition = HlsVideoTransition()
        transition.changed(Format.Builder().setSampleMimeType("video/avc").setInitializationData(listOf(byteArrayOf(1, 2), byteArrayOf(3))).build())
        assertNull(transition.packet(byteArrayOf(8), 0))
        assertArrayEquals(byteArrayOf(1, 2, 3, 9), transition.packet(byteArrayOf(9), C.BUFFER_FLAG_KEY_FRAME))
        assertArrayEquals(byteArrayOf(8), transition.packet(byteArrayOf(8), 0))
    }

    private fun pcm(vararg samples: Int) = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { samples.forEach { putShort(it.toShort()) }; flip() }
    @Test fun surroundDownmixKeepsDialogueSidesAndFrameCount() {
        val mix = LampCorePcm(6, false)
        val center = mix.convert(pcm(0, 0, 16000, 0, 0, 0))
        val l = center.short.toInt(); val r = center.short.toInt()
        assertTrue(l > 3000); assertEquals(l, r)
        val rear = mix.convert(pcm(0, 0, 0, 0, 16000, 0))
        assertTrue(rear.short > 0); assertEquals(0, rear.short.toInt())
        assertEquals(12, mix.inputFrameBytes)
        assertEquals(4, mix.outputFrameBytes)
        assertEquals(8, mix.convert(pcm(*IntArray(12) { 2000 })).remaining())
        assertTrue(mix.convert(pcm(*IntArray(6) { 32767 })).short <= 32767)
    }

    @Test fun floatAndSevenPointOnePcmStayFiniteAndBounded() {
        val source = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).apply {
            putFloat(Float.NaN); putFloat(Float.POSITIVE_INFINITY); repeat(6) { putFloat(10f) }; flip()
        }
        val output = LampCorePcm(8, true).convert(source)
        assertEquals(4, output.remaining())
        assertTrue(output.short > 0); assertTrue(output.short > 0)
        assertThrows(IllegalArgumentException::class.java) { LampCorePcm(6, false).convert(ByteBuffer.allocate(11)) }
    }

    @Test fun parsesSrtVttAndAssAndPreservesCueTimes() {
        val srt = "1\n00:00:01,250 --> 00:00:02,500\nПривет\nмир\n\n2\ninvalid --> bad\nskip"
        assertEquals(listOf(CoreTextCue(1250000, 2500000, "Привет\nмир")), LampCoreSubtitleParser.parse(srt))
        val vtt = "WEBVTT\n\nNOTE ignored\ncomment\n\n1\n00:01.000 --> 00:03.000 align:start\n<v Speaker><c.red>Hello</c></v>\n"
        assertEquals("Hello", LampCoreSubtitleParser.parse(vtt).single().text)
        val ass = "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,{\\b1}One\\NTwo"
        assertEquals("One\nTwo", LampCoreSubtitleParser.parse(ass).single().text)
    }

    @Test fun subtitleTimestampMapHandlesWrapAndAbsentMap() {
        val map = LampCoreSubtitleParser.timestampMap("WEBVTT\nX-TIMESTAMP-MAP=LOCAL:00:00:01.000,MPEGTS:900000")!!
        assertEquals(11_000_000L, LampCoreSubtitleParser.hlsOffset(map, 2_000_000, 12_000_000))
        val wrapped = CoreVttMap(0, 90000)
        val wrapUs = (1L shl 33) * 1_000_000 / 90000
        assertEquals(wrapUs + 1_000_000, LampCoreSubtitleParser.hlsOffset(wrapped, 0, wrapUs + 2_000_000))
        assertEquals(2_000_000L, LampCoreSubtitleParser.hlsOffset(null, 2_000_000, 5_000_000))
    }

    @Test fun subtitleIndexKeepsLongOverlapsAndSupportsBackwardsSeek() {
        val index = CoreCueIndex(listOf(CoreTextCue(0, 10000000, "Long"), CoreTextCue(1000000, 2000000, "Short"), CoreTextCue(4000000, 5000000, "Later")))
        assertEquals("Long\nShort", index.textAt(1500000))
        assertEquals("Long", index.textAt(3000000))
        assertEquals("Long\nLater", index.textAt(4500000))
        assertEquals("Long", index.textAt(500000))
        assertEquals("", index.textAt(10000000))
    }
}
