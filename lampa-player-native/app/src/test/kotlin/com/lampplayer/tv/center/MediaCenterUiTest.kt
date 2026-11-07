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
        var updateChecks = 0
        var uptime = 10000L
        override fun elapsedTime() = uptime
        override suspend fun availableUpdate(): com.lampplayer.tv.update.UpdateInfo? { updateChecks++; return null }
        var nextOnline:OnlineVideo?=null
        var onlineCalls=0
        val requestedPlayers=mutableListOf<Int?>()
        val resolveFailures=java.util.ArrayDeque<Exception>()
        val kinoQueries=mutableListOf<String>()
        var kinoResults=emptyList<CenterTitle>()
        val resolvedItems=mutableListOf<CenterTitle>()
        val sourceQueries=mutableListOf<Pair<String,String>>()
        val sourceResults=mutableMapOf<String,List<CenterTitle>>()
        override suspend fun searchOnlineCatalog(provider:String,query:String):List<CenterTitle> {
            if(provider=="kinovod")return searchKinoVod(query)
            sourceQueries.add(provider to query);return sourceResults[provider].orEmpty()
        }
        override suspend fun searchKinoVod(query:String):List<CenterTitle> { kinoQueries.add(query);return kinoResults }
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
        val verifyFailures=java.util.ArrayDeque<Exception>()
        val verifiedUrls=mutableListOf<String>()
        override suspend fun verifyOnlineUrl(url:String,headers:Map<String,String>):String {
            verifiedUrls.add(url)
            if(verifyFailures.isNotEmpty())throw verifyFailures.removeFirst()
            return url
        }
        override suspend fun resolveOnline(item:CenterTitle):OnlineVideo { onlineCalls++;resolvedItems.add(item);if(resolveFailures.isNotEmpty())throw resolveFailures.removeFirst();return requireNotNull(nextOnline) }
        override suspend fun resolveOnlinePlayer(item:CenterTitle,playerIndex:Int?):OnlineVideo {
            onlineCalls++;resolvedItems.add(item);requestedPlayers.add(playerIndex);if(resolveFailures.isNotEmpty())throw resolveFailures.removeFirst();return requireNotNull(nextOnline)
        }
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
    private fun label(root:View,value:String)=all(root).filterIsInstance<TextView>().first { it.text.toString()==value || it.contentDescription?.toString()==value }
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
            assertFalse(home.hasFocus());assertNotNull(root.findFocus());assertEquals("Каталог: TMDB  ▾",(root.findFocus() as TextView).contentDescription?.toString())
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
            val width=(catalog.parent as View).width
            handle.performClick();frame(root)
            assertTrue(all(root).first { it.tag=="Главная" }.isShown)
            assertEquals(width,(catalog.parent as View).width)
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
            assertEquals(width,(catalog.parent as View).width)
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

    @Test fun legacyOnlineRecoveryOpensTorrentSetupWithoutResolvingOrPlaying() {
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible()
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            CenterStore(host).indexer=""
            val card=com.lampplayer.tv.domain.model.CardMeta("Recovery",contentKey="filmix:tv:912",
                sourcePage="https://source.test/seria/912-show",seasonNumber=2,episodeNumber=5)
            ReflectionHelpers.setField(host,"recovery","quality" to card)
            controller.pause().resume();frame(root)
            assertEquals(0,host.onlineCalls)
            assertNull(shadowOf(host).nextStartedActivity)
            val dialog=org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            assertTrue(dialog.isShowing)
            assertTrue(all(dialog.window!!.decorView).filterIsInstance<TextView>().any { it.text.toString()=="Источники" })
            dialog.dismiss()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun backOnHomeNeedsTwoSeparatePressesWithinTwoSeconds() {
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible()
        try {
            val host=controller.get()
            host.onBackPressedDispatcher.onBackPressed()
            assertFalse(host.isFinishing)
            host.uptime += 2100
            host.onBackPressedDispatcher.onBackPressed()
            assertFalse(host.isFinishing)
            host.uptime += 500
            host.onBackPressedDispatcher.onBackPressed()
            assertTrue(host.isFinishing)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun launcherReopensHomeWhileOrdinaryResumeKeepsTheCurrentScreen() {
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible()
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            host.detailFixture(CenterDetails(CenterTitle(8,"movie","Launch fixture"),emptyList(),""));frame(root)
            controller.pause().resume();frame(root)
            assertTrue(all(root).filterIsInstance<TextView>().any { it.text.toString()=="Launch fixture" })
            controller.newIntent(android.content.Intent().putExtra("open_home",true));frame(root)
            assertTrue(all(root).filterIsInstance<TextView>().any { it.text.toString()=="Казахстанское кино" })
            assertFalse(all(root).filterIsInstance<TextView>().any { it.text.toString()=="Launch fixture" })
            host.onBackPressedDispatcher.onBackPressed();assertFalse(host.isFinishing)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun providerDetailsAndOldOnlineHistoryOnlyOfferTorrentPlayback() {
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible()
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            val item=CenterTitle(947,"tv","Old online",provider="filmix",sourceUrl="https://source.test/seria/947-show")
            val card=com.lampplayer.tv.domain.model.CardMeta(item.title,contentKey=item.key,sourcePage=item.sourceUrl,
                seasonNumber=2,episodeNumber=5)
            CenterStore(host).apply { indexer="";remember("https://cdn.test/expired.m3u8",card) }
            host.detailFixture(CenterDetails(item,emptyList(),""));frame(root)
            assertTrue(all(root).filterIsInstance<TextView>().any { it.text.toString()=="Найти торрент-раздачу" })
            assertFalse(all(root).filterIsInstance<TextView>().any { it.text.contains("Смотреть онлайн") || it.contentDescription=="Открыть страницу источника вручную" })
            all(root).first { it.tag=="continue_title" }.performClick();frame(root)
            assertEquals(0,host.onlineCalls);assertNull(shadowOf(host).nextStartedActivity)
            val dialog=org.robolectric.shadows.ShadowDialog.getLatestDialog()
            assertTrue(dialog.isShowing);dialog.dismiss()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun automaticUpdatePreferencePersistsAndDisabledStartupDoesNotCheck() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=CenterStore(context)
        assertTrue(store.autoUpdates)
        store.autoUpdates=false
        assertFalse(CenterStore(context).autoUpdates)
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible()
        try {
            frame(controller.get().findViewById(android.R.id.content))
            assertEquals(0,controller.get().updateChecks)
        } finally { controller.pause().stop().destroy();store.autoUpdates=true }
        val enabled=Robolectric.buildActivity(Host::class.java).setup().visible()
        try {
            frame(enabled.get().findViewById(android.R.id.content))
            assertEquals(1,enabled.get().updateChecks)
        } finally { enabled.pause().stop().destroy() }
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
            all(root).first { it.tag=="release_filters_toggle" }.performClick();frame(root)
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
            all(root).first { it.tag=="release_filters_toggle" }.performClick();frame(root)
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

    @Test fun toolbarLabelsExpandOnlyOnFocusAndCollapseWhenBrowsingCards() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible().windowFocusChanged(true)
        try {
            val root=controller.get().findViewById<View>(android.R.id.content);frame(root)
            val catalog=label(root,"Каталог: TMDB  ▾")
            assertTrue(catalog.text.toString().contains("Каталог: TMDB"))
            val collapsed=catalog.width
            catalog.requestFocus();frame(root)
            assertEquals("Каталог: TMDB  ▾",catalog.contentDescription?.toString())
            assertEquals(collapsed,catalog.width)
            all(root).first { it.tag=="new_episodes:title:tv:2" }.requestFocus();frame(root)
            assertTrue(catalog.text.toString().contains("Каталог: TMDB"));assertEquals(collapsed,catalog.width)
            screenshot(root,"media-center-compact-toolbar")
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun releaseFiltersStartCollapsedAndCanBeOpenedAndClosedWithoutLosingResults() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible().windowFocusChanged(true)
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            host.releaseFixture(listOf(CenterRelease("Sorting 1080p","one",2,100,"A")));frame(root)
            val panel=all(root).first { it.tag=="release_filters_panel" }
            assertFalse(panel.isShown)
            val toggle=all(root).first { it.tag=="release_filters_toggle" }
            assertTrue("Description control cannot receive remote focus",toggle.requestFocus());toggle.performClick();frame(root);assertTrue(panel.isShown)
            toggle.performClick();frame(root);assertFalse(panel.isShown)
            assertNotNull(all(root).firstOrNull { it.tag=="release:one" })
            screenshot(root,"media-center-collapsed-release-filters")
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun replacementCatalogsAppearInBothSelectorsWithTheirOwnSections() {
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible()
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content);frame(root)
            label(root,"Каталог: TMDB  ▾").performClick();frame(root)
            var dialog=org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            val names=(0 until dialog.listView.adapter.count).map { dialog.listView.adapter.getItem(it).toString() }
            assertFalse(names.contains("Rezka"));assertFalse(names.contains("KazPlay · Қазақша"));assertFalse(names.contains("HDRezka · LV · тест"))
            assertTrue(names.containsAll(listOf("KinoGo · KZ","KinoTac","Zona.movie","KinoKong")))
            val index=names.indexOf("KinoTac")
            dialog.listView.performItemClick(dialog.listView.getChildAt(index),index,index.toLong());frame(root)
            assertEquals("kinotac",CenterStore(host).catalog)
            label(root,"Разделы  ▾").performClick();frame(root)
            dialog=org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            assertEquals(listOf("Фильмы","Сериалы"),
                (0 until dialog.listView.adapter.count).map { dialog.listView.adapter.getItem(it).toString() })
            dialog.dismiss()
            all(root).first { it.tag=="menu_handle" }.performClick();frame(root)
            label(root,"Поиск").performClick();frame(root)
            label(root,"Искать в: KinoTac").performClick();frame(root)
            dialog=org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            assertEquals(CenterCatalog.providers.size,dialog.listView.adapter.count);dialog.dismiss()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun onlineTestIsSelectiveAndKinogoPassesTheSelectedEpisodeToOurPlayer() {
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible()
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            val item=CenterTitle(6873,"tv","Гангстерленд",provider="kinogo",sourceUrl="https://kz.kinogo.moi/6873-gangsterlend-2025.html")
            host.nextOnline=OnlineVideo(item,listOf(OnlineEpisode(2,5,"Пятая","Voice",mapOf(720 to "https://cdn.test/five.m3u8"))),mapOf("Referer" to "https://player.test/embed/movie/9"))
            host.detailFixture(CenterDetails(item,emptyList(),""));frame(root)
            label(root,"Смотреть онлайн · проверка").performClick();frame(root)
            assertEquals(1,host.onlineCalls)
            label(root,"Сезон 2").performClick();frame(root)
            all(root).first { it.tag=="online:2:5:Voice" }.performClick();frame(root)
            val intent=shadowOf(host).nextStartedActivity
            assertTrue(intent.getBooleanExtra("center_online",false))
            val parsed=com.lampplayer.tv.player.IntentParser.parse(intent)!!
            assertEquals("https://cdn.test/five.m3u8",parsed.first)
            assertEquals(2,parsed.second.seasonNumber);assertEquals(5,parsed.second.episodeNumber)
            assertEquals(item.key,parsed.second.contentKey)
            assertEquals("https://player.test/",parsed.second.headers["Referer"])
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun a404RefreshesExactlyTheSameEpisodeOnceAndUsesFreshHeaders() {
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible()
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            val item=CenterTitle(240279,"tv","Король Талсы",provider="kinovod",sourceUrl="https://kinovod.pro/serial/240279-korol-talsy")
            val episode=OnlineEpisode(2,5,"Пятая","Voice",mapOf(720 to "https://cdn.test/expired.m3u8"))
            val old=OnlineVideo(item,listOf(episode),mapOf("Referer" to "https://source.test/old"))
            host.nextOnline=old.copy(episodes=listOf(episode.copy(sources=mapOf(720 to "https://cdn.test/fresh.m3u8"))),
                headers=mapOf("Referer" to "https://source.test/fresh"))
            host.verifyFailures.add(OnlineMediaUnavailable(404,"fixture","HTTP 404"))
            host.nativeFixture(old);frame(root);label(root,"Сезон 2").performClick();frame(root)
            all(root).first { it.tag=="online:2:5:Voice" }.performClick();frame(root)
            val parsed=com.lampplayer.tv.player.IntentParser.parse(shadowOf(host).nextStartedActivity)!!
            assertEquals(1,host.onlineCalls)
            assertEquals(listOf("https://cdn.test/expired.m3u8","https://cdn.test/fresh.m3u8"),host.verifiedUrls)
            assertEquals("https://cdn.test/fresh.m3u8",parsed.first)
            assertEquals(2,parsed.second.seasonNumber);assertEquals(5,parsed.second.episodeNumber)
            assertEquals("Voice",parsed.second.translator);assertEquals("720",parsed.second.quality)
            assertEquals("https://source.test/",parsed.second.headers["Referer"])
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun failedFreshLinkStopsRetryingAndSitePlayerDoesNotMarkAnythingWatched() {
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible()
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            val item=CenterTitle(240279,"tv","Король Талсы",provider="kinovod",sourceUrl="https://kinovod.pro/serial/240279-korol-talsy")
            val episode=OnlineEpisode(2,5,"Пятая","Voice",mapOf(720 to "https://cdn.test/expired.m3u8"))
            val old=OnlineVideo(item,listOf(episode),emptyMap())
            host.nextOnline=old.copy(episodes=listOf(episode.copy(sources=mapOf(720 to "https://cdn.test/fresh.m3u8"))))
            repeat(2) { host.verifyFailures.add(OnlineMediaUnavailable(404,"fixture","HTTP 404")) }
            host.nativeFixture(old);frame(root);label(root,"Сезон 2").performClick();frame(root)
            all(root).first { it.tag=="online:2:5:Voice" }.performClick();frame(root)
            assertEquals(1,host.onlineCalls);assertEquals(2,host.verifiedUrls.size)
            assertNull(shadowOf(host).nextStartedActivity)
            val before=CenterStore(host).history()
            label(root,"Плеер сайта").performClick();frame(root)
            val launch=shadowOf(host).nextStartedActivity
            assertTrue(launch.getBooleanExtra("site_player",false))
            assertEquals(item.sourceUrl,launch.getStringExtra("url"))
            assertEquals(before,CenterStore(host).history())
        } finally { controller.pause().stop().destroy() }
    }


    @Test fun missingPrimaryUsesDeclaredMirrorWithoutRefreshingOrChangingEpisode() {
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible()
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            val item=CenterTitle(240279,"tv","Король Талсы",provider="kinovod",sourceUrl="https://kinovod.pro/serial/240279-korol-talsy")
            val primary="https://cdn.test/expired.m3u8";val mirror="https://backup.test/five.m3u8"
            val episode=OnlineEpisode(2,5,"Пятая","Voice",mapOf(720 to primary),mapOf(720 to listOf(primary,mirror)))
            val video=OnlineVideo(item,listOf(episode),mapOf("Referer" to item.sourceUrl!!))
            host.verifyFailures.add(OnlineMediaUnavailable(404,"fixture","HTTP 404"))
            host.nativeFixture(video);frame(root);label(root,"Сезон 2").performClick();frame(root)
            all(root).first { it.tag=="online:2:5:Voice" }.performClick();frame(root)
            val parsed=com.lampplayer.tv.player.IntentParser.parse(shadowOf(host).nextStartedActivity)!!
            assertEquals(0,host.onlineCalls)
            assertEquals(listOf(primary,mirror),host.verifiedUrls)
            assertEquals(mirror,parsed.first)
            assertEquals(2,parsed.second.seasonNumber);assertEquals(5,parsed.second.episodeNumber)
            assertEquals("Voice",parsed.second.translator);assertEquals("720",parsed.second.quality)
            assertEquals(mirror,parsed.second.episodes.single().url)
            assertEquals("https://kinovod.pro/",parsed.second.headers["Referer"])
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun longSynopsisExpandsAboveTheTextAndKeepsTvFocusOnItsControl() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible().windowFocusChanged(true)
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            host.detailFixture(CenterDetails(CenterTitle(17,"movie","Фильм",overview=("Длинное описание сюжета. ").repeat(140),provider="kinotac"),emptyList(),"Актёр А"))
            frame(root)
            val toggle=all(root).first { it.tag=="description_toggle" }
            assertEquals(View.VISIBLE,toggle.visibility)
            toggle.requestFocus();toggle.performClick();frame(root)
            assertTrue(toggle.hasFocus());assertEquals("Свернуть описание",(toggle as TextView).text.toString())
            assertEquals(Int.MAX_VALUE,(all(root).first { it.tag=="detail_description" } as TextView).maxLines)
            toggle.performClick();frame(root)
            assertTrue(toggle.hasFocus());assertEquals("Показать полностью",toggle.text.toString())
            assertEquals(7,(all(root).first { it.tag=="detail_description" } as TextView).maxLines)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun tmdbOnlineFindsKinovodAndPassesTheTmdbIdentityAndSourcePageToOurPlayer() {
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible()
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            val tmdb=CenterTitle(153312,"tv","Король Талсы",original="Tulsa King",year="2022",poster="https://img.test/tmdb.jpg")
            val source=CenterTitle(240279,"tv","Король Талсы",year="2022",provider="kinovod",sourceUrl="https://kinovod.pro/serial/240279-korol-talsy")
            host.kinoResults=listOf(source)
            host.nextOnline=OnlineVideo(source,listOf(OnlineEpisode(2,5,"Пятая","Voice",mapOf(720 to "https://cdn.test/five.m3u8"))),mapOf("Referer" to source.sourceUrl!!))
            host.detailFixture(CenterDetails(tmdb,listOf(1,2),""));frame(root)
            label(root,"Смотреть онлайн").performClick();frame(root);all(root).first { it.tag=="online_source:kinovod" }.performClick();repeat(4){frame(root)}
            assertEquals(setOf("Король Талсы","Tulsa King"),host.kinoQueries.toSet())
            assertEquals(source.key,host.resolvedItems.single().key)
            label(root,"Сезон 2").performClick();frame(root)
            all(root).first { it.tag=="online:2:5:Voice" }.performClick();frame(root)
            val parsed=com.lampplayer.tv.player.IntentParser.parse(shadowOf(host).nextStartedActivity)!!.second
            assertEquals(153312,parsed.tmdbId);assertEquals(tmdb.key,parsed.contentKey)
            assertEquals(source.sourceUrl,parsed.sourcePage);assertEquals(tmdb.poster,parsed.posterUrl)
            assertEquals(2,parsed.seasonNumber);assertEquals(5,parsed.episodeNumber)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun ambiguousTmdbMatchesWaitForAnExplicitChoiceAndKeepTheSelectedSource() {
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible()
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            val tmdb=CenterTitle(777,"movie","Остров",year="2006")
            val first=CenterTitle(101,"movie","Остров",year="2006",provider="kinovod",sourceUrl="https://kinovod.pro/film/101-one")
            val second=first.copy(id=102,sourceUrl="https://kinovod.pro/film/102-two")
            host.kinoResults=listOf(first,second)
            host.nextOnline=OnlineVideo(second,listOf(OnlineEpisode(0,0,"Остров","Voice",mapOf(720 to "https://cdn.test/movie.mp4"))),emptyMap())
            host.detailFixture(CenterDetails(tmdb,emptyList(),""));frame(root)
            label(root,"Смотреть онлайн").performClick();frame(root);all(root).first { it.tag=="online_source:kinovod" }.performClick();repeat(3){frame(root)}
            assertEquals(0,host.onlineCalls);assertNull(shadowOf(host).nextStartedActivity)
            all(root).first { it.tag=="online_match:${second.key}" }.performClick();repeat(3){frame(root)}
            assertEquals(second.key,host.resolvedItems.single().key)
            assertEquals(second,CenterStore(host).onlineSource(tmdb))
            all(root).first { it.tag=="online:0:0:Voice" }.performClick();frame(root)
            val parsed=com.lampplayer.tv.player.IntentParser.parse(shadowOf(host).nextStartedActivity)!!.second
            assertEquals(tmdb.key,parsed.contentKey);assertEquals(second.sourceUrl,parsed.sourcePage)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun tmdbContinueRefreshesTheSavedKinovodEpisodeWithoutSearchingOrChangingItsIdentity() {
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible()
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            val tmdb=CenterTitle(153312,"tv","Король Талсы",year="2022")
            val source=CenterTitle(240279,"tv","Король Талсы",year="2022",provider="kinovod",sourceUrl="https://kinovod.pro/serial/240279-korol-talsy")
            host.nextOnline=OnlineVideo(source,listOf(OnlineEpisode(2,5,"Пятая","Voice",mapOf(720 to "https://cdn.test/fresh.m3u8"))),emptyMap())
            CenterStore(host).remember("https://cdn.test/expired.m3u8",com.lampplayer.tv.domain.model.CardMeta(
                tmdb.title,tmdbId=tmdb.id,contentKey=tmdb.key,sourcePage=source.sourceUrl,seasonNumber=2,episodeNumber=5,quality="720",translator="Voice"))
            host.detailFixture(CenterDetails(tmdb,listOf(1,2),""));frame(root)
            all(root).first { it.tag=="continue_title" }.performClick();repeat(5){frame(root)}
            assertTrue(host.kinoQueries.isEmpty());assertEquals(source.key,host.resolvedItems.single().key)
            val played=com.lampplayer.tv.player.IntentParser.parse(shadowOf(host).nextStartedActivity)!!
            assertEquals("https://cdn.test/fresh.m3u8",played.first);assertEquals(tmdb.key,played.second.contentKey)
            assertEquals(2,played.second.seasonNumber);assertEquals(5,played.second.episodeNumber)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun partialOnlineLinksAreLabeledAndKeepTheFullWebsiteListAvailable() {
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible()
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            val item=CenterTitle(95526,"tv","Это море переполнится",provider="kinokong",sourceUrl="https://kinokong.es/95526-show.html")
            val notice="Источник отдал только часть ссылок. Полный список серий доступен в плеере сайта."
            host.nativeFixture(OnlineVideo(item,listOf(OnlineEpisode(1,5,"Пятая","Voice",mapOf(0 to "https://cdn.test/five.m3u8"))),
                emptyMap(),notice=notice));frame(root)
            assertTrue(label(root,notice).isShown)
            label(root,"Полный список в плеере сайта").performClick();frame(root)
            val intent=shadowOf(host).nextStartedActivity
            assertEquals(item.sourceUrl,intent.getStringExtra("url"));assertTrue(intent.getBooleanExtra("site_player",false))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun failedVideoCanChooseAnotherSourcePlayerAndKeepSeasonVoiceAndTitle() {
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible()
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            val item=CenterTitle(17,"tv","Show",provider="kinotac",sourceUrl="https://kinotac.org/17-show.html")
            val episode=OnlineEpisode(2,5,"Пятая","Voice",mapOf(720 to "https://cdn.test/bad.m3u8"))
            val first=OnlineVideo(item,listOf(episode),emptyMap(),playerCount=2)
            host.nextOnline=first.copy(episodes=listOf(episode.copy(sources=mapOf(720 to "https://cdn.test/good.m3u8"))),playerIndex=1,pinnedPlayer=true)
            host.verifyFailures.add(java.io.IOException("Нет соединения"))
            host.nativeFixture(first);frame(root);label(root,"Сезон 2").performClick();frame(root)
            all(root).first { it.tag=="online:2:5:Voice" }.performClick();frame(root)
            assertNull(shadowOf(host).nextStartedActivity)
            label(root,"Другой плеер источника").performClick();frame(root)
            val dialog=org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            dialog.listView.performItemClick(dialog.listView.adapter.getView(2,null,dialog.listView),2,2);frame(root)
            assertEquals(listOf(1),host.requestedPlayers)
            assertTrue(all(root).filterIsInstance<TextView>().any { it.text.toString()=="Плеер источника · 2 из 2 · выбран" })
            all(root).first { it.tag=="online:2:5:Voice" }.performClick();frame(root)
            val intent=shadowOf(host).nextStartedActivity;val played=com.lampplayer.tv.player.IntentParser.parse(intent)!!
            assertEquals("https://cdn.test/good.m3u8",played.first);assertEquals(item.key,played.second.contentKey)
            assertEquals(2,played.second.seasonNumber);assertEquals(5,played.second.episodeNumber);assertEquals("Voice",played.second.translator)
            assertEquals(2,intent.getIntExtra("center_player_count",1));assertEquals(1,intent.getIntExtra("center_player_index",0));assertTrue(intent.getBooleanExtra("center_player_pinned",false))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun refreshingA404KeepsThePinnedPlayerInsteadOfReturningToAutomaticSelection() {
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible()
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            val item=CenterTitle(17,"tv","Show",provider="zona",sourceUrl="https://zona.movie/series/show-17/")
            val episode=OnlineEpisode(2,5,"Пятая","Voice",mapOf(720 to "https://cdn.test/expired.m3u8"))
            val video=OnlineVideo(item,listOf(episode),emptyMap(),playerIndex=1,playerCount=2,pinnedPlayer=true)
            host.nextOnline=video.copy(episodes=listOf(episode.copy(sources=mapOf(720 to "https://cdn.test/fresh.m3u8"))))
            host.verifyFailures.add(OnlineMediaUnavailable(404,"fixture","HTTP 404"))
            host.nativeFixture(video);frame(root);label(root,"Сезон 2").performClick();frame(root)
            all(root).first { it.tag=="online:2:5:Voice" }.performClick();frame(root)
            assertEquals(listOf(1),host.requestedPlayers);assertEquals(1,host.onlineCalls)
            val played=com.lampplayer.tv.player.IntentParser.parse(shadowOf(host).nextStartedActivity)!!
            assertEquals("https://cdn.test/fresh.m3u8",played.first);assertEquals(2,played.second.seasonNumber);assertEquals(5,played.second.episodeNumber)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun sourcePlayerRecoveryFromLampPlayerOpensTheChooserWithoutReplayingTheBrokenVideo() {
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible()
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            val item=CenterTitle(17,"tv","Show",provider="kinokong",sourceUrl="https://kinokong.es/17-show.html")
            host.nextOnline=OnlineVideo(item,listOf(OnlineEpisode(2,5,"Пятая","Voice",mapOf(720 to "https://cdn.test/five.m3u8"))),emptyMap(),playerCount=2)
            val card=com.lampplayer.tv.domain.model.CardMeta(item.title,contentKey=item.key,sourcePage=item.sourceUrl,seasonNumber=2,episodeNumber=5,translator="Voice",quality="720")
            ReflectionHelpers.setField(host,"recovery","source_player" to card)
            controller.pause().stop().start().resume().visible();frame(root)
            assertNull(shadowOf(host).nextStartedActivity)
            val dialog=org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            assertEquals("Автоматически ✓",dialog.listView.adapter.getItem(0).toString())
            assertEquals(3,dialog.listView.adapter.count)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun returningToAutomaticPlayerSelectionUsesTheNormalResolver() {
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible()
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            val item=CenterTitle(17,"movie","Movie",provider="kinotac",sourceUrl="https://kinotac.org/17-movie.html")
            val video=OnlineVideo(item,listOf(OnlineEpisode(0,0,"Movie","Voice",mapOf(720 to "https://cdn.test/movie.mp4"))),emptyMap(),playerIndex=1,playerCount=2,pinnedPlayer=true)
            host.nextOnline=video.copy(playerIndex=0,pinnedPlayer=false)
            host.nativeFixture(video);frame(root)
            label(root,"Плеер источника · 2 из 2 · выбран").performClick();frame(root)
            val dialog=org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            dialog.listView.performItemClick(dialog.listView.adapter.getView(0,null,dialog.listView),0,0);frame(root)
            assertEquals(1,host.onlineCalls);assertTrue(host.requestedPlayers.isEmpty())
            assertTrue(all(root).filterIsInstance<TextView>().any { it.text.toString()=="Плеер источника · 1 из 2 · авто" })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun refreshRecoveryKeepsTheSelectedPlayerEvenWhenTheOnlineCacheWasLost() {
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible()
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            val item=CenterTitle(17,"tv","Show",provider="kinotac",sourceUrl="https://kinotac.org/17-show.html")
            host.nextOnline=OnlineVideo(item,listOf(OnlineEpisode(2,5,"Пятая","Voice",mapOf(720 to "https://cdn.test/five.m3u8"))),emptyMap(),playerIndex=1,playerCount=2,pinnedPlayer=true)
            val card=com.lampplayer.tv.domain.model.CardMeta(item.title,contentKey=item.key,sourcePage=item.sourceUrl,seasonNumber=2,episodeNumber=5,translator="Voice",quality="720")
            ReflectionHelpers.setField(host,"recovery","refresh" to card);ReflectionHelpers.setField(host,"recoveryPlayer",1)
            controller.pause().stop().start().resume().visible();frame(root)
            assertEquals(listOf(1),host.requestedPlayers)
            val played=com.lampplayer.tv.player.IntentParser.parse(shadowOf(host).nextStartedActivity)!!
            assertEquals(2,played.second.seasonNumber);assertEquals(5,played.second.episodeNumber)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun aRemovedPinnedPlayerCanReturnToAutomaticSelectionAfterCacheLoss() {
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible()
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            val item=CenterTitle(17,"tv","Show",provider="kinotac",sourceUrl="https://kinotac.org/17-show.html")
            host.nextOnline=OnlineVideo(item,listOf(OnlineEpisode(2,5,"Пятая","Voice",mapOf(720 to "https://cdn.test/five.m3u8"))),emptyMap())
            host.resolveFailures.add(IllegalStateException("Выбранный плеер больше недоступен"))
            val card=com.lampplayer.tv.domain.model.CardMeta(item.title,contentKey=item.key,sourcePage=item.sourceUrl,seasonNumber=2,episodeNumber=5,translator="Voice",quality="720")
            ReflectionHelpers.setField(host,"recovery","refresh" to card);ReflectionHelpers.setField(host,"recoveryPlayer",1)
            controller.pause().stop().start().resume().visible();frame(root)
            assertNull(shadowOf(host).nextStartedActivity)
            label(root,"Другой плеер источника").performClick();frame(root)
            assertEquals(2,host.onlineCalls);assertEquals(listOf(1),host.requestedPlayers)
            assertTrue(all(root).any { it.tag=="online:2:5:Voice" })
            assertNull(shadowOf(host).nextStartedActivity)
        } finally { controller.pause().stop().destroy() }
    }

    private fun tmdbSource(provider:String):CenterTitle = CenterTitle(17,"tv","Король Талсы",year="2022",provider=provider,
        sourceUrl=when(provider) {
            "kinovod" -> "https://kinovod.pro/serial/17-show"
            "kinogo" -> "https://kz.kinogo.moi/17-show.html"
            "kinokong" -> "https://kinokong.es/17-show.html"
            "kinotac" -> "https://kinotac.org/17-show.html"
            else -> "https://zona.movie/series/show-17/"
        })

    @Test fun tmdbCardsOfferEveryOnlineProviderAndPreserveTmdbMetadataWhenLaunchingEach() {
        for(provider in listOf("kinogo","kinokong","kinotac","zona")) {
            val controller=Robolectric.buildActivity(Host::class.java).setup().visible()
            try {
                val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
                val item=CenterTitle(153312,"tv","Король Талсы",original="Tulsa King",year="2022",poster="https://img.test/tmdb.jpg")
                val source=tmdbSource(provider)
                host.sourceResults[provider]=listOf(source)
                host.nextOnline=OnlineVideo(source,listOf(OnlineEpisode(2,5,"Пятая","Voice",mapOf(720 to "https://cdn.test/five.m3u8"))),emptyMap())
                host.detailFixture(CenterDetails(item,listOf(1,2),""));frame(root)
                label(root,"Смотреть онлайн").performClick();frame(root)
                assertEquals(5,all(root).count { it.tag?.toString()?.startsWith("online_source:")==true })
                assertFalse(all(root).any { it.tag=="online_source:filmix" })
                all(root).first { it.tag=="online_source:$provider" }.performClick();repeat(4){frame(root)}
                assertEquals(setOf(provider),host.sourceQueries.map { it.first }.toSet())
                assertEquals(source.key,host.resolvedItems.single().key)
                label(root,"Сезон 2").performClick();frame(root)
                all(root).first { it.tag=="online:2:5:Voice" }.performClick();frame(root)
                val played=com.lampplayer.tv.player.IntentParser.parse(shadowOf(host).nextStartedActivity)!!.second
                assertEquals(item.id,played.tmdbId);assertEquals(item.key,played.contentKey);assertEquals(item.poster,played.posterUrl)
                assertEquals(source.sourceUrl,played.sourcePage);assertEquals(2,played.seasonNumber);assertEquals(5,played.episodeNumber)
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun switchingTmdbSourcesKeepsSeasonVoiceAndUsesTheSelectedSitesBinding() {
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible()
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            val item=CenterTitle(153312,"tv","Король Талсы",year="2022")
            val first=tmdbSource("kinovod");val second=tmdbSource("kinotac")
            CenterStore(host).setOnlineSource(item,first)
            host.nextOnline=OnlineVideo(first,listOf(OnlineEpisode(2,5,"Пятая","Voice",mapOf(720 to "https://cdn.test/first.m3u8"))),emptyMap())
            host.detailFixture(CenterDetails(item,listOf(1,2),""));frame(root)
            label(root,"Смотреть онлайн").performClick();frame(root)
            all(root).first { it.tag=="online_source:kinovod" }.performClick();repeat(3){frame(root)}
            label(root,"Сезон 2").performClick();frame(root)
            label(root,"Другой онлайн-источник").performClick();frame(root)
            host.sourceResults["kinotac"]=listOf(second)
            host.nextOnline=OnlineVideo(second,listOf(OnlineEpisode(2,5,"Пятая","Voice",mapOf(720 to "https://cdn.test/second.m3u8"))),emptyMap())
            all(root).first { it.tag=="online_source:kinotac" }.performClick();repeat(4){frame(root)}
            assertTrue(all(root).any { it.tag=="online:2:5:Voice" })
            all(root).first { it.tag=="online:2:5:Voice" }.performClick();frame(root)
            val played=com.lampplayer.tv.player.IntentParser.parse(shadowOf(host).nextStartedActivity)!!
            assertEquals("https://cdn.test/second.m3u8",played.first);assertEquals(item.key,played.second.contentKey)
            assertEquals(first,CenterStore(host).onlineSource(item,"kinovod"));assertEquals(second,CenterStore(host).onlineSource(item,"kinotac"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun tmdbHistoryRestoresItsOriginalProviderEvenAfterAnotherProviderWasSelected() {
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible()
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            val item=CenterTitle(153312,"tv","Король Талсы",year="2022")
            val source=tmdbSource("kinokong")
            CenterStore(host).apply {
                setOnlineSource(item,source);setOnlineSource(item,tmdbSource("kinotac"))
                remember("https://cdn.test/old.m3u8",com.lampplayer.tv.domain.model.CardMeta(item.title,tmdbId=item.id,contentKey=item.key,
                    sourcePage=source.sourceUrl,seasonNumber=2,episodeNumber=5,quality="720",translator="Voice"))
            }
            host.nextOnline=OnlineVideo(source,listOf(OnlineEpisode(2,5,"Пятая","Voice",mapOf(720 to "https://cdn.test/five.m3u8"))),emptyMap())
            host.detailFixture(CenterDetails(item,listOf(1,2),""));frame(root)
            all(root).first { it.tag=="continue_title" }.performClick();repeat(4){frame(root)}
            assertTrue(host.sourceQueries.isEmpty());assertTrue(host.kinoQueries.isEmpty())
            assertEquals(source.key,host.resolvedItems.single().key)
            val played=com.lampplayer.tv.player.IntentParser.parse(shadowOf(host).nextStartedActivity)!!
            assertEquals(item.key,played.second.contentKey);assertEquals(source.sourceUrl,played.second.sourcePage)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun tmdbPinnedPlayerRecoveryKeepsItsProviderAndTmdbIdentity() {
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible()
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            val item=CenterTitle(153312,"tv","Король Талсы",year="2022")
            val source=tmdbSource("kinokong")
            host.nextOnline=OnlineVideo(source,listOf(OnlineEpisode(2,5,"Пятая","Voice",mapOf(720 to "https://cdn.test/five.m3u8"))),emptyMap(),
                playerIndex=1,playerCount=2,pinnedPlayer=true)
            val card=com.lampplayer.tv.domain.model.CardMeta(item.title,tmdbId=item.id,contentKey=item.key,sourcePage=source.sourceUrl,
                seasonNumber=2,episodeNumber=5,translator="Voice",quality="720")
            ReflectionHelpers.setField(host,"recovery","refresh" to card);ReflectionHelpers.setField(host,"recoveryPlayer",1)
            controller.pause().stop().start().resume().visible();repeat(4){frame(root)}
            assertEquals(listOf(1),host.requestedPlayers)
            val played=com.lampplayer.tv.player.IntentParser.parse(shadowOf(host).nextStartedActivity)!!.second
            assertEquals(item.key,played.contentKey);assertEquals(source.sourceUrl,played.sourcePage);assertEquals(2,played.seasonNumber);assertEquals(5,played.episodeNumber)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun editingASearchQueryWithLeftDoesNotOpenTheNavigationDrawer() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible().windowFocusChanged(true)
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content);frame(root)
            all(root).first { it.tag=="menu_handle" }.performClick();frame(root)
            label(root,"Поиск").performClick();frame(root)
            val query=all(root).first { it.tag=="search_query" } as android.widget.EditText
            query.setText("Матрица");query.setSelection(4);query.requestFocus();frame(root)
            key(root,KeyEvent.KEYCODE_DPAD_LEFT);frame(root)
            assertFalse(all(root).first { it.tag=="Главная" }.isShown)
            assertTrue(query.hasFocus());assertEquals(3,query.selectionStart)
        } finally { controller.pause().stop().destroy() }
    }
}
