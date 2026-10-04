package com.lampplayer.tv.engine

import org.junit.Assert.*
import org.junit.Test

class LampCoreHlsPlaylistTest {
    private val url = "https://media.example/path/master.m3u8?token=1"

    @Test fun masterResolvesRelativeUrlsAndChoosesBoundedQuality() {
        val p = LampCoreHlsPlaylist.parse(url, """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="Russian, stereo",LANGUAGE="ru",DEFAULT=YES,URI="../audio.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720,AUDIO="a"
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080,AUDIO="a"
            high/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=18000000,RESOLUTION=3840x2160,AUDIO="a"
            ultra/index.m3u8
        """.trimIndent())
        assertEquals("https://media.example/path/high/index.m3u8", p.initialVariant().uri)
        assertEquals("https://media.example/audio.m3u8", p.audios.single().uri)
        assertEquals("Russian, stereo", p.audios.single().name)
    }

    @Test fun byteRangesKeyScopeAndImplicitIv() {
        val p = LampCoreHlsPlaylist.parse(url, """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:257
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
            #EXTINF:1.5,
            #EXT-X-BYTERANGE:100@20
            blob.ts
            #EXTINF:2,
            #EXT-X-BYTERANGE:80
            blob.ts
            #EXT-X-DISCONTINUITY
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:1,
            plain.ts
            #EXT-X-ENDLIST
        """.trimIndent())
        assertEquals(HlsRange(120, 80), p.segments[1].range)
        assertEquals(1_500_000L, p.segments[1].startUs)
        assertEquals(258L, p.segments[1].sequence)
        assertArrayEquals(byteArrayOf(1, 1), p.segments[0].key!!.iv(257).takeLast(2).toByteArray())
        assertNull(p.segments[2].key)
        assertEquals(1L, p.segments[2].discontinuity)
        assertEquals(4_500_000L, p.durationUs)
    }

    @Test fun liveTimelineDoesNotResetWhenWindowSlides() {
        fun window(seq: Int, names: String) = LampCoreHlsPlaylist.parse(url,
            "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:$seq\n" + names.split(',').joinToString("") { "#EXTINF:2,\n$it.ts\n" })
        val timeline = HlsTimeline(window(20, "a,b,c"))
        val shifted = timeline.update(window(21, "b,c,d"))
        assertEquals(listOf(2_000_000L, 4_000_000L, 6_000_000L), shifted.segments.map { it.startUs })
        val adjacent = timeline.update(window(24, "e,f"))
        assertEquals(8_000_000L, adjacent.segments.first().startUs)
        assertThrows(HlsException::class.java) { timeline.update(window(30, "x,y")) }
    }

    @Test fun rejectsUnsupportedEncryptionAndInvalidRanges() {
        assertThrows(HlsException::class.java) { LampCoreHlsPlaylist.parse(url, "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"key\"\n#EXTINF:1,\na.ts") }
        assertThrows(HlsException::class.java) { LampCoreHlsPlaylist.parse(url, "#EXTM3U\n#EXTINF:1,\n#EXT-X-BYTERANGE:10\na.ts") }
        assertThrows(HlsException::class.java) { LampCoreHlsPlaylist.parse(url, "#EXTM3U\n#EXT-X-PART:DURATION=0.3,URI=\"p.ts\"") }
    }
}
