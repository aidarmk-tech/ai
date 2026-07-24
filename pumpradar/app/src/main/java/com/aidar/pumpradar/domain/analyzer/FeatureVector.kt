package com.aidar.pumpradar.domain.analyzer

import kotlinx.serialization.Serializable

/**
 * Вектор признаков сигнала на момент снимка (патч §16). Только настоящие данные:
 * будущие MFE/MAE/outcome сюда НЕ входят — целевые метки берутся из outcomes при
 * экспорте. Nullable — если признак ещё не готов.
 */
@Serializable
data class FeatureVector(
    val return15s: Double? = null,
    val return60s: Double? = null,
    val return5m: Double? = null,
    val acceleration: Double? = null,
    val volumeZ30s: Double? = null,
    val takerBuyRatio30s: Double? = null,
    val cvd30s: Double? = null,
    val cvdSlope: Double? = null,        // наклон CVD (для диагностики exhaustion, item 5)
    val spreadBps: Double? = null,
    val obi10: Double? = null,
    val slippagePercent: Double? = null,
    val relativeStrengthVsBtc: Double? = null,
    val largestTradeShare: Double? = null,
    val top3TradeShare: Double? = null,
    val tinyTradeShare: Double? = null,
    val impulse: Int = 0,
    val entryRisk: Int = 0,
    val confidence: Int = 0,
    val exhaustionRisk: Int = 0,
    val artificialRisk: Int = 0,
    val marketWideRisk: Int = 0
)
