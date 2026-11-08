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
        var failSections=false
        var sectionGate:kotlinx.coroutines.CompletableDeferred<Unit>?=null
        val sectionCalls=mutableListOf<Pair<CenterSection,Int>>()
        override suspend fun section(section:CenterSection,page:Int):List<CenterTitle> {
            sectionGate?.await()
            if(failSections)error("Offline fixture")
            sectionCalls.add(section to page)
            return CenterTitlePage(listOf(CenterTitle(880,section.type,"Выбранная подборка")),false)
        }
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
        val categoryCalls get()=backend.sectionCalls
        var updateChecks = 0
        var updateFailure:Exception?=null
        var uptime = 10000L
        override fun elapsedTime() = uptime
        override suspend fun availableUpdate(): com.lampplayer.tv.update.UpdateInfo? { updateChecks++;updateFailure?.let {throw it}; return null }
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
        val browseProviders=mutableListOf<String>()
        val browsePages=mutableMapOf<Int,List<CenterTitle>>()
        override suspend fun browsePage(provider:String,request:CenterBrowse,number:Int):List<CenterTitle> {
            browseCalls.add(request to number);browseProviders.add(provider);return browsePages[number].orEmpty()
        }
        fun libraryFixture(request:CenterBrowse) { loadLibrary(request) }
        fun favoritesFixture() { showHome(CenterStore(this).favorites(),"Избранное") }
        var realLibraryNavigation=false
        fun browseFixture(items:List<CenterTitle>,request:CenterBrowse) { showHome(items,"Результаты поиска",1,null,request) }
        fun mineFixture() { mySeriesScreen() }
        fun onlineFixture(video:OnlineVideo,season:Int?=null,voice:String?=null) { showOnline(video,season,voice) }
        var failGrid=false
        override suspend fun nextGridPage(provider:String,category:CenterSection?,type:String,number:Int):List<CenterTitle> {
            gridCalls.add(number)
            if(failGrid) { failGrid=false;error("temporary") }
            return gridPages[number].orEmpty()
        }
        fun dashboardFixture(boards:List<Pair<CenterSection,List<CenterTitle>>>) { showDashboard(boards) }
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
        override fun loadHome() { if(realLibraryNavigation) { super.loadHome();return };val items=(1..8).map {
            CenterTitle(it, if(it%2==0) "tv" else "movie", listOf("Тихий город","За горизонтом","Новый день","На орбите")[(it-1)%4],
                overview="Истории, к которым хочется возвращаться. Каталог, серии и просмотр в одном приложении.", year="2026",rating="8.2")
        }; showDashboard(CenterSection.all.map { section -> section to items.filter { it.type == section.type } }) }
        fun realHomeFixture() { super.loadHome() }
        fun failHomeAfter(gate:kotlinx.coroutines.CompletableDeferred<Unit>) {
            backend.sectionGate=gate;backend.failSections=true
        }
        fun releaseFixture(items: List<CenterRelease>) { releaseList(CenterTitle(801,"movie","Sorting"),null,items) }
        fun detailFixture(details: CenterDetails) { showDetails(details) }
        var retryCalls=0;var refreshCalls=0;var sourcePlayerCalls=0
        fun errorFixture(error:Exception,item:CenterTitle?=null,withPlayer:Boolean=false) {
            showFailure(error,{retryCalls++},item,if(withPlayer)({sourcePlayerCalls++}) else null,{refreshCalls++})
        }
        fun calls()=backend.detailCalls to backend.episodeCalls
        fun nativeFixture(video:OnlineVideo) { showOnline(video) }
        fun episodesFixture(item:CenterTitle,season:Int,rows:List<CenterEpisode>) { showEpisodes(item,season,rows) }
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
            assertTrue((all(root).first { it.tag=="episode_summary" } as TextView).text.contains("Описание эпизода 2."))
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
            all(root).first { it.tag=="detail_more" }.performClick();frame(root)
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
            assertEquals("tmdb",CenterStore(host).catalog)
            assertEquals("kinovod",CenterStore(host).searchCatalog)
            assertEquals("Источник",(all(root).first { it.tag=="search_query" } as TextView).text.toString())
            assertEquals("search_source",root.findFocus()?.tag)
            all(root).first { it.tag=="search_recent:Король Талсы" }.performClick();repeat(3) { frame(root) }
            assertEquals("Король Талсы",host.browseCalls.single().first.query)
            assertEquals(listOf("kinovod"),host.browseProviders)
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
            assertEquals(CenterCatalog.searchProviders.size,dialog.listView.adapter.count);dialog.dismiss()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun onlineTestIsSelectiveAndKinogoPassesTheSelectedEpisodeToOurPlayer() {
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible()
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            val item=CenterTitle(6873,"tv","Гангстерленд",provider="kinogo",sourceUrl="https://kz.kinogo.moi/6873-gangsterlend-2025.html")
            host.nextOnline=OnlineVideo(item,listOf(OnlineEpisode(2,5,"Пятая","Voice",mapOf(720 to "https://cdn.test/five.m3u8"))),mapOf("Referer" to "https://player.test/embed/movie/9"))
            host.detailFixture(CenterDetails(item,emptyList(),""));frame(root)
            label(root,"Смотреть онлайн").performClick();frame(root)
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
            all(root).first { it.tag=="error_more" }.performClick();frame(root)
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

    @Test fun onlineSeriesUseAVirtualizedGridKeepControlsAboveItAndFocusTheLastEpisode() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible().windowFocusChanged(true)
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content);frame(root)
            val item=CenterTitle(17,"tv","Длинный сериал",provider="kinotac",sourceUrl="https://kinotac.org/17-show.html")
            CenterStore(host).remember("old",com.lampplayer.tv.domain.model.CardMeta(item.title,contentKey=item.key,sourcePage=item.sourceUrl,seasonNumber=2,episodeNumber=63,translator="Voice"))
            label(root,"Каталог: TMDB  ▾").requestFocus()
            host.nativeFixture(OnlineVideo(item,(1..100).map { OnlineEpisode(2,it,"Эпизод $it","Voice",
                mapOf(720 to "https://cdn.test/$it.m3u8",1080 to "https://cdn.test/$it-high.m3u8")) },emptyMap(),playerCount=2))
            repeat(6){frame(root)}
            val grid=all(root).filterIsInstance<androidx.recyclerview.widget.RecyclerView>().first { it.tag=="online_episode_grid" }
            val manager=grid.layoutManager as androidx.recyclerview.widget.GridLayoutManager
            assertTrue(manager.spanCount>=2);assertEquals(100,grid.adapter!!.itemCount);assertTrue(grid.childCount<100)
            assertEquals("online:2:63:Voice",root.findFocus()?.tag)
            val control=label(root,"Плеер источника · 1 из 2 · авто")
            assertTrue(control.getGlobalVisibleRect(android.graphics.Rect()))
            val cp=IntArray(2);val gp=IntArray(2);control.getLocationOnScreen(cp);grid.getLocationOnScreen(gp)
            assertTrue(cp[1]<gp[1])
            key(root,KeyEvent.KEYCODE_DPAD_RIGHT);frame(root)
            assertEquals("online:2:64:Voice",root.findFocus()?.tag)
            key(root,KeyEvent.KEYCODE_DPAD_DOWN);frame(root)
            val number=64+manager.spanCount
            assertEquals("online:2:$number:Voice",root.findFocus()?.tag)
            root.findFocus()!!.performLongClick();frame(root)
            val dialog=org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            assertEquals(2,dialog.listView.adapter.count);dialog.dismiss()
            screenshot(root,"media-center-episode-grid")
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun torrentEpisodeGridShowsTheFocusedEpisodesFullDescription() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible().windowFocusChanged(true)
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            val item=CenterTitle(123,"tv","Сериал TMDB")
            CenterStore(host).remember("old",com.lampplayer.tv.domain.model.CardMeta(item.title,contentKey=item.key,tmdbId=item.id,seasonNumber=3,episodeNumber=50))
            host.episodesFixture(item,3,(1..100).map { CenterEpisode(3,it,"Название $it","Полное описание $it",runtime=45) })
            frame(root)
            // The same focus restoration is used after an asynchronous episode fetch.
            val focus=ReflectionHelpers.getField<(() -> Unit)?>(host,"episodeFocus")!!
            focus();repeat(5){frame(root)}
            val grid=all(root).filterIsInstance<androidx.recyclerview.widget.RecyclerView>().first { it.tag=="torrent_episode_grid" }
            assertTrue(grid.layoutManager is androidx.recyclerview.widget.GridLayoutManager)
            assertEquals("episode:3:50",root.findFocus()?.tag)
            assertTrue(label(root,"Название 50 · 45 мин · Полное описание 50").text.contains("Полное описание 50"))
            label(root,"Описание серии").performClick();frame(root)
            val dialog=org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            val body=all(dialog.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(body.contains("Полное описание 50"))
            dialog.dismiss()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun focusedPosterShowsItsFullNameWithoutChangingTheGridOrCardWidth() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible().windowFocusChanged(true)
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            val long="Очень длинное название фильма, которое должно читаться полностью при выборе карточки, вместе с продолжением названия"
            val items=(1..20).map { CenterTitle(it,"movie",if(it==2)long else "Фильм $it") }
            host.gridFixture(items);repeat(4){frame(root)}
            val grid=all(root).filterIsInstance<androidx.recyclerview.widget.RecyclerView>().first { it.tag=="catalog_grid" }
            val manager=grid.layoutManager as androidx.recyclerview.widget.GridLayoutManager
            val first=all(root).first { it.tag=="title:movie:1" }
            first.requestFocus();frame(root)
            val caption=all(root).filterIsInstance<TextView>().first { it.tag=="focused_catalog_title" }
            val span=manager.spanCount;val width=first.width;val height=caption.height
            key(root,KeyEvent.KEYCODE_DPAD_RIGHT);frame(root)
            assertEquals("title:movie:2",root.findFocus()?.tag);assertEquals(long,caption.text.toString())
            assertEquals(span,manager.spanCount);assertEquals(width,root.findFocus()!!.width);assertEquals(height,caption.height)
            assertEquals(caption.text.length,caption.layout.getLineEnd(caption.layout.lineCount-1))
            screenshot(root,"media-center-focused-title")
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun detailPrioritizesContinueAndAdditionalActionsExpandAndCollapseWithBack() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible().windowFocusChanged(true)
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            val item=CenterTitle(17,"movie","Фильм",provider="kinotac",sourceUrl="https://kinotac.org/17-movie.html")
            CenterStore(host).remember("old",com.lampplayer.tv.domain.model.CardMeta(item.title,contentKey=item.key,sourcePage=item.sourceUrl))
            host.detailFixture(CenterDetails(item,emptyList(),""));frame(root)
            val resume=all(root).first { it.tag=="continue_title" } as TextView
            assertTrue(resume.isActivated);assertTrue(resume.textSize>=17)
            assertEquals(listOf("continue_title","Смотреть онлайн","Найти торрент-раздачу"),
                all(root).filterIsInstance<TextView>().filter { it.isShown&&it.isClickable&&it.tag!="menu_handle" }.take(3).map { it.tag })
            assertFalse(all(root).first { it.tag=="favorite" }.isShown)
            all(root).first { it.tag=="detail_more" }.performClick();frame(root)
            assertTrue(all(root).first { it.tag=="favorite" }.isShown)
            all(root).first { it.tag=="favorite" }.performClick();frame(root)
            assertTrue(CenterStore(host).isFavorite(item));assertEquals("favorite",root.findFocus()?.tag)
            host.onBackPressedDispatcher.onBackPressed();frame(root)
            assertFalse(all(root).first { it.tag=="detail_more_panel" }.isShown)
            assertEquals("detail_more",root.findFocus()?.tag)
            assertTrue((all(root).first { it.tag=="continue_title" } as TextView).text.toString().startsWith("Продолжить"))
            screenshot(root,"media-center-simple-card")
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun online404ShowsThreePrimaryActionsAndKeepsWebsiteAndDiagnosticsUnderMore() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible().windowFocusChanged(true)
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            val item=CenterTitle(17,"tv","Сериал",provider="kinotac",sourceUrl="https://kinotac.org/17-show.html")
            host.errorFixture(IllegalStateException("Не удалось открыть видео",OnlineMediaUnavailable(404,"fixture","HTTP 404")),item,true);frame(root)
            val main=all(root).first { it.tag=="error_primary_actions" } as ViewGroup
            assertEquals(listOf("Обновить ссылки","Другой плеер источника","Назад"),
                (0 until main.childCount).map { (main.getChildAt(it) as TextView).text.toString() })
            assertFalse(label(root,"Плеер сайта").isShown)
            label(root,"Обновить ссылки").performClick();assertEquals(1,host.refreshCalls)
            label(root,"Другой плеер источника").performClick();assertEquals(1,host.sourcePlayerCalls)
            all(root).first { it.tag=="error_more" }.performClick();frame(root)
            assertTrue(label(root,"Плеер сайта").isShown);assertTrue(label(root,"Повторить").isShown)
            host.onBackPressedDispatcher.onBackPressed();frame(root)
            assertFalse(label(root,"Плеер сайта").isShown);assertEquals("error_more",root.findFocus()?.tag)
            screenshot(root,"media-center-simple-error")
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun blockedSourceAndCatalogNetworkErrorOfferDifferentRecoveryActions() {
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible()
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            fun main()=all(root).first { it.tag=="error_primary_actions" } as ViewGroup
            fun labels()=(0 until main().childCount).map { (main().getChildAt(it) as TextView).text.toString() }
            host.errorFixture(OnlineSourceBlocked("country","Недоступно в регионе"),CenterTitle(17,"movie","TMDB"));frame(root)
            assertEquals(listOf("Другой онлайн-источник","Плеер сайта","Назад"),labels())
            host.errorFixture(java.io.IOException("Сеть временно недоступна"));frame(root)
            assertEquals(listOf("Повторить","Выбрать каталог","Назад"),labels())
            label(root,"Повторить").performClick();assertEquals(1,host.retryCalls)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun tmdbSearchPaginationUsesItsOwnProviderWhileTheHomeCatalogRemainsKinotac() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible().windowFocusChanged(true)
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            val store=CenterStore(host);store.catalog="kinotac";store.searchCatalog="tmdb"
            host.browsePages[1]=(1..30).map { CenterTitle(it,"movie","Результат $it") }
            host.browsePages[2]=(31..60).map { CenterTitle(it,"movie","Результат $it") }
            all(root).first { it.tag=="Поиск" }.performClick();frame(root)
            (all(root).first { it.tag=="search_query" } as android.widget.EditText).setText("Матрица")
            all(root).first { it.tag=="search_submit" }.performClick();repeat(4){frame(root)}
            val grid=all(root).filterIsInstance<androidx.recyclerview.widget.RecyclerView>().first { it.tag=="catalog_grid" }
            grid.scrollToPosition(29);repeat(4){frame(root)}
            all(root).first { it.tag=="title:movie:30" }.requestFocus();repeat(4){frame(root)}
            assertTrue(host.browseCalls.any { it.second==2 })
            assertTrue(host.browseProviders.all { it=="tmdb" })
            assertTrue(host.browseCalls.all { it.first.query=="Матрица" })
            assertEquals("kinotac",store.catalog);assertEquals("tmdb",CenterStore(host).searchCatalog)
            val result=all(root).first { it.tag=="title:movie:30" };result.requestFocus();result.performClick();frame(root)
            host.onBackPressedDispatcher.onBackPressed();repeat(3){frame(root)}
            assertEquals("title:movie:30",root.findFocus()?.tag)
            assertTrue(host.browseProviders.all { it=="tmdb" })
            assertTrue(all(root).filterIsInstance<TextView>().any { it.text.toString()=="Поиск: Матрица · TMDB" })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun recentGridSkipsFilteredEmptyPagesAndKeepsTheOriginalRequest() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible().windowFocusChanged(true)
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            val request=CenterBrowse(type="tv",mode="new",country="KZ",translatedOnly=true)
            host.browsePages[2]=CenterTitlePage(emptyList(),true)
            host.browsePages[3]=CenterTitlePage(listOf(CenterTitle(17,"tv","Переведённый сериал")),false)
            host.browseFixture(CenterTitlePage(emptyList(),true),request);repeat(5) { frame(root) }
            assertEquals(listOf(request to 2,request to 3),host.browseCalls)
            val grid=all(root).filterIsInstance<androidx.recyclerview.widget.RecyclerView>().first { it.tag=="catalog_grid" }
            assertEquals(1,grid.adapter!!.itemCount)
            assertTrue(all(root).filterIsInstance<TextView>().any { it.text=="Все карточки загружены" })
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun severalUntranslatedPagesOfferContinuationInsteadOfReportingEnd() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible().windowFocusChanged(true)
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            val request=CenterBrowse(mode="new")
            (2..5).forEach { host.browsePages[it]=CenterTitlePage(emptyList(),true) }
            host.browsePages[6]=CenterTitlePage(listOf(CenterTitle(19,"movie","Новый фильм")),false)
            host.browseFixture(CenterTitlePage(emptyList(),true),request);repeat(5) { frame(root) }
            val status=all(root).filterIsInstance<TextView>().first { it.tag=="grid_status" }
            assertEquals(listOf(2,3,4,5),host.browseCalls.map { it.second })
            assertTrue(status.text.toString().contains("ОК — продолжить"))
            status.performClick();repeat(5) { frame(root) }
            assertEquals(listOf(2,3,4,5,6),host.browseCalls.map { it.second })
            val grid=all(root).filterIsInstance<androidx.recyclerview.widget.RecyclerView>().first { it.tag=="catalog_grid" }
            assertEquals(1,grid.adapter!!.itemCount)
        } finally { controller.pause().stop().destroy() }
    }


    @Test fun savedLibraryFiltersAreReusedOnNavigationAndEachChipClearsOnlyItsField() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible().windowFocusChanged(true)
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            val request=CenterBrowse(type="movie",mode="new",country="KZ",year=2026,genre=18,genreName="Драма",translatedOnly=false)
            val store=CenterStore(host);store.catalog="tmdb";store.rememberLibraryBrowse(request)
            val series=CenterBrowse(type="tv",country="RU");store.rememberLibraryBrowse(series)
            host.browsePages[1]=CenterTitlePage(listOf(CenterTitle(42,"movie","Тихий город")),false)
            host.realLibraryNavigation=true
            all(root).first { it.tag=="Фильмы" }.performClick();repeat(4) { frame(root) }
            assertEquals(request to 1,host.browseCalls.last())
            assertTrue(all(root).any { it.tag=="library_filter_country" })
            screenshot(root,"media-center-active-library-filters")
            all(root).first { it.tag=="library_filter_country" }.performClick();repeat(4) { frame(root) }
            assertEquals(request.copy(country="") to 1,host.browseCalls.last())
            assertEquals(request.copy(country=""),CenterStore(host).libraryBrowse("movie"))
            assertFalse(all(root).any { it.tag=="library_filter_country" })
            assertTrue(all(root).any { it.tag=="library_filter_year" })
            all(root).first { it.tag=="library_filters_reset" }.performClick();repeat(4) { frame(root) }
            assertEquals(CenterBrowse() to 1,host.browseCalls.last())
            assertFalse(all(root).any { it.tag=="library_active_filters" })
            assertEquals(series,CenterStore(host).libraryBrowse("tv"))
            all(root).first { it.tag=="Сериалы" }.performClick();repeat(4) { frame(root) }
            assertEquals(series to 1,host.browseCalls.last())
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun returningToFinishedCatalogDoesNotRequestAnotherPage() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible().windowFocusChanged(true)
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            host.browseFixture(CenterTitlePage(listOf(CenterTitle(43,"movie","Конец каталога")),false),CenterBrowse(mode="new"))
            repeat(4) { frame(root) }
            val poster=all(root).first { it.tag=="title:movie:43" };poster.requestFocus();poster.performClick();frame(root)
            host.onBackPressedDispatcher.onBackPressed();repeat(4) { frame(root) }
            assertTrue(host.browseCalls.isEmpty())
            assertEquals("title:movie:43",root.findFocus()?.tag)
            assertTrue(all(root).filterIsInstance<TextView>().any { it.text=="Все карточки загружены" })
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun posterMenuRequiresExplicitActionAndOffersSeriesFollowingAndPinning() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible().windowFocusChanged(true)
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            val item=CenterTitle(44,"tv","Мой сериал");val store=CenterStore(host)
            host.browseFixture(CenterTitlePage(listOf(item),false),CenterBrowse(type="tv"));repeat(3) { frame(root) }
            fun poster()=all(root).first { it.tag=="title:tv:44" }
            fun dialog()=org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            fun choose(text:String) {
                val d=dialog();val list=d.listView
                val index=(0 until list.adapter.count).first { list.adapter.getItem(it).toString()==text }
                list.performItemClick(list.adapter.getView(index,null,list),index,index.toLong());repeat(3) { frame(root) }
            }
            poster().requestFocus();poster().performLongClick();frame(root)
            assertFalse(store.isFavorite(item));assertFalse(store.isPinned(item));assertFalse(store.isFollowed(item))
            screenshot(dialog().window!!.decorView,"media-center-poster-actions")
            dialog().getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick();frame(root)
            assertFalse(store.isFavorite(item))
            poster().performLongClick();frame(root);choose("В избранное")
            assertTrue(store.isFavorite(item));assertEquals("title:tv:44",root.findFocus()?.tag)
            poster().performLongClick();frame(root);choose("Закрепить на главной")
            assertTrue(store.isPinned(item))
            poster().performLongClick();frame(root);choose("Отслеживать сериал")
            assertTrue(store.isFollowed(item))
            assertEquals(0,host.onlineCalls);assertNull(shadowOf(host).nextStartedActivity)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun removingFavoriteFromPosterMenuRefreshesTheFavoritesGrid() {
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible()
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            val item=CenterTitle(45,"movie","Избранный фильм");val store=CenterStore(host);store.toggle(item)
            host.favoritesFixture();repeat(3) { frame(root) }
            all(root).first { it.tag=="title:movie:45" }.performLongClick();frame(root)
            val dialog=org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            val list=dialog.listView
            val index=(0 until list.adapter.count).first { list.adapter.getItem(it).toString()=="Убрать из избранного" }
            list.performItemClick(list.adapter.getView(index,null,list),index,index.toLong());repeat(3) { frame(root) }
            assertFalse(store.isFavorite(item))
            assertFalse(all(root).any { it.tag=="title:movie:45" })
            assertTrue(all(root).filterIsInstance<TextView>().any { it.text.toString().startsWith("Здесь появятся фильмы") })
        } finally { controller.pause().stop().destroy() }
    }


    @Test fun groupedCategoriesSelectTheExactGenreAndExplainTheRecipe() {
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible()
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            CenterStore(host).catalog="tmdb"
            host.dashboardFixture(CenterSection.tmdbHome.map { it to listOf(CenterTitle(8,it.type,"Выбор")) });frame(root)
            fun dialog()=org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            fun choose(text:String) {
                val list=dialog().listView
                val index=(0 until list.adapter.count).first { list.adapter.getItem(it).toString()==text }
                list.performItemClick(list.adapter.getView(index,null,list),index,index.toLong());repeat(3) { frame(root) }
            }
            label(root,"Разделы  ▾").performClick();frame(root)
            assertEquals(listOf("Фильмы","Сериалы","Жанры фильмов","Жанры сериалов","Страны"),
                (0 until dialog().listView.adapter.count).map { dialog().listView.adapter.getItem(it).toString() })
            choose("Жанры сериалов")
            choose("Фантастика")
            assertEquals("genre_tv_10765",host.categoryCalls.last().first.id)
            assertEquals(10765,host.categoryCalls.last().first.genre)
            assertTrue(all(root).filterIsInstance<TextView>().any { it.tag=="category_description"&&it.text=="По популярности · все годы" })
            host.onBackPressedDispatcher.onBackPressed();repeat(3) { frame(root) }
            label(root,"Разделы  ▾").performClick();frame(root);choose("Сериалы")
            assertTrue((0 until dialog().listView.adapter.count).map { dialog().listView.adapter.getItem(it).toString() }.containsAll(listOf("Новые сериалы","Сериалы недели","Сериалы в эфире")))
            choose("Новые сериалы")
            assertEquals("new_series",host.categoryCalls.last().first.id)
            assertTrue(all(root).filterIsInstance<TextView>().any { it.tag=="category_description"&&it.text.contains("180 дней") })
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun homePrioritizesContinueThenPinsAndShowsRecipesWithoutEmptyShelves() {
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible()
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            val store=CenterStore(host);store.catalog="tmdb"
            val item=CenterTitle(71,"movie","Мой фильм",year="2026")
            store.pin(item)
            store.remember("https://cdn.test/history.mp4",com.lampplayer.tv.domain.model.CardMeta("Продолжить мой фильм",tmdbId=72))
            val boards=CenterSection.tmdbHome.map { section ->
                section to if(section.id=="trending_series")emptyList() else listOf(item.copy(type=section.type))
            }
            host.dashboardFixture(boards);repeat(4) { frame(root) }
            val texts=all(root).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(texts.indexOf("Продолжить просмотр")<texts.indexOf("Закреплённые фильмы"))
            assertTrue(texts.indexOf("Закреплённые фильмы")<texts.indexOf("Свежие фильмы"))
            assertFalse(texts.contains("Сериалы недели"))
            assertTrue(texts.contains("Премьеры за 180 дней · заметные фильмы выше"))
            screenshot(root,"media-center-category-home")
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun localHomeSurvivesPendingAndFailedNetworkShelves() {
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible()
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            val store=CenterStore(host);store.catalog="tmdb"
            store.pin(CenterTitle(88001,"movie","Локальное закрепление"))
            store.remember("https://local.test/continue",com.lampplayer.tv.domain.model.CardMeta("Локальное продолжение",tmdbId=88002))
            val gate=kotlinx.coroutines.CompletableDeferred<Unit>()
            host.failHomeAfter(gate)
            host.realHomeFixture();repeat(4){frame(root)}
            assertTrue(all(root).filterIsInstance<TextView>().any { it.text.toString()=="Продолжить просмотр" })
            assertTrue(all(root).filterIsInstance<TextView>().any { it.text.toString()=="Закреплённые фильмы" })
            gate.complete(Unit);repeat(8){frame(root)}
            val texts=all(root).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(texts.contains("Продолжить просмотр"));assertTrue(texts.contains("Закреплённые фильмы"))
            assertFalse(texts.contains("Не удалось загрузить"))
            assertTrue(texts.any { it.startsWith("Не загрузились:") })
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun menuCancellationRestoresTheFocusedPoster() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible().windowFocusChanged(true)
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            repeat(4){frame(root)}
            val poster=all(root).first { it.isFocusable && (it.tag as? String)?.startsWith("new_movies:")==true }
            assertTrue(poster.requestFocus());frame(root)
            assertEquals(poster.tag,host.window.decorView.findFocus()?.tag)
            all(root).first { it.tag=="menu_handle" }.performClick();frame(root)
            host.onBackPressedDispatcher.onBackPressed();frame(root)
            assertEquals(poster.tag,host.window.decorView.findFocus()?.tag)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun switchingSitesCarriesTheExactEpisodeAndTimestampIntoTheNewPlayerIntent() {
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible()
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            val item=CenterTitle(153312,"tv","Король Талсы",year="2022")
            val first=tmdbSource("kinovod");val second=tmdbSource("kinotac")
            val card=com.lampplayer.tv.domain.model.CardMeta(item.title,tmdbId=item.id,contentKey=item.key,
                seasonNumber=2,episodeNumber=5,translator="Voice",quality="720",startPositionMs=425000)
            CenterStore(host).setOnlineSource(item,first)
            kotlinx.coroutines.runBlocking { com.lampplayer.tv.data.datastore.PositionDataStore(host).savePosition("https://video.test/old",card,
                com.lampplayer.tv.domain.model.PlaybackPosition(time=425.0)) }
            host.onlineFixture(OnlineVideo(first,listOf(OnlineEpisode(2,5,"Пятая","Voice",mapOf(720 to "https://video.test/old"))),emptyMap(),catalogItem=item,resumeCard=card),2,"Voice")
            repeat(3){frame(root)}
            label(root,"Другой онлайн-источник").performClick();repeat(6){frame(root)}
            assertFalse(all(root).any { it.tag=="online_source:kinovod" })
            host.sourceResults["kinotac"]=listOf(second)
            host.nextOnline=OnlineVideo(second,listOf(OnlineEpisode(2,5,"Пятая","Voice",mapOf(720 to "https://video.test/new"))),emptyMap())
            all(root).first { it.tag=="online_source:kinotac" }.performClick();repeat(8){frame(root)}
            val played=com.lampplayer.tv.player.IntentParser.parse(shadowOf(host).nextStartedActivity)!!
            assertEquals("https://video.test/new",played.first)
            assertEquals(item.key,played.second.contentKey);assertEquals(2,played.second.seasonNumber)
            assertEquals(5,played.second.episodeNumber);assertEquals(425000L,played.second.startPositionMs)
            assertEquals("Voice",played.second.translator)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun selectingAnotherEpisodeDoesNotReuseTheTransferredTimestamp() {
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible()
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            val item=CenterTitle(123,"tv","Show",provider="kinovod",sourceUrl="https://kinovod.pro/serial/123-show")
            val card=com.lampplayer.tv.domain.model.CardMeta(item.title,seasonNumber=2,episodeNumber=5,startPositionMs=425000)
            host.onlineFixture(OnlineVideo(item,listOf(OnlineEpisode(2,6,"Шестая","Voice",mapOf(720 to "https://video.test/six"))),emptyMap(),resumeCard=card),2,"Voice")
            repeat(3){frame(root)}
            all(root).first { it.tag=="online:2:6:Voice" }.performClick();frame(root)
            val played=com.lampplayer.tv.player.IntentParser.parse(shadowOf(host).nextStartedActivity)!!.second
            assertEquals(6,played.episodeNumber);assertNull(played.startPositionMs)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun hiddenAndReorderedHomeShelvesRenderInTheSavedOrder() {
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible()
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content);val store=CenterStore(host)
            store.catalog="tmdb";store.pin(CenterTitle(18,"movie","Pin"))
            store.remember("https://video.test/old",com.lampplayer.tv.domain.model.CardMeta("History",tmdbId=19))
            val order=store.homeOrder("tmdb");store.setHomeOrder("tmdb",listOf("new_movies")+order.filterNot { it=="new_movies" })
            store.toggleHomeShelf("tmdb","new_series")
            host.dashboardFixture(CenterSection.tmdbHome.map { it to listOf(CenterTitle(18,it.type,"Card")) });repeat(4){frame(root)}
            val texts=all(root).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(texts.indexOf("Свежие фильмы")<texts.indexOf("Продолжить просмотр"))
            assertFalse(texts.contains("Новые сериалы"));assertTrue(texts.contains("Закреплённые фильмы"))
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun manualUpdateNetworkFailureShowsRetryInsteadOfTheLatestVersionMessage() {
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible()
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            host.updateFailure=java.io.IOException("Не удалось проверить обновление")
            all(root).first { it.tag=="Настройки" }.performClick();frame(root)
            label(root,"Проверить обновление").performClick();repeat(3){frame(root)}
            assertTrue(all(root).filterIsInstance<TextView>().any { it.text.toString()=="Не удалось загрузить" })
            assertTrue(label(root,"Повторить").isShown)
        } finally { controller.pause().stop().destroy() }
    }

}
