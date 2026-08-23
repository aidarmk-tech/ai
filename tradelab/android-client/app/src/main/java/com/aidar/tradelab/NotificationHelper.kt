package com.aidar.tradelab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.ForegroundInfo

object NotificationHelper {
    private const val CHANNEL = "snapshots"
    private const val DOWNLOAD_CHANNEL = "snapshot_download"
    private const val SUCCESS_ID = 4101
    const val FOREGROUND_ID = 4102

    private fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "TradeLab snapshots", NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.createNotificationChannel(
            NotificationChannel(DOWNLOAD_CHANNEL, "TradeLab snapshot download", NotificationManager.IMPORTANCE_LOW)
        )
    }

    fun downloadNotification(
        context: Context,
        text: String = "Preparing snapshot…",
        cancelIntent: android.app.PendingIntent? = null,
    ): Notification {
        ensureChannels(context)
        val b = android.app.Notification.Builder(context, DOWNLOAD_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("TradeLab: snapshot")
            .setContentText(text)
            .setProgress(0, 0, true)
            .setOngoing(true)
        if (cancelIntent != null) {
            b.addAction(
                android.app.Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Отменить",
                    cancelIntent,
                ).build()
            )
        }
        return b.build()
    }

    fun downloading(
        context: Context,
        text: String = "Preparing snapshot…",
        cancelIntent: android.app.PendingIntent? = null,
    ): ForegroundInfo {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        return ForegroundInfo(FOREGROUND_ID, downloadNotification(context, text, cancelIntent), type)
    }

    fun success(context: Context, filename: String, bytes: Long, count: Int = 1) {
        ensureChannels(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        val title = if (count == 1) "TradeLab: snapshot downloaded" else "TradeLab: $count snapshots downloaded"
        val n = android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText("Latest: $filename — ${formatBytes(bytes)}, SHA-256 OK · Download/TradeLab")
            .setAutoCancel(true)
            .build()
        nm.notify(SUCCESS_ID, n)
    }
}
