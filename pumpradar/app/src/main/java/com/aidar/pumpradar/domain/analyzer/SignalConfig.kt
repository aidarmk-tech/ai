package com.aidar.pumpradar.domain.analyzer

/**
 * Единый источник числовых порогов сигналов (item 11): пороги НЕ зашиты в UI и не
 * разбросаны по коду, а собраны здесь с значениями по умолчанию. Может быть
 * переопределён из настроек. Значения соответствуют ужесточённому LONG_CONTINUATION.
 */
data class SignalConfig(
    // ── LONG_CONTINUATION строгий гейт (item 1) ──
    val minTakerBuyRatio30s: Double = 0.875,
    val minReturn15s: Double = 0.70,
    val minReturn60s: Double = 0.80,
    val maxReturn5m: Double = 3.00,
    val minRelativeStrengthVsBtc: Double = 0.0,     // > 0
    // Для потенциальной цели до 3% — дополнительно строже.
    val maxReturn5mForHighTarget: Double = 1.50,
    val minRelStrengthForHighTarget: Double = 0.80,

    // ── Жёсткие запреты LONG (item 2) ──
    val hardVetoTakerBuyRatio30s: Double = 0.75,    // < 0.75 запрет
    val extremeVolumeZ: Double = 25.0,              // «экстремальный» объём
    val minTakerBuyForExtremeVol: Double = 0.80,    // при экстремальном объёме tbr должен быть высоким

    // ── Ликвидность (свежесть/спред/проскальзывание) ──
    val maxSpreadBps: Double = 40.0,
    val maxSlippagePercent: Double = 0.35,
    val maxFeedAgeMs: Long = 10_000,

    // ── priceProgressEfficiency / поглощение (item 3) ──
    val epsilon: Double = 1e-9,

    // ── REVERSAL_WATCH (item 8) ──
    val reversalTakerBuyRatio30s: Double = 0.675,
    val reversalReturn60s: Double = 1.00,

    // ── Риск (item 5) ──
    val exhaustionBlock: Int = 70,                  // exhaustionRisk ≥ 70 → запрет LONG_CONTINUATION

    // ── Цели/стопы (item 9) ──
    val primaryTargetPercent: Double = 1.00,
    val primaryStopPercent: Double = 0.75,
    val extraTargetsPercent: List<Double> = listOf(1.5, 2.0, 3.0),

    // ── Повторные сигналы (item 10) ──
    val repeatNormalMinutes: Int = 5,               // ≥5 мин нормального рынка

    // ── Вершина (item 6) ──
    val maxDistanceFromHighPct: Double = 0.35,      // дальше от максимума → не LONG
    val maxFailedHighAttempts: Int = 2              // ≥2 неудачных попытки обновить максимум → не LONG
)
