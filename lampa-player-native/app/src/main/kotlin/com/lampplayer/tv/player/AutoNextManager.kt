package com.lampplayer.tv.player

import kotlinx.coroutines.*
import javax.inject.Inject

class AutoNextManager @Inject constructor() {

    private var countdownJob: Job? = null
    private var remainingSeconds = 0
    // Once the user cancels, stay off for this episode — otherwise the next
    // position tick re-enters checkAndStart and restarts the countdown, making
    // "Cancel" look broken. Reset() (called on each new episode) re-arms it.
    private var suppressed = false

    fun checkAndStart(
        durationSec: Double,
        currentTimeSec: Double,
        delaySeconds: Int,
        scope: CoroutineScope,
        onTick: (Int) -> Unit,
        onNext: () -> Unit,
    ) {
        if (suppressed) return
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

    /** User cancelled: stop and stay suppressed until the next episode re-arms it. */
    fun suppress() {
        cancel()
        suppressed = true
    }

    /** New episode/media: clear suppression so auto-next can arm again. */
    fun reset() {
        cancel()
        suppressed = false
    }

    fun isActive() = countdownJob?.isActive == true
}
