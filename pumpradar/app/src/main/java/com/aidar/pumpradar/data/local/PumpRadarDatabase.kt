package com.aidar.pumpradar.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [SignalEntity::class, OutcomeEntity::class, AppEventEntity::class],
    version = 1,
    exportSchema = false
)
abstract class PumpRadarDatabase : RoomDatabase() {
    abstract fun signalDao(): SignalDao
    abstract fun outcomeDao(): OutcomeDao
    abstract fun appEventDao(): AppEventDao
}
