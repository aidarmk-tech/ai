package com.lampplayer.tv.center

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
    private var forceQualityOnce = false
    private var recovery: Pair<String, CardMeta>? = null
    private val playerLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_FIRST_USER) {
            val action = result.data?.getStringExtra("recovery")
            val card = runCatching { Gson().fromJson(result.data?.getStringExtra("card"), CardMeta::class.java) }.getOrNull()
            if (action in listOf("refresh", "quality", "torrent") && card != null) recovery = action!! to card
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
    private fun enter(action: () -> Unit) { snapshot()?.let { backStack.addLast(it) }; action() }
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
        job?.cancel()
        if (backStack.isNotEmpty()) restore(backStack.removeLast())
        else if (nav != "Главная") navigate("Главная")
        else finish()
    }
    private fun highlightNavigation() {
        for (i in 0 until navigation.childCount) navigation.getChildAt(i).let {
            if (it is TextView && it.isFocusable) { it.isActivated = it.tag == nav; paintButton(it, it.hasFocus()) }
        }
    }
    private fun navigate(label: String) {
        if (label == "Источники") { sources(); return }
        job?.cancel(); backStack.clear(); currentScreen = null; selected = null; nav = label
        highlightNavigation()
        when (label) {
            "Поиск" -> searchScreen()
            "Настройки" -> settings()
            "История" -> historyScreen()
            "Избранное" -> showHome(store.favorites(), "Избранное")
            else -> loadHome()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = CenterStore(this)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(8, 13, 22)); setPadding(dp(22), dp(22), dp(18), dp(14)) }
        navigation = column().apply { setPadding(0, 0, dp(22), 0) }
        navigation.addView(text("LampMedia", 24, accent).apply { typeface = Typeface.DEFAULT_BOLD })
        space(navigation, 24)
        listOf("Главная", "Фильмы", "Сериалы", "Поиск", "Избранное", "История", "Источники", "Настройки").forEach { label ->
            navigation.addView(button(label) { navigate(label); closeMenu() }.apply {
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(-1, dp(43)).apply { bottomMargin = dp(6) }
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
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && navigation.visibility != View.VISIBLE) {
            val focused = window.decorView.findFocus()
            val point = IntArray(2); val edge = IntArray(2)
            focused?.getLocationOnScreen(point); content.getLocationOnScreen(edge)
            if (focused === menuHandle || (inside(content, focused) &&
                (point[0] <= edge[0] + dp(24) || !inside(content, focused?.focusSearch(View.FOCUS_LEFT))))) {
                openMenu(); return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onResume() {
        super.onResume()
        playerReturn?.let { playerReturn = null; restore(it) }
        recovery?.let { (action, card) ->
            recovery = null
            content.post {
                val parts = card.contentKey?.split(':').orEmpty()
                val provider = parts.firstOrNull()?.takeIf { it in listOf("filmix", "kinovod") }
                val item = CenterTitle(parts.getOrNull(2)?.toIntOrNull() ?: card.tmdbId ?: 0,
                    if (card.seasonNumber != null || parts.getOrNull(1) == "tv") "tv" else "movie",
                    card.title, original = card.originalTitle.orEmpty(), provider = provider, sourceUrl = card.sourcePage)
                enter {
                    when {
                        action == "torrent" -> find(item, card.episodeNumber?.let { CenterEpisode(card.seasonNumber ?: 1, it, "", "") })
                        provider != null && action == "refresh" -> online(item, card, refresh = true)
                        provider != null -> { forceQualityOnce = true; online(item, refresh = true, selectedSeason = card.seasonNumber, selectedVoice = card.translator) }
                        else -> find(item, null)
                    }
                }
            }
        }
    }
    private fun clear(title: String) {
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
    private fun work(message: String, block: suspend () -> Unit) {
        val focusContent = content.hasFocus()
        job?.cancel(); page = "loading"; clear(message)
        content.addView(ProgressBar(this), LinearLayout.LayoutParams(dp(40), dp(40)))
        space(content, 16); content.addView(button("Назад") { back() })
        job = lifecycleScope.launch {
            try {
                block()
                if (focusContent) content.post { content.getFocusables(View.FOCUS_FORWARD).firstOrNull { it.isShown && it.isEnabled }?.requestFocus() }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                page = "error"; clear("Не удалось загрузить")
                content.addView(text(e.message ?: "Проверь соединение и настройки источника", 17, muted)); space(content, 20)
                content.addView(button("Повторить") { work(message, block) })
                content.addView(button("Выбрать каталог") { chooseCatalog() })
                content.addView(button("Поиск торрентов") { sources() })
                content.addView(button("Назад") { back() })
            }
        }
    }
    protected open fun loadHome() {
        selected = null; catalogPage = 1
        if (nav != "Главная") { loadCategory(CenterSection.all.first { it.id == if (nav == "Сериалы") "new_episodes" else "new_movies" }); return }
        work("Загружаю ${CenterCatalog.name(store.catalog)}…") {
            val provider = store.catalog; val limiter = Semaphore(3)
            val results = coroutineScope { CenterSection.all.map { section -> async {
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
    private fun catalogTools() {
        val tools = row()
        tools.addView(button("Каталог: ${CenterCatalog.name(store.catalog)}  ▾") { chooseCatalog() }, LinearLayout.LayoutParams(0, dp(43), 1.2f).apply { rightMargin = dp(9) })
        tools.addView(button("Разделы  ▾") {
            AlertDialog.Builder(this).setTitle("Что посмотреть").setItems(CenterSection.all.map { it.label }.toTypedArray()) { _, index ->
                enter { loadCategory(CenterSection.all[index]) }
            }.show()
        }, LinearLayout.LayoutParams(0, dp(43), 1f))
        content.addView(tools); space(content, 16)
    }
    private fun chooseCatalog() {
        val ids = listOf("tmdb", "kinovod", "filmix")
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
            content.addView(text("TMDB показывает сведения о премьерах и эфире. Для обновлений доступных видео выбери Киновод или Filmix.", 13, muted)); space(content, 14)
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
    protected fun showHome(items: List<CenterTitle>, heading: String = "Популярное сейчас", pageNumber: Int = catalogPage, category: CenterSection? = null) {
        catalogPage = pageNumber
        currentScreen = { showHome(items, heading, pageNumber, category) }; page = "home"; clear(heading)
        if (category != null) catalogTools()
        if (items.isEmpty()) content.addView(text(if (heading == "Избранное") "Здесь появятся фильмы и сериалы, которые ты добавишь в избранное" else "Ничего не найдено", 17, muted))
        if (heading == "Популярное сейчас") items.firstOrNull()?.let { featured(it) }
        if (heading == "Популярное сейчас" && store.history().isNotEmpty()) {
            section("Продолжить просмотр"); historyCards(store.history().take(8)); space(content, 14)
        }
        if (heading == "Популярное сейчас") {
            listOf("movie" to "Фильмы", "tv" to "Сериалы").forEach { (type, label) ->
                val part = items.filter { it.type == type }
                if (part.isNotEmpty()) { section(label, part.size); shelf(part) }
            }
        } else if (items.isNotEmpty()) shelf(items)
        if (heading != "Избранное" && heading != "Результаты поиска") content.addView(button("Следующая подборка   ›") {
            val type = when (nav) { "Фильмы" -> "movie"; "Сериалы" -> "tv"; else -> "all" }
            val nextPage = catalogPage + 1
            enter {
                if (category != null) loadCategory(category, nextPage)
                else work("Загружаю следующую страницу…") { val next = backend.browse(type, page = nextPage); showHome(next, heading, nextPage) }
            }
        })
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
        title = item.title, contentKey = item.key.takeIf { item.provider != null }, tmdbId = item.id.takeIf { item.provider == null },
        seasonNumber = season?.takeIf { it > 0 }, episodeNumber = episode?.takeIf { it > 0 })
    private fun episodeProgress(view: TextView, item: CenterTitle, season: Int, episode: Int, url: String) {
        if (episode <= 0) return
        val label = view.text.toString()
        val last = store.history().firstOrNull { (it.card.contentKey ?: "tmdb:${if (it.card.seasonNumber != null) "tv" else "movie"}:${it.card.tmdbId}") == item.key }?.card
        val current = last?.seasonNumber == season && last.episodeNumber == episode
        lifecycleScope.launch {
            val position = PositionDataStore(applicationContext).getPosition(url, progressCard(item, season, episode))
            val prefix = if (position?.watched == true) "✓ " else if (current) "▶ " else ""
            view.text = prefix + label + if (position != null && position.time > 0) " · ${clock(position.time)} · ${position.percent}%" else ""
            if (current && position?.watched != true) view.setTextColor(accent)
        }
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
                holder.art.removeAllViews(); holder.art.addView(artFrame(item.title, width, artHeight, item.poster, item.id), FrameLayout.LayoutParams(-1, -1))
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
        }
        content.addView(query, LinearLayout.LayoutParams(-1, dp(60)))
        fun search() {
            val term = query.text.toString().trim()
            if (term.length < 2) { query.error = "Введи хотя бы две буквы"; return }
            currentScreen = { searchScreen(term) }
            enter { work("Ищу «$term»…") { showHome(if (store.catalog == "tmdb") backend.browse(query = term) else catalogs.search(store.catalog, store.catalogHost(store.catalog), term), "Результаты поиска") } }
        }
        query.setOnEditorActionListener { _, action, event ->
            if (action == EditorInfo.IME_ACTION_SEARCH || event?.let { it.keyCode == KeyEvent.KEYCODE_ENTER && it.action == KeyEvent.ACTION_DOWN } == true) { search(); true } else false
        }
        content.addView(button("Найти") { search() }); query.requestFocus()
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
        copy.addView(text(facts(movie), 15, accent)); space(copy, 15)
        val saved = store.history().firstOrNull { (it.card.contentKey ?: "tmdb:${movie.type}:${it.card.tmdbId}") == movie.key }
        if (saved != null) {
            val continueButton = button("Продолжить") { enter { resume(saved) } }.apply { tag = "continue_title"; maxLines = 2; layoutParams.height = dp(62) }
            copy.addView(continueButton)
            lifecycleScope.launch {
                val pos = PositionDataStore(applicationContext).getPosition(saved.url, saved.card)
                continueButton.text = "Продолжить" +
                    (saved.card.seasonNumber?.let { " · Сезон $it" } ?: "") +
                    (saved.card.episodeNumber?.let { " · Серия $it" } ?: "") +
                    (pos?.let { " · ${clock(it.time)}" } ?: "")
            }
        }
        if (movie.provider != null) {
            copy.addView(button("Смотреть онлайн · ${CenterCatalog.name(movie.provider)}") { enter { online(movie) } })
            copy.addView(button("Найти торрент-раздачу") { enter { find(movie, null) } })
            movie.updateLabel?.let { copy.addView(text(it, 15, accent)); space(copy, 10) }
        } else if (movie.type == "tv") {
            copy.addView(text("Сезоны", 20)); space(copy, 9)
            val seasons = row()
            details.seasons.forEach { season -> seasons.addView(button("Сезон $season") { enter { episodeList(movie, season) } },
                LinearLayout.LayoutParams(dp(120), dp(44)).apply { rightMargin = dp(9) }) }
            copy.addView(HorizontalScrollView(this).apply { addView(seasons); isHorizontalScrollBarEnabled = false })
            if (details.seasons.isEmpty()) copy.addView(text("Сезоны пока не опубликованы", 15, muted))
        } else copy.addView(button("Смотреть · выбрать раздачу") { enter { find(movie, null) } })
        space(copy, 7)
        copy.addView(button(if (store.isFavorite(movie)) "★  В избранном · убрать" else "☆  Добавить в избранное") {
            store.toggle(movie); showDetails(details)
            content.post { findTag(content, "favorite")?.requestFocus() }
        }.apply { tag = "favorite" })
        copy.addView(button(if (store.isPinned(movie)) "Открепить с главной" else "Закрепить на главной") {
            store.pin(movie); showDetails(details); content.post { findTag(content, "pin")?.requestFocus() }
        }.apply { tag = "pin" })
        if (movie.provider != null) copy.addView(button("Открыть страницу источника вручную") { website(movie) })
        copy.addView(button("Открыть свою magnet-ссылку / .torrent") { magnet(movie, null) })
        space(copy, 12)
        copy.addView(text("Описание", 18)); space(copy, 9)
        copy.addView(text(movie.overview.ifBlank { "Описание пока отсутствует" }, 16, muted))
        if (details.actors.isNotBlank()) { space(copy, 18); copy.addView(text("В ролях", 18)); space(copy, 8); copy.addView(text(details.actors, 15, muted)) }
        body.addView(copy, LinearLayout.LayoutParams(0, -2, 1f)); content.addView(body)
    }
    protected open suspend fun resolveOnline(item: CenterTitle): OnlineVideo = CenterOnline(this).resolve(item)
    private fun online(item: CenterTitle, resume: CardMeta? = null, refresh: Boolean = false, returnTo: Screen? = null,
        selectedSeason: Int? = null, selectedVoice: String? = null) {
        val previous = returnTo ?: if (resume != null && page == "online") snapshot() else null
        work("Получаю список серий · ${CenterCatalog.name(item.provider)}…") {
            val cached = onlineCache[item.key]?.takeIf { System.currentTimeMillis() - it.first < 180000 && !refresh }?.second
            val video = cached ?: resolveOnline(item).also {
                if (onlineCache.size >= 4) onlineCache.remove(onlineCache.keys.first())
                onlineCache[item.key] = System.currentTimeMillis() to it
            }
            if (resume != null) {
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
            showOnline(video, season, voice ?: store.voice(video.item.key)?.takeIf { saved -> video.episodes.any { (season == null || it.season == season) && it.voice == saved } })
        }
    }
    protected fun showOnline(video: OnlineVideo, season: Int? = null, voice: String? = null, focusFirst: Boolean = true) {
        val hadFocus = content.hasFocus()
        currentScreen = { showOnline(video, season, voice, false) }; page = "online"; clear(video.item.title)
        val entries = video.episodes.filter { season == null || it.season == season }
        val voices = entries.map { it.voice }.distinct()
        val selectedVoice = voice ?: voices.singleOrNull()
        content.addView(text(listOfNotNull(CenterCatalog.name(video.item.provider),
            season?.takeIf { it > 0 }?.let { "Сезон $it" }, selectedVoice).joinToString(" · "), 15, muted)); space(content, 16)
        val seasons = video.episodes.map { it.season }.distinct().sorted()
        if (video.item.type == "tv" && season == null) {
            seasons.forEach { value -> content.addView(button(if(value>0) "Сезон $value" else "Серии") { enter { showOnline(video, value, store.voice(video.item.key)?.takeIf { saved -> video.episodes.any { it.season == value && it.voice == saved } }) } }) }
        } else {
            if (selectedVoice == null && voices.size > 1) {
                section("Озвучка")
                voices.forEach { value -> content.addView(button(value) { store.setVoice(video.item.key, value); enter { showOnline(video, season, value) } }) }
            } else {
                if (voices.size > 1) content.addView(button("Изменить озвучку") {
                    AlertDialog.Builder(this).setTitle("Озвучка").setItems(voices.toTypedArray()) { _, index ->
                        store.setVoice(video.item.key, voices[index]); showOnline(video, season, voices[index])
                    }.setNeutralButton("Сбросить выбор") { _, _ -> store.setVoice(video.item.key, null); showOnline(video, season) }
                        .setNegativeButton("Отмена", null).show()
                })
                content.addView(text("Удерживай ОК на серии или фильме, чтобы изменить качество", 13, muted)); space(content, 8)
                entries.filter { it.voice == selectedVoice }.forEach { episode ->
                    val label = if (episode.number > 0) "Серия ${episode.number} · ${episode.title}" else "Смотреть · ${episode.voice}"
                    content.addView(button(label) { chooseOnlineQuality(video, episode) }.apply {
                        tag = "online:${episode.season}:${episode.number}:${episode.voice}"; maxLines = 2; layoutParams.height = dp(62)
                        setOnLongClickListener { chooseOnlineQuality(video, episode, forceChoice = true); true }
                        episodeProgress(this, video.item, episode.season, episode.number, episode.sources.values.firstOrNull().orEmpty())
                    })
                }
            }
        }
        content.addView(button("Обновить список и ссылки") { online(video.item, refresh = true, selectedSeason = season, selectedVoice = selectedVoice) })
        if (focusFirst && hadFocus) content.post { content.getFocusables(View.FOCUS_FORWARD).firstOrNull()?.requestFocus() }
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
    private fun playOnline(video: OnlineVideo, episode: OnlineEpisode, quality: Int, returnTo: Screen? = null) {
        if (System.currentTimeMillis() - video.fetchedAt > 180000) {
            online(video.item, CardMeta(video.item.title, seasonNumber = episode.season.takeIf { it>0 }, episodeNumber = episode.number,
                translator = episode.voice, quality = quality.toString()), refresh = true, returnTo = returnTo ?: snapshot()); return
        }
        val url = episode.sources[quality] ?: return
        val candidates = video.episodes.filter { it.season == episode.season && it.voice == episode.voice && it.number > 0 && it.sources.containsKey(quality) }.sortedBy { it.number }
        val playlist = if (candidates.groupBy { it.number }.all { it.value.size == 1 }) candidates.mapIndexed { index, entry ->
            com.lampplayer.tv.domain.model.EpisodeItem(index, entry.title, entry.sources.getValue(quality), entry.season.takeIf { it>0 }, entry.number)
        } else emptyList()
        val meta = metadata(video.item).apply {
            if (episode.number > 0) { if(episode.season>0)addProperty("season_number", episode.season); addProperty("episode_number", episode.number) }
            addProperty("translator", episode.voice); addProperty("quality", quality.toString())
            addProperty("headers", Gson().toJson(video.headers))
            if (playlist.isNotEmpty()) {
                addProperty("episodes", Gson().toJson(playlist))
                addProperty("episode_index", playlist.indexOfFirst { it.url == url }.coerceAtLeast(0))
            }
        }
        launchPlayer(url, meta.toString(), returnTo)
    }
    private fun episodeList(item: CenterTitle, season: Int) {
        work("Загружаю серии…") { showEpisodes(item, season, backend.episodes(item, season)) }
    }
    protected fun showEpisodes(item: CenterTitle, season: Int, episodes: List<CenterEpisode>) {
        seasonData[item.id to season] = episodes
        currentScreen = { showEpisodes(item, season, episodes) }; page = "episodes"; clear("${item.title} · Сезон $season")
        if (episodes.isEmpty()) { content.addView(text("Для этого сезона пока нет серий", 18, muted)); return }
        val columns = row()
        val list = column()
        val info = column().apply { setPadding(dp(22), 0, 0, 0) }
        fun describe(episode: CenterEpisode) {
            info.removeAllViews()
            info.addView(artFrame(episode.name, 290, 160, episode.still ?: item.backdrop, episode.number), LinearLayout.LayoutParams(-1, dp(160)))
            space(info, 14); info.addView(text(episode.name, 21).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }); space(info, 9)
            info.addView(text(listOfNotNull(episode.airDate.takeIf { it.isNotBlank() }, episode.runtime.takeIf { it > 0 }?.let { "$it мин" }).joinToString(" · "), 13, accent))
            space(info, 12); info.addView(text(episode.overview.ifBlank { "Описание серии пока отсутствует" }, 15, muted).apply { maxLines = 8; ellipsize = TextUtils.TruncateAt.END })
        }
        episodes.forEach { episode ->
            list.addView(button("${episode.number}. ${episode.name}") { enter { find(item, episode) } }.apply {
                episodeProgress(this, item, episode.season, episode.number, "")
                tag = "episode:${episode.season}:${episode.number}"; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                layoutParams.height = dp(62)
                setOnFocusChangeListener { v, focus -> paintButton(v, focus); if (focus) describe(episode) }
            })
        }
        describe(episodes.first())
        val episodeScroll = ScrollView(this).apply { addView(list); isFillViewport = true }
        columns.addView(episodeScroll, LinearLayout.LayoutParams(0, dp((resources.configuration.screenHeightDp - 120).coerceAtLeast(240)), 1.12f))
        columns.addView(info, LinearLayout.LayoutParams(0, -2, 1f)); content.addView(columns)
    }
    private fun find(item: CenterTitle, episode: CenterEpisode?) {
        if (store.indexer.isBlank()) { sources { find(item, episode) }; return }
        work("Ищу раздачи…") {
            val releases = backend.searchReleases(store.indexer, store.apiKey, item, episode, store.indexerType)
            releaseList(item, episode, releases)
        }
    }
    private fun releaseList(item: CenterTitle, episode: CenterEpisode?, releases: List<CenterRelease>, quality: String = "Все") {
        currentScreen = { releaseList(item, episode, releases, quality) }
        page = "releases"; clear("Раздачи · ${item.title}" + (episode?.let { " · S${it.season}E${it.number}" } ?: ""))
        val filters = row()
        listOf("Все", "2160", "1080", "720").forEach { value -> filters.addView(button(value) { releaseList(item, episode, releases, value) },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(8) }) }
        content.addView(filters); space(content, 12)
        val visible = releases.filter { quality == "Все" || it.title.contains(quality) }
        if (visible.isEmpty()) content.addView(text("Раздачи не найдены. Измени качество или проверь источник", 18, muted))
        visible.forEach { release ->
            content.addView(button("${release.title}\nСиды: ${release.seeds} · ${size(release.size)} · ${release.tracker}") {
                enter { openRelease(item, episode, release) }
            }.apply { layoutParams.height = dp(86); maxLines = 3 })
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
            if (saved.card.sourcePage != null && !localTorrent(saved.url)) {
                val key = saved.card.contentKey?.split(':').orEmpty()
                val provider = key.getOrNull(0); val type = key.getOrNull(1); val id = key.getOrNull(2)?.toIntOrNull()
                check(provider in listOf("kinovod", "filmix") && type in listOf("movie", "tv") && id != null) { "Открой карточку фильма в каталоге" }
                // Launch a fresh resolver outside this work coroutine; never reuse an expired website stream.
                content.post { online(CenterTitle(id!!, type!!, saved.card.title, overview = saved.card.overview.orEmpty(),
                    poster = saved.card.posterUrl, provider = provider, sourceUrl = saved.card.sourcePage), saved.card) }
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
        val url = item.sourceUrl ?: return
        store.remember(url, CardMeta(title = item.title, contentKey = item.key, sourcePage = url,
            posterUrl = item.poster, overview = item.overview, releaseYear = item.year.toIntOrNull()))
        launchWebsite(url, metadata(item).toString())
    }
    private fun launchWebsite(url: String, metadata: String) {
        playerReturn = if (page == "loading" && backStack.isNotEmpty()) backStack.removeLast() else snapshot()
        startActivity(Intent(this, CenterWebActivity::class.java).putExtra("url", url).putExtra("lampa_data", metadata))
    }
    private fun launchPlayer(url: String, metadata: String, returnTo: Screen? = null) {
        playerReturn = returnTo ?: if (page == "loading" && backStack.isNotEmpty()) backStack.removeLast() else snapshot()
        playerLauncher.launch(Intent(this, PlayerActivity::class.java).putExtra("media_center", true).setAction(Intent.ACTION_VIEW)
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
        form.addView(text("Укажи действующее зеркало сайта. Формат страниц должен соответствовать Киноводу / Filmix.", 15, muted))
        val kv = EditText(this).apply { hint = "Киновод"; setText(store.catalogHost("kinovod")); isSingleLine = true; setTextColor(ink) }
        val fx = EditText(this).apply { hint = "Filmix"; setText(store.catalogHost("filmix")); isSingleLine = true; setTextColor(ink) }
        form.addView(kv); form.addView(fx)
        val dialog = AlertDialog.Builder(this).setTitle("Адреса каталогов").setView(form).setNegativeButton("Отмена", null).setPositiveButton("Сохранить", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (kv.text.toString().trim().toHttpUrlOrNull() == null) kv.error = "Нужен полный http(s) адрес"
            else if (fx.text.toString().trim().toHttpUrlOrNull() == null) fx.error = "Нужен полный http(s) адрес"
            else { store.setCatalogHost("kinovod", kv.text.toString()); store.setCatalogHost("filmix", fx.text.toString()); catalogs.clearCache(); dialog.dismiss() }
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
    private fun update() {
        work("Проверяю обновление…") {
            val update = UpdateManager.check(BuildConfig.VERSION_CODE)
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
