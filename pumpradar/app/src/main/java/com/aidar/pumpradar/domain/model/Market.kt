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
