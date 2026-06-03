package com.lampplayer.tv.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "metadata_cache")
data class MetadataEntity(
    @PrimaryKey val tmdbId: Int,
    val type: String,
    val json: String,
    val cachedAt: Long = System.currentTimeMillis(),
)
