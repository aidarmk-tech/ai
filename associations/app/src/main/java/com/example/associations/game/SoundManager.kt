package com.example.associations.game

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * Простые звуки тапа/победы через системный ToneGenerator — без raw-ресурсов.
 * Включение/выключение управляется настройкой.
 */
class SoundManager {
    private var tone: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, 70)
    } catch (e: RuntimeException) {
        null
    }

    fun tap(enabled: Boolean) {
        if (!enabled) return
        runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 60) }
    }

    fun win(enabled: Boolean) {
        if (!enabled) return
        runCatching { tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 400) }
    }

    fun release() {
        runCatching { tone?.release() }
        tone = null
    }
}
