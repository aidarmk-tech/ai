package com.lampplayer.tv.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource

@UnstableApi
class ErrorRecoveryManager {

    enum class Action { RETRY, SHOW_FATAL }

    private var retryCount = 0
    private val maxRetries = 3

    /** True when the last error was a network/IO failure (vs decode/parse). */
    var lastWasNetwork = false
        private set

    /** Backoff before the upcoming retry: 2s, 4s, 8s, 16s (capped). */
    val retryDelayMs: Long get() = 1000L shl retryCount.coerceIn(1, 4)

    fun reset() { retryCount = 0; lastWasNetwork = false }

    /**
     * Classify the error and prepare the player for a retry. The caller schedules
     * `player.prepare()` after [retryDelayMs]. Network errors retry **indefinitely**
     * (with capped backoff); permanent HTTP/access failures stop and other 4xx responses are bounded
     * (408/429 remain retryable) — a dropped/мобильный connection should reconnect, not
     * give up; decode/parse errors are bounded to [maxRetries] before going fatal.
     */
    fun onError(error: PlaybackException, player: Player): Action {
        val status = httpFailure(error)?.responseCode
        val permanent = status in listOf(401, 403, 410) ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NO_PERMISSION ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED
        // A missing HLS segment can be temporary, but retrying the same missing URL forever cannot recover.
        val boundedHttp = error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS &&
            (status == null || (status in 400..499 && status !in listOf(408, 429)))
        if (permanent || boundedHttp) {
            lastWasNetwork = false
            if (permanent || retryCount >= maxRetries) return Action.SHOW_FATAL
            retryCount++
            return Action.RETRY
        }
        lastWasNetwork = isNetwork(error)
        if (lastWasNetwork) {
            retryCount = (retryCount + 1).coerceAtMost(4)   // unbounded retries, capped delay
            return Action.RETRY
        }
        if (retryCount >= maxRetries) { retryCount = 0; return Action.SHOW_FATAL }
        retryCount++
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> {
                // Force lowest bitrate / software decoding
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setForceLowestBitrate(true)
                    .build()
                Action.RETRY
            }
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> Action.RETRY
            else -> Action.SHOW_FATAL
        }
    }

    private fun isNetwork(e: PlaybackException): Boolean = e.errorCode in setOf(
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    )

    companion object {
        fun httpFailure(error: Throwable): HttpDataSource.InvalidResponseCodeException? {
            var cause: Throwable? = error
            repeat(12) {
                val current = cause ?: return null
                if (current is HttpDataSource.InvalidResponseCodeException) return current
                cause = current.cause?.takeUnless { it === current }
            }
            return null
        }
        fun httpMessage(error: Throwable): String? = httpFailure(error)?.responseCode?.let { code ->
            when (code) {
                404, 410 -> "Видео или часть потока не найдены (HTTP $code). Вернись к списку, обнови ссылки или выбери другое качество."
                401, 403 -> "Источник отказал в доступе (HTTP $code). Проверь доступ к видео на странице источника."
                else -> "Сервер видео вернул HTTP $code"
            }
        }
    }
}
