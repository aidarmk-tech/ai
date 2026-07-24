package com.aidar.pumpradar.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 → v2 (патч §12/§26): таблица кластеров рыночных событий + новые колонки
 * сигнала (eventId, трёхосевые риски, метка, тир, версия алгоритма). Не
 * destructive — существующая история сигналов и исходов сохраняется.
 */
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
        // Новые nullable-колонки сигнала.
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

/**
 * v2 → v3 (патч §15): таблица training_snapshots для будущего ML. Только новая
 * таблица — существующие данные не затрагиваются.
 */
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
