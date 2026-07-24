package com.aidar.pumpradar.domain.analyzer

/**
 * Общий вход для теневых (SHADOW/PAPER) двусторонних детекторов. Ордера не
 * отправляются, API-ключ не требуется — это только разметка исходов по потоку.
 *
 * Поля потока (cvd, takerBuyRatio, obi10, bid/ask notional) берутся из тех же
 * 30с-метрик и стакана, что и LONG-пайплайн, но интерпретируются двусторонне.
 */
data class ShadowStrategyInput(
    val now: Long,
    val price: Double,
    val cvd: Double,
    val cvdSlope: Double,
    val takerBuyRatio: Double?,   // доля агрессивных покупок; <0.5 → доминируют продажи
    val spreadBps: Double?,
    val slippagePercent: Double?,
    val obi10: Double?,           // >0 bid-тяжёлый, <0 ask-тяжёлый
    val bidNotionalTop10: Double?,
    val askNotionalTop10: Double?,
    val isUpImpulse: Boolean,     // восходящий импульс (как в LONG-сканере)
    val isDownImpulse: Boolean    // самостоятельный нисходящий импульс (зеркально)
)
