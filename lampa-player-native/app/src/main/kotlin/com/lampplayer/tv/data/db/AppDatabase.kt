package com.lampplayer.tv.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [MetadataEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun metadataDao(): MetadataDao
}
