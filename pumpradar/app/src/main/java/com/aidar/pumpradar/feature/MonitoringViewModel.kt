package com.aidar.pumpradar.feature

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aidar.pumpradar.data.local.AppEventDao
import com.aidar.pumpradar.data.local.SignalDao
import com.aidar.pumpradar.data.preferences.AppSettings
import com.aidar.pumpradar.data.preferences.SettingsRepository
import com.aidar.pumpradar.service.MonitoringController
import com.aidar.pumpradar.service.MonitoringService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar
import javax.inject.Inject

@HiltViewModel
class MonitoringViewModel @Inject constructor(
    app: Application,
    private val controller: MonitoringController,
    settingsRepo: SettingsRepository,
    signalDao: SignalDao,
    appEventDao: AppEventDao
) : AndroidViewModel(app) {

    val state = controller.state
    val stats = controller.stats
    val paused = controller.paused
    val candidates = controller.candidates

    val settings = settingsRepo.settings.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings()
    )

    val signalsToday = signalDao.countSince(startOfToday()).stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 0
    )
    val strongToday = signalDao.countSinceWithScore(startOfToday(), 70).stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 0
    )
    val appEvents = appEventDao.recent(100).stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    fun startMonitoring() = MonitoringService.start(getApplication())
    fun stopMonitoring() = MonitoringService.stop(getApplication())
    fun togglePause() = controller.setPaused(!controller.paused.value)

    private fun startOfToday(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }
}
