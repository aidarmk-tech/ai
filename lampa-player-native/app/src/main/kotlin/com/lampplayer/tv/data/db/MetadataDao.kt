package com.lampplayer.tv.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MetadataDao {
    @Query("SELECT * FROM metadata_cache WHERE tmdbId = :id AND type = :type LIMIT 1")
    suspend fun get(id: Int, type: String): MetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MetadataEntity)

    @Query("DELETE FROM metadata_cache WHERE cachedAt < :before")
    suspend fun deleteExpired(before: Long)
}
