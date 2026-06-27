package com.example.associations.game

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.associations.data.GameStorage
import com.example.associations.data.Settings
import com.example.associations.model.GameState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * MVVM: единственный источник истины — [state] (StateFlow<GameState>).
 * UI только читает состояние и шлёт события (ходы) обратно во ViewModel.
 */
class GameViewModel(app: Application) : AndroidViewModel(app) {

    private val storage = GameStorage(app)
    private val sound = SoundManager()

    private val _state = MutableStateFlow(GameLogic.newGame(1))
    val state: StateFlow<GameState> = _state.asStateFlow()

    /** Текущий уровень сложности игрока (растёт с каждой победой). */
    private val _level = MutableStateFlow(1)
    val level: StateFlow<Int> = _level.asStateFlow()

    /** Полная история для Undo. */
    private val history = ArrayDeque<GameState>()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    val settings: StateFlow<Settings> = storage.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    private var timerJob: Job? = null

    init {
        viewModelScope.launch { _level.value = storage.loadLevel() }
    }

    /** Пытается продолжить сохранённую партию; иначе раздаёт новую текущего уровня. */
    fun startOrResume() {
        viewModelScope.launch {
            val saved = storage.loadGame()
            if (saved != null && !saved.isWon) {
                _state.value = saved
                _level.value = saved.level
            } else {
                _state.value = GameLogic.newGame(_level.value)
            }
            history.clear()
            _canUndo.value = false
            startTimer()
        }
    }

    /** Новая партия текущего уровня. */
    fun newGame() {
        _state.value = GameLogic.newGame(_level.value)
        history.clear()
        _canUndo.value = false
        persist()
        startTimer()
    }

    // ----- Ходы ------------------------------------------------------------

    fun onStockClick() {
        val next = GameLogic.drawFromStock(_state.value) ?: return
        commit(next)
        sound.tap(settings.value.soundEnabled)
    }

    /** Перемещение карты/серии (через drag или tap-выбор). */
    fun onMove(fromPile: Int, cardIndex: Int, toPile: Int): Boolean {
        val next = GameLogic.move(_state.value, fromPile, cardIndex, toPile) ?: return false
        commit(next)
        sound.tap(settings.value.soundEnabled)
        if (next.isWon) onWin()
        return true
    }

    /** Двойной тап — авто-отправка на основание. */
    fun onAutoMove(fromPile: Int, cardIndex: Int): Boolean {
        val next = GameLogic.autoToFoundation(_state.value, fromPile, cardIndex) ?: return false
        commit(next)
        sound.tap(settings.value.soundEnabled)
        if (next.isWon) onWin()
        return true
    }

    fun undo() {
        if (history.isEmpty()) return
        val prev = history.removeLast()
        _state.value = prev
        _canUndo.value = history.isNotEmpty()
        persist()
    }

    private fun onWin() {
        stopTimer()
        sound.win(settings.value.soundEnabled)
        // Повышаем уровень — следующая партия будет сложнее.
        val next = _state.value.level + 1
        _level.value = next
        viewModelScope.launch {
            storage.saveLevel(next)
            storage.clearGame()
        }
    }

    private fun commit(next: GameState) {
        history.addLast(_state.value)
        if (history.size > MAX_HISTORY) history.removeFirst()
        _canUndo.value = true
        _state.value = next
        persist()
    }

    // ----- Таймер ----------------------------------------------------------

    private fun startTimer() {
        stopTimer()
        timerJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                val s = _state.value
                if (!s.isWon) {
                    _state.value = s.copy(elapsedSec = s.elapsedSec + 1)
                }
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    // ----- Сохранение и настройки -----------------------------------------

    private fun persist() {
        val snapshot = _state.value
        viewModelScope.launch { storage.saveGame(snapshot) }
    }

    fun setSound(enabled: Boolean) {
        viewModelScope.launch { storage.setSound(enabled) }
    }

    fun setDark(enabled: Boolean) {
        viewModelScope.launch { storage.setDark(enabled) }
    }

    fun onPause() {
        stopTimer()
        persist()
    }

    override fun onCleared() {
        stopTimer()
        sound.release()
        super.onCleared()
    }

    private companion object {
        const val MAX_HISTORY = 200
    }
}
