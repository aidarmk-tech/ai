package com.binancetrader.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BotStatus(
    val running: Boolean = false,
    val statusLine: String = "Бот остановлен",
    val logLines: List<String> = emptyList()
)

/** Общее состояние бота: сервис пишет, Activity подписывается. */
object BotState {

    private val fmt = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault())

    private val _status = MutableStateFlow(BotStatus())
    val status: StateFlow<BotStatus> = _status

    @Synchronized
    fun setRunning(running: Boolean) {
        _status.value = _status.value.copy(
            running = running,
            statusLine = if (running) "Бот запущен" else "Бот остановлен"
        )
    }

    @Synchronized
    fun setStatusLine(line: String) {
        _status.value = _status.value.copy(statusLine = line)
    }

    @Synchronized
    fun log(line: String) {
        val stamped = "${fmt.format(Date())}  $line"
        val lines = (_status.value.logLines + stamped).takeLast(300)
        _status.value = _status.value.copy(logLines = lines)
    }
}
