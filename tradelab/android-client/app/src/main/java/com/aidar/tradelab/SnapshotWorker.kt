package com.aidar.tradelab

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SnapshotWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val connection = applicationContext.getSharedPreferences("connection", Context.MODE_PRIVATE)
        if (connection.getString("read_token", "").isNullOrBlank()) {
            return@withContext Result.success()
        }

        val prefs = applicationContext.getSharedPreferences("snapshots", Context.MODE_PRIVATE)
        val forceCreate = inputData.getBoolean(KEY_FORCE_CREATE, false)
        prefs.edit().putLong("last_attempt_ms", System.currentTimeMillis()).remove("last_error").apply()

        try {
            // Manual downloads are user-initiated and may be large. Promote only
            // those to a dataSync foreground worker so locking the screen does not
            // make the app/process lifecycle own the transfer.
            if (forceCreate) setForeground(NotificationHelper.downloading(applicationContext))

            val repo = SnapshotRepository(applicationContext)
            val manifests = if (forceCreate) {
                listOf(repo.createFresh())
            } else {
                val lastCreated = prefs.getLong("last_snapshot_created_ms", 0L)
                repo.since(lastCreated)
            }

            if (manifests.isEmpty()) return@withContext Result.success()

            var lastFileName = ""
            var lastFileSize = 0L
            for (manifest in manifests) {
                if (forceCreate) {
                    setForeground(
                        NotificationHelper.downloading(
                            applicationContext,
                            "${manifest.filename} · ${formatBytes(manifest.bytes)} · resumable",
                        )
                    )
                }
                val saved = repo.download(manifest)
                lastFileName = saved.filename
                lastFileSize = saved.bytes
                prefs.edit()
                    .putString("last_file", saved.filename)
                    .putString("last_snapshot_id", manifest.id)
                    .putLong("last_snapshot_created_ms", manifest.createdAtMs)
                    .putLong("last_at_ms", System.currentTimeMillis())
                    .putLong("last_size", saved.bytes)
                    .remove("last_error")
                    .apply()
            }
            NotificationHelper.success(applicationContext, lastFileName, lastFileSize, manifests.size)
            Result.success()
        } catch (e: Exception) {
            prefs.edit()
                .putString("last_error", e.message ?: e.javaClass.simpleName)
                .putLong("last_error_ms", System.currentTimeMillis())
                .apply()
            // WorkManager retries. SnapshotRepository keeps the .part bytes, so
            // retries continue via HTTP Range rather than starting from byte 0.
            Result.retry()
        }
    }

    companion object {
        const val KEY_FORCE_CREATE = "force_create"
    }
}
