package com.aidar.tradelab

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object NotificationHelper {
    private const val CHANNEL = "snapshots"

    fun success(context: Context, filename: String, bytes: Long) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "TradeLab snapshots", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val mb = bytes / 1024.0 / 1024.0
        val n = android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("TradeLab: snapshot downloaded")
            .setContentText("$filename — %.1f MB, SHA-256 OK".format(mb))
            .setAutoCancel(true)
            .build()
        nm.notify(filename.hashCode(), n)
    }
}
