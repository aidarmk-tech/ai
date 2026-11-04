package com.lampplayer.tv.center

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.InputEvent
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[28],qualifiers="w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class MediaCenterUiTest {
    class FakeBackend : CenterBackend() {
        var detailCalls=0; var episodeCalls=0
        override suspend fun details(item:CenterTitle):CenterDetails {
            detailCalls++
            return CenterDetails(item,listOf(1,2,3),"Анна Иванова, Алексей Петров, Мария Соколова")
        }
        override suspend fun episodes(item:CenterTitle,season:Int):List<CenterEpisode> {
            episodeCalls++
            return (1..12).map { CenterEpisode(season,it,"Серия $it", "Описание эпизода $it. Герои отправляются в путь и узнают больше о загадочном городе.",airDate="2026-10-01",runtime=45) }
        }
    }
    class Host : MediaCenterActivity() {
        override val backend=FakeBackend()
        var nextOnline:OnlineVideo?=null
        var onlineCalls=0
        override suspend fun resolveOnline(item:CenterTitle):OnlineVideo { onlineCalls++;return requireNotNull(nextOnline) }
        override fun loadHome() { val items=(1..8).map {
            CenterTitle(it, if(it%2==0) "tv" else "movie", listOf("Тихий город","За горизонтом","Новый день","На орбите")[(it-1)%4],
                overview="Истории, к которым хочется возвращаться. Каталог, серии и просмотр в одном приложении.", year="2026",rating="8.2")
        }; showDashboard(CenterSection.all.map { section -> section to items.filter { it.type == section.type } }) }
        fun calls()=backend.detailCalls to backend.episodeCalls
        fun nativeFixture(video:OnlineVideo) { showOnline(video) }
    }
    private fun all(view:View):List<View> = listOf(view) + if(view is ViewGroup)
        (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun frame(root:View) {
        repeat(2) {
            shadowOf(Looper.getMainLooper()).idleFor(200,TimeUnit.MILLISECONDS)
            root.measure(View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(540,View.MeasureSpec.EXACTLY))
            root.layout(0,0,960,540)
        }
    }
    private fun screenshot(root:View,name:String) {
        val bitmap=Bitmap.createBitmap(960,540,Bitmap.Config.ARGB_8888);root.draw(Canvas(bitmap))
        val file=File("build/ui-preview/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    private fun label(root:View,value:String)=all(root).filterIsInstance<TextView>().first { it.text.toString()==value }
    private fun key(root:View,key:Int) {
        for(action in listOf(KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP)) {
            ReflectionHelpers.callInstanceMethod<Any?>(root.rootView.parent!!,"dispatchInputEvent",
                ReflectionHelpers.ClassParameter.from(InputEvent::class.java,KeyEvent(action,key)))
        }
    }
    @Test fun homeAndSearchRenderAndRemoteCanLeaveTheNavigation() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible().windowFocusChanged(true)
        try {
            val root=controller.get().findViewById<View>(android.R.id.content);frame(root)
            val home=label(root,"Главная");home.requestFocus();frame(root);key(root,KeyEvent.KEYCODE_DPAD_RIGHT);frame(root)
            assertFalse(home.hasFocus());assertNotNull(root.findFocus());assertEquals("Каталог: TMDB  ▾",(root.findFocus() as TextView).text.toString())
            assertTrue(all(root).filterIsInstance<TextView>().any { it.text.toString()=="Казахстанское кино" })
            assertTrue(all(root).filterIsInstance<TextView>().any { it.text.toString()=="Русские сериалы" })
            screenshot(root,"media-center-home")
            label(root,"Поиск").performClick();frame(root)
            assertTrue(all(root).filterIsInstance<TextView>().any { it.text.toString()=="Найти фильм или сериал" })
            screenshot(root,"media-center-search")
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun seriesActionsComeBeforeSynopsisAndBackRestoresTheSelectedEpisodeAndPoster() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible().windowFocusChanged(true)
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content);frame(root)
            val poster=all(root).first { it.tag=="new_episodes:title:tv:2" };poster.requestFocus();frame(root);poster.performClick();frame(root)
            val season=label(root,"Сезон 1")
            assertTrue(all(root).indexOf(season)<all(root).indexOf(label(root,"Описание")))
            screenshot(root,"media-center-detail")
            season.requestFocus();season.performClick();frame(root)
            val second=all(root).first { it.tag=="episode:1:2" };second.requestFocus();frame(root)
            assertTrue(all(root).filterIsInstance<TextView>().any { it.text.startsWith("Описание эпизода 2.") })
            screenshot(root,"media-center-episodes")
            host.onBackPressedDispatcher.onBackPressed();frame(root)
            assertEquals("Сезон 1",root.findFocus()?.tag)
            host.onBackPressedDispatcher.onBackPressed();frame(root)
            assertEquals("new_episodes:title:tv:2",root.findFocus()?.tag)
            assertEquals(1 to 1,host.calls())
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun nativeEpisodePickerStartsOurPlayerWithOnlyTheSelectedSeasonAndVoice() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible().windowFocusChanged(true)
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            val item=CenterTitle(17,"tv","Мой сериал",provider="kinovod",sourceUrl="https://source.test/serial/17-show")
            val entries=listOf(
                OnlineEpisode(1,1,"Первая","Дубляж",mapOf(720 to "https://cdn.test/1.m3u8")),
                OnlineEpisode(1,2,"Вторая","Дубляж",mapOf(720 to "https://cdn.test/2.m3u8")),
                OnlineEpisode(1,4,"Четвёртая","Дубляж",mapOf(720 to "https://cdn.test/4.m3u8")),
                OnlineEpisode(1,2,"Second","Оригинал",mapOf(720 to "https://cdn.test/en2.m3u8")),
                OnlineEpisode(2,1,"Другой сезон","Дубляж",mapOf(720 to "https://cdn.test/s2.m3u8")))
            host.nativeFixture(OnlineVideo(item,entries,mapOf("Referer" to item.sourceUrl!!)));frame(root)
            assertTrue(label(root,"Сезон 1").requestFocus());label(root,"Сезон 1").performClick();frame(root)
            assertEquals("Дубляж",root.findFocus()?.tag)
            label(root,"Дубляж").performClick();frame(root)
            assertEquals("online:1:1:Дубляж",root.findFocus()?.tag)
            screenshot(root,"media-center-native-episodes")
            assertTrue(all(root).filterIsInstance<TextView>().any { it.text.toString()=="Киновод · Сезон 1 · Дубляж" })
            all(root).first { it.tag=="online:1:2:Дубляж" }.apply { requestFocus();performClick() }
            val intent=shadowOf(host).nextStartedActivity
            assertEquals("com.lampplayer.tv.player.PlayerActivity",intent.component!!.className)
            val parsed=com.lampplayer.tv.player.IntentParser.parse(intent)!!
            assertEquals("https://cdn.test/2.m3u8",parsed.first)
            assertEquals(listOf(1,2,4),parsed.second.episodes.map { it.episode })
            assertEquals("Дубляж",parsed.second.translator)
            assertNull(com.lampplayer.tv.player.EpisodeNavigation.next(parsed.second.episodes,parsed.first))
            controller.pause().resume();frame(root)
            assertEquals("online:1:2:Дубляж",root.findFocus()?.tag)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun refreshingAnExpiredLinkReturnsToTheSameEpisodeWithoutPoppingTheVoiceScreen() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible().windowFocusChanged(true)
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            val item=CenterTitle(17,"tv","Сериал",provider="kinovod",sourceUrl="https://source.test/serial/17-show")
            val old=OnlineVideo(item,listOf(
                OnlineEpisode(1,1,"Первая","Дубляж",mapOf(720 to "https://cdn.test/old1.m3u8")),
                OnlineEpisode(1,2,"Вторая","Дубляж",mapOf(720 to "https://cdn.test/old2.m3u8")),
                OnlineEpisode(1,2,"Second","Оригинал",mapOf(720 to "https://cdn.test/en2.m3u8"))),emptyMap(),fetchedAt=0)
            host.nextOnline=old.copy(episodes=old.episodes.map { e -> e.copy(sources=e.sources.mapValues { it.value.replace("old","fresh") }) },fetchedAt=System.currentTimeMillis())
            host.nativeFixture(old);frame(root)
            label(root,"Сезон 1").apply { requestFocus();performClick() };frame(root)
            label(root,"Дубляж").performClick();frame(root)
            all(root).first { it.tag=="online:1:2:Дубляж" }.apply { requestFocus();performClick() };frame(root)
            val parsed=com.lampplayer.tv.player.IntentParser.parse(shadowOf(host).nextStartedActivity)!!
            assertEquals("https://cdn.test/fresh2.m3u8",parsed.first);assertEquals(1,host.onlineCalls)
            controller.pause().resume();frame(root)
            assertEquals("online:1:2:Дубляж",root.findFocus()?.tag)
            all(root).first { it.tag=="online:1:1:Дубляж" }.apply { requestFocus();performClick() };frame(root)
            assertEquals("https://cdn.test/fresh1.m3u8",com.lampplayer.tv.player.IntentParser.parse(shadowOf(host).nextStartedActivity)!!.first)
            assertEquals(1,host.onlineCalls)
            controller.pause().resume();frame(root)
            host.onBackPressedDispatcher.onBackPressed();frame(root)
            assertEquals("Дубляж",root.findFocus()?.tag)
            host.onBackPressedDispatcher.onBackPressed();frame(root)
            assertEquals("Сезон 1",root.findFocus()?.tag);assertEquals(1,host.onlineCalls)
        } finally { controller.pause().stop().destroy() }
    }


    @Test fun manualRefreshKeepsTheSeasonAndVoiceAndUsesFreshLinks() {
        checkOnlineRefresh("keep")
    }
    @Test fun removedVoiceReturnsToVoiceSelectionInTheSameSeason() {
        checkOnlineRefresh("voice")
    }
    @Test fun removedSeasonReturnsToAvailableSeasons() {
        checkOnlineRefresh("season")
    }
    private fun checkOnlineRefresh(change: String) {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible().windowFocusChanged(true)
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            val item=CenterTitle(17,"tv","Сериал",provider="kinovod",sourceUrl="https://source.test/serial/17-show")
            val old=OnlineVideo(item,listOf(
                OnlineEpisode(1,1,"Первый сезон","Дубляж",mapOf(720 to "https://cdn.test/s1.m3u8")),
                OnlineEpisode(2,1,"Первая","Дубляж",mapOf(720 to "https://cdn.test/old.m3u8")),
                OnlineEpisode(2,1,"Original","Оригинал",mapOf(720 to "https://cdn.test/en.m3u8")),
                OnlineEpisode(2,1,"Other","Другая",mapOf(720 to "https://cdn.test/other.m3u8"))),emptyMap())
            host.nextOnline=old.copy(episodes=old.episodes.filter {
                when(change) {
                    "voice" -> it.season!=2 || it.voice!="Дубляж"
                    "season" -> it.season!=2
                    else -> true
                }
            }.map { it.copy(sources=it.sources.mapValues { source -> source.value.replace("old","fresh") }) })
            host.nativeFixture(old);frame(root)
            label(root,"Сезон 2").apply { requestFocus();performClick() };frame(root)
            label(root,"Дубляж").performClick();frame(root)
            label(root,"Обновить список и ссылки").apply { requestFocus();performClick() };frame(root)
            assertEquals(1,host.onlineCalls)
            assertNull(shadowOf(host).nextStartedActivity)
            when(change) {
                "keep" -> {
                    assertNotNull(label(root,"Киновод · Сезон 2 · Дубляж"))
                    all(root).first { it.tag=="online:2:1:Дубляж" }.performClick();frame(root)
                    val parsed=com.lampplayer.tv.player.IntentParser.parse(shadowOf(host).nextStartedActivity)!!
                    assertEquals("https://cdn.test/fresh.m3u8",parsed.first)
                    assertEquals(2,parsed.second.seasonNumber)
                    assertEquals("Дубляж",parsed.second.translator)
                    controller.pause().resume();frame(root)
                    host.onBackPressedDispatcher.onBackPressed();frame(root)
                    assertNotNull(label(root,"Озвучка"))
                    host.onBackPressedDispatcher.onBackPressed();frame(root)
                    assertNotNull(label(root,"Сезон 2"))
                }
                "voice" -> {
                    assertNotNull(label(root,"Киновод · Сезон 2"))
                    assertNotNull(label(root,"Озвучка"))
                    assertNotNull(label(root,"Оригинал"))
                    assertFalse(all(root).any { it.tag=="online:2:1:Дубляж" })
                }
                else -> {
                    assertNotNull(label(root,"Сезон 1"))
                    assertFalse(all(root).filterIsInstance<TextView>().any { it.text.toString()=="Сезон 2" })
                }
            }
        } finally { controller.pause().stop().destroy() }
    }
}
