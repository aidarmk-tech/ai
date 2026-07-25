package com.aidar.pumpradar.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS market_event_clusters (
                id TEXT NOT NULL PRIMARY KEY,
                symbol TEXT NOT NULL,
                startedAt INTEGER NOT NULL,
                endedAt INTEGER,
                firstSignalId TEXT NOT NULL,
                peakImpulseScore INTEGER NOT NULL,
                signalCount INTEGER NOT NULL,
                state TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("ALTER TABLE signals ADD COLUMN eventId TEXT")
        db.execSQL("ALTER TABLE signals ADD COLUMN opportunityLabel TEXT")
        db.execSQL("ALTER TABLE signals ADD COLUMN entryRiskScore INTEGER")
        db.execSQL("ALTER TABLE signals ADD COLUMN confidenceScore INTEGER")
        db.execSQL("ALTER TABLE signals ADD COLUMN exhaustionRisk INTEGER")
        db.execSQL("ALTER TABLE signals ADD COLUMN artificialRisk INTEGER")
        db.execSQL("ALTER TABLE signals ADD COLUMN marketWideRisk INTEGER")
        db.execSQL("ALTER TABLE signals ADD COLUMN liquidityTier TEXT")
        db.execSQL("ALTER TABLE signals ADD COLUMN algorithmVersion TEXT")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS training_snapshots (
                id TEXT NOT NULL PRIMARY KEY,
                signalId TEXT,
                eventId TEXT,
                symbol TEXT NOT NULL,
                snapshotTime INTEGER NOT NULL,
                snapshotType TEXT NOT NULL,
                algorithmVersion TEXT NOT NULL,
                liquidityTier TEXT NOT NULL,
                opportunityLabel TEXT NOT NULL,
                featureVectorJson TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_training_snapshots_eventId ON training_snapshots(eventId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_training_snapshots_symbol ON training_snapshots(symbol)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_training_snapshots_snapshotTime ON training_snapshots(snapshotTime)")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS signal_trajectories (
                signalId TEXT NOT NULL PRIMARY KEY,
                symbol TEXT NOT NULL,
                referencePrice REAL NOT NULL,
                startedAt INTEGER NOT NULL,
                resolutionMs INTEGER NOT NULL,
                pointCount INTEGER NOT NULL,
                pointsJson TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS shadow_signals (
                id TEXT NOT NULL PRIMARY KEY,
                strategy TEXT NOT NULL,
                side TEXT NOT NULL,
                symbol TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                referencePrice REAL NOT NULL,
                spreadBps REAL,
                slippagePercent REAL,
                price30s REAL,
                price1m REAL,
                price3m REAL,
                price5m REAL,
                price15m REAL,
                mfePercent REAL,
                maePercent REAL,
                pointsJson TEXT,
                completed INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_shadow_signals_strategy ON shadow_signals(strategy)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_shadow_signals_createdAt ON shadow_signals(createdAt)")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS snapshot_outcomes (
                snapshotId TEXT NOT NULL PRIMARY KEY,
                symbol TEXT NOT NULL,
                snapshotType TEXT NOT NULL,
                referencePrice REAL NOT NULL,
                createdAt INTEGER NOT NULL,
                price30s REAL, price1m REAL, price3m REAL, price5m REAL, price15m REAL,
                mfePercent REAL, maePercent REAL,
                pointCount INTEGER NOT NULL,
                long075TargetTime INTEGER, long050StopTime INTEGER,
                long100TargetTime INTEGER, long075StopTime INTEGER,
                long200TargetTime INTEGER, long100StopTime INTEGER,
                short075TargetTime INTEGER, short050StopTime INTEGER,
                short100TargetTime INTEGER, short075StopTime INTEGER,
                short200TargetTime INTEGER, short100StopTime INTEGER,
                firstBarrierLong075_050 TEXT, firstBarrierLong100_075 TEXT, firstBarrierLong200_100 TEXT,
                firstBarrierShort075_050 TEXT, firstBarrierShort100_075 TEXT, firstBarrierShort200_100 TEXT,
                completed INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }
}

/** v5 → v6: точная оценка цели +3% и защищённого плана после +1%. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE snapshot_outcomes ADD COLUMN long300TargetTime INTEGER")
        db.execSQL("ALTER TABLE snapshot_outcomes ADD COLUMN firstBarrierLong300_100 TEXT")
        db.execSQL("ALTER TABLE snapshot_outcomes ADD COLUMN plan3ActivationTime INTEGER")
        db.execSQL("ALTER TABLE snapshot_outcomes ADD COLUMN plan3TargetTime INTEGER")
        db.execSQL("ALTER TABLE snapshot_outcomes ADD COLUMN plan3ExitTime INTEGER")
        db.execSQL("ALTER TABLE snapshot_outcomes ADD COLUMN plan3Result TEXT")
        db.execSQL("ALTER TABLE snapshot_outcomes ADD COLUMN plan3GrossReturnPercent REAL")
    }
}
