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
}
