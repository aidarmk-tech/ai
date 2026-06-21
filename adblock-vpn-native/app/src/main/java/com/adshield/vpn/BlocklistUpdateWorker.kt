package com.adshield.vpn

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Periodically refreshes the enabled blocklists in the background. */
class BlocklistUpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val result = BlocklistUpdater.update(applicationContext)
        // Apply the refreshed lists to a running tunnel.
        if (VpnState.running.value) {
            AdVpnService.stop(applicationContext)
            kotlinx.coroutines.delay(400)
            AdVpnService.start(applicationContext)
        }
        return if (result is BlocklistUpdater.Result.Error) Result.retry() else Result.success()
    }

    companion object {
        private const val WORK_NAME = "blocklist-auto-update"

        /** Enable or disable the daily background update according to settings. */
        fun reschedule(ctx: Context) {
            val wm = WorkManager.getInstance(ctx)
            if (!Prefs.autoUpdate(ctx)) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<BlocklistUpdateWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
