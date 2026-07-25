package com.aidar.pumpradar.domain.analyzer

/**
 * Единый источник числовых порогов сигналов.
 *
 * Основная цель версии: качественный ранний LONG или контролируемый ретест,
 * затем сопровождение движения до +3% с защитой после достижения +1%.
 * Это правила фильтрации и paper-оценки, а не гарантия результата.
 */
data class SignalConfig(
    // ── Ранний LONG_CONTINUATION ──
    val minTakerBuyRatio30s: Double = 0.875,
    val minReturn15s: Double = 0.70,
    val minReturn60s: Double = 0.80,
    val maxReturn5m: Double = 3.00,
    val minRelativeStrengthVsBtc: Double = 0.0,

    // ── Контролируемый откат / ретест ──
    val minRetestPullbackPercent: Double = 0.80,
    val maxRetestPullbackPercent: Double = 3.50,
    val minRetestRecoveryPercent: Double = 0.35,
    val minRetestTakerBuyRatio30s: Double = 0.65,
    val minRetestReturn15s: Double = 0.15,
    val maxRetestDistanceFromHighPercent: Double = 2.50,

    // ── Основной экспериментальный фильтр TRADE_3 ──
    val minTrade3ImpulseScore: Int = 60,
    val maxTrade3EntryRisk: Int = 35,
    val maxTrade3ExhaustionRisk: Int = 20,
    val maxTrade3SpreadBps: Double = 30.0,
    val maxTrade3SlippagePercent: Double = 0.15,
    val trade3SlotMinutes: Int = 15,

    // ── Расширенный контрольный контур TRADE3_SHADOW ──
    // Не уведомляет о входе и не отправляет ордера. Нужен только для сравнения
    // со строгим TRADE_3 на новой независимой выборке.
    val minShadowTrade3ImpulseScore: Int = 60,
    val minShadowReturn15s: Double = 0.70,
    val minShadowReturn60s: Double = 0.50,
    val minShadowReturn5m: Double = 0.50,
    val maxShadowReturn5m: Double = 2.50,
    val minShadowTakerBuyRatio30s: Double = 0.72,
    val maxShadowDistanceFromHighPct: Double = 0.75,
    val maxShadowEntryRisk: Int = 35,
    val maxShadowExhaustionRisk: Int = 20,
    val maxShadowSpreadBps: Double = 30.0,
    val maxShadowSlippagePercent: Double = 0.15,

    // ── План сопровождения до движения +3% ──
    val target3Percent: Double = 3.00,
    val initialStopPercent: Double = 0.75,
    val protectionActivationPercent: Double = 1.00,
    val protectedStopPercent: Double = 0.15,
    val partialFractionAtProtection: Double = 0.20,
    val minHoldTakerBuyRatio30s: Double = 0.65,
    val minHoldTakerBuyRatio15s: Double = 0.60,
    val minHoldTakerBuyRatio5s: Double = 0.55,
    val maxDrawdownFromPeakWhileHolding: Double = 0.80,
    val weakeningConfirmTicks: Int = 2,

    // ── Жёсткие запреты LONG ──
    val hardVetoTakerBuyRatio30s: Double = 0.75,
    val extremeVolumeZ: Double = 25.0,
    val minTakerBuyForExtremeVol: Double = 0.80,

    // ── Ликвидность ──
    val maxSpreadBps: Double = 40.0,
    val maxSlippagePercent: Double = 0.35,
    val maxFeedAgeMs: Long = 10_000,

    // ── Поглощение ──
    val epsilon: Double = 1e-9,

    // ── REVERSAL_WATCH ──
    val reversalTakerBuyRatio30s: Double = 0.675,
    val reversalReturn60s: Double = 1.00,

    // ── Риск ──
    val exhaustionBlock: Int = 70,

    // ── Исходы для сравнительного анализа ──
    val primaryTargetPercent: Double = 1.00,
    val primaryStopPercent: Double = 0.75,
    val extraTargetsPercent: List<Double> = listOf(1.5, 2.0, 3.0),

    // ── Повторные сигналы ──
    val repeatNormalMinutes: Int = 5,

    // ── Вершина ──
    val maxDistanceFromHighPct: Double = 0.35,
    val maxFailedHighAttempts: Int = 2
)
