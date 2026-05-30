package com.lampa.player.domain.model

data class PlaybackPosition(
    val time: Double = 0.0,
    val duration: Double = 0.0,
    val percent: Int = 0,
    val watched: Boolean = false,
    val updated: Long = System.currentTimeMillis(),
)
