package com.aidar.tradelab

import android.Manifest
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var serverUrl: EditText
    private lateinit var readToken: EditText
    private lateinit var latestButton: Button
    private lateinit var freshButton: Button
    private lateinit var cancelButton: Button
    private var manualInfo: WorkInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
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
                enqueueManual(SnapshotWorker.MODE_LATEST)
            }
        }

        freshButton = Button(this).apply {
            text = "Создать свежий и скачать"
            setOnClickListener {
                if (!saveConnection()) return@setOnClickListener
                enqueueManual(SnapshotWorker.MODE_FRESH)
            }
        }

        cancelButton = Button(this).apply {
            text = "Отменить текущую задачу"
            isEnabled = false
            setOnClickListener {
                WorkManager.getInstance(this@MainActivity).cancelUniqueWork(MANUAL_WORK)
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 70, 40, 40)
            addView(TextView(this@MainActivity).apply {
                text = "TradeLab client 0.2.2\nA/B/C/D shadow tournament"
                textSize = 22f
            })
            addView(serverUrl)
            addView(readToken)
            addView(saveButton)
            addView(TextView(this@MainActivity).apply {
                text = "Файлы: Download/TradeLab · докачка после обрыва включена"
            })
            addView(status)
            addView(latestButton)
            addView(freshButton)
            addView(cancelButton)
        }
        setContentView(root)

        scheduleSnapshots()
        observeManualWork()
        renderStatus()
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
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
        return true
    }

    private fun enqueueManual(mode: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<SnapshotWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(SnapshotWorker.KEY_MODE to mode))
            .build()

        // REPLACE is intentional: a stale ENQUEUED/RETRY job must never make a
        // new user tap a no-op. Partial bytes remain on disk, so MODE_LATEST can
        // continue the same latest snapshot via HTTP Range.
        WorkManager.getInstance(this).enqueueUniqueWork(
            MANUAL_WORK,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private fun observeManualWork() {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(MANUAL_WORK)
            .observe(this) { infos ->
                manualInfo = infos.firstOrNull { !it.state.isFinished } ?: infos.lastOrNull()
                renderStatus()
            }
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
        val info = manualInfo
        val active = info != null && !info.state.isFinished
        latestButton.isEnabled = !active
        freshButton.isEnabled = !active
        cancelButton.isEnabled = active

        if (active && info != null) {
            val progress = info.progress
            val stage = progress.getString(SnapshotWorker.KEY_STAGE)
                ?: p.getString("worker_stage", "")
                ?: ""
            val file = progress.getString(SnapshotWorker.KEY_FILE)?.takeIf { it.isNotBlank() }
                ?: p.getString("downloading_file", null)
            val done = if (progress.hasKeyWithValueOfType<Long>(SnapshotWorker.KEY_DONE)) {
                progress.getLong(SnapshotWorker.KEY_DONE, 0L)
            } else p.getLong("download_done", 0L)
            val total = if (progress.hasKeyWithValueOfType<Long>(SnapshotWorker.KEY_TOTAL)) {
                progress.getLong(SnapshotWorker.KEY_TOTAL, 0L)
            } else p.getLong("download_total", 0L)

            val stateText = when (info.state) {
                WorkInfo.State.ENQUEUED -> "В очереди / ожидает сеть"
                WorkInfo.State.BLOCKED -> "Ожидает зависимость"
                WorkInfo.State.RUNNING -> stageText(stage)
                else -> info.state.name
            }
            val percent = if (total > 0L) (done * 100L / total).coerceIn(0L, 100L) else null
            status.text = buildString {
                append("Текущая задача: ").append(stateText)
                if (!file.isNullOrBlank()) append("\nФайл: ").append(file)
                if (total > 0L) {
                    append("\nПрогресс: ").append(formatBytes(done)).append(" / ").append(formatBytes(total))
                    append(" (").append(percent).append("%)")
                }
                append("\nМожно закрыть приложение или экран — задача остаётся в WorkManager.")
                append("\nЕсли она реально застряла, нажмите «Отменить», затем «Скачать последний готовый».")
            }
            return
        }

        val file = p.getString("last_file", null)
        val at = p.getLong("last_at_ms", 0)
        val created = p.getLong("last_snapshot_created_ms", 0)
        val size = p.getLong("last_size", 0)
        val error = p.getString("last_error", null)

        val terminal = when (info?.state) {
            WorkInfo.State.CANCELLED -> "Последняя ручная задача отменена."
            WorkInfo.State.FAILED -> "Последняя ручная задача завершилась ошибкой."
            WorkInfo.State.SUCCEEDED -> "Последняя ручная задача завершена."
            else -> null
        }

        status.text = buildString {
            if (terminal != null) append(terminal).append('\n')
            if (file == null) {
                append("Готовых скачанных снапшотов пока нет.")
            } else {
                append("Последний файл: ").append(file)
                append("\nSnapshot сервера: ").append(java.util.Date(created))
                append("\nСкачан: ").append(java.util.Date(at))
                append("\nРазмер: ").append(formatBytes(size))
                append("\nSHA-256: OK")
                append("\nПапка: Download/TradeLab")
            }
            if (!error.isNullOrBlank()) append("\nПоследняя ошибка: ").append(error)
            append("\n\n«Скачать последний готовый» не создаёт новую БД и обычно быстрее.")
            append("\n«Создать свежий и скачать» сначала ждёт snapshot на VPS.")
        }
    }

    private fun stageText(stage: String): String = when (stage) {
        SnapshotWorker.STAGE_CREATING -> "Сервер создаёт свежий snapshot"
        SnapshotWorker.STAGE_CHECKING -> "Проверяю последний готовый snapshot"
        SnapshotWorker.STAGE_DOWNLOADING -> "Скачивание"
        SnapshotWorker.STAGE_VERIFYING -> "Проверка SHA-256"
        SnapshotWorker.STAGE_SAVED -> "Сохранение завершено"
        SnapshotWorker.STAGE_UP_TO_DATE -> "Новых snapshot нет"
        SnapshotWorker.STAGE_RETRYING -> "Ошибка сети, ожидается повтор"
        else -> "Выполняется"
    }

    companion object {
        private const val MANUAL_WORK = "tradelab-manual-snapshot"
    }
}
