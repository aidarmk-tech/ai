package com.aidar.tradelab

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
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
    private lateinit var serverUrl: EditText
    private lateinit var readToken: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        if (android.os.Build.VERSION.SDK_INT <= 28) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 101)
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
                val request = OneTimeWorkRequestBuilder<SnapshotWorker>()
                    .setInputData(workDataOf(SnapshotWorker.KEY_FORCE_CREATE to true))
                    .build()
                WorkManager.getInstance(this@MainActivity).enqueue(request)
                status.text = "Creating and downloading a fresh snapshot…\nDestination: Download/TradeLab"
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 80, 40, 40)
            addView(TextView(this@MainActivity).apply {
                text = "TradeLab 0.1\n4 participants · CHAMPION + RESERVE"
                textSize = 22f
            })
            addView(serverUrl)
            addView(readToken)
            addView(saveButton)
            addView(TextView(this@MainActivity).apply { text = "Snapshots: Download/TradeLab" })
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
            "No downloaded snapshot yet. Automatic interval: 4h.\nDestination: Download/TradeLab"
        } else {
            "Last snapshot: $file\nServer snapshot: ${java.util.Date(created)}\nDownloaded: ${java.util.Date(at)}\nSize: ${formatBytes(size)}\nSaved to: Download/TradeLab\nStatus: SHA-256 OK"
        }
    }
}
