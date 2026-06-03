package com.lampplayer.tv.domain.model

data class EpisodeItem(
    val index: Int,
    val title: String,
    val url: String,
    val season: Int? = null,
    val episode: Int? = null,
    val watched: Boolean = false,
)
