package com.aidar.pumpradar.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "signals")
data class SignalEntity(
    @PrimaryKey val id: String,
    val symbol: String,
    val createdAt: Long,
    val level: String,
    val stage: String,
    val score: Int,
    val referencePrice: Double,
    val return15s: Double?,
    val return60s: Double?,
    val return5m: Double?,
    val quoteVolume30s: Double?,
    val volumeZ30s: Double?,
    val tradeCount30s: Int?,
    val takerBuyRatio30s: Double?,
    val cvd30s: Double?,
    val spreadBps: Double?,
    val obi10: Double?,
    val slippagePercent: Double?,
    val relativeStrengthVsBtc: Double?,
    val reasonsJson: String,
    val risksJson: String,
    val dataQualityJson: String,
    // Патч §12/§26 (v2): кластеризация и трёхосевые риски. Nullable — совместимо
    // со старыми записями после миграции.
    val eventId: String? = null,
    val opportunityLabel: String? = null,
    val entryRiskScore: Int? = null,
    val confidenceScore: Int? = null,
    val exhaustionRisk: Int? = null,
    val artificialRisk: Int? = null,
    val marketWideRisk: Int? = null,
    val liquidityTier: String? = null,
    val algorithmVersion: String? = null
)

/** Кластер сигналов одного рыночного события (патч §12). */
@Entity(tableName = "market_event_clusters")
data class MarketEventClusterEntity(
    @PrimaryKey val id: String,
    val symbol: String,
    val startedAt: Long,
    val endedAt: Long?,
    val firstSignalId: String,
    val peakImpulseScore: Int,
    val signalCount: Int,
    val state: String
)

@Entity(
    tableName = "outcomes",
    foreignKeys = [
        ForeignKey(
            entity = SignalEntity::class,
            parentColumns = ["id"],
            childColumns = ["signalId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("signalId")]
)
data class OutcomeEntity(
    @PrimaryKey val signalId: String,
    val price30s: Double?,
    val price1m: Double?,
    val price3m: Double?,
    val price5m: Double?,
    val price15m: Double?,
    val mfePercent: Double?,
    val maePercent: Double?,
    val timeToMfeSeconds: Long?,
    val evaluatedUntil: Long,
    val completed: Boolean,
    val approximate: Boolean = false
)

/** Проекция: завершённый исход вместе с полями исходного сигнала (JOIN). */
data class SignalOutcome(
    val symbol: String,
    val level: String,
    val score: Int,
    val createdAt: Long,
    val mfePercent: Double?,
    val maePercent: Double?,
    val timeToMfeSeconds: Long?,
    val spreadBps: Double?,        // спред на момент сигнала — для executable-оценки (0A.13)
    val slippagePercent: Double?,  // проскальзывание на момент сигнала
    // Опорная цена и контрольные точки — для настраиваемого критерия «цель/стоп/горизонт».
    val referencePrice: Double?,
    val price30s: Double?,
    val price1m: Double?,
    val price3m: Double?,
    val price5m: Double?,
    val price15m: Double?,
    val eventId: String?          // кластер рыночного события (патч §12)
)

@Entity(tableName = "app_events")
data class AppEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val severity: String,
    val subsystem: String,
    val message: String
)
