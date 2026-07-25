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

@Entity(
    tableName = "training_snapshots",
    indices = [Index("eventId"), Index("symbol"), Index("snapshotTime")]
)
data class TrainingSnapshotEntity(
    @PrimaryKey val id: String,
    val signalId: String?,
    val eventId: String?,
    val symbol: String,
    val snapshotTime: Long,
    val snapshotType: String,
    val algorithmVersion: String,
    val liquidityTier: String,
    val opportunityLabel: String,
    val featureVectorJson: String
)

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

data class SignalOutcome(
    val symbol: String,
    val level: String,
    val score: Int,
    val createdAt: Long,
    val mfePercent: Double?,
    val maePercent: Double?,
    val timeToMfeSeconds: Long?,
    val spreadBps: Double?,
    val slippagePercent: Double?,
    val referencePrice: Double?,
    val price30s: Double?,
    val price1m: Double?,
    val price3m: Double?,
    val price5m: Double?,
    val price15m: Double?,
    val eventId: String?,
    val opportunityLabel: String?,
    val liquidityTier: String?,
    val entryRiskScore: Int?,
    val confidenceScore: Int?
)

@Entity(tableName = "signal_trajectories")
data class SignalTrajectoryEntity(
    @PrimaryKey val signalId: String,
    val symbol: String,
    val referencePrice: Double,
    val startedAt: Long,
    val resolutionMs: Long,
    val pointCount: Int,
    val pointsJson: String
)

/**
 * Исход снимка признаков для supervised learning. Помимо MFE/MAE хранит точный
 * порядок достижения барьеров и результат защищённого плана сопровождения до +3%.
 */
@Entity(tableName = "snapshot_outcomes")
data class SnapshotOutcomeEntity(
    @PrimaryKey val snapshotId: String,
    val symbol: String,
    val snapshotType: String,
    val referencePrice: Double,
    val createdAt: Long,
    val price30s: Double? = null,
    val price1m: Double? = null,
    val price3m: Double? = null,
    val price5m: Double? = null,
    val price15m: Double? = null,
    val mfePercent: Double? = null,
    val maePercent: Double? = null,
    val pointCount: Int = 0,
    val long075TargetTime: Long? = null,
    val long050StopTime: Long? = null,
    val long100TargetTime: Long? = null,
    val long075StopTime: Long? = null,
    val long200TargetTime: Long? = null,
    val long100StopTime: Long? = null,
    val long300TargetTime: Long? = null,
    val short075TargetTime: Long? = null,
    val short050StopTime: Long? = null,
    val short100TargetTime: Long? = null,
    val short075StopTime: Long? = null,
    val short200TargetTime: Long? = null,
    val short100StopTime: Long? = null,
    val firstBarrierLong075_050: String? = null,
    val firstBarrierLong100_075: String? = null,
    val firstBarrierLong200_100: String? = null,
    val firstBarrierLong300_100: String? = null,
    val firstBarrierShort075_050: String? = null,
    val firstBarrierShort100_075: String? = null,
    val firstBarrierShort200_100: String? = null,
    // План: 20% на +1%, остаток до +3%, защитный уровень +0.15%.
    val plan3ActivationTime: Long? = null,
    val plan3TargetTime: Long? = null,
    val plan3ExitTime: Long? = null,
    val plan3Result: String? = null,
    val plan3GrossReturnPercent: Double? = null,
    val completed: Boolean = false
)

@Entity(
    tableName = "shadow_signals",
    indices = [Index("strategy"), Index("createdAt")]
)
data class ShadowSignalEntity(
    @PrimaryKey val id: String,
    val strategy: String,
    val side: String,
    val symbol: String,
    val createdAt: Long,
    val referencePrice: Double,
    val spreadBps: Double?,
    val slippagePercent: Double?,
    val price30s: Double? = null,
    val price1m: Double? = null,
    val price3m: Double? = null,
    val price5m: Double? = null,
    val price15m: Double? = null,
    val mfePercent: Double? = null,
    val maePercent: Double? = null,
    val pointsJson: String? = null,
    val completed: Boolean = false
)

@Entity(tableName = "app_events")
data class AppEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val severity: String,
    val subsystem: String,
    val message: String
)
