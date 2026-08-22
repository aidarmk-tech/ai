package com.aidar.tradelab

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)

        status = TextView(this)
        val button = Button(this).apply {
            text = "Download fresh snapshot now"
            setOnClickListener {
                val request = OneTimeWorkRequestBuilder<SnapshotWorker>()
                    .setInputData(workDataOf(SnapshotWorker.KEY_FORCE_CREATE to true))
                    .build()
                WorkManager.getInstance(this@MainActivity).enqueue(request)
                status.text = "Creating and downloading a fresh snapshot…"
            }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 80, 40, 40)
            addView(TextView(this@MainActivity).apply {
                text = "TradeLab 0.1\n4 participants · CHAMPION + RESERVE"
                textSize = 22f
            })
            addView(status)
            addView(button)
        }
        setContentView(root)
        scheduleSnapshots()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun scheduleSnapshots() {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = PeriodicWorkRequestBuilder<SnapshotWorker>(4, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "tradelab-snapshot-4h",
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private fun refreshStatus() {
        val p = getSharedPreferences("snapshots", MODE_PRIVATE)
        val file = p.getString("last_file", null)
        val at = p.getLong("last_at_ms", 0)
        val created = p.getLong("last_snapshot_created_ms", 0)
        val size = p.getLong("last_size", 0)
        status.text = if (file == null) {
            "No downloaded snapshot yet. Automatic interval: 4h."
        } else {
            "Last snapshot: $file\nServer snapshot: ${java.util.Date(created)}\nDownloaded: ${java.util.Date(at)}\nSize: %.1f MB\nStatus: SHA-256 OK".format(size / 1024.0 / 1024.0)
        }
    }
}
