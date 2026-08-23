package com.aidar.tradelab

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.content.ContentUris
import java.io.File

class SnapshotsScreen(private val activity: MainActivity) {
    private val root = ScrollView(activity)
    private val content = LinearLayout(activity)
    private lateinit var progressCard: View
    private lateinit var progressTitle: TextView
    private lateinit var progressDetail: TextView
    private lateinit var cancelButton: Button
    private lateinit var smartButton: Button
    private lateinit var staleBox: LinearLayout
    private lateinit var deviceListBox: LinearLayout
    private lateinit var syncInfo: TextView

    init {
        root.setBackgroundColor(Ui.BG)
        root.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 12), Ui.dp(activity, 12), Ui.dp(activity, 24))
        content.orientation = LinearLayout.VERTICAL
        root.addView(content)
        build()
    }

    fun view(): View = root

    fun tick() { renderProgress(); renderSyncInfo() }

    fun onResumeTick() { renderDeviceList(); renderProgress(); renderSyncInfo() }

    private fun prefs() = activity.getSharedPreferences("snapshots", Context.MODE_PRIVATE)

    private fun build() {
        content.removeAllViews()

        // --- primary action ---
        val primary = Ui.card(activity)
        primary.addView(Ui.text(activity, "Данные исследования", 17, Ui.TEXT, bold = true))
        primary.addView(Ui.spacer(activity, 4))
        primary.addView(
            Ui.text(
                activity,
                "Компактный снапшот (~50 МБ) кладётся в Download/TradeLab",
                13, Ui.MUTED,
            )
        )
        primary.addView(Ui.spacer(activity, 10))
        smartButton = Button(activity).apply {
            text = "Обновить данные"
            textSize = 16f
        }
        smartButton.setOnClickListener {
            val mode = pickSmartMode()
            if (mode == SnapshotWorker.MODE_LATEST && latestIsStale()) {
                staleBox.visibility = View.VISIBLE
            }
            start(mode)
        }
        primary.addView(smartButton)
        primary.addView(Ui.spacer(activity, 8))

        staleBox = Ui.card(activity).apply { setBackgroundColor(Ui.CARD_ALT) }
        staleBox.addView(Ui.text(activity, "Последний готовый снапшот на сервере устарел.", 13, Ui.AMBER))
        staleBox.addView(Ui.spacer(activity, 6))
        val mkFresh = Button(activity).apply { text = "Создать свежий 6ч и скачать" }
        mkFresh.setOnClickListener { start(SnapshotWorker.MODE_FRESH) }
        staleBox.addView(mkFresh)
        staleBox.visibility = View.GONE
        primary.addView(staleBox)

        progressCard = progressView()
        primary.addView(progressCard)
        content.addView(primary)

        // --- advanced ---
        content.addView(Ui.spacer(activity, 12))
        val adv = Ui.card(activity)
        adv.addView(Ui.text(activity, "Дополнительно", 15, Ui.TEXT, bold = true))
        adv.addView(Ui.spacer(activity, 8))

        val wifiRow = Ui.row(activity)
        val wifiCheck = CheckBox(activity).apply {
            text = "Полный экспорт только по Wi-Fi"
            isChecked = wifiOnlyPref()
            setOnCheckedChangeListener { _, checked ->
                prefs().edit().putBoolean("full_wifi_only", checked).apply()
            }
        }
        wifiRow.addView(wifiCheck)
        adv.addView(wifiRow)

        val fullBtn = Button(activity).apply { text = "Полная база 72ч (сотни МБ)" }
        fullBtn.setOnClickListener {
            if (wifiOnlyPref() && ApiClient.isMetered(activity)) {
                toast("Выключен Wi-Fi: полный экспорт отложен (настройка «только Wi-Fi»)")
                return@setOnClickListener
            }
            start(SnapshotWorker.MODE_FULL)
        }
        adv.addView(fullBtn)

        val listTitle = Ui.text(activity, "Скачанные снапшоты", 14, Ui.MUTED, bold = true)
        adv.addView(Ui.spacer(activity, 12))
        adv.addView(listTitle)
        deviceListBox = Ui.column(activity)
        adv.addView(deviceListBox)
        content.addView(adv)

        // --- autosync info ---
        content.addView(Ui.spacer(activity, 12))
        val info = Ui.card(activity)
        syncInfo = Ui.text(activity, "", 13, Ui.MUTED)
        info.addView(syncInfo)
        content.addView(info)

        renderProgress()
        renderSyncInfo()
        renderDeviceList()
    }

    private fun latestIsStale(): Boolean {
        val created = prefs().getLong("last_snapshot_created_ms", 0L)
        return created > 0 && System.currentTimeMillis() - created > 7 * 3600_000L
    }

    private fun pickSmartMode(): String {
        val last = prefs().getLong("last_snapshot_created_ms", 0L)
        return if (last == 0L) SnapshotWorker.MODE_LATEST else SnapshotWorker.MODE_LATEST
    }

    private fun wifiOnlyPref(): Boolean =
        prefs().getBoolean("full_wifi_only", true)

    private fun start(mode: String) {
        val p = prefs()
        p.edit()
            .putBoolean(ManualSnapshotService.KEY_ACTIVE, true)
            .putString(ManualSnapshotService.KEY_MODE, mode)
            .putString(ManualSnapshotService.KEY_STAGE, ManualSnapshotService.STAGE_STARTING)
            .remove(ManualSnapshotService.KEY_ERROR)
            .putLong(ManualSnapshotService.KEY_DONE, 0L)
            .putLong(ManualSnapshotService.KEY_TOTAL, 0L)
            .apply()
        activity.startManual(mode)
        renderProgress()
    }

    private fun progressView(): View {
        val c = Ui.card(activity).apply { setBackgroundColor(Ui.CARD_ALT) }
        c.visibility = View.GONE
        progressTitle = Ui.text(activity, "", 14, Ui.TEXT, bold = true)
        c.addView(progressTitle)
        progressDetail = Ui.text(activity, "", 12, Ui.MUTED, mono = true)
        c.addView(progressDetail)
        c.addView(Ui.spacer(activity, 6))
        cancelButton = Button(activity).apply { text = "Отменить загрузку" }
        cancelButton.setOnClickListener {
            activity.startService(
                Intent(activity, ManualSnapshotService::class.java)
                    .setAction(ManualSnapshotService.ACTION_CANCEL)
            )
        }
        c.addView(cancelButton)
        return c
    }

    private fun renderProgress() {
        val p = prefs()
        val active = p.getBoolean(ManualSnapshotService.KEY_ACTIVE, false)
        val stage = p.getString(ManualSnapshotService.KEY_STAGE, null)
        val err = p.getString(ManualSnapshotService.KEY_ERROR, null)
        val file = p.getString(ManualSnapshotService.KEY_FILE, null) ?: ""
        val done = p.getLong(ManualSnapshotService.KEY_DONE, 0L)
        val total = p.getLong(ManualSnapshotService.KEY_TOTAL, 0L)

        val showCard = active || !err.isNullOrBlank()
        progressCard.visibility = if (showCard) View.VISIBLE else View.GONE
        if (!showCard) return

        when {
            active -> {
                progressTitle.text = "Загрузка: ${stageText(stage)}"
                progressDetail.text = if (total > 0) "$file · ${formatBytes(done)} / ${formatBytes(total)}" else stage ?: ""
                cancelButton.isEnabled = true
            }
            else -> {
                progressTitle.text = "Ошибка загрузки"
                progressDetail.text = friendlyError(err ?: "")
                cancelButton.isEnabled = false
                cancelButton.text = "Закрыто"
            }
        }
    }

    private fun renderSyncInfo() {
        val p = prefs()
        val attempt = p.getLong("last_attempt_ms", 0L)
        val lastSaved = p.getLong("last_at_ms", 0L)
        val savedFile = p.getString("last_file", null)
        val size = p.getLong("last_size", 0L)
        val b = StringBuilder()
        b.append("Автосинк: каждые ~4 часа (catch-up)")
        if (attempt > 0) b.append(" · попытка ").append(Ui.ageText(attempt, System.currentTimeMillis()))
        if (savedFile != null) {
            b.append("\nЛокальный файл: ").append(savedFile)
            if (size > 0) b.append(" · ").append(formatBytes(size))
            b.append(" · ").append(Ui.ageText(lastSaved, System.currentTimeMillis()))
        }
        syncInfo.text = b.toString()
    }

    private data class LocalFile(val name: String, val bytes: Long, val addedMs: Long, val uri: Uri?)

    private fun localSnapshots(): List<LocalFile> {
        val out = mutableListOf<LocalFile>()
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = activity.contentResolver
            val projection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.SIZE,
                MediaStore.Downloads.DATE_ADDED,
            )
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Downloads.RELATIVE_PATH}=?",
                arrayOf(Environment.DIRECTORY_DOWNLOADS + "/TradeLab/"),
                "${MediaStore.Downloads.DATE_ADDED} DESC",
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getLong(cur.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                    out.add(
                        LocalFile(
                            name = cur.getString(cur.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)),
                            bytes = cur.getLong(cur.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)),
                            addedMs = cur.getLong(cur.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)) * 1000,
                            uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id),
                        )
                    )
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TradeLab")
            dir.listFiles { f -> f.name.endsWith(".sqlite3.gz") }
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { out.add(LocalFile(it.name, it.length(), it.lastModified(), null)) }
        }
        return out
    }

    private fun renderDeviceList() {
        deviceListBox.removeAllViews()
        val files = try { localSnapshots() } catch (_: Exception) { emptyList() }
        if (files.isEmpty()) {
            deviceListBox.addView(Ui.text(activity, "Пока ничего не скачано", 13, Ui.MUTED))
            return
        }
        for (f in files.take(10)) {
            val row = Ui.card(activity).apply { setBackgroundColor(Ui.CARD_ALT) }
            row.setPadding(Ui.dp(activity, 10), Ui.dp(activity, 8), Ui.dp(activity, 10), Ui.dp(activity, 8))
            val left = Ui.column(activity)
            left.addView(Ui.text(activity, f.name, 13, Ui.TEXT, mono = true))
            left.addView(
                Ui.text(
                    activity,
                    formatBytes(f.bytes) + " · " + Ui.ageText(f.addedMs, System.currentTimeMillis()),
                    11, Ui.MUTED,
                )
            )
            row.addView(left)
            row.setOnClickListener { share(f) }
            row.setOnLongClickListener { delete(f); renderDeviceList(); true }
            deviceListBox.addView(row)
            deviceListBox.addView(Ui.spacer(activity, 6))
        }
        deviceListBox.addView(Ui.text(activity, "Тап — поделиться · долгий тап — удалить", 11, Ui.MUTED))
    }

    private fun share(f: LocalFile) {
        val intent = Intent(Intent.ACTION_SEND)
        if (f.uri != null && Build.VERSION.SDK_INT >= 29) {
            intent.type = "application/gzip"
            intent.putExtra(Intent.EXTRA_STREAM, f.uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            intent.type = "application/gzip"
        }
        activity.startActivity(Intent.createChooser(intent, "Поделиться снапшотом"))
    }

    private fun delete(f: LocalFile) {
        try {
            if (f.uri != null && Build.VERSION.SDK_INT >= 29) {
                activity.contentResolver.delete(f.uri, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TradeLab")
                File(dir, f.name).delete()
            }
        } catch (_: Exception) { }
        renderDeviceList()
    }

    private fun friendlyError(raw: String): String = when {
        raw.contains("401") || raw.contains("token", true) -> "Неверный read token. Проверь его в Настройках."
        raw.contains("HTTP 5") -> "Ошибка сервера. Попробуй позже."
        raw.contains("timeout", true) || raw.contains("Timeout") -> "Таймаут соединения."
        raw.contains("sha256", true) -> "Файл повреждён при передаче — попробуй ещё раз."
        else -> raw
    }

    private fun stageText(stage: String?): String = when (stage) {
        ManualSnapshotService.STAGE_STARTING -> "старт"
        ManualSnapshotService.STAGE_CHECKING -> "проверяю наличие"
        ManualSnapshotService.STAGE_CREATING -> "сервер создаёт компактный"
        ManualSnapshotService.STAGE_CREATING_FULL -> "сервер создаёт полную копию"
        ManualSnapshotService.STAGE_DOWNLOADING -> "скачивание"
        ManualSnapshotService.STAGE_VERIFYING -> "проверяю SHA-256"
        ManualSnapshotService.STAGE_SAVED -> "сохранено ✓"
        ManualSnapshotService.STAGE_CANCELLED -> "отменено"
        ManualSnapshotService.STAGE_FAILED -> "ошибка"
        else -> stage ?: ""
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_LONG).show()
    }
}
