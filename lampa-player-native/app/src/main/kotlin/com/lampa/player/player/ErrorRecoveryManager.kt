package com.lampa.player.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay

@UnstableApi
class ErrorRecoveryManager {

    enum class Action { RETRY, SHOW_FATAL }

    private var retryCount = 0
    private val maxRetries = 3

    fun onError(error: PlaybackException, player: Player): Action {
        if (retryCount >= maxRetries) {
            retryCount = 0
            return Action.SHOW_FATAL
        }
        retryCount++
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            -> {
                player.prepare()
                Action.RETRY
            }
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            -> {
                // Fallback: disable hardware acceleration via track selector
                val params = player.trackSelectionParameters.buildUpon()
                    .setForceLowestBitrate(true)
                    .build()
                player.trackSelectionParameters = params
                player.prepare()
                Action.RETRY
            }
            else -> Action.SHOW_FATAL
        }
    }
}
