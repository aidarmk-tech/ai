package com.aidar.pumpradar.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import com.aidar.pumpradar.domain.model.MonitoringStats
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

/**
 * Foreground-сервис фонового мониторинга (Режим B). Показывает постоянное
 * уведомление и держит движок анализа. Запускается только явным действием
 * пользователя. Тип specialUse (ТЗ раздел 5.2).
 */
@AndroidEntryPoint
class MonitoringService : Service() {

    @Inject lateinit var controller: MonitoringController
    @Inject lateinit var notificationFactory: ServiceNotificationFactory

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMonitoring()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_PAUSE -> {
                controller.setPaused(!controller.paused.value)
                refreshNotification()
                return START_STICKY
            }
            else -> if (!started) startMonitoring()
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        started = true
        controller.onStarting()
        val notif = notificationFactory.build(MonitoringStats(), paused = false)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        controller.onStarted()

        // Перерисовываем уведомление при изменении статистики/паузы.
        combine(controller.stats, controller.paused) { stats, paused -> stats to paused }
            .onEach { (stats, paused) ->
                if (started) {
                    NotificationManagerCompat.from(this).notify(
                        NOTIF_ID, notificationFactory.build(stats, paused)
                    )
                }
            }
            .launchIn(scope)
    }

    private fun refreshNotification() {
        NotificationManagerCompat.from(this)
            .notify(NOTIF_ID, notificationFactory.build(controller.stats.value, controller.paused.value))
    }

    private fun stopMonitoring() {
        started = false
        controller.onStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        if (started) controller.onStopped()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.aidar.pumpradar.START"
        const val ACTION_STOP = "com.aidar.pumpradar.STOP"
        const val ACTION_TOGGLE_PAUSE = "com.aidar.pumpradar.TOGGLE_PAUSE"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            val i = Intent(context, MonitoringService::class.java).setAction(ACTION_START)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MonitoringService::class.java).setAction(ACTION_STOP))
        }
    }
}
