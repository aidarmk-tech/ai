package com.aidar.tradelab

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object NotificationHelper {
    private const val CHANNEL = "snapshots"

    fun success(context: Context, filename: String, bytes: Long, count: Int = 1) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "TradeLab snapshots", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val title = if (count == 1) "TradeLab: snapshot downloaded" else "TradeLab: $count snapshots downloaded"
        val n = android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText("Latest: $filename — ${formatBytes(bytes)}, SHA-256 OK · Download/TradeLab")
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, n)
    }

    private const val NOTIFICATION_ID = 4101
}
