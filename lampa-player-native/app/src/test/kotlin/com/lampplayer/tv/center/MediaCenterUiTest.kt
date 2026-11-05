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
        override suspend fun genres(type:String)=listOf(18 to "Драма",35 to "Комедия")
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
        val gridCalls=mutableListOf<Int>()
        val gridPages=mutableMapOf<Int,List<CenterTitle>>()
        val browseCalls=mutableListOf<Pair<CenterBrowse,Int>>()
        val browsePages=mutableMapOf<Int,List<CenterTitle>>()
        override suspend fun browsePage(provider:String,request:CenterBrowse,number:Int):List<CenterTitle> {
            browseCalls.add(request to number);return browsePages[number].orEmpty()
        }
        fun browseFixture(items:List<CenterTitle>,request:CenterBrowse) { showHome(items,"Результаты поиска",1,null,request) }
        fun mineFixture() { mySeriesScreen() }
        var failGrid=false
        override suspend fun nextGridPage(provider:String,category:CenterSection?,type:String,number:Int):List<CenterTitle> {
            gridCalls.add(number)
            if(failGrid) { failGrid=false;error("temporary") }
            return gridPages[number].orEmpty()
        }
        fun gridFixture(items:List<CenterTitle>) { showHome(items,"Grid",1,null) }
        override suspend fun resolveOnline(item:CenterTitle):OnlineVideo { onlineCalls++;return requireNotNull(nextOnline) }
        override fun loadHome() { val items=(1..8).map {
            CenterTitle(it, if(it%2==0) "tv" else "movie", listOf("Тихий город","За горизонтом","Новый день","На орбите")[(it-1)%4],
                overview="Истории, к которым хочется возвращаться. Каталог, серии и просмотр в одном приложении.", year="2026",rating="8.2")
        }; showDashboard(CenterSection.all.map { section -> section to items.filter { it.type == section.type } }) }
        fun releaseFixture(items: List<CenterRelease>) { releaseList(CenterTitle(801,"movie","Sorting"),null,items) }
        fun detailFixture(details: CenterDetails) { showDetails(details) }
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
    private fun awaitUi(root: View, ready: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!ready() && System.nanoTime() < deadline) { Thread.sleep(10); frame(root) }
        assertTrue("UI did not finish reading saved progress", ready())
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
            all(root).first { it.tag=="menu_handle" }.performClick();frame(root)
            val home=all(root).first { it.tag=="Главная" };home.requestFocus();frame(root);key(root,KeyEvent.KEYCODE_DPAD_RIGHT);frame(root)
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
    @Test fun expiredLinkWithRemovedQualityKeepsTheEpisodeListForReselection() {
        checkOnlineRefresh("quality")
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
                OnlineEpisode(2,1,"Other","Другая",mapOf(720 to "https://cdn.test/other.m3u8"))),emptyMap(), fetchedAt=if(change=="quality") 0 else System.currentTimeMillis())
            host.nextOnline=old.copy(episodes=old.episodes.filter {
                when(change) {
                    "voice" -> it.season!=2 || it.voice!="Дубляж"
                    "season" -> it.season!=2
                    else -> true
                }
            }.map { entry -> entry.copy(sources=entry.sources.mapKeys { if(change=="quality") 480 else it.key }
                .mapValues { source -> source.value.replace("old","fresh") }) }, fetchedAt=System.currentTimeMillis())
            host.nativeFixture(old);frame(root)
            label(root,"Сезон 2").apply { requestFocus();performClick() };frame(root)
            label(root,"Дубляж").performClick();frame(root)
            if(change=="quality") all(root).first { it.tag=="online:2:1:Дубляж" }.apply { requestFocus();performClick() }
            else label(root,"Обновить список и ссылки").apply { requestFocus();performClick() }
            frame(root)
            assertEquals(1,host.onlineCalls)
            assertNull(shadowOf(host).nextStartedActivity)
            when(change) {
                "keep", "quality" -> {
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

    @Test fun qualityChoicePersistsPerProviderAndCanBeOverriddenOrReset() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible().windowFocusChanged(true)
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            val item=CenterTitle(21,"movie","Фильм",provider="kinovod",sourceUrl="https://source.test/film/21-title")
            val episode=OnlineEpisode(0,0,"Фильм","Дубляж",mapOf(
                1080 to "https://cdn.test/1080.m3u8",720 to "https://cdn.test/720.m3u8"))
            val video=OnlineVideo(item,listOf(episode),emptyMap())
            fun show() { host.nativeFixture(video);frame(root) }
            fun entry()=all(root).first { it.tag=="online:0:0:Дубляж" }
            fun dialog()=org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            fun choose(index:Int) {
                val list=dialog().listView
                list.performItemClick(list.getChildAt(index),index,list.adapter.getItemId(index));frame(root)
            }
            fun played()=com.lampplayer.tv.player.IntentParser.parse(shadowOf(host).nextStartedActivity)!!
            show();entry().performClick();frame(root)
            assertNull(shadowOf(host).nextStartedActivity)
            choose(1)
            assertEquals("https://cdn.test/720.m3u8",played().first)
            assertEquals(720,CenterStore(host).preferredQuality("kinovod"))
            assertNull(CenterStore(host).preferredQuality("filmix"))
            controller.pause().resume();frame(root)
            entry().performClick();frame(root)
            assertEquals("https://cdn.test/720.m3u8",played().first)
            controller.pause().resume();frame(root)
            entry().performLongClick();frame(root)
            assertNull(shadowOf(host).nextStartedActivity)
            choose(0)
            assertEquals("1080",played().second.quality)
            controller.pause().resume();frame(root)
            entry().performLongClick();frame(root)
            dialog().getButton(android.content.DialogInterface.BUTTON_NEUTRAL).performClick();frame(root)
            assertNull(CenterStore(host).preferredQuality("kinovod"))
            assertNull(shadowOf(host).nextStartedActivity)
            entry().performClick();frame(root)
            assertTrue(dialog().isShowing)
            dialog().dismiss()
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun unavailablePreferredQualityRequiresConfirmationEvenForOneFallback() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible().windowFocusChanged(true)
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            CenterStore(host).setPreferredQuality("filmix",1080)
            val item=CenterTitle(21,"movie","Фильм",provider="filmix",sourceUrl="https://source.test/film/21-title")
            host.nativeFixture(OnlineVideo(item,listOf(OnlineEpisode(0,0,"Фильм","Дубляж",
                mapOf(480 to "https://cdn.test/480.m3u8"))),emptyMap()));frame(root)
            all(root).first { it.tag=="online:0:0:Дубляж" }.performClick();frame(root)
            assertNull(shadowOf(host).nextStartedActivity)
            val dialog=org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            assertTrue(dialog.isShowing)
            dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick();frame(root)
            assertEquals(1080,CenterStore(host).preferredQuality("filmix"))
            assertNull(shadowOf(host).nextStartedActivity)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun autoQualityIsARealPreferenceAndDoesNotLeakToAnotherProvider() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        CenterStore(context).setPreferredQuality("kinovod",0)
        assertEquals(0,CenterStore(context).preferredQuality("kinovod"))
        assertNull(CenterStore(context).preferredQuality("filmix"))
        CenterStore(context).setPreferredQuality("kinovod",null)
        assertNull(CenterStore(context).preferredQuality("kinovod"))
    }

    @Test fun drawerOverlaysTheCatalogAndClosesWithBackOrRight() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible().windowFocusChanged(true)
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content);frame(root)
            val handle=all(root).first { it.tag=="menu_handle" }
            val catalog=label(root,"Каталог: TMDB  ▾")
            assertFalse(all(root).first { it.tag=="Главная" }.isShown)
            val width=catalog.width
            handle.performClick();frame(root)
            assertTrue(all(root).first { it.tag=="Главная" }.isShown)
            assertEquals(width,catalog.width)
            assertEquals("Главная",root.findFocus()?.tag)
            screenshot(root,"media-center-drawer-open")
            host.onBackPressedDispatcher.onBackPressed();frame(root)
            assertFalse(all(root).first { it.tag=="Главная" }.isShown)
            assertEquals("Каталог: TMDB  ▾",root.findFocus()?.tag)
            host.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_LEFT))
            host.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_LEFT));frame(root)
            assertTrue(all(root).first { it.tag=="Главная" }.isShown)
            key(root,KeyEvent.KEYCODE_DPAD_RIGHT);frame(root)
            assertFalse(all(root).first { it.tag=="Главная" }.isShown)
            assertEquals(width,catalog.width)
            screenshot(root,"media-center-drawer-closed")
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun watchedEpisodesAndResumeUseStablePositions() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible().windowFocusChanged(true)
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            val item=CenterTitle(981,"tv","Progress series",provider="kinovod",sourceUrl="https://source.test/serial/981-show")
            val card=com.lampplayer.tv.domain.model.CardMeta(item.title,contentKey=item.key,sourcePage=item.sourceUrl,seasonNumber=2,episodeNumber=5)
            val positions=com.lampplayer.tv.data.datastore.PositionDataStore(host)
            kotlinx.coroutines.runBlocking {
                positions.savePosition("old-token",card,com.lampplayer.tv.domain.model.PlaybackPosition(1112.0,2400.0,46,false))
                positions.savePosition("watched-token",card.copy(episodeNumber=4),com.lampplayer.tv.domain.model.PlaybackPosition(2390.0,2400.0,99,true))
            }
            CenterStore(host).remember("old-token",card)
            host.detailFixture(CenterDetails(item,listOf(2),""));repeat(8) { frame(root) }
            val resume=all(root).filterIsInstance<TextView>().first { it.tag=="continue_title" }
            awaitUi(root) { resume.text.toString().contains("Сезон 2 · Серия 5 · 18:32") }
            assertTrue(resume.text.toString().contains("Сезон 2 · Серия 5 · 18:32"))
            val video=OnlineVideo(item,listOf(4,5).map { OnlineEpisode(2,it,"Episode $it","Voice",mapOf(720 to "https://cdn.test/new-$it.m3u8")) },emptyMap())
            host.nativeFixture(video);frame(root);label(root,"Сезон 2").performClick();repeat(8) { frame(root) }
            val watched=all(root).filterIsInstance<TextView>().first { it.tag=="online:2:4:Voice" }
            val current=all(root).filterIsInstance<TextView>().first { it.tag=="online:2:5:Voice" }
            awaitUi(root) { watched.text.startsWith("✓") && current.text.startsWith("▶") && current.text.contains("46%") }
            assertTrue(watched.text.startsWith("✓"))
            assertTrue(current.text.startsWith("▶"))
            assertTrue(current.text.contains("46%"))
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun voicePreferenceIsPerTitleAndCardSizesSurviveReopening() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=CenterStore(context)
        store.setVoice("kinovod:tv:1","Дубляж")
        store.setVoice("filmix:tv:1","Оригинал")
        assertEquals("Дубляж",CenterStore(context).voice("kinovod:tv:1"))
        assertEquals("Оригинал",CenterStore(context).voice("filmix:tv:1"))
        assertNull(store.voice("kinovod:tv:2"))
        for(size in listOf("compact","normal","large")) { store.cardSize=size;assertEquals(size,CenterStore(context).cardSize) }
        store.setVoice("kinovod:tv:1",null);assertNull(store.voice("kinovod:tv:1"))
    }
    @Test fun compactCardsLeaveRoomForMoreTitles() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        fun cardWidth(size:String):Int {
            CenterStore(context).cardSize=size
            val controller=Robolectric.buildActivity(Host::class.java).setup().visible()
            try {
                val root=controller.get().findViewById<View>(android.R.id.content);frame(root)
                return all(root).first { it.tag=="new_episodes:title:tv:2" }.width
            } finally { controller.pause().stop().destroy() }
        }
        assertTrue(cardWidth("compact") < cardWidth("normal"))
        assertTrue(cardWidth("normal") < cardWidth("large"))
    }

    @Test fun recoveryQualityReturnsToTheSameEpisodeAndDoesNotAutoplay() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible().windowFocusChanged(true)
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            val item=CenterTitle(912,"tv","Recovery",provider="kinovod",sourceUrl="https://source.test/serial/912-show")
            val card=com.lampplayer.tv.domain.model.CardMeta(item.title,contentKey=item.key,sourcePage=item.sourceUrl,seasonNumber=2,episodeNumber=5,translator="Voice",quality="720")
            CenterStore(host).setPreferredQuality("kinovod",720)
            host.nextOnline=OnlineVideo(item,listOf(OnlineEpisode(2,5,"Episode 5","Voice",mapOf(
                720 to "https://cdn.test/fresh720.m3u8",1080 to "https://cdn.test/fresh1080.m3u8"))),emptyMap())
            ReflectionHelpers.setField(host,"recovery","quality" to card)
            controller.pause().resume();repeat(4) { frame(root) }
            assertEquals(1,host.onlineCalls)
            assertNull(shadowOf(host).nextStartedActivity)
            all(root).first { it.tag=="online:2:5:Voice" }.performClick();frame(root)
            val dialog=org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            assertTrue(dialog.isShowing)
            assertNull(shadowOf(host).nextStartedActivity)
            dialog.dismiss()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun releaseFiltersKeepRemoteFocusAndPersistAcrossStoreInstances() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible().windowFocusChanged(true)
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            host.releaseFixture(listOf(
                CenterRelease("Film 1080p","magnet:?xt=urn:btih:a",0,100,"A"),
                CenterRelease("Film 720p","magnet:?xt=urn:btih:b",5,200,"B")))
            frame(root)
            all(root).first { it.tag=="release_seeded" }.apply { requestFocus();performClick() };frame(root)
            assertTrue(CenterStore(host).seededOnly)
            assertEquals("release_seeded",root.findFocus()?.tag)
            assertEquals(1,all(root).count { (it.tag as? String)?.startsWith("release:")==true })
            all(root).first { it.tag=="release_quality:1080" }.performClick();frame(root)
            assertEquals(0,all(root).count { (it.tag as? String)?.startsWith("release:")==true })
            all(root).first { it.tag=="release_sort" }.performClick();frame(root)
            val dialog=org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            val list=dialog.listView
            list.performItemClick(list.getChildAt(1),1,list.adapter.getItemId(1));frame(root)
            assertEquals("small",CenterStore(host).releaseSort)
            assertEquals("release_sort",root.findFocus()?.tag)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun gridAutomaticallyAppendsDeduplicatedPagesAndRestoresTheSelectedCard() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible().windowFocusChanged(true)
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            fun item(id:Int)=CenterTitle(id,"movie","Grid movie $id")
            host.gridPages[2]=(3..8).map { item(it) }
            host.gridFixture((1..3).map { item(it) });repeat(4) { frame(root) }
            var grid=all(root).filterIsInstance<androidx.recyclerview.widget.RecyclerView>().first { it.tag=="catalog_grid" }
            assertTrue(grid.layoutManager is androidx.recyclerview.widget.GridLayoutManager)
            assertEquals(8,grid.adapter!!.itemCount)
            assertEquals(listOf(2),host.gridCalls)
            val poster=all(root).first { it.tag=="title:movie:2" };poster.requestFocus();poster.performClick();frame(root)
            host.onBackPressedDispatcher.onBackPressed();repeat(3) { frame(root) }
            assertEquals("title:movie:2",root.findFocus()?.tag)
            grid=all(root).filterIsInstance<androidx.recyclerview.widget.RecyclerView>().first { it.tag=="catalog_grid" }
            grid.scrollToPosition(7);repeat(4) { frame(root) }
            all(root).firstOrNull { it.tag=="title:movie:8" }?.requestFocus();repeat(3) { frame(root) }
            assertTrue(host.gridCalls.contains(3))
            assertEquals(8,grid.adapter!!.itemCount)
            assertFalse(all(root).filterIsInstance<TextView>().any { it.text.toString()=="Следующая подборка   ›" })
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun gridRetryRetainsLoadedCardsAndRequestsTheSamePage() {
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible()
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            host.failGrid=true
            host.gridFixture(listOf(CenterTitle(1,"movie","One")));repeat(3) { frame(root) }
            val status=all(root).filterIsInstance<TextView>().first { it.tag=="grid_status" }
            assertTrue(status.text.startsWith("Не удалось"))
            host.gridPages[2]=listOf(CenterTitle(2,"movie","Two"))
            status.performClick();repeat(3) { frame(root) }
            val grid=all(root).filterIsInstance<androidx.recyclerview.widget.RecyclerView>().first { it.tag=="catalog_grid" }
            assertEquals(2,grid.adapter!!.itemCount)
            assertEquals(listOf(2,2),host.gridCalls)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun exactFilterHidesUnrelatedResultsAndLongPressShowsReleaseDetails() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible().windowFocusChanged(true)
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            host.releaseFixture(listOf(
                CenterRelease("Unrelated 1080p","other",50,100,"A"),
                CenterRelease("Sorting 1080p HEVC HDR10","right",2,200,"B",voices=listOf("Дубляж"))))
            frame(root)
            val exact=all(root).first { it.tag=="release_exact" }
            exact.requestFocus();exact.performClick();frame(root)
            assertEquals("release_exact",root.findFocus()?.tag)
            assertEquals(1,all(root).count { (it.tag as? String)?.startsWith("release:")==true })
            all(root).first { it.tag=="release:right" }.performLongClick();frame(root)
            val dialog=org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            val message=dialog.findViewById<TextView>(android.R.id.message)!!.text.toString()
            assertTrue(message.contains("Дубляж"));assertTrue(message.contains("HEVC"));assertTrue(message.contains("HDR10"))
            assertNull(shadowOf(host).nextStartedActivity)
            dialog.dismiss()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun searchGridContinuesTheSameQueryAndPersonalSeriesAreFinite() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible().windowFocusChanged(true)
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            val request=CenterBrowse(type="all",query="Источники")
            host.browsePages[2]=listOf(CenterTitle(2,"tv","Result 2"))
            host.browseFixture(listOf(CenterTitle(1,"movie","Result 1")),request);repeat(4) { frame(root) }
            assertEquals(listOf(request to 2),host.browseCalls)
            var grid=all(root).filterIsInstance<androidx.recyclerview.widget.RecyclerView>().first { it.tag=="catalog_grid" }
            assertEquals(2,grid.adapter!!.itemCount)
            CenterStore(host).toggleFollow(CenterTitle(8,"tv","Following",provider="kinovod",sourceUrl="https://source.test/serial/8-show"))
            host.mineFixture();repeat(3) { frame(root) }
            grid=all(root).filterIsInstance<androidx.recyclerview.widget.RecyclerView>().first { it.tag=="catalog_grid" }
            assertEquals(1,grid.adapter!!.itemCount)
            assertEquals(listOf(request to 2),host.browseCalls)
            assertTrue(all(root).filterIsInstance<TextView>().any { it.text=="Сериалы из подписок и истории просмотра" })
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun titleCanBeAddedAndRemovedFromMySeriesWithoutStartingPlayback() {
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible()
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            val item=CenterTitle(321,"tv","Follow me")
            host.detailFixture(CenterDetails(item,listOf(1),""));frame(root)
            all(root).first { it.tag=="follow_series" }.performClick();frame(root)
            assertTrue(CenterStore(host).isFollowed(item))
            all(root).first { it.tag=="follow_series" }.performClick();frame(root)
            assertFalse(CenterStore(host).isFollowed(item));assertNull(shadowOf(host).nextStartedActivity)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun changingSearchSourceRetainsTheTypedQueryAndRecentSearchStartsImmediately() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible().windowFocusChanged(true)
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            CenterStore(host).rememberSearch("Король Талсы")
            all(root).first { it.tag=="Поиск" }.performClick();frame(root)
            (all(root).first { it.tag=="search_query" } as android.widget.EditText).setText("Источник")
            all(root).first { it.tag=="search_source" }.performClick();frame(root)
            val dialog=org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            val list=dialog.listView;list.performItemClick(list.getChildAt(1),1,list.adapter.getItemId(1));frame(root)
            assertEquals("kinovod",CenterStore(host).catalog)
            assertEquals("Источник",(all(root).first { it.tag=="search_query" } as TextView).text.toString())
            assertEquals("search_source",root.findFocus()?.tag)
            all(root).first { it.tag=="search_recent:Король Талсы" }.performClick();repeat(3) { frame(root) }
            assertEquals("Король Талсы",host.browseCalls.single().first.query)
            assertEquals(1,host.browseCalls.single().second)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun longPressRemovesOnlyOneRecentSearchWithoutIssuingARequest() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible()
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            CenterStore(host).rememberSearch("Первый");CenterStore(host).rememberSearch("Второй")
            all(root).first { it.tag=="Поиск" }.performClick();frame(root)
            all(root).first { it.tag=="search_recent:Второй" }.performLongClick();frame(root)
            assertEquals(listOf("Первый"),CenterStore(host).searches())
            assertEquals("search_recent:Первый",root.findFocus()?.tag)
            assertTrue(host.browseCalls.isEmpty())
        } finally { controller.pause().stop().destroy() }
    }
}
