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

        try {
            val repo = SnapshotRepository(applicationContext)
            val prefs = applicationContext.getSharedPreferences("snapshots", Context.MODE_PRIVATE)
            val forceCreate = inputData.getBoolean(KEY_FORCE_CREATE, false)
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
                val file = repo.download(manifest)
                lastFileName = file.name
                lastFileSize = file.length()
                prefs.edit()
                    .putString("last_file", file.name)
                    .putString("last_snapshot_id", manifest.id)
                    .putLong("last_snapshot_created_ms", manifest.createdAtMs)
                    .putLong("last_at_ms", System.currentTimeMillis())
                    .putLong("last_size", file.length())
                    .apply()
            }
            NotificationHelper.success(applicationContext, lastFileName, lastFileSize, manifests.size)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val KEY_FORCE_CREATE = "force_create"
    }
}
