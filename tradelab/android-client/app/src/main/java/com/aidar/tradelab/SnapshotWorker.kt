package com.aidar.tradelab

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SnapshotWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val connection = applicationContext.getSharedPreferences("connection", Context.MODE_PRIVATE)
        if (connection.getString("read_token", "").isNullOrBlank()) {
            return@withContext Result.failure(
                workDataOf(KEY_STAGE to STAGE_FAILED, KEY_ERROR to "Read token is empty")
            )
        }

        val prefs = applicationContext.getSharedPreferences("snapshots", Context.MODE_PRIVATE)
        val mode = inputData.getString(KEY_MODE) ?: MODE_CATCHUP
        val manual = mode != MODE_CATCHUP
        prefs.edit()
            .putLong("last_attempt_ms", System.currentTimeMillis())
            .putString("worker_mode", mode)
            .remove("last_error")
            .apply()

        suspend fun stage(name: String, file: String? = null, done: Long = 0L, total: Long = 0L) {
            val data = workDataOf(
                KEY_STAGE to name,
                KEY_FILE to (file ?: ""),
                KEY_DONE to done,
                KEY_TOTAL to total,
                KEY_MODE to mode,
            )
            setProgress(data)
            val edit = prefs.edit().putString("worker_stage", name)
                .putLong("download_done", done)
                .putLong("download_total", total)
            if (file.isNullOrBlank()) edit.remove("downloading_file") else edit.putString("downloading_file", file)
            edit.apply()

            if (manual && name in setOf(STAGE_DOWNLOADING, STAGE_VERIFYING)) {
                val text = when (name) {
                    STAGE_DOWNLOADING -> if (total > 0) "$file · ${formatBytes(done)} / ${formatBytes(total)}" else "$file · starting download"
                    STAGE_VERIFYING -> "$file · verifying SHA-256…"
                    else -> "TradeLab snapshot"
                }
                setForeground(NotificationHelper.downloading(applicationContext, text))
            }
        }

        try {
            val repo = SnapshotRepository(applicationContext)
            val manifests = when (mode) {
                MODE_FRESH -> {
                    stage(STAGE_CREATING)
                    listOf(repo.createFresh())
                }
                MODE_FULL -> {
                    stage(STAGE_CREATING)
                    listOf(repo.createFull())
                }
                MODE_LATEST -> {
                    stage(STAGE_CHECKING)
                    listOf(repo.latest())
                }
                else -> {
                    stage(STAGE_CHECKING)
                    val lastCreated = prefs.getLong("last_snapshot_created_ms", 0L)
                    if (lastCreated <= 0L) {
                        // Fresh install (or wiped prefs): never backfill the whole
                        // server archive — just fetch the single newest snapshot.
                        listOf(repo.latest())
                    } else {
                        // Normal catch-up; cap the backlog so a long offline
                        // period cannot trigger a huge sequential download.
                        val pending = repo.since(lastCreated)
                        if (pending.size > 2) pending.takeLast(2) else pending
                    }
                }
            }

            if (manifests.isEmpty()) {
                stage(STAGE_UP_TO_DATE)
                return@withContext Result.success()
            }

            var lastFileName = ""
            var lastFileSize = 0L
            for (manifest in manifests) {
                stage(STAGE_DOWNLOADING, manifest.filename, 0L, manifest.bytes)

                var lastPersisted = -1L
                var lastForegroundAt = 0L
                val saved = repo.download(manifest) { downloaded, total ->
                    val now = System.currentTimeMillis()
                    if (lastPersisted < 0L || downloaded == total || downloaded - lastPersisted >= 256 * 1024) {
                        val currentStage = if (downloaded >= total && total > 0) STAGE_VERIFYING else STAGE_DOWNLOADING
                        val progress = workDataOf(
                            KEY_STAGE to currentStage,
                            KEY_FILE to manifest.filename,
                            KEY_DONE to downloaded,
                            KEY_TOTAL to total,
                            KEY_MODE to mode,
                        )
                        setProgressAsync(progress)
                        prefs.edit()
                            .putString("worker_stage", currentStage)
                            .putString("downloading_file", manifest.filename)
                            .putLong("download_done", downloaded)
                            .putLong("download_total", total)
                            .apply()
                        lastPersisted = downloaded
                    }
                    if (manual && (downloaded == total || now - lastForegroundAt >= 1000L)) {
                        val text = if (downloaded >= total && total > 0) {
                            "${manifest.filename} · verifying SHA-256…"
                        } else {
                            "${manifest.filename} · ${formatBytes(downloaded)} / ${formatBytes(total)}"
                        }
                        setForegroundAsync(NotificationHelper.downloading(applicationContext, text))
                        lastForegroundAt = now
                    }
                }

                lastFileName = saved.filename
                lastFileSize = saved.bytes
                val savedEdit = prefs.edit()
                    .putString("last_file", saved.filename)
                    .putString("last_snapshot_id", manifest.id)
                    .putLong("last_file_created_ms", manifest.createdAtMs)
                    .putLong("last_at_ms", System.currentTimeMillis())
                    .putLong("last_size", saved.bytes)
                    .remove("last_error")
                    .remove("downloading_file")
                    .remove("download_done")
                    .remove("download_total")
                if (mode != MODE_FULL) {
                    savedEdit.putLong("last_snapshot_created_ms", manifest.createdAtMs)
                }
                savedEdit.apply()
                stage(STAGE_SAVED, saved.filename, saved.bytes, saved.bytes)
            }
            NotificationHelper.success(applicationContext, lastFileName, lastFileSize, manifests.size)
            Result.success()
        } catch (e: Exception) {
            val detail = buildString {
                append(e.javaClass.simpleName)
                if (!e.message.isNullOrBlank()) append(": ").append(e.message)
            }
            prefs.edit()
                .putString("worker_stage", STAGE_FAILED)
                .putString("last_error", detail)
                .putLong("last_error_ms", System.currentTimeMillis())
                .apply()

            val failure = workDataOf(
                KEY_STAGE to STAGE_FAILED,
                KEY_ERROR to detail,
                KEY_MODE to mode,
            )

            if (manual) Result.failure(failure) else Result.retry()
        }
    }

    companion object {
        const val KEY_MODE = "mode"
        const val KEY_STAGE = "stage"
        const val KEY_FILE = "file"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"

        const val MODE_FRESH = "fresh"
        const val MODE_FULL = "full"
        const val MODE_LATEST = "latest"
        const val MODE_CATCHUP = "catchup"

        const val STAGE_CREATING = "CREATING"
        const val STAGE_CHECKING = "CHECKING"
        const val STAGE_DOWNLOADING = "DOWNLOADING"
        const val STAGE_VERIFYING = "VERIFYING"
        const val STAGE_SAVED = "SAVED"
        const val STAGE_UP_TO_DATE = "UP_TO_DATE"
        const val STAGE_RETRYING = "RETRYING"
        const val STAGE_FAILED = "FAILED"
    }
}
