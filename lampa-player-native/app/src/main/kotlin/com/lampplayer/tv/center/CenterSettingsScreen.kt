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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
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

/* Настройки LampMedia: интерфейс, каталоги, источники раздач, движок и обновление. */

internal fun MediaCenterActivity.sources(after: (() -> Unit)? = null) {
    val form = column().apply { setPadding(dp(24), dp(8), dp(24), 0) }
    form.addView(text("Адрес JacRed или полный адрес Torznab API. Можно использовать свой сервер поиска.", 16, muted))
    val type = Spinner(this).apply {
        adapter = ArrayAdapter(this@sources, android.R.layout.simple_spinner_dropdown_item, listOf("JacRed", "Torznab / Jackett / Prowlarr"))
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
                    engineBackend.searchReleases(endpoint.text.toString().trim(), key.text.toString().trim(),
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
internal fun MediaCenterActivity.catalogSettings() {
    val form = column().apply { setPadding(dp(24), dp(10), dp(24), 0) }
    form.addView(text("Адрес сайта для каждого каталога. Зеркало должно сохранять его формат страниц.", 15, muted))
    val fields = (CenterCatalog.sites + "num").associateWith { provider ->
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
internal fun MediaCenterActivity.settings() {
    job?.cancel(); currentScreen = { settings() }; page = "settings"; clear("Настройки")
    content.addView(text("LampMedia · ${BuildConfig.VERSION_NAME}", 16, muted)); space(content, 18)
    content.addView(button("Размер карточек · " + when (store.cardSize) { "compact" -> "Компактные"; "large" -> "Крупные"; else -> "Обычные" }) {
        val sizes = listOf("compact", "normal", "large")
        AlertDialog.Builder(this).setTitle("Размер карточек")
            .setSingleChoiceItems(arrayOf("Компактные", "Обычные", "Крупные"), sizes.indexOf(store.cardSize)) { dialog, index ->
                store.cardSize = sizes[index]; dialog.dismiss(); settings()
            }.setNegativeButton("Отмена", null).show()
    })
    content.addView(button("Размер текста · " + CenterTheme.scaleLabels.getValue(store.textScale)) {
        val keys = CenterTheme.scaleLabels.keys.toList()
        AlertDialog.Builder(this).setTitle("Размер текста")
            .setSingleChoiceItems(CenterTheme.scaleLabels.values.toTypedArray(), keys.indexOf(store.textScale)) { dialog, index ->
                store.textScale = keys[index]; dialog.dismiss(); reopenSettings = true; recreate()
            }.setNegativeButton("Отмена", null).show()
    })
    content.addView(button("Оформление · " + CenterTheme.paletteLabels.getValue(store.palette)) {
        val keys = CenterTheme.paletteLabels.keys.toList()
        AlertDialog.Builder(this).setTitle("Оформление")
            .setSingleChoiceItems(CenterTheme.paletteLabels.values.toTypedArray(), keys.indexOf(store.palette)) { dialog, index ->
                store.palette = keys[index]; dialog.dismiss(); reopenSettings = true; recreate()
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
    updateStatus?.let { content.addView(text(it,14,muted).apply {tag="update_status"}) }
    content.addView(button("Проверить обновление") { update() })
    content.addView(button("Полки главной"){homeSettings()})
    content.addView(button("Торрент-движок · состояние и буфер") { enter { engineScreen() } })
    content.addView(button("Сохранить резервную копию") {
        runCatching { exportBackupLauncher.launch("LampMedia-backup.json") }.onFailure {
            Toast.makeText(this,"На устройстве нет доступного приложения для выбора файлов",Toast.LENGTH_LONG).show()
        }
    })
    content.addView(button("Восстановить из резервной копии") {
        AlertDialog.Builder(this).setTitle("Восстановить библиотеку?")
            .setMessage("История, избранное, сериалы и прогресс будут объединены с текущими. Более свежий прогресс сохранится. Адреса подключений и ключи доступа не переносятся.")
            .setNegativeButton("Отмена",null).setPositiveButton("Выбрать файл"){_,_->
                runCatching { importBackupLauncher.launch(arrayOf("application/json","text/plain","application/octet-stream")) }.onFailure {
                    Toast.makeText(this,"На устройстве нет доступного приложения для выбора файлов",Toast.LENGTH_LONG).show()
                }
            }.show()
    })
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
internal fun MediaCenterActivity.engineScreen(start: Boolean = false) {
    if (CenterEngineService.base == null && !start) {
        currentScreen = { engineScreen() }; page = "engine"; clear("Торрент-движок")
        content.addView(text("Остановлен · автоматически запустится при открытии раздачи", 17, muted)); space(content, 18)
        content.addView(button("Запустить и настроить") { engineScreen(true) })
        return
    }
    work("Проверяю торрент-движок…") {
        val base = CenterEngineService.ensure(this@engineScreen)
        val config = engineBackend.engineSettings(base)
        showEngine(config)
    }
}
internal fun MediaCenterActivity.showEngine(config: JsonObject) {
    currentScreen = { engineScreen() }; page = "engine"; clear("Торрент-движок")
    val cache = (config.get("CacheSize")?.asLong ?: 0) / (1024 * 1024)
    content.addView(text("Работает · TorrServer MatriX.148", 18, accent)); space(content, 14)
    content.addView(text("Буфер в памяти: $cache МБ", 21)); space(content, 12)
    content.addView(text("Большой буфер помогает при нестабильной скорости загрузки, но увеличивает расход памяти. Это размер кэша TorrServer, а не всей памяти приложения.", 16, muted)); space(content, 12)
    content.addView(text("Изменение буфера переподключает раздачи. Применяй его между просмотрами.", 14, muted)); space(content, 18)
    val options = row()
    listOf(32, 64, 128, 256).forEach { mb -> options.addView(button("$mb МБ" + if (cache == mb.toLong()) " ✓" else "") {
        work("Меняю размер буфера…") {
            val base = CenterEngineService.ensure(this@showEngine)
            engineBackend.setEngineCache(base, mb); showEngine(engineBackend.engineSettings(base))
        }
    }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(8) }) }
    content.addView(options); space(content, 20)
    content.addView(button("Остановить движок и освободить буфер") { CenterEngineService.stop(this); engineScreen() })
}
internal fun MediaCenterActivity.update(available: com.lampplayer.tv.update.UpdateInfo? = null) {
    work("Проверяю обновление…") {
        val update = available ?: checkForUpdate()
        if (update == null) { settings(); Toast.makeText(this@update, "Установлена последняя версия", Toast.LENGTH_SHORT).show() }
        else {
            val file = UpdateManager.download(this@update, update.url, update.sha256) ?: error("Загрузка обновления не удалась")
            check(UpdateManager.install(this@update, file)) { "Не удалось установить обновление" }
            settings()
        }
    }
}
