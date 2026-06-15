package com.lampplayer.tv.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

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
     * (with capped backoff) — a dropped/мобильный connection should reconnect, not
     * give up; decode/parse errors are bounded to [maxRetries] before going fatal.
     */
    fun onError(error: PlaybackException, player: Player): Action {
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
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
    )
}
