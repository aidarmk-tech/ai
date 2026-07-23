package com.aidar.pumpradar.domain.model

/** Торговая пара из exchangeInfo (universe). */
data class SymbolInfo(
    val symbol: String,
    val baseAsset: String,
    val quoteAsset: String
)

/** Один элемент потока !miniTicker@arr. */
data class MiniTicker(
    val symbol: String,
    val close: Double,
    val high: Double,
    val low: Double,
    val quoteVolume: Double,
    val eventTime: Long
)

/** Точка ценового ряда (кольцевой буфер сканера). */
data class PriceSample(
    val time: Long,
    val price: Double
)

/** Агрегированная сделка (aggTrade). */
data class AggTrade(
    val symbol: String,
    val price: Double,
    val quantity: Double,
    val quoteValue: Double,
    val buyerIsMaker: Boolean,
    val tradeTime: Long
) {
    /** m == false → покупатель taker → агрессивный покупатель (ТЗ раздел 8.2). */
    val isAggressiveBuy: Boolean get() = !buyerIsMaker
}

/** Лучшие bid/ask (bookTicker). */
data class BookTicker(
    val symbol: String,
    val bidPrice: Double,
    val askPrice: Double
)

/** Метрики потока сделок и стакана по кандидату (ТЗ раздел 8.2). */
data class CandidateMetrics(
    val ready: Boolean,
    val quoteVolume30s: Double,
    val tradeCount30s: Int,
    val tradesPerSecond: Double,
    val takerBuyRatio30s: Double?,
    val cvd30s: Double,
    val cvdSlope: Double,
    val volumeZ30s: Double?,   // null → NotReady (мало истории)
    val spreadBps: Double?
)

/** Готовый живой сигнал для UI и уведомлений. */
data class LiveSignal(
    val symbol: String,
    val price: Double,
    val score: Int,
    val level: String,
    val stage: String,
    val return60s: Double?,
    val takerBuyRatio30s: Double?,
    val volumeZ30s: Double?,
    val cvd30s: Double,
    val spreadBps: Double?,
    val reasons: List<String>,
    val risks: List<String>
)

/** Кандидат первого уровня для живого сканера. */
data class Candidate(
    val symbol: String,
    val price: Double,
    val return15s: Double?,
    val return60s: Double?,
    val return5m: Double?,
    val acceleration: Double?,
    val quoteVolume24h: Double,
    val relativeStrengthVsBtc: Double?,
    val preScore: Double
)
