package com.lampplayer.tv.player

import kotlinx.coroutines.*
import javax.inject.Inject

class AutoNextManager @Inject constructor() {

    private var countdownJob: Job? = null
    private var remainingSeconds = 0

    fun checkAndStart(
        durationSec: Double,
        currentTimeSec: Double,
        delaySeconds: Int,
        scope: CoroutineScope,
        onTick: (Int) -> Unit,
        onNext: () -> Unit,
    ) {
        val remaining = durationSec - currentTimeSec
        if (remaining > delaySeconds || durationSec <= 0) return
        if (countdownJob?.isActive == true) return

        remainingSeconds = remaining.toInt().coerceAtLeast(0)
        countdownJob = scope.launch {
            while (remainingSeconds > 0) {
                onTick(remainingSeconds)
                delay(1000)
                remainingSeconds--
            }
            onNext()
        }
    }

    fun cancel() {
        countdownJob?.cancel()
        countdownJob = null
        remainingSeconds = 0
    }

    fun isActive() = countdownJob?.isActive == true
}
