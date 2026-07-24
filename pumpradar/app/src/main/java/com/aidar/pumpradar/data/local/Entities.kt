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

/** Снимок признаков для будущего обучения (патч §15). Целевые метки берутся из
 *  outcomes при экспорте — в самом снимке будущих данных нет (§16). */
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
    val snapshotType: String,          // TRIGGERED / NEAR_MISS / RANDOM_NORMAL
    val algorithmVersion: String,
    val liquidityTier: String,
    val opportunityLabel: String,
    val featureVectorJson: String
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
    val eventId: String?,          // кластер рыночного события (патч §12)
    // Поля для Strategy Lab (патч §13) — вычисление стратегий по истории.
    val opportunityLabel: String?,
    val liquidityTier: String?,
    val entryRiskScore: Int?,
    val confidenceScore: Int?
)

/**
 * Секундная траектория best bid/ask после сигнала (двусторонний анализ).
 * Позволяет пересчитать исход по временной последовательности с учётом стороны
 * стакана, спреда, проскальзывания и задержки реакции — то, что нельзя
 * восстановить из 5 контрольных точек. Пишется только для новых событий.
 */
@Entity(tableName = "signal_trajectories")
data class SignalTrajectoryEntity(
    @PrimaryKey val signalId: String,
    val symbol: String,
    val referencePrice: Double,
    val startedAt: Long,
    val resolutionMs: Long,        // номинальный шаг сэмплирования (~тик движка)
    val pointCount: Int,
    val pointsJson: String         // сериализованный List<TrajectoryPoint>
)

/**
 * Теневой (SHADOW/PAPER) сигнал двусторонней стратегии. Не связан с LONG-историей
 * и уведомлениями: отдельная разметка исходов по стратегиям
 * (LONG_CONTINUATION / PUMP_REVERSAL_SHORT / DUMP_CONTINUATION_SHORT /
 * DUMP_REBOUND_LONG). Ордера не отправляются, API-ключ не требуется.
 *
 * Строка вставляется в момент срабатывания (completed=false) и дополняется
 * контрольными точками, MFE/MAE и секундной траекторией через 15 мин.
 */
@Entity(
    tableName = "shadow_signals",
    indices = [Index("strategy"), Index("createdAt")]
)
data class ShadowSignalEntity(
    @PrimaryKey val id: String,
    val strategy: String,
    val side: String,              // LONG / SHORT
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
    val pointsJson: String? = null,   // секундная траектория bid/ask (nullable)
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
