package com.aidar.pumpradar.data.remote

import com.aidar.pumpradar.core.math.MathUtils.validMarketNumber
import com.aidar.pumpradar.domain.model.MiniTicker
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
