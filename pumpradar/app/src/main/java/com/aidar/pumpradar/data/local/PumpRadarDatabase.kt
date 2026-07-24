package com.aidar.pumpradar.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        SignalEntity::class, OutcomeEntity::class, AppEventEntity::class,
        MarketEventClusterEntity::class, TrainingSnapshotEntity::class,
        SignalTrajectoryEntity::class, ShadowSignalEntity::class,
        SnapshotOutcomeEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class PumpRadarDatabase : RoomDatabase() {
    abstract fun signalDao(): SignalDao
    abstract fun outcomeDao(): OutcomeDao
    abstract fun appEventDao(): AppEventDao
    abstract fun clusterDao(): ClusterDao
    abstract fun trainingSnapshotDao(): TrainingSnapshotDao
    abstract fun signalTrajectoryDao(): SignalTrajectoryDao
    abstract fun shadowSignalDao(): ShadowSignalDao
    abstract fun snapshotOutcomeDao(): SnapshotOutcomeDao
}
