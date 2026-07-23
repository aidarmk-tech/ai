package com.aidar.pumpradar.data.remote

import com.aidar.pumpradar.core.math.MathUtils.validMarketNumber
import com.aidar.pumpradar.domain.model.AggTrade
import com.aidar.pumpradar.domain.model.BookTicker
import com.aidar.pumpradar.domain.model.DepthLevel
import com.aidar.pumpradar.domain.model.MiniTicker
import com.aidar.pumpradar.domain.model.PartialDepth
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Элемент !miniTicker@arr. Числа приходят строками (ТЗ раздел 28). */
@Serializable
data class MiniTickerDto(
    @SerialName("s") val symbol: String,
    @SerialName("c") val close: String,
    @SerialName("o") val open: String = "0",
    @SerialName("h") val high: String = "0",
    @SerialName("l") val low: String = "0",
    @SerialName("q") val quoteVolume: String = "0",
    @SerialName("E") val eventTime: Long = 0
) {
    fun toModel(): MiniTicker? {
        val c = close.toDoubleOrNull() ?: return null
        if (!c.validMarketNumber() || c <= 0.0) return null
        return MiniTicker(
            symbol = symbol,
            close = c,
            high = high.toDoubleOrNull() ?: c,
            low = low.toDoubleOrNull() ?: c,
            quoteVolume = quoteVolume.toDoubleOrNull() ?: 0.0,
            eventTime = eventTime
        )
    }
}

/** aggTrade: p цена, q количество, m покупатель-maker, T время сделки. */
@Serializable
data class AggTradeDto(
    @SerialName("s") val symbol: String = "",
    @SerialName("p") val price: String,
    @SerialName("q") val quantity: String,
    @SerialName("m") val buyerIsMaker: Boolean = false,
    @SerialName("T") val tradeTime: Long = 0
) {
    fun toModel(symbol: String): AggTrade? {
        val p = price.toDoubleOrNull() ?: return null
        val q = quantity.toDoubleOrNull() ?: return null
        if (!p.validMarketNumber() || !q.validMarketNumber() || p <= 0.0) return null
        return AggTrade(symbol, p, q, p * q, buyerIsMaker, tradeTime)
    }
}

/** bookTicker: b лучший bid, a лучший ask. */
@Serializable
data class BookTickerDto(
    @SerialName("s") val symbol: String = "",
    @SerialName("b") val bidPrice: String,
    @SerialName("a") val askPrice: String
) {
    fun toModel(symbol: String): BookTicker? {
        val b = bidPrice.toDoubleOrNull() ?: return null
        val a = askPrice.toDoubleOrNull() ?: return null
        if (a < b || a <= 0.0 || b <= 0.0) return null
        return BookTicker(symbol, b, a)
    }
}

/** depth20@100ms: bids/asks — массивы пар ["price","qty"]. */
@Serializable
data class DepthDto(
    val bids: List<List<String>> = emptyList(),
    val asks: List<List<String>> = emptyList()
) {
    fun toModel(symbol: String): PartialDepth {
        fun levels(rows: List<List<String>>) = rows.mapNotNull { row ->
            if (row.size < 2) return@mapNotNull null
            val p = row[0].toDoubleOrNull() ?: return@mapNotNull null
            val q = row[1].toDoubleOrNull() ?: return@mapNotNull null
            if (p <= 0.0 || q < 0.0) null else DepthLevel(p, q)
        }
        return PartialDepth(symbol, levels(bids), levels(asks))
    }
}
