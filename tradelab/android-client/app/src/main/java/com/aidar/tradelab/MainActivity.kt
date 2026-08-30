package com.aidar.tradelab

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var serverUrl: EditText
    private lateinit var readToken: EditText
    private lateinit var latestButton: Button
    private lateinit var freshButton: Button
    private lateinit var fullButton: Button
    private lateinit var cancelButton: Button
    private lateinit var mlgateButton: Button
    private lateinit var mlgateStatus: TextView
    private var mlgateRepo: MlgateRepository? = null
    private var mlgateEvents: List<MlgateEvent> = emptyList()
    private var mlgateStats: MlgateStats? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        private var frames = 0
        override fun run() {
            renderStatus()
            frames++
            if (frames % 240 == 0) refreshMlgate()
            uiHandler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        if (android.os.Build.VERSION.SDK_INT <= 28 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 101)
        }

        val connection = getSharedPreferences("connection", MODE_PRIVATE)
        serverUrl = EditText(this).apply {
            hint = "Server URL"
            setText(connection.getString("server_url", BuildConfig.SERVER_URL) ?: BuildConfig.SERVER_URL)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        readToken = EditText(this).apply {
            hint = "Read token"
            setText(connection.getString("read_token", "") ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }

        status = TextView(this).apply { textSize = 16f }

        val saveButton = Button(this).apply {
            text = "Сохранить подключение"
            setOnClickListener {
                if (saveConnection()) renderStatus()
            }
        }

        latestButton = Button(this).apply {
            text = "Скачать последний готовый"
            setOnClickListener {
                if (!saveConnection()) return@setOnClickListener
                startManual(SnapshotWorker.MODE_LATEST)
            }
        }

        freshButton = Button(this).apply {
            text = "Создать свежий 6ч и скачать"
            setOnClickListener {
                if (!saveConnection()) return@setOnClickListener
                startManual(SnapshotWorker.MODE_FRESH)
            }
        }

        fullButton = Button(this).apply {
            text = "Полная исследовательская база 72ч"
            setOnClickListener {
                if (!saveConnection()) return@setOnClickListener
                startManual(SnapshotWorker.MODE_FULL)
            }
        }

        cancelButton = Button(this).apply {
            text = "Отменить текущую загрузку"
            isEnabled = false
            setOnClickListener {
                startService(
                    Intent(this@MainActivity, ManualSnapshotService::class.java)
                        .setAction(ManualSnapshotService.ACTION_CANCEL)
                )
            }
        }

        mlgateStatus = TextView(this).apply { textSize = 14f }
        mlgateButton = Button(this).apply {
            text = "Обновить ML gate (EXTREME_ML_GATE_V1)"
            setOnClickListener {
                if (!saveConnection()) return@setOnClickListener
                mlgateStatus.text = "Загрузка…"
                refreshMlgate()
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 70, 40, 40)
            addView(TextView(this@MainActivity).apply {
                text = "TradeLab client 0.3.0\nA/B/C/D shadow tournament"
                textSize = 22f
            })
            addView(serverUrl)
            addView(readToken)
            addView(saveButton)
            addView(TextView(this@MainActivity).apply {
                text = "Ручная загрузка: прямой foreground service · без очереди WorkManager"
            })
            addView(status)
            addView(latestButton)
            addView(freshButton)
            addView(fullButton)
            addView(cancelButton)
            addView(TextView(this@MainActivity).apply {
                text = "───── ML Gate (EXTREME_ML_GATE_V1) ─────"
                textSize = 18f
            })
            addView(mlgateButton)
            addView(mlgateStatus)
        }
        setContentView(root)

        WorkManager.getInstance(this).cancelUniqueWork(LEGACY_MANUAL_WORK)
        scheduleSnapshots()

        val c = getSharedPreferences("connection", MODE_PRIVATE)
        val savedUrl = c.getString("server_url", "") ?: ""
        val savedToken = c.getString("read_token", "") ?: ""
        if (savedUrl.isNotBlank() && savedToken.isNotBlank()) {
            mlgateRepo = MlgateRepository(savedUrl, savedToken)
        }
        renderStatus()
        refreshMlgate()
    }

    override fun onResume() {
        super.onResume()
        uiHandler.removeCallbacks(refreshRunnable)
        uiHandler.post(refreshRunnable)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    private fun saveConnection(): Boolean {
        val url = serverUrl.text.toString().trim().trimEnd('/')
        val token = readToken.text.toString().trim()
        if (!url.startsWith("https://")) {
            status.text = "Нужен HTTPS адрес сервера."
            return false
        }
        if (token.isBlank()) {
            status.text = "Введите read token."
            return false
        }
        getSharedPreferences("connection", MODE_PRIVATE).edit()
            .putString("server_url", url)
            .putString("read_token", token)
            .apply()
        mlgateRepo = MlgateRepository(url, token)
        refreshMlgate()
        return true
    }

    private fun startManual(mode: String) {
        val p = getSharedPreferences("snapshots", MODE_PRIVATE)
        p.edit()
            .putBoolean(ManualSnapshotService.KEY_ACTIVE, true)
            .putString(ManualSnapshotService.KEY_MODE, mode)
            .putString(ManualSnapshotService.KEY_STAGE, ManualSnapshotService.STAGE_STARTING)
            .remove(ManualSnapshotService.KEY_ERROR)
            .putLong(ManualSnapshotService.KEY_DONE, 0L)
            .putLong(ManualSnapshotService.KEY_TOTAL, 0L)
            .apply()
        val intent = Intent(this, ManualSnapshotService::class.java)
            .putExtra(ManualSnapshotService.EXTRA_MODE, mode)
        startForegroundService(intent)
        renderStatus()
    }

    private fun scheduleSnapshots() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<SnapshotWorker>(4, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(SnapshotWorker.KEY_MODE to SnapshotWorker.MODE_CATCHUP))
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "tradelab-snapshot-4h",
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private fun renderStatus() {
        val p = getSharedPreferences("snapshots", MODE_PRIVATE)
        val active = p.getBoolean(ManualSnapshotService.KEY_ACTIVE, false)
        val stage = p.getString(ManualSnapshotService.KEY_STAGE, null)
        val manualError = p.getString(ManualSnapshotService.KEY_ERROR, null)
        val currentFile = p.getString(ManualSnapshotService.KEY_FILE, null)
        val done = p.getLong(ManualSnapshotService.KEY_DONE, 0L)
        val total = p.getLong(ManualSnapshotService.KEY_TOTAL, 0L)

        latestButton.isEnabled = !active
        freshButton.isEnabled = !active
        fullButton.isEnabled = !active
        cancelButton.isEnabled = active

        if (active) {
            val percent = if (total > 0L) (done * 100L / total).coerceIn(0L, 100L) else null
            status.text = buildString {
                append("Текущая задача: ").append(stageText(stage))
                if (!currentFile.isNullOrBlank()) append("\nФайл: ").append(currentFile)
                if (total > 0L) {
                    append("\nПрогресс: ").append(formatBytes(done)).append(" / ").append(formatBytes(total))
                    append(" (").append(percent).append("%)")
                }
                append("\nРаботает напрямую, без очереди WorkManager.")
                append("\nЭкран можно выключить после начала загрузки.")
            }
            return
        }

        val file = p.getString("last_file", null)
        val at = p.getLong("last_at_ms", 0)
        val compactCursor = p.getLong("last_snapshot_created_ms", 0)
        val created = p.getLong("last_file_created_ms", compactCursor)
        val size = p.getLong("last_size", 0)
        val lastError = manualError ?: p.getString("last_error", null)

        status.text = buildString {
            when (stage) {
                ManualSnapshotService.STAGE_FAILED -> append("Ошибка ручной загрузки.")
                ManualSnapshotService.STAGE_CANCELLED -> append("Ручная загрузка отменена.")
                ManualSnapshotService.STAGE_SAVED -> append("Ручная загрузка завершена.")
            }
            if (!lastError.isNullOrBlank()) {
                if (isNotEmpty()) append('\n')
                append("Причина: ").append(lastError)
            }
            if (file == null) {
                if (isNotEmpty()) append("\n\n")
                append("Готовых скачанных снапшотов пока нет.")
            } else {
                if (isNotEmpty()) append("\n\n")
                append("Последний файл: ").append(file)
                append("\nSnapshot сервера: ").append(java.util.Date(created))
                append("\nСкачан: ").append(java.util.Date(at))
                append("\nРазмер: ").append(formatBytes(size))
                append("\nSHA-256: OK")
                append("\nПапка: Download/TradeLab")
            }
            append("\n\nПоследний готовый — без создания новой БД.")
            append("\nСвежий 6ч — обычный компактный файл для регулярного анализа.")
            append("\nПолная 72ч — ручной глубокий экспорт всей raw-истории, которую ещё хранит VPS.")
        }
    }

    private fun refreshMlgate() {
        val repo = mlgateRepo ?: return
        Thread {
            val events = repo.fetchEvents(10)
            val stats = repo.fetchStats()
            uiHandler.post {
                mlgateEvents = events
                mlgateStats = stats
                renderMlgate()
            }
        }.start()
    }

    private fun renderMlgate() {
        val stats = mlgateStats
        val events = mlgateEvents
        mlgateStatus.text = buildString {
            if (mlgateRepo == null) {
                append("\nНажмите «Сохранить подключение» для инициализации.")
                return@buildString
            }
            if (stats == null) {
                append("\nНет данных или ошибка соединения.")
                return@buildString
            }
            val rate = stats.acceptanceRate ?: -1.0
            append("ACCEPT: ").append(stats.accepts)
            append("   VETO: ").append(stats.vetos)
            append("\nAcceptance rate: ").append(if (rate >= 0) "%.1f%%".format(rate * 100) else "n/a")
            val pt = stats.avgPTail
            if (pt != null) append("\navg p_tail: ").append("%.3f".format(pt))
            val erm = stats.avgExpectedReturn
            if (erm != null) append("   avg exp.ret: ").append("%.2f".format(erm))
            val ms = stats.avgMlScore
            if (ms != null) append("   avg ml_score: ").append("%.2f".format(ms))
            if (events.isNotEmpty()) {
                append("\n\nПоследние решения:")
                for (e in events) {
                    val tag = if (e.eventType.contains("ACCEPT")) "✅ ACCEPT" else "⛔ VETO"
                    append("\n• ").append(e.symbol).append(" ").append(e.side).append(" ").append(tag)
                    if (e.pTail != null) append("  p_tail=").append("%.3f".format(e.pTail))
                    if (e.mlScore != null) append("  score=").append("%.2f".format(e.mlScore))
                    append("\n    ").append(formatMlgateTime(e.tsMs))
                }
            }
        }
    }

    private fun formatMlgateTime(tsMs: Long): String {
        if (tsMs <= 0L) return "?"
        val f = java.text.SimpleDateFormat("dd.MM HH:mm:ss", java.util.Locale.getDefault())
        f.timeZone = java.util.TimeZone.getDefault()
        return f.format(java.util.Date(tsMs))
    }

    private fun stageText(stage: String?): String = when (stage) {
        ManualSnapshotService.STAGE_STARTING -> "Стартует foreground service"
        ManualSnapshotService.STAGE_CHECKING -> "Проверяю последний готовый snapshot"
        ManualSnapshotService.STAGE_CREATING -> "Сервер создаёт свежий 6ч snapshot"
        ManualSnapshotService.STAGE_CREATING_FULL -> "Сервер создаёт полную 72ч базу"
        ManualSnapshotService.STAGE_DOWNLOADING -> "Скачивание"
        ManualSnapshotService.STAGE_VERIFYING -> "Проверка SHA-256"
        ManualSnapshotService.STAGE_SAVED -> "Сохранено"
        else -> stage ?: "Запуск"
    }

    companion object {
        private const val LEGACY_MANUAL_WORK = "tradelab-manual-snapshot"
    }
}
