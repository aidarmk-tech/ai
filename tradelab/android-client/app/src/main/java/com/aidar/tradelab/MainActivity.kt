package com.aidar.tradelab

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private var dashboard: DashboardScreen? = null
    private var snapshots: SnapshotsScreen? = null
    private var settings: SettingsScreen? = null
    private var currentId = 0
    private val uiHandler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            if (currentId == TAB_SNAPSHOTS) snapshots?.tick()
            uiHandler.postDelayed(this, 500L)
        }
    }

    private val autoRefresh = object : Runnable {
        override fun run() {
            if (currentId == TAB_DASHBOARD && !lifecyclePaused) dashboard?.refresh(force = false)
            uiHandler.postDelayed(this, 45_000L)
        }
    }
    private var lifecyclePaused = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        if (android.os.Build.VERSION.SDK_INT <= 28 &&
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            @Suppress("DEPRECATION")
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 101)
        }
        WorkManager.getInstance(this).cancelUniqueWork(LEGACY_MANUAL_WORK)
        scheduleSnapshots()
        rebuild()
    }

    fun configured(): Boolean {
        val c = getSharedPreferences("connection", MODE_PRIVATE)
        return !c.getString("read_token", "").isNullOrBlank() &&
            !(c.getString("server_url", BuildConfig.SERVER_URL) ?: "").isBlank()
    }

    fun rebuild() {
        if (!configured()) {
            settings = SettingsScreen(this, onboarding = true) { rebuild() }
            setContentView(settings!!.view())
            currentId = 0
            return
        }
        dashboard = DashboardScreen(this)
        snapshots = SnapshotsScreen(this)

        val container = FrameLayout(this)
        container.addView(dashboard!!.view())
        currentId = TAB_DASHBOARD

        val nav = BottomNavigationView(this)
        nav.menu.apply {
            add(0, TAB_DASHBOARD, 0, "Дашборд")
            add(0, TAB_SNAPSHOTS, 1, "Снапшоты")
            add(0, TAB_SETTINGS, 2, "Настройки")
        }
        nav.setBackgroundColor(Ui.CARD)
        nav.itemIconTintList = null
        nav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                TAB_DASHBOARD -> showTab(container, nav, dashboard?.view())
                TAB_SNAPSHOTS -> {
                    showTab(container, nav, snapshots?.view())
                    snapshots?.onResumeTick()
                }
                else -> showSettings(container, nav)
            }
            true
        }

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }
        root.addView(
            container,
            android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        root.addView(nav)
        setContentView(root)
        dashboard?.refresh()
    }

    private fun showTab(container: FrameLayout, nav: BottomNavigationView, view: android.view.View?) {
        currentId = nav.selectedItemId
        container.removeAllViews()
        if (view != null) container.addView(view)
    }

    private fun showSettings(container: FrameLayout, nav: BottomNavigationView) {
        currentId = TAB_SETTINGS
        container.removeAllViews()
        settings = SettingsScreen(this, onboarding = false) {}
        container.addView(settings!!.view())
    }

    override fun onResume() {
        super.onResume()
        lifecyclePaused = false
        uiHandler.post(tick)
        uiHandler.post(autoRefresh)
    }

    override fun onPause() {
        lifecyclePaused = true
        uiHandler.removeCallbacks(tick)
        uiHandler.removeCallbacks(autoRefresh)
        super.onPause()
    }

    fun startManual(mode: String) {
        val p = getSharedPreferences("snapshots", MODE_PRIVATE)
        p.edit()
            .putBoolean(ManualSnapshotService.KEY_ACTIVE, true)
            .putString(ManualSnapshotService.KEY_MODE, mode)
            .putString(ManualSnapshotService.KEY_STAGE, ManualSnapshotService.STAGE_STARTING)
            .remove(ManualSnapshotService.KEY_ERROR)
            .putLong(ManualSnapshotService.KEY_DONE, 0L)
            .putLong(ManualSnapshotService.KEY_TOTAL, 0L)
            .apply()
        val intent = Intent(this, ManualSnapshotService::class.java)
            .putExtra(ManualSnapshotService.EXTRA_MODE, mode)
        startForegroundService(intent)
    }

    private fun scheduleSnapshots() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<SnapshotWorker>(4, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(SnapshotWorker.KEY_MODE to SnapshotWorker.MODE_CATCHUP))
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "tradelab-snapshot-4h",
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    companion object {
        const val LEGACY_MANUAL_WORK = "tradelab-manual-snapshot"
        const val TAB_DASHBOARD = 1001
        const val TAB_SNAPSHOTS = 1002
        const val TAB_SETTINGS = 1003
    }
}
