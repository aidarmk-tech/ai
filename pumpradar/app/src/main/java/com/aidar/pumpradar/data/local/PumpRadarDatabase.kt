package com.aidar.pumpradar.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        SignalEntity::class, OutcomeEntity::class, AppEventEntity::class,
        MarketEventClusterEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class PumpRadarDatabase : RoomDatabase() {
    abstract fun signalDao(): SignalDao
    abstract fun outcomeDao(): OutcomeDao
    abstract fun appEventDao(): AppEventDao
    abstract fun clusterDao(): ClusterDao
}
