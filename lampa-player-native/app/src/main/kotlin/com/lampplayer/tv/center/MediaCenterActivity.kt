package com.lampplayer.tv.center

import androidx.core.view.doOnLayout
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Parcelable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnNextLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.lampplayer.tv.BuildConfig
import com.lampplayer.tv.data.datastore.PositionDataStore
import com.lampplayer.tv.domain.model.CardMeta
import com.lampplayer.tv.player.PlayerActivity
import com.lampplayer.tv.player.TorrentEpisodeResolver
import com.lampplayer.tv.settings.SettingsActivity
import com.lampplayer.tv.update.UpdateManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.ArrayDeque

/** Native TV catalog; playback and episode navigation use the shared player. */
open class MediaCenterActivity : AppCompatActivity() {
    protected open val backend = CenterBackend()
    private var exitPressedAt: Long? = null
    private var automaticUpdate: com.lampplayer.tv.update.UpdateInfo? = null
    private var updatePrompt: AlertDialog? = null
    private var updateBusy = false
    protected open fun elapsedTime(): Long = android.os.SystemClock.elapsedRealtime()
    private var onlineReport: String? = null
    private class OnlineFailure(message:String, val report:String, cause:Exception):IllegalStateException(message,cause)
    private fun onlineFailure(error:Exception):OnlineFailure {
        val message=(error.message ?: "Не удалось открыть видео").replace(Regex("https?://\\S+"),"<ссылка>").take(300)
        return OnlineFailure(message,(onlineReport ?: "LampMedia · ${BuildConfig.VERSION_NAME}")+"\n"+message,error)
    }
    private var forceQualityOnce = false
    private var recovery: Pair<String, CardMeta>? = null
    private var recoveryPlayer:Int?=null
    private val playerLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_FIRST_USER) {
            val action = result.data?.getStringExtra("recovery")
            val card = runCatching { Gson().fromJson(result.data?.getStringExtra("card"), CardMeta::class.java) }.getOrNull()
            if (action in listOf("refresh", "quality", "torrent", "source_player") && card != null) {
                recovery = action!! to card
                recoveryPlayer=result.data?.takeIf { it.getBooleanExtra("center_player_pinned",false) }
                    ?.getIntExtra("center_player_index",-1)?.takeIf { it>=0 }
            }
        }
    }
    private lateinit var store: CenterStore
    private val catalogs = CenterCatalog()
    private val onlineCache = linkedMapOf<String, Pair<Long, OnlineVideo>>()
    private lateinit var content: LinearLayout
    private lateinit var navigation: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var menuHandle: TextView
    private var menuOpen = false
    private var job: Job? = null
    private var gridJob: Job? = null
    private var gridGeneration = 0
    private var episodeFocus: (() -> Unit)? = null
    private var detailMoreKey:String?=null
    private var compactToolbar: LinearLayout? = null
    private var releaseFiltersExpanded = false
    private var page = "home"
    private var nav = "Главная"
    private var selected: CenterTitle? = null
    private data class Screen(val draw: () -> Unit, val focus: String?, val y: Int, val rails: List<Parcelable?>)
    private val backStack = ArrayDeque<Screen>()
    private var currentScreen: (() -> Unit)? = null
    private var playerReturn: Screen? = null
    private val seasonData = mutableMapOf<Pair<Int, Int>, List<CenterEpisode>>()
    private val ink = Color.rgb(242, 246, 252)
    private val muted = Color.rgb(153, 172, 197)
    private val accent = Color.rgb(136, 196, 255)
    private val panel = Color.rgb(20, 30, 45)
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun bg(color: Int, stroke: Int = Color.TRANSPARENT, radius: Int = 14) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); setStroke(dp(2), stroke)
    }
    private fun text(value: String, size: Int = 16, color: Int = ink) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); includeFontPadding = false
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun facts(item: CenterTitle) = listOf(if (item.type == "tv") "Сериал" else "Фильм", item.year,
        item.rating.takeIf { it.isNotBlank() }?.let { "★ $it" }).filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun paintButton(view: View, focus: Boolean) {
        view.background = bg(when { focus -> Color.rgb(38, 65, 96); view.isActivated -> Color.rgb(25, 45, 66); else -> panel },
            if (focus) accent else Color.TRANSPARENT)
        (view as? TextView)?.setTextColor(if (focus || view.isActivated) ink else muted)
    }
    private fun button(label: String, action: () -> Unit) = text(label, 15).apply {
        id = View.generateViewId(); tag = label; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(15), dp(10), dp(15), dp(10)); isFocusable = true; isClickable = true
        background = bg(panel)
        layoutParams = LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(8) }
        setOnClickListener { action() }
        setOnFocusChangeListener { v, focus -> paintButton(v, focus) }
    }
    private fun iconButton(symbol: String, label: String, action: () -> Unit): TextView =
        button(label, action).apply {
            text = "$symbol  $label"; textSize=12f; contentDescription=label; tag=label
            gravity=Gravity.CENTER; maxLines=1; ellipsize=TextUtils.TruncateAt.END
            setPadding(dp(10),0,dp(10),0)
            layoutParams=LinearLayout.LayoutParams(-2,dp(34)).apply { rightMargin=dp(6) }
            setOnFocusChangeListener { view,focused -> paintButton(view,focused) }
        }
    private fun space(parent: LinearLayout, n: Int) { parent.addView(View(this), LinearLayout.LayoutParams(1, dp(n))) }
    private fun findTag(view: View, key: String): View? {
        if (view.tag == key) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findTag(view.getChildAt(i), key)?.let { return it }
        return null
    }
    private fun rails(view: View): List<RecyclerView> = if (view is RecyclerView) listOf(view)
        else if (view is ViewGroup) (0 until view.childCount).flatMap { rails(view.getChildAt(it)) } else emptyList()
    private fun snapshot() = currentScreen?.let { Screen(it, content.findFocus()?.tag as? String, scroll.scrollY,
        rails(content).map { rail -> rail.layoutManager?.onSaveInstanceState() }) }
    private fun enter(action: () -> Unit) { exitPressedAt = null; snapshot()?.let { backStack.addLast(it) }; action() }
    private fun restore(screen: Screen) {
        screen.draw()
        rails(content).zip(screen.rails).forEach { (rail, state) -> rail.layoutManager?.onRestoreInstanceState(state) }
        content.doOnNextLayout {
            (screen.focus?.let { findTag(content, it) } ?: content.getFocusables(View.FOCUS_FORWARD).firstOrNull())?.requestFocus()
            scroll.scrollTo(0, screen.y)
        }
    }
    private fun back() {
        if (navigation.visibility == View.VISIBLE) { closeMenu(); return }
        if(page=="detail"&&detailMoreKey==selected?.key) {
            detailMoreKey=null;findTag(content,"detail_more_panel")?.visibility=View.GONE
            (findTag(content,"detail_more") as? TextView)?.apply { text="Ещё";isActivated=false;paintButton(this,hasFocus());requestFocus() }
            return
        }
        if(page=="error"&&findTag(content,"error_more_panel")?.visibility==View.VISIBLE) {
            findTag(content,"error_more_panel")?.visibility=View.GONE
            (findTag(content,"error_more") as? TextView)?.apply { text="Ещё";requestFocus() };return
        }
        job?.cancel()
        if (backStack.isNotEmpty()) restore(backStack.removeLast())
        else if (nav != "Главная") navigate("Главная")
        else {
            val now = elapsedTime()
            if (exitPressedAt?.let { now - it in 0L..2000L } == true) finish()
            else {
                exitPressedAt = now
                Toast.makeText(this, "Нажми «Назад» ещё раз для выхода", Toast.LENGTH_SHORT).show()
            }
        }
    }
    private fun highlightNavigation() {
        for (i in 0 until navigation.childCount) navigation.getChildAt(i).let {
            if (it is TextView && it.isFocusable) { it.isActivated = it.tag == nav; paintButton(it, it.hasFocus()) }
        }
    }
    private fun navigate(label: String) {
        exitPressedAt = null
        if (label == "Источники") { sources(); return }
        job?.cancel(); backStack.clear(); currentScreen = null; selected = null;detailMoreKey=null; nav = label
        highlightNavigation()
        when (label) {
            "Поиск" -> searchScreen()
            "Настройки" -> settings()
            "История" -> historyScreen()
            "Мои сериалы" -> mySeriesScreen()
            "Избранное" -> showHome(store.favorites(), "Избранное")
            else -> loadHome()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.lampplayer.tv.data.BrowserMediaSession.install { url -> android.webkit.CookieManager.getInstance().getCookie(url) }
        store = CenterStore(this)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(8, 13, 22)); setPadding(dp(22), dp(22), dp(18), dp(14)) }
        navigation = column().apply { setPadding(0, 0, dp(22), 0) }
        navigation.addView(text("LampMedia", 24, accent).apply { typeface = Typeface.DEFAULT_BOLD })
        space(navigation, 24)
        listOf("Главная", "Фильмы", "Сериалы", "Поиск", "Мои сериалы", "Избранное", "История", "Источники", "Настройки").forEach { label ->
            navigation.addView(button(label) { navigate(label); closeMenu() }.apply {
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(-1, dp(38)).apply { bottomMargin = dp(4) }
                setOnKeyListener { _, key, event ->
                    if (key == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN) {
                        content.getFocusables(View.FOCUS_FORWARD).firstOrNull { it.isShown && it.isEnabled }?.requestFocus() == true
                    } else false
                }
            })
        }

        scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false; isSmoothScrollingEnabled = true }
        content = column().apply { setPadding(dp(8), 0, dp(8), dp(24)) }
        scroll.addView(content, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1).apply { leftMargin = dp(48) })
        menuHandle = button("☰") { openMenu() }.apply {
            tag = "menu_handle"; contentDescription = "Открыть меню"
            gravity = Gravity.CENTER; setPadding(0, 0, 0, 0)
        }
        root.addView(menuHandle, FrameLayout.LayoutParams(dp(42), dp(48)))
        navigation.setPadding(dp(10), dp(8), dp(10), dp(8))
        navigation.setBackgroundColor(Color.rgb(13, 22, 36))
        navigation.elevation = dp(16).toFloat()
        navigation.visibility = View.GONE
        root.addView(navigation, FrameLayout.LayoutParams(dp(220), -1))
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, focused ->
            if (menuOpen && focused != null && inside(content, focused)) hideMenu()
        }
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = back()
        })
        highlightNavigation(); menuHandle.requestFocus(); loadHome()
        checkAutomaticUpdates()
    }
    private fun inside(parent: View, view: View?): Boolean {
        var current = view
        while (current != null) {
            if (current === parent) return true
            current = current.parent as? View
        }
        return false
    }
    private fun openMenu() {
        exitPressedAt = null
        menuOpen = true; menuHandle.visibility = View.INVISIBLE
        navigation.visibility = View.VISIBLE
        navigation.translationX = -dp(220).toFloat()
        navigation.animate().translationX(0f).setDuration(150).start()
        findTag(navigation, nav)?.requestFocus()
    }
    private fun hideMenu() {
        menuOpen = false
        navigation.animate().cancel()
        navigation.visibility = View.GONE; menuHandle.visibility = View.VISIBLE
    }
    private fun closeMenu() {
        hideMenu()
        content.getFocusables(View.FOCUS_FORWARD).firstOrNull { it.isShown && it.isEnabled }?.requestFocus()
            ?: menuHandle.requestFocus()
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.repeatCount > 0) return true
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode != KeyEvent.KEYCODE_BACK) exitPressedAt = null
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && navigation.visibility != View.VISIBLE) {
            val focused = window.decorView.findFocus()
            val point = IntArray(2); val edge = IntArray(2)
            focused?.getLocationOnScreen(point); content.getLocationOnScreen(edge)
            if (focused === menuHandle || (inside(content, focused) && focused !is EditText &&
                (point[0] <= edge[0] + dp(24) || !inside(content, focused?.focusSearch(View.FOCUS_LEFT))))) {
                openMenu(); return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onResume() {
        super.onResume()
        playerReturn?.let { playerReturn = null; restore(it) }
        offerAutomaticUpdate()
        recovery?.let { (action, card) ->
            recovery = null
            content.post {
                val parts = card.contentKey?.split(':').orEmpty()
                val provider = parts.firstOrNull()?.takeIf { CenterCatalog.isSiteIdentity(it) }
                val item = CenterTitle(parts.getOrNull(2)?.toIntOrNull() ?: card.tmdbId ?: 0,
                    if (card.seasonNumber != null || parts.getOrNull(1) == "tv") "tv" else "movie",
                    card.title, original = card.originalTitle.orEmpty(), year=card.releaseYear?.toString().orEmpty(), provider = provider, sourceUrl = card.sourcePage)
                val canOnline=provider==null || CenterOnline.onlineSupported(provider)
                val actualProvider=provider ?: (CenterOnlineMatch.fromPage(item) ?: store.onlineSource(item))?.provider
                val preferredPlayer=if(CenterOnline.manualPlayers(actualProvider))recoveryPlayer ?: onlineCache[item.key]?.second?.takeIf { it.pinnedPlayer }?.playerIndex else null
                recoveryPlayer=null
                enter {
                    when {
                        action=="source_player" && CenterOnline.manualPlayers(actualProvider) ->
                            online(item,selectedSeason=card.seasonNumber,selectedVoice=card.translator,choosePlayerAfterLoad=true)
                        action=="refresh" && canOnline -> online(item,card,refresh=true,playerIndex=preferredPlayer)
                        action=="quality" && canOnline -> {
                            forceQualityOnce=true;online(item,refresh=true,selectedSeason=card.seasonNumber,selectedVoice=card.translator,playerIndex=preferredPlayer)
                        }
                        else -> find(item, card.episodeNumber?.let { CenterEpisode(card.seasonNumber ?: 1, it, "", "") })
                    }
                }
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open_home", false)) {
            playerReturn = null; recovery = null; recoveryPlayer=null; hideMenu()
            navigate("Главная")
            checkAutomaticUpdates()
        }
    }
    override fun onPause() {
        exitPressedAt = null
        updatePrompt?.dismiss(); updatePrompt = null
        super.onPause()
    }
    protected open suspend fun availableUpdate(): com.lampplayer.tv.update.UpdateInfo? = UpdateManager.check(BuildConfig.VERSION_CODE)
    protected open fun checkAutomaticUpdates() {
        if (!store.autoUpdates || updateBusy || automaticUpdate != null) return
        updateBusy = true
        lifecycleScope.launch {
            try {
                automaticUpdate = availableUpdate()
                offerAutomaticUpdate()
            } finally { updateBusy = false }
        }
    }
    private fun offerAutomaticUpdate() {
        val info = automaticUpdate ?: return
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) ||
            playerReturn != null || updatePrompt != null || isFinishing) return
        updatePrompt = AlertDialog.Builder(this).setTitle("Доступна версия ${info.versionName}")
            .setMessage("Скачать обновление и открыть установку? Настройки и история сохранятся.")
            .setNegativeButton("Позже") { _, _ -> automaticUpdate = null }
            .setPositiveButton("Обновить") { _, _ -> automaticUpdate = null; update(info) }
            .create().also { dialog ->
                dialog.setOnDismissListener { updatePrompt = null }
                dialog.show()
            }
    }
    private fun clear(title: String) {
        gridGeneration++; gridJob?.cancel(); gridJob = null; compactToolbar = null; episodeFocus=null
        content.removeAllViews(); scroll.scrollTo(0, 0)
        content.addView(text(title, 27).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
        space(content, 16)
    }
    private fun section(label: String, count: Int? = null) {
        val heading = row().apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(text(label, 20).apply { typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, -2, 1f))
        count?.let { heading.addView(text(it.toString(), 13, muted)) }
        content.addView(heading); space(content, 8)
    }
    private fun work(message: String, onlineItem: CenterTitle? = null, onlineRecovery: (() -> Unit)? = null, onlineRefresh:(()->Unit)?=null, block: suspend () -> Unit) {
        val focusContent = content.hasFocus()
        job?.cancel(); page = "loading"; clear(message)
        content.addView(ProgressBar(this), LinearLayout.LayoutParams(dp(40), dp(40)))
        space(content, 16); content.addView(button("Назад") { back() })
        job = lifecycleScope.launch {
            try {
                block()
                if (focusContent && playerReturn == null && page != "loading") content.post { episodeFocus?.invoke() ?: content.getFocusables(View.FOCUS_FORWARD).firstOrNull { it.isShown && it.isEnabled }?.requestFocus() }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { showFailure(e,{work(message,onlineItem,onlineRecovery,onlineRefresh,block)},onlineItem,onlineRecovery,onlineRefresh) }
        }
    }
    protected fun showFailure(error:Exception,retry:()->Unit,onlineItem:CenterTitle?=null,onlineRecovery:(()->Unit)?=null,onlineRefresh:(()->Unit)?=null) {
        page="error";clear("Не удалось загрузить")
        content.addView(text(error.message ?: "Проверь соединение и настройки источника",17,muted));space(content,18)
        val again="Повторить" to retry
        val refresh:Pair<String,()->Unit> = "Обновить ссылки" to {
            if(onlineRefresh!=null)onlineRefresh() else onlineItem?.let { online(it,refresh=true) }
        }
        val site:Pair<String,()->Unit> = "Плеер сайта" to { onlineItem?.let { website(it) } }
        val torrent:Pair<String,()->Unit> = "Выбрать раздачу" to { onlineItem?.let { find(it,null) } }
        val catalog:Pair<String,()->Unit> = "Выбрать каталог" to { chooseCatalog() }
        val player=onlineRecovery?.let { "Другой плеер источника" to it }
        val source=onlineItem?.takeIf { it.provider==null }?.let { item -> "Другой онлайн-источник" to { showTmdbOnlineSources(item) } }
        val alternative=player ?: source ?: if(onlineItem!=null)site else catalog
        val causes=generateSequence<Throwable>(error){it.cause}.take(8).toList()
        val blocked=onlineItem!=null&&causes.any { it is OnlineSourceBlocked }
        val missing=causes.any { it is OnlineMediaUnavailable&&it.status==404 }
        val first=when { blocked -> alternative;missing&&onlineItem!=null -> refresh;else -> again }
        val second=if(blocked&&onlineItem!=null)if(first.first==site.first)torrent else site else alternative
        val primary=column().apply { tag="error_primary_actions" }
        listOf(first,second,"Назад" to {back()}).forEach { (label,action) -> primary.addView(button(label,action)) }
        content.addView(primary)
        val extras=mutableListOf<Pair<String,()->Unit>>(again)
        if(onlineItem!=null) {
            extras+=refresh;player?.let { extras+=it };extras+=site;source?.let { extras+=it };extras+=torrent
        }
        extras+=catalog;extras+="Поиск торрентов" to {sources()}
        if(error is OnlineFailure)extras+="Скопировать отчёт" to {
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("LampMedia online",error.report))
            Toast.makeText(this,"Отчёт скопирован",Toast.LENGTH_SHORT).show()
        }
        val panel=column().apply { tag="error_more_panel";visibility=View.GONE }
        val mainLabels=setOf(first.first,second.first,"Назад")
        extras.distinctBy { it.first }.filterNot { it.first in mainLabels }.forEach { (label,action) ->panel.addView(button(label,action)) }
        lateinit var more:TextView
        more=button("Ещё") {
            panel.visibility=if(panel.visibility==View.VISIBLE)View.GONE else View.VISIBLE
            more.text=if(panel.visibility==View.VISIBLE)"Свернуть действия" else "Ещё"
        }.apply { tag="error_more" }
        content.addView(more);content.addView(panel)
        content.post { primary.getChildAt(0)?.requestFocus() }
    }
    protected open fun loadHome() {
        selected = null; catalogPage = 1
        if (nav != "Главная") { loadLibrary(CenterBrowse(type=if(nav=="Сериалы") "tv" else "movie")); return }
        work("Загружаю ${CenterCatalog.name(store.catalog)}…") {
            val provider = store.catalog; val limiter = Semaphore(3)
            val results = coroutineScope { CenterCatalog.sections(provider).map { section -> async {
                section to runCatching {
                    limiter.withPermit { catalogItems(provider, section) }
                }.also { result -> result.exceptionOrNull()?.let { if (it is CancellationException) throw it } }
            } }.awaitAll() }
            val boards = results.mapNotNull { (section, result) -> result.getOrNull()?.let { section to it } }
            if (boards.isEmpty()) throw results.firstNotNullOf { it.second.exceptionOrNull() }
            showDashboard(boards, results.filter { it.second.isFailure }.map { it.first.label })
        }
    }
    private suspend fun catalogItems(provider: String, section: CenterSection, number: Int = 1) =
        if (provider == "tmdb") backend.section(section, number) else catalogs.browse(provider, store.catalogHost(provider), section, number)
    private fun loadCategory(section: CenterSection, number: Int = 1) {
        work("${section.label} · ${CenterCatalog.name(store.catalog)}…") {
            showHome(catalogItems(store.catalog, section, number), section.label, number, section)
        }
    }
    protected open suspend fun browsePage(provider: String, request: CenterBrowse, number: Int): List<CenterTitle> =
        if(provider=="tmdb") backend.library(request,number)
        else if(request.query.isNotBlank() && number==1) catalogs.search(provider,store.catalogHost(provider),request.query)
        else emptyList()
    protected fun mySeriesScreen() {
        showHome(store.mySeries(),"Мои сериалы")
    }
    private fun loadLibrary(request: CenterBrowse) {
        if(store.catalog!="tmdb") {
            val section=CenterCatalog.sections(store.catalog).first { it.type==request.type && it.country==null }
            loadCategory(section); return
        }
        work("Загружаю каталог…") {
            showHome(browsePage("tmdb",request,1),if(request.type=="tv") "Сериалы" else "Фильмы",1,null,request)
        }
    }
    private fun libraryTools(request: CenterBrowse) {
        if(request.mode!="popular" || request.country.isNotBlank() || request.year!=null || request.genre!=null) content.addView(text(listOf(if(request.mode=="new") "Новые" else "Популярные",
            request.country.takeIf { it.isNotBlank() },request.year?.toString(),request.genreName.takeIf { it.isNotBlank() }).filterNotNull().joinToString(" · "),14,accent))
        compactToolbar?.addView(iconButton("≡", "Подборка и фильтры") {
            val expected=gridGeneration
            job?.cancel()
            job=lifecycleScope.launch {
                try {
                    val genres=backend.genres(request.type)
                    if(expected!=gridGeneration)return@launch
                    val form=column().apply { setPadding(dp(20),dp(8),dp(20),0) }
                    fun choice(label:String,values:List<String>,selected:Int):Spinner {
                        form.addView(text(label,14,muted))
                        return Spinner(this@MediaCenterActivity).apply {
                            adapter=ArrayAdapter(this@MediaCenterActivity,android.R.layout.simple_spinner_dropdown_item,values)
                            setSelection(selected.coerceAtLeast(0));form.addView(this)
                        }
                    }
                    val mode=choice("Подборка",listOf("Популярные","Новые"),if(request.mode=="new")1 else 0)
                    val countries=listOf("","RU","KZ","US")
                    val country=choice("Страна",listOf("Все страны","Россия","Казахстан","США"),countries.indexOf(request.country))
                    val genreItems=listOf(0 to "Все жанры")+genres
                    val genre=choice("Жанр",genreItems.map { it.second },genreItems.indexOfFirst { it.first==request.genre })
                    form.addView(text(if(request.type=="tv") "Год начала сериала" else "Год фильма",14,muted))
                    val year=EditText(this@MediaCenterActivity).apply { hint="Любой год";setText(request.year?.toString().orEmpty());inputType=InputType.TYPE_CLASS_NUMBER;form.addView(this) }
                    val dialog=AlertDialog.Builder(this@MediaCenterActivity).setTitle("Фильтры каталога").setView(form)
                        .setNegativeButton("Отмена",null).setNeutralButton("Сбросить") { _,_-> loadLibrary(CenterBrowse(type=request.type)) }
                        .setPositiveButton("Применить",null).create()
                    dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val value=year.text.toString().trim()
                        val parsed=value.toIntOrNull()
                        if(value.isNotEmpty()&&(parsed==null||parsed !in 1888..(java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)+1))) { year.error="Укажи год из четырёх цифр";return@setOnClickListener }
                        val selectedGenre=genreItems[genre.selectedItemPosition]
                        dialog.dismiss()
                        loadLibrary(request.copy(mode=if(mode.selectedItemPosition==1)"new" else "popular",
                            country=countries[country.selectedItemPosition],year=parsed,genre=selectedGenre.first.takeIf { it>0 },
                            genreName=selectedGenre.second.takeIf { selectedGenre.first>0 }.orEmpty()))
                    } }
                    dialog.show()
                } catch(e:CancellationException) { throw e }
                catch(_:Exception) { Toast.makeText(this@MediaCenterActivity,"Не удалось загрузить жанры. Попробуй ещё раз",Toast.LENGTH_SHORT).show() }
            }
        }.apply { tag="library_filters" })
        if(request.type=="tv") compactToolbar?.addView(iconButton("▷", "Новые серии · эфир") { enter { loadCategory(CenterSection.all.first { it.id=="new_episodes" }) } })
    }
    private fun catalogTools() {
        val tools = row(); compactToolbar = tools
        tools.addView(iconButton("▦", "Каталог: ${CenterCatalog.name(store.catalog)}  ▾") { chooseCatalog() })
        tools.addView(iconButton("☰", "Разделы  ▾") {
            val sections = CenterCatalog.sections(store.catalog)
            AlertDialog.Builder(this).setTitle("Что посмотреть").setItems(sections.map { it.label }.toTypedArray()) { _, index ->
                enter { loadCategory(sections[index]) }
            }.show()
        })
        content.addView(tools); space(content, 8)
    }
    private fun chooseCatalog() {
        val ids = CenterCatalog.providers
        AlertDialog.Builder(this).setTitle("Каталог фильмов и сериалов")
            .setSingleChoiceItems(ids.map { CenterCatalog.name(it) }.toTypedArray(), ids.indexOf(store.catalog)) { dialog, index ->
                store.catalog = ids[index]; dialog.dismiss(); navigate("Главная")
            }.setNegativeButton("Закрыть", null).show()
    }
    protected fun showDashboard(boards: List<Pair<CenterSection, List<CenterTitle>>>, unavailable: List<String> = emptyList()) {
        currentScreen = { showDashboard(boards, unavailable) }; page = "home"; clear("Главная"); catalogTools()
        val pinned = store.pinned()
        if (pinned.isNotEmpty()) { section("Закреплённое", pinned.size); shelf(pinned, "pinned:") }
        boards.firstOrNull()?.second?.firstOrNull()?.let { featured(it) }
        if (store.history().isNotEmpty()) { section("Продолжить просмотр"); historyCards(store.history().take(8)); space(content, 14) }
        if (store.catalog == "tmdb") {
            content.addView(text("Каталоги показывают описания фильмов и сериалов. Онлайн KinoGo, KinoKong, KinoTac, Zona.movie и Киновода доступен в режиме проверки; также можно выбрать торрент-раздачу.", 13, muted)); space(content, 14)
        }
        boards.forEach { (category, items) ->
            section(category.label, items.size)
            if (items.isEmpty()) content.addView(text("В этом каталоге пока ничего нет", 15, muted)) else shelf(items, "${category.id}:")
            content.addView(button("Все: ${category.label}   ›") { enter { loadCategory(category) } }); space(content, 14)
        }
        if (unavailable.isNotEmpty()) content.addView(text("Не загрузились: ${unavailable.joinToString()}. Можно обновить подборки или сменить каталог.", 14, muted))
        content.addView(button("Обновить подборки") { catalogs.clearCache(); loadHome() })
    }
    private fun artFrame(title: String, width: Int, height: Int, url: String?, seed: Int = 0): FrameLayout {
        val frame = FrameLayout(this)
        frame.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(
            Color.rgb(30 + (seed * 7 and 31), 54, 79), Color.rgb(12, 23 + (seed * 3 and 15), 40)))
        frame.addView(text(title.take(1).uppercase(), 52, Color.rgb(77, 111, 144)).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))
        Glide.with(this).load(url).override(dp(width), dp(height)).dontAnimate().into(image)
        return frame
    }
    private fun catalogArt(item: CenterTitle, width: Int, height: Int): FrameLayout {
        val frame = artFrame(item.title,width,height,item.poster,item.id)
        // Some Rezka pages provide covers only on the detail page. Fetch visible cards
        // without blocking the grid; detaching/recycling a card cancels its request.
        if(item.provider in listOf("rezka","hdrezka") && item.poster==null) {
            var coverJob: Job? = null
            frame.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    coverJob = lifecycleScope.launch {
                        try {
                            val poster = catalogs.detail(item).title.poster
                            if(frame.isAttachedToWindow) Glide.with(this@MediaCenterActivity).load(poster)
                                .override(dp(width),dp(height)).dontAnimate().into(frame.getChildAt(1) as ImageView)
                        } catch(e: CancellationException) { throw e }
                        catch(_: Exception) { /* Keep the title placeholder; opening the card can retry. */ }
                    }
                }
                override fun onViewDetachedFromWindow(view: View) { coverJob?.cancel(); coverJob=null }
            })
        }
        return frame
    }
    private fun featured(item: CenterTitle) {
        val hero = artFrame(item.title, 780, 215, item.backdrop ?: item.poster, item.id)
        hero.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.argb(250, 15, 25, 41), Color.argb(235, 15, 25, 41), Color.argb(50, 15, 25, 41))) }, FrameLayout.LayoutParams(-1, -1))
        val copy = column().apply { setPadding(dp(23), dp(17), dp(23), dp(12)) }
        copy.addView(text(facts(item), 12, accent))
        space(copy, 8)
        copy.addView(text(item.title, 29).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; typeface = Typeface.DEFAULT_BOLD })
        space(copy, 9)
        copy.addView(text(item.overview.ifBlank { "Открой карточку, чтобы узнать больше" }, 14, muted).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        space(copy, 12)
        copy.addView(button(if (item.type == "tv") "Сезоны и серии   ›" else "Открыть фильм   ›") { enter { detail(item) } },
            LinearLayout.LayoutParams(dp(230), dp(43)))
        hero.addView(copy, FrameLayout.LayoutParams(-1, -2)); content.addView(hero, LinearLayout.LayoutParams(-1, dp(215))); space(content, 20)
    }
    protected fun showHome(items: List<CenterTitle>, heading: String = "Популярное сейчас", pageNumber: Int = catalogPage, category: CenterSection? = null, request: CenterBrowse? = null, sourceProvider:String=store.catalog) {
        catalogPage = pageNumber
        currentScreen = { showHome(items, heading, pageNumber, category, request,sourceProvider) }; page = "home"; clear(heading)
        if (category != null || request != null || heading=="Мои сериалы") catalogTools()
        if(request != null && request.query.isBlank()) libraryTools(request)
        if(heading=="Мои сериалы") content.addView(text("Сериалы из подписок и истории просмотра",14,muted))
        if(request?.query?.isNotBlank()==true) content.addView(text("Поиск: ${request.query} · ${CenterCatalog.name(sourceProvider)}",14,muted))
        if (items.isEmpty()) {
            content.addView(text(if (heading == "Избранное") "Здесь появятся фильмы и сериалы из избранного" else "Ничего не найдено", 17, muted))
            return
        }
        titleGrid(items, heading, pageNumber, category, request != null || heading !in listOf("Избранное", "Результаты поиска", "Мои сериалы"), request,sourceProvider)
    }
    protected open suspend fun nextGridPage(provider: String, category: CenterSection?, type: String, number: Int): List<CenterTitle> =
        if (category != null) catalogItems(provider, category, number) else backend.browse(type, page = number)
    private fun titleGrid(initial: List<CenterTitle>, heading: String, firstPage: Int, category: CenterSection?, canLoad: Boolean, request: CenterBrowse? = null, sourceProvider:String=store.catalog) {
        val loaded = initial.distinctBy { it.key }.toMutableList()
        val caption=content.getChildAt(0) as TextView
        (content.getChildAt(1) as? View)?.layoutParams?.height=dp(6)
        content.addView(text(heading,12,muted),0)
        caption.apply {
            tag="focused_catalog_title";maxLines=2;ellipsize=null;text=loaded.firstOrNull()?.title.orEmpty()
            layoutParams=LinearLayout.LayoutParams(-1,dp(52))
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this,14,22,1,android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        fun describe(item:CenterTitle) { caption.text=item.title;caption.contentDescription=item.title }

        val provider = sourceProvider
        val type = when (nav) { "Фильмы" -> "movie"; "Сериалы" -> "tv"; else -> "all" }
        val generation = gridGeneration
        var number = firstPage
        var busy = false; var ended = !canLoad; var failed = false
        val desired = when (store.cardSize) { "compact" -> 116; "large" -> 178; else -> 146 }
        val columns = ((resources.configuration.screenWidthDp - 110) / desired).coerceIn(2, 10)
        val grid = RecyclerView(this).apply {
            tag = "catalog_grid"; layoutManager = GridLayoutManager(this@MediaCenterActivity, columns)
            itemAnimator = null; clipToPadding = false; setPadding(dp(3), dp(6), dp(3), dp(8))
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
                override fun onInterceptTouchEvent(rv: RecyclerView, event: android.view.MotionEvent): Boolean {
                    if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) rv.parent.requestDisallowInterceptTouchEvent(true)
                    return false
                }
            })
        }
        val status = text("", 14, muted).apply { tag = "grid_status"; setPadding(dp(8),dp(8),dp(8),dp(8)) }
        lateinit var adapter: RecyclerView.Adapter<Poster>
        fun rememberScreen() { currentScreen = { showHome(loaded.toList(), heading, number, category, request,provider) } }
        fun nextPage() {
            if (busy || ended || failed || generation != gridGeneration || page != "home") return
            busy = true; status.text = "Загружаю ещё…"; status.isFocusable = false
            gridJob = lifecycleScope.launch {
                try {
                    if(number>=500) { ended=true;status.text="Достигнут конец каталога";return@launch }
                    val batch = if(request != null) browsePage(provider,request,number+1) else nextGridPage(provider, category, type, number + 1)
                    if (generation != gridGeneration) return@launch
                    val keys = loaded.mapTo(hashSetOf()) { it.key }
                    val fresh = batch.filter { keys.add(it.key) }
                    if (fresh.isEmpty()) ended = true else {
                        val offset = loaded.size
                        loaded.addAll(fresh); number++; catalogPage = number
                        rememberScreen(); adapter.notifyItemRangeInserted(offset, fresh.size)
                    }
                    status.text = if (ended) "Все карточки загружены" else ""
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    if (generation == gridGeneration) {
                        failed = true; status.text = "Не удалось загрузить продолжение · ОК, чтобы повторить"
                        status.isFocusable = true; status.isClickable = true
                    }
                } finally { busy = false }
            }
        }
        status.setOnClickListener { failed = false; nextPage() }
        adapter = object : RecyclerView.Adapter<Poster>() {
            override fun getItemCount() = loaded.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Poster {
                val card = column().apply {
                    isFocusable = true; isClickable = true; setPadding(dp(7),dp(7),dp(7),dp(7)); background = bg(panel)
                    layoutParams = RecyclerView.LayoutParams(-1, dp(desired * 3 / 2 + 55)).apply { setMargins(dp(4),dp(4),dp(4),dp(4)) }
                }
                val art = FrameLayout(this@MediaCenterActivity)
                card.addView(art, LinearLayout.LayoutParams(-1,0,1f))
                val title = text("",14).apply { maxLines=1; ellipsize=TextUtils.TruncateAt.END }
                val info = text("",11,muted).apply { maxLines=1; ellipsize=TextUtils.TruncateAt.END }
                card.addView(title); card.addView(info)
                return Poster(card,art,title,info)
            }
            override fun onBindViewHolder(holder: Poster, position: Int) {
                val item = loaded[position]
                holder.itemView.tag = if(item.provider == null) "title:${item.type}:${item.id}" else "title:${item.key}"
                holder.title.text = item.title
                holder.info.text = item.updateLabel ?: facts(item)
                if(heading=="Мои сериалы") {
                    val saved=store.history().firstOrNull { (it.card.contentKey ?: "tmdb:tv:${it.card.tmdbId}")==item.key }
                    if(saved!=null) holder.info.text=listOfNotNull(saved.card.seasonNumber?.let { "Сезон $it" },saved.card.episodeNumber?.let { "Серия $it" }).joinToString(" · ")
                }
                holder.art.removeAllViews()
                holder.art.addView(catalogArt(item,desired,desired*3/2),FrameLayout.LayoutParams(-1,-1))
                holder.itemView.setOnClickListener { enter { detail(item) } }
                holder.itemView.setOnLongClickListener {
                    store.toggle(item); Toast.makeText(this@MediaCenterActivity,if(store.isFavorite(item)) "Добавлено в избранное" else "Убрано из избранного",Toast.LENGTH_SHORT).show(); true
                }
                holder.itemView.setOnFocusChangeListener { view, focused ->
                    if(focused)describe(item)
                    view.background = bg(if(focused) Color.rgb(34,54,79) else panel,if(focused) accent else Color.TRANSPARENT)
                    if(focused && holder.bindingAdapterPosition >= loaded.size-columns*2) nextPage()
                }
            }
        }
        grid.adapter = adapter
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && (rv.layoutManager as GridLayoutManager).findLastVisibleItemPosition() >= loaded.size-columns*2) nextPage()
            }
        })
        content.addView(grid,LinearLayout.LayoutParams(-1,dp((resources.configuration.screenHeightDp-190).coerceAtLeast(220))))
        content.addView(status)
        rememberScreen()
        grid.post { if (loaded.size <= columns*2) nextPage() }
    }
    private var catalogPage = 1
    private fun rail(adapter: RecyclerView.Adapter<*>, height: Int) {
        content.addView(RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MediaCenterActivity, LinearLayoutManager.HORIZONTAL, false)
            setPadding(dp(4), dp(7), dp(4), dp(7)); clipToPadding = false; itemAnimator = null
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS; this.adapter = adapter
        }, LinearLayout.LayoutParams(-1, dp(height)))
    }
    private fun clock(seconds: Double): String {
        val value = seconds.toLong().coerceAtLeast(0)
        return "${value / 60}:${(value % 60).toString().padStart(2, '0')}"
    }
    private fun progressCard(item: CenterTitle, season: Int?, episode: Int?) = CardMeta(
        title = item.title, contentKey = item.key, tmdbId = item.id.takeIf { item.provider == null },
        seasonNumber = season?.takeIf { it > 0 }, episodeNumber = episode?.takeIf { it > 0 })
    private fun lastEpisode(item:CenterTitle)=store.history().firstOrNull {
        (it.card.contentKey ?: "tmdb:${if(it.card.seasonNumber!=null)"tv" else "movie"}:${it.card.tmdbId}")==item.key
    }?.card

    private fun episodeProgress(view:TextView,item:CenterTitle,season:Int,episode:Int,url:String,bar:ProgressBar?=null) {
        if(episode<=0)return
        val label=view.text.toString();val bound=view.tag
        val last=lastEpisode(item);val current=last!=null&&(last.seasonNumber ?: 0)==season&&last.episodeNumber==episode
        bar?.progress=0
        lifecycleScope.launch {
            val position=PositionDataStore(applicationContext).getPosition(url,progressCard(item,season,episode))
            if(view.tag!=bound)return@launch
            val prefix=if(position?.watched==true)"✓ " else if(current)"▶ " else ""
            view.text=prefix+label+if(position!=null&&position.time>0)" · ${clock(position.time)} · ${position.percent}%" else ""
            bar?.progress=position?.percent?.coerceIn(0,100) ?: 0
            if(current&&position?.watched!=true)view.setTextColor(accent)
        }
    }
    private data class EpisodeChoice(val tag:String,val label:String,val season:Int,val number:Int,val url:String="",
        val action:()->Unit,val longAction:(()->Unit)?=null,val onFocus:(()->Unit)?=null)
    private class EpisodeHolder(view:View,val label:TextView,val progress:ProgressBar):RecyclerView.ViewHolder(view)
    private fun episodeGrid(item:CenterTitle,entries:List<EpisodeChoice>,tag:String,height:Int):Pair<RecyclerView,()->Unit> {
        val span=((resources.configuration.screenWidthDp-110)/165).coerceIn(2,7)
        val manager=GridLayoutManager(this,span)
        val grid=RecyclerView(this).apply {
            this.tag=tag;layoutManager=manager;itemAnimator=null;clipToPadding=false;setPadding(dp(3),dp(4),dp(3),dp(4))
            descendantFocusability=ViewGroup.FOCUS_AFTER_DESCENDANTS
            addOnItemTouchListener(object:RecyclerView.SimpleOnItemTouchListener() {
                override fun onInterceptTouchEvent(rv:RecyclerView,event:android.view.MotionEvent):Boolean {
                    if(event.actionMasked==android.view.MotionEvent.ACTION_DOWN)rv.parent.requestDisallowInterceptTouchEvent(true)
                    return false
                }
            })
        }
        grid.adapter=object:RecyclerView.Adapter<EpisodeHolder>() {
            override fun getItemCount()=entries.size
            override fun onCreateViewHolder(parent:ViewGroup,viewType:Int):EpisodeHolder {
                val frame=FrameLayout(this@MediaCenterActivity).apply {
                    layoutParams=RecyclerView.LayoutParams(-1,dp(90)).apply { setMargins(dp(4),dp(4),dp(4),dp(4)) }
                }
                val label=button("") {}.apply { maxLines=3;ellipsize=TextUtils.TruncateAt.END;textSize=14f;setPadding(dp(12),dp(9),dp(12),dp(11)) }
                val bar=ProgressBar(this@MediaCenterActivity,null,android.R.attr.progressBarStyleHorizontal).apply {
                    max=100;isIndeterminate=false;isFocusable=false;progressTintList=android.content.res.ColorStateList.valueOf(accent)
                }
                frame.addView(label,FrameLayout.LayoutParams(-1,-1))
                frame.addView(bar,FrameLayout.LayoutParams(-1,dp(4),Gravity.BOTTOM).apply { leftMargin=dp(10);rightMargin=dp(10);bottomMargin=dp(5) })
                return EpisodeHolder(frame,label,bar)
            }
            override fun onBindViewHolder(holder:EpisodeHolder,position:Int) {
                val entry=entries[position];holder.label.tag=entry.tag;holder.label.text=entry.label
                paintButton(holder.label,holder.label.hasFocus())
                holder.label.setOnClickListener { entry.action() }
                holder.label.setOnLongClickListener { entry.longAction?.let { it();true } ?: false }
                holder.label.setOnFocusChangeListener { view,focused -> paintButton(view,focused);if(focused)entry.onFocus?.invoke() }
                episodeProgress(holder.label,item,entry.season,entry.number,entry.url,holder.progress)
            }
        }
        val rows=(entries.size+span-1)/span
        content.addView(grid,LinearLayout.LayoutParams(-1,dp((rows*98+8).coerceAtMost(height).coerceAtLeast(100))))
        val focus:()->Unit={
            val last=lastEpisode(item)
            val index=entries.indexOfFirst { last!=null&&it.season==(last.seasonNumber ?: 0)&&it.number==last.episodeNumber }.coerceAtLeast(0)
            manager.scrollToPositionWithOffset(index,0)
            grid.doOnNextLayout { (grid.findViewHolderForAdapterPosition(index) as? EpisodeHolder)?.label?.requestFocus() }
            grid.requestLayout()
        }
        return grid to focus
    }
    private fun shelf(items: List<CenterTitle>, prefix: String = "") {
        val width = when (store.cardSize) { "compact" -> 104; "large" -> 164; else -> 132 }
        val artHeight = width * 166 / 132
        val cardHeight = artHeight + 65
        rail(object : RecyclerView.Adapter<Poster>() {
            override fun getItemCount() = items.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Poster {
                val view = column().apply {
                    isFocusable = true; isClickable = true; background = bg(panel)
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    layoutParams = RecyclerView.LayoutParams(dp(width), dp(cardHeight)).apply { rightMargin = dp(14) }
                }
                val art = FrameLayout(this@MediaCenterActivity)
                view.addView(art, LinearLayout.LayoutParams(-1, dp(artHeight)))
                val title = text("", 14).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(7), 0, 0) }
                val info = text("", 11, muted).apply { setPadding(0, dp(5), 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
                view.addView(title); view.addView(info)
                return Poster(view, art, title, info)
            }
            override fun onBindViewHolder(holder: Poster, position: Int) {
                val item = items[position]; holder.title.text = item.title; holder.info.text = item.updateLabel?.takeIf { it.isNotBlank() } ?: listOf(item.year, item.rating.takeIf { it.isNotBlank() }?.let { "★ $it" }).filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
                holder.art.removeAllViews(); holder.art.addView(catalogArt(item, width, artHeight), FrameLayout.LayoutParams(-1, -1))
                holder.itemView.tag = prefix + if (item.provider == null) "title:${item.type}:${item.id}" else "title:${item.key}"
                holder.itemView.setOnClickListener { enter { detail(item) } }
                holder.itemView.setOnLongClickListener { store.toggle(item); Toast.makeText(this@MediaCenterActivity,
                    if (store.isFavorite(item)) "Добавлено в избранное" else "Убрано из избранного", Toast.LENGTH_SHORT).show(); true }
                holder.itemView.setOnFocusChangeListener { v, focus ->
                    v.background = bg(if (focus) Color.rgb(34, 54, 79) else panel, if (focus) accent else Color.TRANSPARENT)
                    v.animate().scaleX(if (focus) 1.025f else 1f).scaleY(if (focus) 1.025f else 1f).setDuration(100).start()
                }
            }
        }, cardHeight + 22)
    }
    private class Poster(view: View, val art: FrameLayout, val title: TextView, val info: TextView) : RecyclerView.ViewHolder(view)
    private fun historyScreen() {
        currentScreen = { historyScreen() }; page = "history"; clear("История просмотра")
        val history = store.history()
        if (history.isEmpty()) content.addView(text("Здесь появятся фильмы и сериалы после начала просмотра", 17, muted))
        else {
            content.addView(text("Удерживай ОК на карточке, чтобы убрать её из истории", 14, muted)); space(content, 16)
            historyCards(history)
        }
    }
    private fun historyCards(history: List<CenterHistory>) {
        rail(object : RecyclerView.Adapter<HistoryHolder>() {
            override fun getItemCount() = history.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryHolder {
                val view = column().apply {
                    isFocusable = true; isClickable = true; setPadding(dp(7), dp(7), dp(7), dp(7)); background = bg(panel)
                    layoutParams = RecyclerView.LayoutParams(dp(220), dp(200)).apply { rightMargin = dp(14) }
                }
                val image = FrameLayout(this@MediaCenterActivity); view.addView(image, LinearLayout.LayoutParams(-1, dp(110)))
                val title = text("", 15).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(8), 0, 0) }
                val time = text("", 12, muted).apply { setPadding(0, dp(6), 0, dp(7)) }
                val progress = ProgressBar(this@MediaCenterActivity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
                view.addView(title); view.addView(time); view.addView(progress, LinearLayout.LayoutParams(-1, dp(4)))
                return HistoryHolder(view, image, title, time, progress)
            }
            override fun onBindViewHolder(holder: HistoryHolder, position: Int) {
                val saved = history[position]; holder.url = saved.url; val card = saved.card
                holder.title.text = card.title
                val episode = card.episodeNumber?.let { number -> (card.seasonNumber?.let { "S$it · " } ?: "") + "Серия $number" } ?: if (card.contentKey?.contains(":tv:") == true) "Сериал" else "Фильм"
                holder.time.text = episode; holder.progress.progress = 0
                holder.art.removeAllViews(); holder.art.addView(artFrame(card.title, 220, 110, card.backdropUrl ?: card.posterUrl), FrameLayout.LayoutParams(-1, -1))
                holder.itemView.tag = "history:${card.contentKey ?: card.tmdbId}:${card.seasonNumber}:${card.title}"
                holder.itemView.setOnClickListener { enter { resume(saved) } }
                holder.itemView.setOnLongClickListener {
                    store.removeHistory(card); currentScreen?.invoke(); content.post { content.getFocusables(View.FOCUS_FORWARD).firstOrNull()?.requestFocus() }; true
                }
                holder.itemView.setOnFocusChangeListener { v, focus -> v.background = bg(if (focus) Color.rgb(34, 54, 79) else panel, if (focus) accent else Color.TRANSPARENT) }
                lifecycleScope.launch {
                    val progress = PositionDataStore(applicationContext).getPosition(saved.url, card)
                    if (holder.url == saved.url && progress != null) {
                        holder.time.text = "$episode · ${progress.time.toInt() / 60}:${(progress.time.toInt() % 60).toString().padStart(2, '0')}"
                        holder.progress.progress = progress.percent.coerceIn(0, 100)
                    }
                }
            }
        }, 220)
    }
    private class HistoryHolder(view: View, val art: FrameLayout, val title: TextView, val time: TextView, val progress: ProgressBar) : RecyclerView.ViewHolder(view) { var url = "" }

    private fun searchScreen(value: String = "") {
        job?.cancel(); currentScreen = { searchScreen(value) }; page = "search"; clear("Найти фильм или сериал")
        val query = EditText(this).apply {
            hint = "Название"; setText(value); setTextColor(ink); setHintTextColor(muted); isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH; inputType = InputType.TYPE_CLASS_TEXT
            tag = "search_query"; filters = arrayOf(android.text.InputFilter.LengthFilter(120))
        }
        content.addView(query, LinearLayout.LayoutParams(-1, dp(60)))
        fun search() {
            val term = query.text.toString().trim()
            if (term.length < 2) { query.error = "Введи хотя бы две буквы"; return }
            store.rememberSearch(term)
            (getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                ?.hideSoftInputFromWindow(query.windowToken,0)
            val provider=store.searchCatalog
            currentScreen = { searchScreen(term) }
            enter { work("Ищу «$term»…") {
                val spec=CenterBrowse(type="all",query=term)
                showHome(browsePage(provider,spec,1),"Результаты поиска",1,null,if(provider=="tmdb") spec else null,sourceProvider=provider)
            } }
        }
        query.setOnEditorActionListener { _, action, event ->
            if (action == EditorInfo.IME_ACTION_SEARCH || event?.let { it.keyCode == KeyEvent.KEYCODE_ENTER && it.action == KeyEvent.ACTION_DOWN } == true) { search(); true } else false
        }
        content.addView(button("Искать в: ${CenterCatalog.name(store.searchCatalog)}") {
            val providers=CenterCatalog.providers
            AlertDialog.Builder(this).setTitle("Каталог для поиска")
                .setSingleChoiceItems(providers.map { CenterCatalog.name(it) }.toTypedArray(),providers.indexOf(store.searchCatalog)) { dialog,index ->
                    store.searchCatalog=providers[index];dialog.dismiss();searchScreen(query.text.toString())
                    content.post { findTag(content,"search_source")?.requestFocus() }
                }.setNegativeButton("Отмена",null).show()
        }.apply { tag="search_source" })
        content.addView(button("Найти") { search() }.apply { tag="search_submit" })
        val recent=store.searches()
        if(recent.isNotEmpty()) {
            space(content,16);section("Недавние запросы")
            content.addView(text("ОК — повторить поиск · Удержание ОК — удалить запрос",13,muted))
            space(content,10)
            recent.forEach { term ->
                content.addView(button(term) { query.setText(term);search() }.apply {
                    tag="search_recent:$term"
                    setOnLongClickListener {
                        store.removeSearch(term);searchScreen(query.text.toString())
                        content.post {
                            val next=store.searches().firstOrNull()?.let { findTag(content,"search_recent:$it") }
                            (next ?: findTag(content,"search_submit"))?.requestFocus()
                        }
                        true
                    }
                })
            }
            content.addView(button("Очистить историю поиска") {
                AlertDialog.Builder(this).setTitle("Очистить историю поиска?")
                    .setMessage("История просмотра и избранное сохранятся")
                    .setNegativeButton("Отмена",null).setPositiveButton("Очистить") { _,_->
                        store.clearSearches();searchScreen(query.text.toString())
                        content.post { findTag(content,"search_submit")?.requestFocus() }
                    }.show()
            })
        }
        query.requestFocus()
    }
    private fun detail(item: CenterTitle) {
        selected = item
        work("Открываю ${item.title}…") { showDetails(if (item.provider == null) backend.details(item) else catalogs.detail(item)) }
    }
    protected fun showDetails(details: CenterDetails) {
        currentScreen = { showDetails(details) }; val movie = details.title; selected = movie
        page = "detail"; clear(movie.title)
        val body = row()
        body.addView(artFrame(movie.title, 185, 278, movie.poster, movie.id), LinearLayout.LayoutParams(dp(185), dp(278)))
        val copy = column().apply { setPadding(dp(24), 0, 0, 0) }
        val moreActions=column().apply { tag="detail_more_panel";visibility=if(detailMoreKey==movie.key)View.VISIBLE else View.GONE }
        fun primary(view:TextView):TextView = view.apply {
            isActivated=true;textSize=17f;typeface=Typeface.DEFAULT_BOLD
            paintButton(this,false);setTextColor(ink)
            setOnFocusChangeListener { v,focused ->paintButton(v,focused);(v as TextView).setTextColor(ink) }
        }
        copy.addView(text(facts(movie),15,accent));space(copy,12)
        val saved=store.history().firstOrNull { (it.card.contentKey ?: "tmdb:${movie.type}:${it.card.tmdbId}")==movie.key }
        if(saved!=null) {
            val resumeButton=primary(button("Продолжить"){enter {resume(saved)}}).apply { tag="continue_title";maxLines=2;layoutParams.height=dp(62) }
            copy.addView(resumeButton)
            lifecycleScope.launch {
                val pos=PositionDataStore(applicationContext).getPosition(saved.url,saved.card)
                resumeButton.text="Продолжить"+(saved.card.seasonNumber?.let {" · Сезон $it"} ?: "")+
                    (saved.card.episodeNumber?.let {" · Серия $it"} ?: "")+(pos?.let {" · ${clock(it.time)}"} ?: "")
            }
        }
        val canOnline=movie.provider==null||CenterOnline.onlineSupported(movie.provider)
        if(canOnline) {
            val watch=button("Смотреть онлайн"){enter {if(movie.provider==null)showTmdbOnlineSources(movie) else online(movie)}}
            if(saved==null)primary(watch)
            copy.addView(watch)
        }
        if(movie.provider!=null||movie.type!="tv") {
            val torrent=button("Найти торрент-раздачу"){enter {find(movie,null)}}
            if(saved==null&&!canOnline)primary(torrent)
            copy.addView(torrent)
        } else {
            copy.addView(text("Сезоны",20));space(copy,6)
            copy.addView(text("Торренты · выбери сезон и серию",13,muted));space(copy,8)
            val seasons=row()
            details.seasons.forEach { season ->seasons.addView(button("Сезон $season"){enter {episodeList(movie,season)}},
                LinearLayout.LayoutParams(dp(120),dp(44)).apply {rightMargin=dp(9)}) }
            copy.addView(HorizontalScrollView(this).apply {addView(seasons);isHorizontalScrollBarEnabled=false})
            if(details.seasons.isEmpty())copy.addView(text("Сезоны пока не опубликованы",15,muted))
        }
        movie.updateLabel?.let {copy.addView(text(it,15,accent));space(copy,8)}
        lateinit var more:TextView
        more=button(if(moreActions.visibility==View.VISIBLE)"Свернуть действия" else "Ещё") {
            val expanded=moreActions.visibility!=View.VISIBLE
            detailMoreKey=movie.key.takeIf {expanded};moreActions.visibility=if(expanded)View.VISIBLE else View.GONE
            more.text=if(expanded)"Свернуть действия" else "Ещё";more.isActivated=expanded;paintButton(more,more.hasFocus())
        }.apply {tag="detail_more";isActivated=moreActions.visibility==View.VISIBLE;paintButton(this,false)}
        copy.addView(more);copy.addView(moreActions)
        if(movie.type=="tv")moreActions.addView(button(if(store.isFollowed(movie))"✓ В моих сериалах · убрать" else "+ В мои сериалы") {
            store.toggleFollow(movie);showDetails(details);content.post {findTag(content,"follow_series")?.requestFocus()}
        }.apply {tag="follow_series"})
        moreActions.addView(button(if(store.isFavorite(movie))"★ В избранном · убрать" else "Добавить в избранное") {
            store.toggle(movie);showDetails(details);content.post {findTag(content,"favorite")?.requestFocus()}
        }.apply {tag="favorite"})
        moreActions.addView(button(if(store.isPinned(movie))"Открепить с главной" else "Закрепить на главной") {
            store.pin(movie);showDetails(details);content.post {findTag(content,"pin")?.requestFocus()}
        }.apply {tag="pin"})
        if(movie.provider!=null&&canOnline)moreActions.addView(button("Плеер сайта"){website(movie)})
        moreActions.addView(button("Открыть свою magnet-ссылку / .torrent"){magnet(movie,null)})
        space(copy, 12)
        val descriptionHeader=row()
        descriptionHeader.addView(text("Описание",18),LinearLayout.LayoutParams(0,-2,1f))
        var expanded=false
        val synopsis=text(movie.overview.ifBlank { "Описание пока отсутствует" },16,muted).apply {
            tag="detail_description";maxLines=7;ellipsize=TextUtils.TruncateAt.END
        }
        val toggle=button("Показать полностью") {
            expanded=!expanded;synopsis.maxLines=if(expanded)Int.MAX_VALUE else 7
            synopsis.ellipsize=if(expanded)null else TextUtils.TruncateAt.END
            (findTag(content,"description_toggle") as? TextView)?.text=if(expanded)"Свернуть описание" else "Показать полностью"
        }.apply { tag="description_toggle";visibility=View.GONE }
        descriptionHeader.addView(toggle,LinearLayout.LayoutParams(dp(210),dp(44)))
        copy.addView(descriptionHeader);space(copy,9);copy.addView(synopsis)
        synopsis.doOnLayout {
            val layout=synopsis.layout
            toggle.visibility=if(layout!=null&&layout.lineCount>0&&layout.getEllipsisCount(layout.lineCount-1)>0)View.VISIBLE else View.GONE
        }
        if (details.actors.isNotBlank()) { space(copy, 18); copy.addView(text("В ролях", 18)); space(copy, 8); copy.addView(text(details.actors, 15, muted)) }
        body.addView(copy, LinearLayout.LayoutParams(0, -2, 1f)); content.addView(body)
    }
    protected open suspend fun resolveOnline(item: CenterTitle): OnlineVideo = resolveOnlinePlayer(item,null)
    protected open suspend fun resolveOnlinePlayer(item:CenterTitle,playerIndex:Int?):OnlineVideo {
        val resolver=CenterOnline(this)
        fun report() = "LampMedia · ${BuildConfig.VERSION_NAME}\nИсточник: ${CenterCatalog.name(item.provider)}\nКарточка: ${item.key}\n"+
            resolver.diagnostics.substringBefore(" errors=")
        try { return resolver.resolve(item,playerIndex) }
        catch(e:CancellationException) { throw e }
        catch(e:Exception) { onlineReport=report();throw onlineFailure(e) }
        finally { onlineReport=report() }
    }

    protected open suspend fun searchKinoVod(query:String):List<CenterTitle> =
        catalogs.search("kinovod",store.catalogHost("kinovod"),query)

    protected open suspend fun searchOnlineCatalog(provider:String,query:String):List<CenterTitle> =
        if(provider=="kinovod")searchKinoVod(query) else catalogs.search(provider,store.catalogHost(provider),query)

    private fun showTmdbOnlineSources(item:CenterTitle,resume:CardMeta?=null,refresh:Boolean=false,returnTo:Screen?=null,
        selectedSeason:Int?=null,selectedVoice:String?=null) {
        currentScreen={showTmdbOnlineSources(item,resume,refresh,returnTo,selectedSeason,selectedVoice)}
        page="online_sources";clear("Смотреть онлайн · ${item.title}")
        content.addView(text("Выбери источник. Доступные серии, озвучки и качество зависят от сайта.",15,muted));space(content,12)
        CenterOnlineMatch.providers.forEach { provider ->
            val saved=store.onlineSource(item,provider)
            content.addView(button(CenterCatalog.name(provider)+(if(saved!=null)" · сохранён выбор" else "")) {
                enter { tmdbOnline(item,resume,refresh,returnTo,selectedSeason,selectedVoice,sourceProvider=provider) }
            }.apply { tag="online_source:$provider" })
        }
        content.addView(button("Найти торрент-раздачу") { enter { find(item,null) } })
        content.addView(button("Назад") { back() })
    }

    private fun tmdbOnline(item:CenterTitle,resume:CardMeta?=null,refresh:Boolean=false,returnTo:Screen?=null,
        selectedSeason:Int?=null,selectedVoice:String?=null,forceSearch:Boolean=false,query:String?=null,
        sourceProvider:String?=null,playerIndex:Int?=null,choosePlayerAfterLoad:Boolean=false) {
        val restored=CenterOnlineMatch.fromPage(item) ?: store.onlineSource(item)
        val provider=sourceProvider ?: restored?.provider?.takeIf { resume!=null||refresh||choosePlayerAfterLoad }
        if(provider==null) { showTmdbOnlineSources(item,resume,refresh,returnTo,selectedSeason,selectedVoice);return }
        check(provider in CenterOnlineMatch.providers)
        val name=CenterCatalog.name(provider)
        onlineReport=null
        work("Ищу «${item.title}» · $name…",onlineItem=item) {
            fun choose(source:CenterTitle) {
                store.setOnlineSource(item,source)
                content.post { online(source,resume,refresh,returnTo,selectedSeason,selectedVoice,item.copy(sourceUrl=source.sourceUrl),
                    playerIndex=playerIndex,choosePlayerAfterLoad=choosePlayerAfterLoad) }
            }
            val saved=if(forceSearch)null else restored?.takeIf { it.provider==provider } ?: store.onlineSource(item,provider)
            if(saved!=null) { choose(saved);return@work }
            val queries=query?.let { listOf(it) } ?: CenterOnlineMatch.queries(item)
            val results=coroutineScope { queries.map { term -> async {
                try { Result.success(searchOnlineCatalog(provider,term)) } catch(e:CancellationException) { throw e } catch(e:Exception) { Result.failure<List<CenterTitle>>(e) }
            } }.awaitAll() }
            if(results.isNotEmpty()&&results.all { it.isFailure })throw results.first().exceptionOrNull()!!
            val matches=CenterOnlineMatch.candidates(item,results.flatMap { it.getOrNull().orEmpty() }.filter { it.provider==provider })
            val exact=CenterOnlineMatch.automatic(item,matches)
            if(!forceSearch&&exact!=null)choose(exact)
            else showTmdbOnlineChoices(item,matches,resume,refresh,returnTo,selectedSeason,selectedVoice,query ?: item.title,provider)
        }
    }
    private fun showTmdbOnlineChoices(item:CenterTitle,matches:List<CenterTitle>,resume:CardMeta?,refresh:Boolean,returnTo:Screen?,
        selectedSeason:Int?,selectedVoice:String?,query:String,provider:String) {
        currentScreen={showTmdbOnlineChoices(item,matches,resume,refresh,returnTo,selectedSeason,selectedVoice,query,provider)}
        val name=CenterCatalog.name(provider)
        page="online_match";clear("$name · ${item.title}")
        content.addView(text("TMDB · "+facts(item),15,accent));space(content,12)
        content.addView(text(if(matches.isEmpty())"Совпадений не найдено. Попробуй другое или оригинальное название."
            else "Выбери совпадение: проверь название и год выхода.",15,muted));space(content,12)
        matches.forEach { source ->
            content.addView(button(source.title+" · "+facts(source)) {
                store.setOnlineSource(item,source)
                enter { online(source,resume,refresh,returnTo,selectedSeason,selectedVoice,item.copy(sourceUrl=source.sourceUrl)) }
            }.apply { tag="online_match:${source.key}";maxLines=2;layoutParams.height=dp(62) })
        }
        val input=EditText(this).apply { setText(query);hint="Название · $name";isSingleLine=true;setTextColor(ink);tag="online_match_query" }
        content.addView(input)
        content.addView(button(if(provider=="kinovod")"Искать на Киноводе" else "Искать · $name") {
            val value=input.text.toString().trim()
            if(value.length !in 2..120)input.error="Введи от 2 до 120 символов"
            else tmdbOnline(item,resume,refresh,returnTo,selectedSeason,selectedVoice,true,value,sourceProvider=provider)
        })
        content.addView(button("Другой онлайн-источник") { showTmdbOnlineSources(item,resume,refresh,returnTo,selectedSeason,selectedVoice) })
        content.addView(button("Найти торрент-раздачу") { enter { find(item,null) } })
        content.addView(button("Назад") { back() })
    }

    private fun online(item: CenterTitle, resume: CardMeta? = null, refresh: Boolean = false, returnTo: Screen? = null,
        selectedSeason: Int? = null, selectedVoice: String? = null, catalogItem: CenterTitle? = null, playerIndex:Int?=null, choosePlayerAfterLoad:Boolean=false) {
        if(item.provider==null) { tmdbOnline(item,resume,refresh,returnTo,selectedSeason,selectedVoice,playerIndex=playerIndex,choosePlayerAfterLoad=choosePlayerAfterLoad);return }
        val identity=catalogItem ?: item
        onlineReport=null
        val previous = returnTo ?: if (resume != null && page == "online") snapshot() else null
        val contextSeason=selectedSeason ?: resume?.let { it.seasonNumber ?: 0 }
        val contextVoice=selectedVoice ?: resume?.translator
        val previousVideo=onlineCache[identity.key]?.second?.takeIf { it.item.key==item.key&&it.item.sourceUrl==item.sourceUrl }
        work("Получаю список серий · ${CenterCatalog.name(item.provider)}…",onlineItem=identity,
            onlineRecovery=previousVideo?.takeIf { it.playerCount>1 }?.let { old -> { chooseOnlinePlayer(old,contextSeason,contextVoice) } }
                ?: if(playerIndex!=null)({online(item,refresh=true,selectedSeason=contextSeason,selectedVoice=contextVoice,catalogItem=catalogItem,choosePlayerAfterLoad=true)}) else null,
            onlineRefresh={online(item,resume,refresh=true,selectedSeason=selectedSeason,selectedVoice=selectedVoice,catalogItem=catalogItem,playerIndex=playerIndex)}) {
            val cached = onlineCache[identity.key]?.takeIf { System.currentTimeMillis() - it.first < 180000 && !refresh && it.second.item.key==item.key && it.second.item.sourceUrl==item.sourceUrl && (playerIndex==null||it.second.pinnedPlayer&&it.second.playerIndex==playerIndex) }?.second
            val video = cached ?: (if(playerIndex==null)resolveOnline(item) else resolveOnlinePlayer(item,playerIndex)).copy(catalogItem=catalogItem).also {
                if (onlineCache.size >= 4) onlineCache.remove(onlineCache.keys.first())
                onlineCache[identity.key] = System.currentTimeMillis() to it
            }
            if (resume != null && !choosePlayerAfterLoad) {
                val matches = video.episodes.filter { it.season == (resume.seasonNumber ?: 0) && it.number == (resume.episodeNumber ?: 0) &&
                    (resume.translator == null || it.voice == resume.translator) }
                val entry = matches.singleOrNull()
                val quality = resume.quality?.toIntOrNull()
                if (entry != null && quality != null && entry.sources.containsKey(quality)) {
                    val refreshedReturn = previous?.copy(draw = {
                        showOnline(video, entry.season.takeIf { item.type == "tv" }, entry.voice, false)
                    })
                    playOnline(video, entry, quality, refreshedReturn); return@work
                }
            }
            // Keep available context after refresh, but never invent a missing season or voice.
            val wantedSeason = selectedSeason ?: resume?.let { it.seasonNumber ?: 0 }
            val season = wantedSeason?.takeIf { value -> video.episodes.any { it.season == value } }
            val wantedVoice = selectedVoice ?: resume?.translator
            val voice = wantedVoice?.takeIf { value -> video.episodes.any {
                (season == null || it.season == season) && it.voice == value
            } }
            showOnline(video, season, voice ?: store.voice(video.identity.key)?.takeIf { saved -> video.episodes.any { (season == null || it.season == season) && it.voice == saved } })
            if(choosePlayerAfterLoad&&video.playerCount>1)chooseOnlinePlayer(video,season,voice)
        }
    }
    private fun chooseOnlinePlayer(video:OnlineVideo,season:Int?=null,voice:String?=null) {
        if(!CenterOnline.manualPlayers(video.item.provider)||video.playerCount<=1)return
        val labels=listOf("Автоматически"+if(!video.pinnedPlayer)" ✓" else "")+
            (0 until video.playerCount).map { "Плеер ${it+1}"+if(video.pinnedPlayer&&video.playerIndex==it)" ✓" else "" }
        AlertDialog.Builder(this).setTitle("Плеер источника").setItems(labels.toTypedArray()) { _,index ->
            online(video.item,refresh=true,selectedSeason=season,selectedVoice=voice,catalogItem=video.catalogItem,
                playerIndex=(index-1).takeIf { index>0 })
        }.setNegativeButton("Отмена",null).show()
    }
    protected fun showOnline(video:OnlineVideo,season:Int?=null,voice:String?=null,focusFirst:Boolean=true) {
        val hadFocus=content.hasFocus()
        currentScreen={showOnline(video,season,voice,false)};page="online";clear(video.identity.title)
        video.notice?.let { message -> content.addView(text(message,14,muted));content.addView(button("Полный список в плеере сайта"){website(video.identity)});space(content,8) }
        val seasons=video.episodes.map { it.season }.distinct().sorted()
        val last=lastEpisode(video.identity)
        val activeSeason=season ?: last?.takeIf { it.episodeNumber!=null }?.let { it.seasonNumber ?: 0 }?.takeIf { it in seasons }
        val entries=video.episodes.filter { activeSeason==null||it.season==activeSeason }
        val voices=entries.map { it.voice }.distinct()
        val restoredVoice=last?.translator?.takeIf { season==null&&activeSeason!=null&&it in voices }
        val selectedVoice=voice?.takeIf { it in voices } ?: restoredVoice ?: voices.singleOrNull()
        content.addView(text(listOfNotNull(CenterCatalog.name(video.item.provider),activeSeason?.takeIf { it>0 }?.let { "Сезон $it" },selectedVoice).joinToString(" · "),15,muted));space(content,10)
        fun chips(values:List<Pair<String,()->Unit>>,selected:String?=null):List<TextView> {
            val strip=row()
            val controls=values.map { (label,click) -> button(label,click).apply {
                textSize=14f;maxLines=1;isActivated=label==selected;paintButton(this,false)
                layoutParams=LinearLayout.LayoutParams(-2,dp(38)).apply { rightMargin=dp(7) }
                strip.addView(this)
            } }
            content.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false;addView(strip) });space(content,8)
            return controls
        }
        val seasonControls=if(video.item.type=="tv")chips(seasons.map { value -> (if(value>0)"Сезон $value" else "Серии") to {
            enter { showOnline(video,value,store.voice(video.identity.key)?.takeIf { saved -> video.episodes.any { it.season==value&&it.voice==saved } }) }
        } },activeSeason?.let { if(it>0)"Сезон $it" else "Серии" }) else emptyList()
        var voiceControls=emptyList<TextView>()
        if((video.item.type!="tv"||activeSeason!=null)&&selectedVoice==null&&voices.size>1) {
            content.addView(text("Озвучка",14,muted));space(content,6)
            voiceControls=chips(voices.map { value -> value to {store.setVoice(video.identity.key,value);enter { showOnline(video,activeSeason,value) }} })
        } else if(voices.size>1&&selectedVoice!=null&&(activeSeason!=null||video.item.type!="tv")) {
            content.addView(button("Изменить озвучку") {
                AlertDialog.Builder(this).setTitle("Озвучка").setItems(voices.toTypedArray()) { _,index ->
                    store.setVoice(video.identity.key,voices[index]);showOnline(video,activeSeason,voices[index])
                }.setNeutralButton("Сбросить выбор") { _,_->store.setVoice(video.identity.key,null);showOnline(video,activeSeason) }.setNegativeButton("Отмена",null).show()
            }.apply { layoutParams.height=dp(38);textSize=14f })
        }
        if(CenterOnline.manualPlayers(video.item.provider)&&video.playerCount>1)
            content.addView(button("Плеер источника · ${video.playerIndex+1} из ${video.playerCount}"+if(video.pinnedPlayer)" · выбран" else " · авто") {
                chooseOnlinePlayer(video,activeSeason,selectedVoice)
            }.apply { layoutParams.height=dp(38);textSize=14f })
        if(video.item.type=="tv"&&activeSeason==null) {
            content.addView(text("Выбери сезон",15,muted))
            episodeFocus={seasonControls.firstOrNull()?.requestFocus();Unit}
        } else if(selectedVoice==null&&voices.size>1) {
            episodeFocus={voiceControls.firstOrNull()?.requestFocus();Unit}
        } else {
            content.addView(text("Удерживай ОК на серии или фильме, чтобы изменить качество",13,muted));space(content,6)
            val rows=entries.filter { it.voice==selectedVoice }.map { episode ->
                EpisodeChoice("online:${episode.season}:${episode.number}:${episode.voice}",
                    if(episode.number>0)"Серия ${episode.number} · ${episode.title}" else "Смотреть · ${episode.voice}",
                    episode.season,episode.number,episode.sources.values.firstOrNull().orEmpty(),
                    action={chooseOnlineQuality(video,episode)},longAction={chooseOnlineQuality(video,episode,forceChoice=true)})
            }
            episodeFocus=episodeGrid(video.identity,rows,"online_episode_grid",(resources.configuration.screenHeightDp-290).coerceAtLeast(144)).second
        }
        if(video.catalogItem!=null) {
            content.addView(button("Другое совпадение · ${CenterCatalog.name(video.item.provider)}") { tmdbOnline(video.identity,forceSearch=true,sourceProvider=video.item.provider) })
            content.addView(button("Другой онлайн-источник") { enter { showTmdbOnlineSources(video.identity,selectedSeason=activeSeason,selectedVoice=selectedVoice) } })
        }
        content.addView(button("Обновить список и ссылки") { online(video.item,refresh=true,selectedSeason=activeSeason,selectedVoice=selectedVoice,catalogItem=video.catalogItem,playerIndex=video.playerIndex.takeIf { video.pinnedPlayer }) })
        if(focusFirst&&hadFocus)content.post { episodeFocus?.invoke() }
    }
    private fun chooseOnlineQuality(video: OnlineVideo, episode: OnlineEpisode, forceChoice: Boolean = false) {
        if (forceQualityOnce && !forceChoice) { forceQualityOnce = false; chooseOnlineQuality(video, episode, true); return }
        val qualities = episode.sources.keys.sortedDescending()
        if (qualities.isEmpty()) {
            Toast.makeText(this, "Нет доступного качества. Обнови список и ссылки", Toast.LENGTH_SHORT).show()
            return
        }
        val preferred = store.preferredQuality(video.item.provider)
        if (!forceChoice && preferred != null && preferred in qualities) {
            playOnline(video, episode, preferred); return
        }
        // A sole fallback still needs confirmation when the saved quality disappeared.
        if (!forceChoice && preferred == null && qualities.size == 1) {
            playOnline(video, episode, qualities.single()); return
        }
        val labels = qualities.map { (if (it == 0) "Авто" else "${it}p") + if (it == preferred) " ✓" else "" }
        AlertDialog.Builder(this).setTitle(listOfNotNull(
            episode.number.takeIf { it > 0 }?.let { "Серия $it" }, episode.voice,
            if (preferred != null && preferred !in qualities) "Сохранённое качество недоступно" else "Качество"
        ).joinToString(" · "))
            .setItems(labels.toTypedArray()) { _, index ->
                store.setPreferredQuality(video.item.provider, qualities[index])
                playOnline(video, episode, qualities[index])
            }
            .setNeutralButton("Сбросить выбор") { _, _ -> store.setPreferredQuality(video.item.provider, null) }
            .setNegativeButton("Отмена", null).show()
    }
    protected open suspend fun verifyOnlineUrl(url:String,headers:Map<String,String>):String {
        try { return CenterOnline.playableUrl(url,headers) }
        catch(e:CancellationException) { throw e }
        catch(e:Exception) { throw onlineFailure(e) }
    }
    private fun playOnline(video: OnlineVideo, episode: OnlineEpisode, quality: Int, returnTo: Screen? = null) {
        if (System.currentTimeMillis() - video.fetchedAt > 180000) {
            online(video.identity, CardMeta(video.identity.title, seasonNumber = episode.season.takeIf { it>0 }, episodeNumber = episode.number,
                translator = episode.voice, quality = quality.toString()), refresh = true, returnTo = returnTo ?: snapshot(),playerIndex=video.playerIndex.takeIf { video.pinnedPlayer }); return
        }
        val previous=returnTo ?: snapshot()
        work("Проверяю доступность видео…",onlineItem=video.identity,onlineRecovery=
            if(CenterOnline.manualPlayers(video.item.provider)&&video.playerCount>1)({chooseOnlinePlayer(video,episode.season.takeIf { it>0 },episode.voice)}) else null,
            onlineRefresh={online(video.item,refresh=true,selectedSeason=episode.season.takeIf { it>0 },selectedVoice=episode.voice,
                catalogItem=video.catalogItem,playerIndex=video.playerIndex.takeIf { video.pinnedPlayer })}) {
            var current=video
            var selected=episode
            fun choices(entry:OnlineEpisode)=entry.alternatives[quality]?.takeIf { it.isNotEmpty() }
                ?: listOf(entry.sources[quality] ?: error("Качество недоступно"))
            val initial=choices(selected)
            val playable=try { CenterOnline.firstPlayable(initial,current.headers,::verifyOnlineUrl) } catch(first:Exception) {
                if(first is CancellationException)throw first
                val missing=generateSequence<Throwable>(first) { it.cause }
                    .any { it is OnlineMediaUnavailable && it.status==404 }
                if(!missing)throw first
                onlineCache.remove(video.identity.key)
                val fresh=(if(video.pinnedPlayer)resolveOnlinePlayer(video.item,video.playerIndex) else resolveOnline(video.item)).copy(catalogItem=video.catalogItem)
                check(fresh.item.key==video.item.key) { "Источник вернул другую карточку" }
                val match=fresh.episodes.singleOrNull { it.season==episode.season && it.number==episode.number && it.voice==episode.voice }
                    ?: error("Источник изменил список серий или озвучку. Обнови список")
                check(match.sources.containsKey(quality)) { "Выбранное качество исчезло. Выбери качество заново" }
                onlineCache[video.identity.key]=System.currentTimeMillis() to fresh
                if(choices(match)==initial && fresh.headers==video.headers)throw first
                current=fresh;selected=match
                // Exactly one refresh; never switch the selected episode, voice or quality.
                CenterOnline.firstPlayable(choices(match),fresh.headers,::verifyOnlineUrl)
            }
            val candidates=current.episodes.filter { it.season==selected.season && it.voice==selected.voice && it.number>0 && it.sources.containsKey(quality) }.sortedBy { it.number }
            val playlist=if(candidates.groupBy { it.number }.all { it.value.size==1 }) candidates.mapIndexed { index,entry ->
                val uri=if(entry.number==selected.number)playable else entry.sources.getValue(quality)
                com.lampplayer.tv.domain.model.EpisodeItem(index,entry.title,uri,entry.season.takeIf { it>0 },entry.number)
            } else emptyList()
            val meta=metadata(current.identity).apply {
                addProperty("content_key",current.identity.key)
                if(selected.number>0) { if(selected.season>0)addProperty("season_number",selected.season);addProperty("episode_number",selected.number) }
                addProperty("translator",selected.voice);addProperty("quality",quality.toString())
                addProperty("headers",Gson().toJson(CenterOnline.mediaHeaders(playable,current.headers)))
                if(playlist.isNotEmpty()) {
                    addProperty("episodes",Gson().toJson(playlist))
                    addProperty("episode_index",playlist.indexOfFirst { it.url==playable }.coerceAtLeast(0))
                }
            }
            val target=if(current!==video)previous?.copy(draw={showOnline(current,selected.season.takeIf { current.item.type=="tv" },selected.voice,false)}) else previous
            launchPlayer(playable,meta.toString(),target,online=true,onlinePlayerCount=current.playerCount,onlinePlayerIndex=current.playerIndex,onlinePinnedPlayer=current.pinnedPlayer)
        }
    }
    private fun episodeList(item: CenterTitle, season: Int) {
        work("Загружаю серии…") { showEpisodes(item, season, backend.episodes(item, season)) }
    }
    protected fun showEpisodes(item:CenterTitle,season:Int,episodes:List<CenterEpisode>) {
        seasonData[item.id to season]=episodes
        currentScreen={showEpisodes(item,season,episodes)};page="episodes";clear("${item.title} · Сезон $season")
        if(episodes.isEmpty()) { content.addView(text("Для этого сезона пока нет серий",18,muted));return }
        var selected=episodes.first()
        val summary=text("",15,muted).apply { maxLines=2;ellipsize=TextUtils.TruncateAt.END;tag="episode_summary" }
        content.addView(summary);space(content,10)
        fun describe(episode:CenterEpisode) {
            selected=episode
            summary.text=listOf(episode.name,episode.airDate,episode.runtime.takeIf { it>0 }?.let { "$it мин" }.orEmpty(),episode.overview).filter { it.isNotBlank() }.joinToString(" · ")
        }
        describe(selected)
        episodeFocus=episodeGrid(item,episodes.map { episode ->
            EpisodeChoice("episode:${episode.season}:${episode.number}","${episode.number}. ${episode.name}",episode.season,episode.number,
                action={enter { find(item,episode) }},onFocus={describe(episode)})
        },"torrent_episode_grid",(resources.configuration.screenHeightDp-220).coerceAtLeast(160)).second
        content.addView(button("Описание серии") {
            AlertDialog.Builder(this).setTitle("${selected.number}. ${selected.name}")
                .setMessage(selected.overview.ifBlank { "Описание серии пока отсутствует" }).setPositiveButton("Закрыть",null).show()
        })
    }
    private fun find(item: CenterTitle, episode: CenterEpisode?) {
        if (store.indexer.isBlank()) { sources { find(item, episode) }; return }
        work("Ищу раздачи…") {
            val searchItem = if(item.provider=="kazplay" && item.original.isNotBlank()) item.copy(title=item.original) else item
            val releases = backend.searchReleases(store.indexer, store.apiKey, searchItem, episode, store.indexerType)
            releaseList(item, episode, releases)
        }
    }
    protected fun releaseList(item: CenterTitle, episode: CenterEpisode?, releases: List<CenterRelease>, quality: String = "Все") {
        currentScreen = { releaseList(item, episode, releases, quality) }
        page = "releases"; clear("Раздачи · ${item.title}" + (episode?.let { " · S${it.season}E${it.number}" } ?: ""))
        fun redraw(focus: String) {
            releaseList(item, episode, releases, quality)
            content.post { findTag(content, focus)?.requestFocus() }
        }
        val controls = column().apply { visibility = if(releaseFiltersExpanded) View.VISIBLE else View.GONE; tag="release_filters_panel" }
        content.addView(iconButton("≡", "Фильтры раздач") {
            releaseFiltersExpanded = !releaseFiltersExpanded
            controls.visibility = if(releaseFiltersExpanded) View.VISIBLE else View.GONE
        }.apply { tag="release_filters_toggle" })
        content.addView(controls)
        val sorting = row()
        val sortLabel = when (store.releaseSort) { "small" -> "Меньше размер"; "large" -> "Больше размер"; else -> "Больше сидов" }
        sorting.addView(button("Порядок · $sortLabel") {
            val ids = listOf("seeds", "small", "large")
            AlertDialog.Builder(this).setTitle("Сортировка раздач")
                .setSingleChoiceItems(arrayOf("Больше сидов", "Меньше размер", "Больше размер"), ids.indexOf(store.releaseSort)) { dialog, index ->
                    store.releaseSort = ids[index]; dialog.dismiss(); redraw("release_sort")
                }.setNegativeButton("Отмена", null).show()
        }.apply { tag = "release_sort" }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(8) })
        sorting.addView(button(if (store.seededOnly) "Сиды > 0 · Вкл" else "Сиды > 0 · Выкл") {
            store.seededOnly = !store.seededOnly; redraw("release_seeded")
        }.apply { tag = "release_seeded" }, LinearLayout.LayoutParams(0, dp(48), 1f))
        controls.addView(sorting); space(controls, 10)
        val filters = row()
        listOf("Все", "2160", "1080", "720", "480").forEach { value -> filters.addView(button(value) { releaseList(item, episode, releases, value); content.post { findTag(content, "release_quality:$value")?.requestFocus() } }.apply { tag = "release_quality:$value"; isActivated = quality == value; paintButton(this, false) },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(8) }) }
        controls.addView(filters); space(controls, 12)
        controls.addView(button(if(store.exactReleases) "Точные совпадения · Вкл" else "Точные совпадения · Выкл") {
            store.exactReleases = !store.exactReleases; redraw("release_exact")
        }.apply { tag = "release_exact" })
        val visible = CenterBackend.selectReleases(releases, quality, store.releaseSort, store.seededOnly)
            .filter { !store.exactReleases || CenterBackend.matchesRelease(it, item, episode) }
            .sortedByDescending { CenterBackend.matchesRelease(it, item, episode) }
        content.addView(text("${quality} · ${sortLabel} · ${visible.size} / ${releases.size} раздач", 13, muted)); space(content, 10)
        if (visible.isEmpty()) content.addView(text("Нет раздач с выбранными фильтрами. Измени качество, отключи фильтр сидов или точные совпадения", 18, muted))
        visible.forEach { release ->
            content.addView(button("${release.title}\nРаздают: ${if (release.seedsKnown) release.seeds.toString() else "—"} · Качают: ${release.leechers?.toString() ?: "—"}\nБитрейт: ${release.bitrate ?: "—"} · ${size(release.size)} · ${release.tracker}") {
                enter { openRelease(item, episode, release) }
            }.apply {
                tag = "release:${release.link}"; layoutParams.height = dp(132); maxLines = 5
                if(release.voices.isNotEmpty()) text = text.toString() + "\nОзвучка: " + release.voices.joinToString(", ")
                setOnLongClickListener {
                    AlertDialog.Builder(this@MediaCenterActivity).setTitle(release.title)
                        .setMessage("Трекер: ${release.tracker}\nОзвучка: ${release.voices.joinToString(", ").ifBlank { "Не указана" }}\nФормат: ${CenterBackend.releaseFormat(release)}\nБитрейт: ${release.bitrate ?: "Не указан"}\nРазмер: ${size(release.size)}\nРаздают: ${if(release.seedsKnown) release.seeds.toString() else "—"} · Качают: ${release.leechers?.toString() ?: "—"}")
                        .setPositiveButton("Смотреть") { _, _ -> enter { openRelease(item, episode, release) } }
                        .setNegativeButton("Закрыть", null).show()
                    true
                }
            })
        }
        content.addView(button("Открыть свою magnet-ссылку / .torrent") { magnet(item, episode) })
    }
    private fun magnet(item: CenterTitle, episode: CenterEpisode?) {
        val edit = EditText(this).apply { hint = "magnet:?xt=… или https://…torrent"; isSingleLine = true; setTextColor(ink) }
        val dialog = AlertDialog.Builder(this).setTitle("Своя раздача").setView(edit).setNegativeButton("Отмена", null)
            .setPositiveButton("Открыть", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val link = edit.text.toString().trim()
            if (!CenterBackend.playableSource(link)) edit.error = "Нужна magnet-ссылка или HTTP-ссылка на .torrent"
            else { dialog.dismiss(); enter { openRelease(item, episode, CenterRelease(item.title, link, 0, 0, "Своя раздача")) } }
        } }
        dialog.show()
    }
    private fun openRelease(item: CenterTitle, requested: CenterEpisode?, release: CenterRelease) {
        work("Получаю файлы раздачи…") {
            torrentLoading(item, release.title, "Запускаю торрент-движок…")
            val base = CenterEngineService.ensure(this@MediaCenterActivity)
            torrentLoading(item, release.title, "Подключаюсь к раздаче и получаю файлы…")
            val torrent = backend.openRelease(base, release, item)
            val matches = if (requested == null) emptyList() else torrent.files.filter { it.episode() == (requested.season to requested.number) }
            if ((requested == null && torrent.files.size == 1) || matches.size == 1) {
                play(item, requested, base, torrent, matches.singleOrNull() ?: torrent.files.single())
            } else {
                showFiles(item, requested, base, torrent)
            }
        }
    }
    private fun showFiles(item: CenterTitle, requested: CenterEpisode?, base: String, torrent: CenterTorrent) {
        currentScreen = { showFiles(item, requested, base, torrent) }
        page = "files"; clear("Выбери файл · ${item.title}")
        if (requested != null) content.addView(text("Искомая серия: S${requested.season}E${requested.number}. Проверь имя файла", 16, muted))
        torrent.files.forEach { file -> content.addView(button("${file.name} · ${size(file.size)}") { play(item, requested, base, torrent, file) }.apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; layoutParams.height = dp(66)
        }) }
    }
    protected fun torrentLoading(item: CenterTitle, detail: String, stage: String): Pair<ProgressBar, TextView> {
        page = "torrent_loading"; clear("Готовим просмотр")
        val card = column().apply { setPadding(dp(28), dp(24), dp(28), dp(24)); background = bg(panel, Color.rgb(35, 58, 82), 20) }
        card.addView(text(item.title, 26).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        space(card, 10)
        card.addView(text(detail, 14, muted).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        space(card, 28)
        card.addView(text(stage, 18, accent))
        space(card, 18)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true; max = 100; tag = "torrent_progress"
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            indeterminateTintList = android.content.res.ColorStateList.valueOf(accent)
        }
        card.addView(bar, LinearLayout.LayoutParams(-1, dp(12)))
        space(card, 12)
        val status = text("Ожидаем ответ раздачи…", 14, muted).apply { tag = "torrent_status" }
        card.addView(status)
        space(card, 10)
        card.addView(text("Скорость · пиры · буфер: ожидаем данные движка", 14, muted).apply { tag = "torrent_metrics" })
        space(card, 24)
        card.addView(text("Загружается начало выбранного файла. Полный фильм скачивать не нужно.", 14, muted))
        content.addView(card, LinearLayout.LayoutParams(-1, -2))
        space(content, 18)
        content.addView(button("Отмена") { back() })
        return bar to status
    }
    private fun play(item: CenterTitle, requested: CenterEpisode?, base: String, torrent: CenterTorrent, file: CenterFile) {
        val returnTo = snapshot()
        work("Подготавливаю видео…") {
            val (bar, status) = torrentLoading(item, file.name, "Предзагрузка начала видео")
            val preparation = currentCoroutineContext()[Job]
            val poll = lifecycleScope.launch {
                while (isActive && preparation?.isActive == true && page == "torrent_loading") {
                    try {
                        val metrics = backend.torrentMetrics(base, torrent.hash)
                        (findTag(content, "torrent_metrics") as? TextView)?.text = metrics
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { (findTag(content, "torrent_metrics") as? TextView)?.text = "Статистика движка временно недоступна" }
                    delay(1000)
                }
            }
            try { backend.prepareStream(backend.stream(base, torrent.hash, file)) { bytes, total ->
                runOnUiThread {
                    if (preparation?.isActive == true && page == "torrent_loading") {
                        bar.isIndeterminate = false
                        bar.progress = ((bytes * 100) / total.coerceAtLeast(1)).toInt().coerceIn(0, 100)
                        status.text = "${bar.progress}% · ${bytes / 1024} / ${total / 1024} КБ"
                    }
                }
            }
            } finally { poll.cancelAndJoin() }
            currentCoroutineContext().ensureActive()
            launchTorrent(item, requested, base, torrent, file, returnTo)
        }
    }
    private fun launchTorrent(item: CenterTitle, requested: CenterEpisode?, base: String, torrent: CenterTorrent, file: CenterFile, returnTo: Screen?) {
        val url = backend.stream(base, torrent.hash, file)
        val number = file.episode() ?: requested?.let { it.season to it.number }
        val playlist = if (item.type == "tv") {
            val m3u = "#EXTM3U\n" + torrent.files.joinToString("\n") { backend.stream(base, torrent.hash, it) }
            TorrentEpisodeResolver.parse(url, m3u)
        } else null
        val meta = metadata(item).apply {
            if (item.type == "tv" && number != null) { addProperty("season_number", number.first); addProperty("episode_number", number.second) }
            if (playlist != null) { addProperty("episodes", Gson().toJson(playlist.episodes.map { entry ->
                val name = seasonData[item.id to (entry.season ?: 0)]?.firstOrNull { it.number == entry.episode }?.name
                if (name.isNullOrBlank()) entry else entry.copy(title = "${entry.episode}. $name")
            })); addProperty("episode_index", playlist.currentIndex) }
        }
        launchPlayer(url, meta.toString(), returnTo)
    }
    private fun resume(saved: CenterHistory) {
        work("Продолжаю ${saved.card.title}…") {
            if (!localTorrent(saved.url)) {
                val card = saved.card
                val key = card.contentKey?.split(':').orEmpty()
                val provider = key.firstOrNull()?.takeIf { CenterCatalog.isSiteIdentity(it) }
                val item = CenterTitle(key.getOrNull(2)?.toIntOrNull() ?: card.tmdbId ?: 0,
                    if (card.seasonNumber != null || key.getOrNull(1) == "tv") "tv" else "movie",
                    card.title, original = card.originalTitle.orEmpty(), overview = card.overview.orEmpty(),
                    poster = card.posterUrl, year=card.releaseYear?.toString().orEmpty(), provider = provider, sourceUrl = card.sourcePage)
                content.post {
                    if(provider==null||CenterOnline.onlineSupported(provider)) online(item,card,refresh=true)
                    else find(item, card.episodeNumber?.let { CenterEpisode(card.seasonNumber ?: 1, it, "", "") })
                }
                return@work
            }
            val base = CenterEngineService.ensure(this@MediaCenterActivity)
            val url = CenterStore.rebase(saved.url, base)
            val card = saved.card.copy(episodes = saved.card.episodes.map { it.copy(url = CenterStore.rebase(it.url, base)) })
            val meta = metadata(card).apply {
                addProperty("season_number", card.seasonNumber); addProperty("episode_number", card.episodeNumber)
                addProperty("episodes", Gson().toJson(card.episodes)); addProperty("episode_index", card.currentEpisodeIndex)
            }
            launchPlayer(url, meta.toString())
        }
    }
    private fun metadata(item: CenterTitle) = JsonObject().apply {
        addProperty("title", item.title); addProperty("original_title", item.original)
        if (item.provider == null) addProperty("tmdb_id", item.id) else addProperty("content_key", item.key)
        addProperty("source_page", item.sourceUrl); addProperty("poster_url", item.poster)
        addProperty("backdrop_url", item.backdrop); addProperty("overview", item.overview); addProperty("release_year", item.year.toIntOrNull())
    }
    private fun metadata(card: CardMeta) = JsonObject().apply {
        addProperty("title", card.title); addProperty("tmdb_id", card.tmdbId); addProperty("content_key", card.contentKey)
        addProperty("source_page", card.sourcePage); addProperty("poster_url", card.posterUrl); addProperty("backdrop_url", card.backdropUrl)
        addProperty("overview", card.overview); addProperty("release_year", card.releaseYear)
    }
    private fun localTorrent(url: String): Boolean = url.toHttpUrlOrNull()?.let { it.host == "127.0.0.1" && it.encodedPath.startsWith("/stream/") } == true
    private fun website(item: CenterTitle) {
        if(item.provider==null) {
            val source=CenterOnlineMatch.fromPage(item) ?: store.onlineSource(item)
            if(source==null) { showTmdbOnlineSources(item);return }
            launchWebsite(CenterOnline.source(source).toString(),metadata(item.copy(sourceUrl=source.sourceUrl)).toString(),source.provider)
            return
        }
        val url=runCatching { CenterOnline.source(item).toString() }.getOrElse {
            Toast.makeText(this,it.message ?: "Нет адреса плеера",Toast.LENGTH_SHORT).show();return
        }
        launchWebsite(url,metadata(item).toString(),item.provider)
    }
    private fun launchWebsite(url: String, metadata: String, provider:String?=null) {
        playerReturn = if (page == "loading" && backStack.isNotEmpty()) backStack.removeLast() else snapshot()
        startActivity(Intent(this, CenterWebActivity::class.java).putExtra("url", url).putExtra("lampa_data", metadata).putExtra("site_player",true).putExtra("catalog_provider",provider))
    }
    private fun launchPlayer(url: String, metadata: String, returnTo: Screen? = null, online: Boolean = false, onlinePlayerCount:Int=1,onlinePlayerIndex:Int=0,onlinePinnedPlayer:Boolean=false) {
        playerReturn = returnTo ?: if (page == "loading" && backStack.isNotEmpty()) backStack.removeLast() else snapshot()
        playerLauncher.launch(Intent(this, PlayerActivity::class.java).putExtra("media_center", true).putExtra("center_online",online).putExtra("center_player_count",onlinePlayerCount).putExtra("center_player_index",onlinePlayerIndex).putExtra("center_player_pinned",onlinePinnedPlayer).setAction(Intent.ACTION_VIEW)
            .setDataAndType(android.net.Uri.parse(url), "video/*").putExtra("lampa_data", metadata))
    }
    private fun sources(after: (() -> Unit)? = null) {
        val form = column().apply { setPadding(dp(24), dp(8), dp(24), 0) }
        form.addView(text("Адрес JacRed или полный адрес Torznab API. Можно использовать свой сервер поиска.", 16, muted))
        val type = Spinner(this).apply {
            adapter = ArrayAdapter(this@MediaCenterActivity, android.R.layout.simple_spinner_dropdown_item, listOf("JacRed", "Torznab / Jackett / Prowlarr"))
            setSelection(if (store.indexerType == "torznab") 1 else 0)
        }
        form.addView(type)
        val endpoint = EditText(this).apply { hint = "https://…"; setText(store.indexer); isSingleLine = true; setTextColor(ink); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        val key = EditText(this).apply { hint = "API-ключ, если нужен"; setText(store.apiKey); isSingleLine = true; setTextColor(ink); inputType = 129 }
        val status = text("Проверка отправляет пробный поисковый запрос", 14, muted)
        form.addView(endpoint); form.addView(key); form.addView(status)
        fun valid(): Boolean {
            if (endpoint.text.toString().trim().toHttpUrlOrNull() != null) return true
            endpoint.error = "Укажи полный адрес с http:// или https://"; return false
        }
        var probe: Job? = null
        val dialog = AlertDialog.Builder(this).setTitle("Источники").setView(form).setNegativeButton("Отмена", null)
            .setNeutralButton("Проверить", null).setPositiveButton("Сохранить", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if (!valid()) return@setOnClickListener
                probe?.cancel(); status.text = "Проверяю поиск…"
                val check = dialog.getButton(AlertDialog.BUTTON_NEUTRAL); check.isEnabled = false
                probe = lifecycleScope.launch {
                    try {
                        backend.searchReleases(endpoint.text.toString().trim(), key.text.toString().trim(),
                            CenterTitle(0, "movie", "Проверка соединения", "Connection check"), null,
                            if (type.selectedItemPosition == 1) "torznab" else "jacred")
                        status.text = "Источник ответил корректно. Настройки можно сохранить"
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { status.text = e.message ?: "Не удалось подключиться к источнику" }
                    finally { check.isEnabled = true }
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (valid()) {
                    store.indexer = endpoint.text.toString().trim(); store.apiKey = key.text.toString()
                    store.indexerType = if (type.selectedItemPosition == 1) "torznab" else "jacred"
                    dialog.dismiss(); after?.invoke()
                }
            }
        }
        dialog.setOnDismissListener { probe?.cancel() }; dialog.show()
    }
    private fun catalogSettings() {
        val form = column().apply { setPadding(dp(24), dp(10), dp(24), 0) }
        form.addView(text("Адрес сайта для каждого каталога. Зеркало должно сохранять его формат страниц.", 15, muted))
        val fields = CenterCatalog.sites.associateWith { provider ->
            EditText(this).apply {
                hint = CenterCatalog.name(provider); setText(store.catalogHost(provider)); isSingleLine = true
                setTextColor(ink); contentDescription = CenterCatalog.name(provider)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                form.addView(this)
            }
        }
        val container = ScrollView(this).apply { addView(form) }
        val dialog = AlertDialog.Builder(this).setTitle("Адреса каталогов").setView(container)
            .setNegativeButton("Отмена", null).setPositiveButton("Сохранить", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val invalid = fields.values.filter { it.text.toString().trim().toHttpUrlOrNull() == null }
            invalid.forEach { it.error = "Нужен полный http(s) адрес" }
            if (invalid.isNotEmpty()) invalid.first().requestFocus()
            else {
                fields.forEach { (provider, field) -> store.setCatalogHost(provider,field.text.toString()) }
                catalogs.clearCache(); dialog.dismiss()
            }
        } }; dialog.show()
    }
    private fun settings() {
        job?.cancel(); currentScreen = { settings() }; page = "settings"; clear("Настройки")
        content.addView(text("LampMedia · ${BuildConfig.VERSION_NAME}", 16, muted)); space(content, 18)
        content.addView(button("Размер карточек · " + when (store.cardSize) { "compact" -> "Компактные"; "large" -> "Крупные"; else -> "Обычные" }) {
            val sizes = listOf("compact", "normal", "large")
            AlertDialog.Builder(this).setTitle("Размер карточек")
                .setSingleChoiceItems(arrayOf("Компактные", "Обычные", "Крупные"), sizes.indexOf(store.cardSize)) { dialog, index ->
                    store.cardSize = sizes[index]; dialog.dismiss(); settings()
                }.setNegativeButton("Отмена", null).show()
        })
        content.addView(button("Каталоги · выбор и адреса сайтов") { catalogSettings() })
        content.addView(button("Источники поиска раздач") { sources() })
        content.addView(button("Плеер: движок, звук, субтитры, автопереход") { startActivity(Intent(this, SettingsActivity::class.java)) })
        content.addView(button("Автообновление · " + if (store.autoUpdates) "Вкл" else "Выкл") {
            store.autoUpdates = !store.autoUpdates
            if (!store.autoUpdates) automaticUpdate = null
            settings()
        })
        content.addView(text("При запуске проверяем новую версию. Установку подтверждает Android.", 13, muted))
        content.addView(button("Проверить обновление") { update() })
        content.addView(button("Торрент-движок · состояние и буфер") { enter { engineScreen() } })
        content.addView(button("Очистить историю просмотра") {
            AlertDialog.Builder(this).setTitle("Очистить историю?").setMessage("Избранное и настройки сохранятся")
                .setNegativeButton("Отмена", null).setPositiveButton("Очистить") { _, _ -> store.clearHistory(); Toast.makeText(this, "История очищена", Toast.LENGTH_SHORT).show() }.show()
        })
        content.addView(button("Лицензии и исходный код движка") {
            val license = assets.open("licenses/TorrServer-GPL-3.0.txt").bufferedReader().use { it.readText() } +
                "\n\nHTML parser: jsoup 1.18.3 · MIT\nhttps://jsoup.org\n\n" + assets.open("licenses/jsoup-MIT.txt").bufferedReader().use { it.readText() }
            AlertDialog.Builder(this).setTitle("TorrServer MatriX.148 · GPL-3.0")
                .setMessage("Исходный код и сборка: https://github.com/YouROK/TorrServer/tree/MatriX.148\n\n$license")
                .setPositiveButton("Закрыть", null).show()
        })
    }
    private fun engineScreen(start: Boolean = false) {
        if (CenterEngineService.base == null && !start) {
            currentScreen = { engineScreen() }; page = "engine"; clear("Торрент-движок")
            content.addView(text("Остановлен · автоматически запустится при открытии раздачи", 17, muted)); space(content, 18)
            content.addView(button("Запустить и настроить") { engineScreen(true) })
            return
        }
        work("Проверяю торрент-движок…") {
            val base = CenterEngineService.ensure(this@MediaCenterActivity)
            val config = backend.engineSettings(base)
            showEngine(config)
        }
    }
    private fun showEngine(config: JsonObject) {
        currentScreen = { engineScreen() }; page = "engine"; clear("Торрент-движок")
        val cache = (config.get("CacheSize")?.asLong ?: 0) / (1024 * 1024)
        content.addView(text("Работает · TorrServer MatriX.148", 18, accent)); space(content, 14)
        content.addView(text("Буфер в памяти: $cache МБ", 21)); space(content, 12)
        content.addView(text("Большой буфер помогает при нестабильной скорости загрузки, но увеличивает расход памяти. Это размер кэша TorrServer, а не всей памяти приложения.", 16, muted)); space(content, 12)
        content.addView(text("Изменение буфера переподключает раздачи. Применяй его между просмотрами.", 14, muted)); space(content, 18)
        val options = row()
        listOf(32, 64, 128, 256).forEach { mb -> options.addView(button("$mb МБ" + if (cache == mb.toLong()) " ✓" else "") {
            work("Меняю размер буфера…") {
                val base = CenterEngineService.ensure(this@MediaCenterActivity)
                backend.setEngineCache(base, mb); showEngine(backend.engineSettings(base))
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(8) }) }
        content.addView(options); space(content, 20)
        content.addView(button("Остановить движок и освободить буфер") { CenterEngineService.stop(this); engineScreen() })
    }
    private fun update(available: com.lampplayer.tv.update.UpdateInfo? = null) {
        work("Проверяю обновление…") {
            val update = available ?: UpdateManager.check(BuildConfig.VERSION_CODE)
            if (update == null) { settings(); Toast.makeText(this@MediaCenterActivity, "Установлена последняя версия", Toast.LENGTH_SHORT).show() }
            else {
                val file = UpdateManager.download(this@MediaCenterActivity, update.url, update.sha256) ?: error("Загрузка обновления не удалась")
                check(UpdateManager.install(this@MediaCenterActivity, file)) { "Не удалось установить обновление" }
                settings()
            }
        }
    }
    private fun size(bytes: Long): String = when {
        bytes <= 0 -> "размер неизвестен"
        bytes < 1073741824L -> "%.0f МБ".format(bytes / 1048576.0)
        else -> "%.1f ГБ".format(bytes / 1073741824.0)
    }
}
