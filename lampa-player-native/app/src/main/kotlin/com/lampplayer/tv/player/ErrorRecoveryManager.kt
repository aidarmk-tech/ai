package com.lampplayer.tv.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

@UnstableApi
class ErrorRecoveryManager {

    enum class Action { RETRY, SHOW_FATAL }

    private var retryCount = 0
    private val maxRetries = 3

    /** Backoff before the upcoming retry: 2s, 4s, 8s — don't hammer a dead network. */
    val retryDelayMs: Long get() = 1000L shl retryCount.coerceIn(1, 3)

    fun reset() { retryCount = 0 }

    /**
     * Classify the error and prepare the player's parameters for a retry.
     * The caller schedules `player.prepare()` itself after [retryDelayMs].
     */
    fun onError(error: PlaybackException, player: Player): Action {
        if (retryCount >= maxRetries) { retryCount = 0; return Action.SHOW_FATAL }
        retryCount++
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> Action.RETRY
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
}
