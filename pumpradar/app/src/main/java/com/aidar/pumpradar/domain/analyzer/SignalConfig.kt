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

    // ── План сопровождения до движения +3% ──
    val target3Percent: Double = 3.00,
    val initialStopPercent: Double = 0.75,
    val protectionActivationPercent: Double = 1.00,
    val protectedStopPercent: Double = 0.15,
    val partialFractionAtProtection: Double = 0.20,
    // Пороговые значения для следующего live-этапа HOLD_FOR_3 / EXIT_WEAKENING.
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
