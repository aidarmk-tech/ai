package com.lampplayer.tv.center

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.lampplayer.tv.BuildConfig
import com.lampplayer.tv.data.datastore.PositionDataStore
import com.lampplayer.tv.domain.model.CardMeta
import com.lampplayer.tv.player.IntentParser
import com.lampplayer.tv.player.PlayerActivity
import com.lampplayer.tv.player.TorrentEpisodeResolver
import com.lampplayer.tv.settings.SettingsActivity
import com.lampplayer.tv.update.UpdateManager
import kotlinx.coroutines.*

/** Native TV catalog; all playback and episode navigation use the shared player. */
open class MediaCenterActivity : AppCompatActivity() {
    protected val backend = CenterBackend()
    private lateinit var store: CenterStore
    private lateinit var content: LinearLayout
    private lateinit var navigation: LinearLayout
    private var job: Job? = null
    private var page = "home"
    private var nav = "Главная"
    private var selected: CenterTitle? = null
    private var returnedFromPlayer = false
    private val ink = Color.rgb(242, 246, 252)
    private val muted = Color.rgb(164, 180, 202)
    private val accent = Color.rgb(136, 196, 255)
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun bg(color: Int, stroke: Int = Color.TRANSPARENT, radius: Int = 14) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); setStroke(dp(2), stroke)
    }
    private fun text(value: String, size: Int = 16, color: Int = ink) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); includeFontPadding = false
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun button(label: String, action: () -> Unit) = text(label, 16).apply {
        id = View.generateViewId(); gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12)); isFocusable = true; isClickable = true
        background = bg(Color.rgb(25, 34, 49))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { bottomMargin = dp(8) }
        setOnClickListener { action() }
        setOnFocusChangeListener { v, focus ->
            v.background = bg(if (focus) Color.rgb(37, 59, 87) else Color.rgb(25, 34, 49), if (focus) accent else Color.TRANSPARENT)
        }
    }
    private fun space(parent: LinearLayout, n: Int) { parent.addView(View(this), LinearLayout.LayoutParams(1, dp(n))) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = CenterStore(this)
        val root = row().apply { setBackgroundColor(Color.rgb(9, 14, 23)); setPadding(dp(24), dp(24), dp(24), dp(16)) }
        navigation = column().apply { setPadding(0, 0, dp(24), 0) }
        navigation.addView(text("LampMedia", 25, accent).apply { typeface = Typeface.DEFAULT_BOLD })
        space(navigation, 26)
        listOf("Главная", "Фильмы", "Сериалы", "Поиск", "Избранное", "Источники", "Настройки").forEach { label ->
            navigation.addView(button(label) {
                nav = label
                when (label) {
                    "Поиск" -> searchScreen()
                    "Источники" -> sources()
                    "Настройки" -> settings()
                    "Избранное" -> showHome(store.favorites(), "Избранное")
                    else -> loadHome()
                }
            })
        }
        root.addView(navigation, LinearLayout.LayoutParams(dp(185), -1))
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        content = column().apply { setPadding(dp(8), 0, dp(8), dp(24)) }
        scroll.addView(content, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(0, -1, 1f))
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if ((page == "home" || (page == "loading" && selected == null)) && nav == "Главная") { job?.cancel(); finish() }
                else { job?.cancel(); nav = "Главная"; loadHome() }
            }
        })
        navigation.getChildAt(2)?.requestFocus()
        loadHome()
    }
    override fun onResume() {
        super.onResume()
        if (returnedFromPlayer) { returnedFromPlayer = false; nav = "Главная"; loadHome() }
    }
    private fun clear(title: String) {
        content.removeAllViews()
        content.addView(text(title, 29).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
        space(content, 18)
    }
    private fun work(message: String, block: suspend () -> Unit) {
        val focusContent = content.hasFocus()
        job?.cancel(); page = "loading"; clear(message)
        content.addView(text("Можно вернуться назад", 14, muted))
        content.addView(button("Назад") { job?.cancel(); nav = "Главная"; loadHome() })
        job = lifecycleScope.launch {
            try {
                block()
                if (focusContent) content.post { content.getFocusables(View.FOCUS_FORWARD).firstOrNull { it.isShown && it.isEnabled }?.requestFocus() }
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                page = "error"; clear("Не удалось загрузить")
                content.addView(text(e.message ?: "Проверь соединение и настройки источника", 17, muted))
                space(content, 20)
                content.addView(button("Источники") { sources() })
                content.addView(button("На главную") { nav = "Главная"; loadHome() })
            }
        }
    }
    protected open fun loadHome() {
        selected = null
        catalogPage = 1
        val type = when (nav) { "Фильмы" -> "movie"; "Сериалы" -> "tv"; else -> "all" }
        work("Загружаю каталог…") { showHome(backend.browse(type), if (type == "all") "Популярное сейчас" else nav) }
    }
    protected fun showHome(items: List<CenterTitle>, heading: String = "Популярное сейчас") {
        page = "home"; clear(heading)
        if (items.isEmpty()) content.addView(text("Ничего не найдено", 18, muted))
        if (heading == "Популярное сейчас") {
            val history = store.history()
            if (history.isNotEmpty()) {
                content.addView(text("Продолжить просмотр", 21))
                history.take(5).forEach { saved ->
                    val episode = saved.card.episodeNumber?.let { " · S${saved.card.seasonNumber ?: 1} E$it" }.orEmpty()
                    val resumeButton = button(saved.card.title + episode) { resume(saved) }
                    content.addView(resumeButton)
                    lifecycleScope.launch {
                        val progress = PositionDataStore(applicationContext).getPosition(saved.url, saved.card)
                        if (progress != null && progress.time > 0)
                            resumeButton.text = saved.card.title + episode + " · ${progress.time.toInt() / 60}:${(progress.time.toInt() % 60).toString().padStart(2, '0')}"
                    }
                }
                space(content, 16)
            }
        }
        items.firstOrNull()?.let { item ->
            val hero = row().apply { background = bg(Color.rgb(19, 29, 44)); setPadding(dp(18), dp(18), dp(18), dp(18)) }
            val art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = bg(Color.rgb(32, 47, 67)) }
            hero.addView(art, LinearLayout.LayoutParams(dp(235), dp(150)))
            Glide.with(this).load(item.backdrop ?: item.poster).into(art)
            val copy = column().apply { setPadding(dp(22), 0, 0, 0) }
            copy.addView(text(item.title, 27).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            space(copy, 7); copy.addView(text("${if (item.type == "tv") "Сериал" else "Фильм"} · ${item.year} · ★ ${item.rating}", 14, muted))
            space(copy, 9); copy.addView(text(item.overview, 14, muted).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            space(copy, 9); copy.addView(button("Открыть карточку") { detail(item) })
            hero.addView(copy, LinearLayout.LayoutParams(0, -2, 1f)); content.addView(hero)
            space(content, 22)
        }
        shelf(items)
        val next = button("Ещё") {
            val type = when (nav) { "Фильмы" -> "movie"; "Сериалы" -> "tv"; else -> "all" }
            work("Загружаю следующую страницу…") { showHome(backend.browse(type, page = ++catalogPage), heading) }
        }
        if (heading != "Избранное" && heading != "Результаты поиска") content.addView(next)
    }
    private var catalogPage = 1
    private fun shelf(items: List<CenterTitle>) {
        val rail = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MediaCenterActivity, LinearLayoutManager.HORIZONTAL, false)
            setPadding(dp(4), dp(8), dp(4), dp(8)); clipToPadding = false; itemAnimator = null
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        rail.adapter = object : RecyclerView.Adapter<Poster>() {
            override fun getItemCount() = items.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Poster {
                val view = column().apply {
                    isFocusable = true; isClickable = true; background = bg(Color.rgb(20, 29, 43))
                    setPadding(dp(7), dp(7), dp(7), dp(7))
                    layoutParams = RecyclerView.LayoutParams(dp(146), dp(250)).apply { rightMargin = dp(14) }
                }
                val image = ImageView(this@MediaCenterActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                view.addView(image, LinearLayout.LayoutParams(-1, dp(180)))
                val title = text("", 15).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(7), 0, 0) }
                view.addView(title)
                return Poster(view, image, title)
            }
            override fun onBindViewHolder(holder: Poster, position: Int) {
                val item = items[position]; holder.title.text = item.title
                Glide.with(holder.image).load(item.poster).into(holder.image)
                holder.itemView.setOnClickListener { detail(item) }
                holder.itemView.setOnFocusChangeListener { v, focus ->
                    v.background = bg(if (focus) Color.rgb(36, 55, 81) else Color.rgb(20, 29, 43), if (focus) accent else Color.TRANSPARENT)
                    v.animate().scaleX(if (focus) 1.035f else 1f).scaleY(if (focus) 1.035f else 1f).setDuration(120).start()
                }
            }
        }
        content.addView(rail, LinearLayout.LayoutParams(-1, dp(275)))
    }
    private class Poster(view: View, val image: ImageView, val title: TextView) : RecyclerView.ViewHolder(view)

    private fun searchScreen() {
        job?.cancel(); page = "search"; clear("Найти фильм или сериал")
        val query = EditText(this).apply {
            hint = "Название"; setTextColor(ink); setHintTextColor(muted); isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH; inputType = InputType.TYPE_CLASS_TEXT
        }
        content.addView(query, LinearLayout.LayoutParams(-1, dp(60)))
        fun search() {
            val value = query.text.toString().trim()
            if (value.length < 2) { query.error = "Введи хотя бы две буквы"; return }
            work("Ищу «$value»…") { showHome(backend.browse(query = value), "Результаты поиска") }
        }
        query.setOnEditorActionListener { _, _, _ -> search(); true }
        content.addView(button("Найти") { search() }); query.requestFocus()
    }
    private fun detail(item: CenterTitle) {
        selected = item
        work("Открываю ${item.title}…") {
            val details = backend.details(item); val movie = details.title; selected = movie
            page = "detail"; clear(movie.title)
            content.addView(text("${movie.year} · ★ ${movie.rating}", 16, accent)); space(content, 14)
            content.addView(text(movie.overview.ifBlank { "Описание пока отсутствует" }, 18, muted)); space(content, 14)
            if (details.actors.isNotBlank()) { content.addView(text("В ролях: ${details.actors}", 15, muted)); space(content, 14) }
            if (movie.type == "tv") details.seasons.forEach { season ->
                content.addView(button("Сезон $season") { episodeList(movie, season) })
            } else content.addView(button("Смотреть · выбрать раздачу") { find(movie, null) })
            content.addView(button(if (store.isFavorite(movie)) "Убрать из избранного" else "В избранное") { store.toggle(movie); detail(movie) })
            content.addView(button("Открыть свою magnet-ссылку / .torrent") { magnet(movie, null) })
        }
    }
    private fun episodeList(item: CenterTitle, season: Int) {
        work("Загружаю серии…") {
            val episodes = backend.episodes(item, season); page = "episodes"; clear("${item.title} · Сезон $season")
            if (episodes.isEmpty()) content.addView(text("Для этого сезона пока нет серий", 18, muted))
            episodes.forEach { episode -> content.addView(button("${episode.number}. ${episode.name}") { find(item, episode) }) }
        }
    }
    private fun find(item: CenterTitle, episode: CenterEpisode?) {
        if (store.indexer.isBlank()) { sources { find(item, episode) }; return }
        work("Ищу раздачи…") {
            val releases = backend.searchReleases(store.indexer, store.apiKey, item, episode)
            releaseList(item, episode, releases)
        }
    }
    private fun releaseList(item: CenterTitle, episode: CenterEpisode?, releases: List<CenterRelease>, quality: String = "Все") {
        page = "releases"; clear("Раздачи · ${item.title}" + (episode?.let { " · S${it.season}E${it.number}" } ?: ""))
        val filters = row()
        listOf("Все", "2160", "1080", "720").forEach { value -> filters.addView(button(value) { releaseList(item, episode, releases, value) },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(8) }) }
        content.addView(filters); space(content, 12)
        val visible = releases.filter { quality == "Все" || it.title.contains(quality) }
        if (visible.isEmpty()) content.addView(text("Раздачи не найдены. Измени качество или проверь источник", 18, muted))
        visible.forEach { release ->
            content.addView(button("${release.title}\nСиды: ${release.seeds} · ${size(release.size)} · ${release.tracker}") {
                openRelease(item, episode, release)
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
            else { dialog.dismiss(); openRelease(item, episode, CenterRelease(item.title, link, 0, 0, "Своя раздача")) }
        } }
        dialog.show()
    }
    private fun openRelease(item: CenterTitle, requested: CenterEpisode?, release: CenterRelease) {
        work("Получаю файлы раздачи…") {
            val base = CenterEngineService.ensure(this@MediaCenterActivity)
            val torrent = backend.openRelease(base, release, item)
            val matches = if (requested == null) emptyList() else torrent.files.filter { it.episode() == (requested.season to requested.number) }
            if ((requested == null && torrent.files.size == 1) || matches.size == 1) {
                play(item, requested, base, torrent, matches.singleOrNull() ?: torrent.files.single())
            } else {
                page = "files"; clear("Выбери файл · ${item.title}")
                if (requested != null) content.addView(text("Искомая серия: S${requested.season}E${requested.number}. Проверь имя файла", 16, muted))
                torrent.files.forEach { file -> content.addView(button("${file.name} · ${size(file.size)}") { play(item, requested, base, torrent, file) }) }
            }
        }
    }
    private fun play(item: CenterTitle, requested: CenterEpisode?, base: String, torrent: CenterTorrent, file: CenterFile) {
        val url = backend.stream(base, torrent.hash, file)
        val number = file.episode() ?: requested?.let { it.season to it.number }
        val playlist = if (item.type == "tv") {
            val m3u = "#EXTM3U\n" + torrent.files.joinToString("\n") { backend.stream(base, torrent.hash, it) }
            TorrentEpisodeResolver.parse(url, m3u)
        } else null
        val meta = JsonObject().apply {
            addProperty("title", item.title); addProperty("tmdb_id", item.id)
            addProperty("poster_url", item.poster); addProperty("backdrop_url", item.backdrop); addProperty("overview", item.overview)
            if (item.type == "tv" && number != null) { addProperty("season_number", number.first); addProperty("episode_number", number.second) }
            if (playlist != null) { addProperty("episodes", Gson().toJson(playlist.episodes)); addProperty("episode_index", playlist.currentIndex) }
        }
        launchPlayer(url, meta.toString())
    }
    private fun resume(saved: CenterHistory) {
        work("Продолжаю ${saved.card.title}…") {
            val base = CenterEngineService.ensure(this@MediaCenterActivity)
            val url = CenterStore.rebase(saved.url, base)
            val card = saved.card.copy(episodes = saved.card.episodes.map { it.copy(url = CenterStore.rebase(it.url, base)) })
            val meta = JsonObject().apply {
                addProperty("title", card.title); addProperty("tmdb_id", card.tmdbId); addProperty("poster_url", card.posterUrl)
                addProperty("overview", card.overview); addProperty("season_number", card.seasonNumber); addProperty("episode_number", card.episodeNumber)
                addProperty("episodes", Gson().toJson(card.episodes)); addProperty("episode_index", card.currentEpisodeIndex)
            }
            launchPlayer(url, meta.toString())
        }
    }
    private fun launchPlayer(url: String, metadata: String) {
        returnedFromPlayer = true
        startActivity(Intent(this, PlayerActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setDataAndType(android.net.Uri.parse(url), "video/*").putExtra("lampa_data", metadata))
    }
    private fun sources(after: (() -> Unit)? = null) {
        val form = column().apply { setPadding(dp(24), dp(8), dp(24), 0) }
        form.addView(text("Адрес JacRed или полный адрес Torznab API. Можно использовать свой сервер поиска.", 16, muted))
        val endpoint = EditText(this).apply { hint = "https://…"; setText(store.indexer); isSingleLine = true; setTextColor(ink) }
        val key = EditText(this).apply { hint = "API-ключ, если нужен"; setText(store.apiKey); isSingleLine = true; setTextColor(ink); inputType = 129 }
        form.addView(endpoint); form.addView(key)
        val dialog = AlertDialog.Builder(this).setTitle("Источники").setView(form).setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = endpoint.text.toString().trim()
            if (!value.startsWith("https://") && !value.startsWith("http://")) endpoint.error = "Укажи полный адрес с http:// или https://"
            else { store.indexer = value; store.apiKey = key.text.toString(); dialog.dismiss(); after?.invoke() }
        } }
        dialog.show()
    }
    private fun settings() {
        job?.cancel(); page = "settings"; clear("Настройки")
        content.addView(text("LampMedia · ${BuildConfig.VERSION_NAME}", 16, muted)); space(content, 18)
        content.addView(button("Источники поиска раздач") { sources() })
        content.addView(button("Плеер: движок, звук, субтитры, автопереход") { startActivity(Intent(this, SettingsActivity::class.java)) })
        content.addView(button("Проверить обновление") { update() })
        content.addView(button("Остановить торрент-движок") { CenterEngineService.stop(this); Toast.makeText(this, "Движок остановлен", Toast.LENGTH_SHORT).show() })
        content.addView(button("Лицензии и исходный код движка") {
            val license = assets.open("licenses/TorrServer-GPL-3.0.txt").bufferedReader().use { it.readText() }
            AlertDialog.Builder(this).setTitle("TorrServer MatriX.148 · GPL-3.0")
                .setMessage("Исходный код и сборка: https://github.com/YouROK/TorrServer/tree/MatriX.148\n\n$license")
                .setPositiveButton("Закрыть", null).show()
        })
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
    private fun size(bytes: Long): String = if (bytes <= 0) "размер неизвестен" else "%.1f ГБ".format(bytes / 1073741824.0)
}
