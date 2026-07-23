package com.aidar.pumpradar.service

import com.aidar.pumpradar.domain.model.Candidate
import com.aidar.pumpradar.domain.model.LiveSignal
import com.aidar.pumpradar.domain.model.MonitoringState
import com.aidar.pumpradar.domain.model.MonitoringStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Единый держатель состояния мониторинга. Сервис и ViewModel'и читают отсюда.
 * Аналитический движок (WebSocket + сканер) подключается на следующем этапе
 * и публикует сюда обновления через updateStats/setState.
 */
@Singleton
class MonitoringController @Inject constructor() {

    private val _state = MutableStateFlow<MonitoringState>(MonitoringState.Stopped)
    val state: StateFlow<MonitoringState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(MonitoringStats())
    val stats: StateFlow<MonitoringStats> = _stats.asStateFlow()

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    private val _candidates = MutableStateFlow<List<Candidate>>(emptyList())
    val candidates: StateFlow<List<Candidate>> = _candidates.asStateFlow()

    fun setCandidates(list: List<Candidate>) {
        _candidates.value = list
    }

    private val _liveSignals = MutableStateFlow<List<LiveSignal>>(emptyList())
    val liveSignals: StateFlow<List<LiveSignal>> = _liveSignals.asStateFlow()

    fun setLiveSignals(list: List<LiveSignal>) {
        _liveSignals.value = list
    }

    fun signalFor(symbol: String): LiveSignal? = _liveSignals.value.firstOrNull { it.symbol == symbol }

    fun onStarting() {
        _state.value = MonitoringState.Starting
    }

    fun onStarted() {
        _paused.value = false
        _state.value = MonitoringState.Running(System.currentTimeMillis())
    }

    fun onStopped() {
        _state.value = MonitoringState.Stopped
        _stats.value = MonitoringStats()
        _paused.value = false
        _candidates.value = emptyList()
        _liveSignals.value = emptyList()
    }

    fun setPaused(value: Boolean) {
        _paused.value = value
    }

    fun setDegraded(message: String) {
        _state.value = MonitoringState.Degraded(message)
    }

    fun setError(message: String, recoverable: Boolean) {
        _state.value = MonitoringState.Error(message, recoverable)
    }

    fun updateStats(transform: (MonitoringStats) -> MonitoringStats) {
        _stats.value = transform(_stats.value)
    }

    val isActive: Boolean
        get() = _state.value is MonitoringState.Running ||
            _state.value is MonitoringState.Starting ||
            _state.value is MonitoringState.Degraded
}
