package com.aidar.tradelab

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ManualSnapshotService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var transferJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground("Starting manual snapshot…")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            transferJob?.cancel()
            prefs().edit()
                .putBoolean(KEY_ACTIVE, false)
                .putString(KEY_STAGE, STAGE_CANCELLED)
                .apply()
            stopSelf()
            return START_NOT_STICKY
        }

        val mode = intent?.getStringExtra(EXTRA_MODE) ?: SnapshotWorker.MODE_LATEST
        if (transferJob?.isActive == true) return START_REDELIVER_INTENT

        transferJob = scope.launch { runTransfer(mode) }
        return START_REDELIVER_INTENT
    }

    private suspend fun runTransfer(mode: String) {
        val p = prefs()
        p.edit()
            .putBoolean(KEY_ACTIVE, true)
            .putString(KEY_MODE, mode)
            .putString(KEY_STAGE, STAGE_STARTING)
            .remove(KEY_ERROR)
            .putLong(KEY_DONE, 0L)
            .putLong(KEY_TOTAL, 0L)
            .apply()

        try {
            val repo = SnapshotRepository(applicationContext)
            val manifest = when (mode) {
                SnapshotWorker.MODE_FRESH -> {
                    setStage(STAGE_CREATING, "Server is creating a fresh compact snapshot…")
                    repo.createFresh()
                }
                SnapshotWorker.MODE_FULL -> {
                    setStage(STAGE_CREATING_FULL, "Server is creating the full retained research DB…")
                    repo.createFull()
                }
                else -> {
                    setStage(STAGE_CHECKING, "Checking latest ready snapshot…")
                    repo.latest()
                }
            }

            p.edit()
                .putString(KEY_FILE, manifest.filename)
                .putLong(KEY_TOTAL, manifest.bytes)
                .apply()
            setStage(STAGE_DOWNLOADING, "${manifest.filename} · starting download")

            var lastUiAt = 0L
            val saved = repo.download(manifest) { done, total ->
                if (transferJob?.isActive != true) throw CancellationException("manual download cancelled")
                val now = System.currentTimeMillis()
                p.edit()
                    .putString(KEY_STAGE, if (done >= total && total > 0L) STAGE_VERIFYING else STAGE_DOWNLOADING)
                    .putString(KEY_FILE, manifest.filename)
                    .putLong(KEY_DONE, done)
                    .putLong(KEY_TOTAL, total)
                    .apply()
                if (now - lastUiAt >= 1000L || done >= total) {
                    val text = if (done >= total && total > 0L) {
                        "${manifest.filename} · verifying SHA-256…"
                    } else {
                        "${manifest.filename} · ${formatBytes(done)} / ${formatBytes(total)}"
                    }
                    startAsForeground(text)
                    lastUiAt = now
                }
            }

            val savedEdit = p.edit()
                .putBoolean(KEY_ACTIVE, false)
                .putString(KEY_STAGE, STAGE_SAVED)
                .putString(KEY_FILE, saved.filename)
                .putLong(KEY_DONE, saved.bytes)
                .putLong(KEY_TOTAL, saved.bytes)
                .putString("last_file", saved.filename)
                .putString("last_snapshot_id", manifest.id)
                .putLong("last_file_created_ms", manifest.createdAtMs)
                .putLong("last_at_ms", System.currentTimeMillis())
                .putLong("last_size", saved.bytes)
                .remove(KEY_ERROR)
            // Periodic catch-up lists only compact analysis snapshots. A newer
            // manual full export must not move that compact cursor forward.
            if (mode != SnapshotWorker.MODE_FULL) {
                savedEdit.putLong("last_snapshot_created_ms", manifest.createdAtMs)
            }
            savedEdit.apply()
            NotificationHelper.success(applicationContext, saved.filename, saved.bytes)
        } catch (e: CancellationException) {
            p.edit()
                .putBoolean(KEY_ACTIVE, false)
                .putString(KEY_STAGE, STAGE_CANCELLED)
                .apply()
        } catch (e: Exception) {
            val detail = buildString {
                append(e.javaClass.simpleName)
                if (!e.message.isNullOrBlank()) append(": ").append(e.message)
            }
            p.edit()
                .putBoolean(KEY_ACTIVE, false)
                .putString(KEY_STAGE, STAGE_FAILED)
                .putString(KEY_ERROR, detail)
                .putString("last_error", detail)
                .putLong("last_error_ms", System.currentTimeMillis())
                .apply()
        } finally {
            stopSelf()
        }
    }

    private fun prefs() = getSharedPreferences("snapshots", MODE_PRIVATE)

    private fun setStage(stage: String, notification: String) {
        prefs().edit().putString(KEY_STAGE, stage).apply()
        startAsForeground(notification)
    }

    private fun startAsForeground(text: String) {
        val cancelPi = android.app.PendingIntent.getService(
            this,
            0,
            Intent(this, ManualSnapshotService::class.java).setAction(ACTION_CANCEL),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationHelper.downloadNotification(this, text, cancelPi)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NotificationHelper.FOREGROUND_ID,
                n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NotificationHelper.FOREGROUND_ID, n)
        }
    }

    override fun onDestroy() {
        transferJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val ACTION_CANCEL = "com.aidar.tradelab.action.CANCEL_MANUAL_SNAPSHOT"

        const val KEY_ACTIVE = "manual_active"
        const val KEY_MODE = "manual_mode"
        const val KEY_STAGE = "manual_stage"
        const val KEY_FILE = "manual_file"
        const val KEY_DONE = "manual_done"
        const val KEY_TOTAL = "manual_total"
        const val KEY_ERROR = "manual_error"

        const val STAGE_STARTING = "STARTING"
        const val STAGE_CHECKING = "CHECKING"
        const val STAGE_CREATING = "CREATING"
        const val STAGE_CREATING_FULL = "CREATING_FULL"
        const val STAGE_DOWNLOADING = "DOWNLOADING"
        const val STAGE_VERIFYING = "VERIFYING"
        const val STAGE_SAVED = "SAVED"
        const val STAGE_FAILED = "FAILED"
        const val STAGE_CANCELLED = "CANCELLED"
    }
}
