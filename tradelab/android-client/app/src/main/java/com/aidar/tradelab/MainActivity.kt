package com.aidar.tradelab

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var serverUrl: EditText
    private lateinit var readToken: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }

        val connection = getSharedPreferences("connection", MODE_PRIVATE)
        serverUrl = EditText(this).apply {
            hint = "Server URL"
            setText(connection.getString("server_url", BuildConfig.SERVER_URL) ?: BuildConfig.SERVER_URL)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        readToken = EditText(this).apply {
            hint = "Read token"
            setText(connection.getString("read_token", "") ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }

        status = TextView(this)

        val saveButton = Button(this).apply {
            text = "Save connection"
            setOnClickListener {
                if (saveConnection()) status.text = "Connection settings saved.\nSnapshots folder: Download/TradeLab"
            }
        }

        val button = Button(this).apply {
            text = "Download fresh snapshot now"
            setOnClickListener {
                if (!saveConnection()) return@setOnClickListener
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
                val request = OneTimeWorkRequestBuilder<SnapshotWorker>()
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                    .setInputData(workDataOf(SnapshotWorker.KEY_FORCE_CREATE to true))
                    .build()
                // KEEP means repeated taps do not create multiple large snapshots.
                // If a transfer is retrying, the same job continues its .part file.
                WorkManager.getInstance(this@MainActivity).enqueueUniqueWork(
                    "tradelab-manual-snapshot",
                    ExistingWorkPolicy.KEEP,
                    request,
                )
                status.text = "Creating/downloading snapshot…\nYou can lock the screen. Interrupted transfers resume automatically.\nDestination: Download/TradeLab"
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 80, 40, 40)
            addView(TextView(this@MainActivity).apply {
                text = "TradeLab 0.2.1\n4 participants · CHAMPION + RESERVE"
                textSize = 22f
            })
            addView(serverUrl)
            addView(readToken)
            addView(saveButton)
            addView(TextView(this@MainActivity).apply { text = "Snapshots: Download/TradeLab · resumable" })
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

    private fun saveConnection(): Boolean {
        val url = serverUrl.text.toString().trim().trimEnd('/')
        val token = readToken.text.toString().trim()
        if (!url.startsWith("https://")) {
            status.text = "HTTPS server URL is required."
            return false
        }
        if (token.isBlank()) {
            status.text = "Enter the Android read token printed by the VPS bootstrap."
            return false
        }
        getSharedPreferences("connection", MODE_PRIVATE).edit()
            .putString("server_url", url)
            .putString("read_token", token)
            .apply()
        return true
    }

    private fun scheduleSnapshots() {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = PeriodicWorkRequestBuilder<SnapshotWorker>(4, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
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
        val downloading = p.getString("downloading_file", null)
        val done = p.getLong("download_done", 0)
        val total = p.getLong("download_total", 0)
        val error = p.getString("last_error", null)

        val progress = if (downloading != null && total > 0) {
            "\nCurrent: $downloading\nProgress: ${formatBytes(done)} / ${formatBytes(total)}\nResume: enabled"
        } else ""
        val problem = if (!error.isNullOrBlank()) {
            "\nLast interruption: $error\nWorkManager will retry from the saved partial file."
        } else ""

        status.text = if (file == null) {
            "No completed snapshot yet. Automatic interval: 4h.\nDestination: Download/TradeLab$progress$problem"
        } else {
            "Last snapshot: $file\nServer snapshot: ${java.util.Date(created)}\nDownloaded: ${java.util.Date(at)}\nSize: ${formatBytes(size)}\nSaved to: Download/TradeLab\nStatus: SHA-256 OK$progress$problem"
        }
    }
}
