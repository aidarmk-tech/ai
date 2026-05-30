package com.lampa.player.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

@UnstableApi
class ErrorRecoveryManager {

    enum class Action { RETRY, SHOW_FATAL }

    private var retryCount = 0
    private val maxRetries = 3

    fun reset() { retryCount = 0 }

    fun onError(error: PlaybackException, player: Player): Action {
        if (retryCount >= maxRetries) { retryCount = 0; return Action.SHOW_FATAL }
        retryCount++
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
                player.prepare()
                Action.RETRY
            }
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> {
                // Force lowest bitrate / software decoding
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setForceLowestBitrate(true)
                    .build()
                player.prepare()
                Action.RETRY
            }
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> {
                // Try again — sometimes manifest is temporarily broken
                player.prepare()
                Action.RETRY
            }
            else -> Action.SHOW_FATAL
        }
    }
}
