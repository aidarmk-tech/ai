package com.aidar.pumpradar.domain.analyzer

/**
 * Единый источник числовых порогов сигналов. Значения подобраны под две ручные
 * стратегии: раннее продолжение импульса и восстановление после контролируемого
 * отката. Это фильтры качества, а не гарантия достижения цели.
 */
data class SignalConfig(
    // ── Ранний LONG_CONTINUATION ──
    val minTakerBuyRatio30s: Double = 0.875,
    val minReturn15s: Double = 0.70,
    val minReturn60s: Double = 0.80,
    val maxReturn5m: Double = 3.00,
    val minRelativeStrengthVsBtc: Double = 0.0,

    // ── Редкий высокий потенциал 5–7% ──
    val maxReturn5mForHighTarget: Double = 1.50,
    val minRelStrengthForHighTarget: Double = 0.80,
    val minReturn60sForHighPotential: Double = 1.00,
    val highPotentialTargetPercent: Double = 7.00,

    // ── Контролируемый откат / ретест ──
    val minRetestPullbackPercent: Double = 0.80,
    val maxRetestPullbackPercent: Double = 3.50,
    val minRetestRecoveryPercent: Double = 0.35,
    val minRetestTakerBuyRatio30s: Double = 0.65,
    val minRetestReturn15s: Double = 0.15,
    val maxRetestDistanceFromHighPercent: Double = 2.50,

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

    // ── Цели/стопы ──
    val primaryTargetPercent: Double = 1.00,
    val primaryStopPercent: Double = 0.75,
    val extraTargetsPercent: List<Double> = listOf(1.5, 2.0, 3.0, 5.0, 7.0),

    // ── Повторные сигналы ──
    val repeatNormalMinutes: Int = 5,

    // ── Вершина ──
    val maxDistanceFromHighPct: Double = 0.35,
    val maxFailedHighAttempts: Int = 2
)
