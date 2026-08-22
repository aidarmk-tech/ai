package com.aidar.tradelab

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SnapshotWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val repo = SnapshotRepository(applicationContext)
            val forceCreate = inputData.getBoolean(KEY_FORCE_CREATE, false)
            val manifest = if (forceCreate) repo.createFresh() else repo.latest()
            val file = repo.download(manifest)
            applicationContext.getSharedPreferences("snapshots", Context.MODE_PRIVATE).edit()
                .putString("last_file", file.name)
                .putString("last_snapshot_id", manifest.id)
                .putLong("last_snapshot_created_ms", manifest.createdAtMs)
                .putLong("last_at_ms", System.currentTimeMillis())
                .putLong("last_size", file.length())
                .apply()
            NotificationHelper.success(applicationContext, file.name, file.length())
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val KEY_FORCE_CREATE = "force_create"
    }
}
