package com.aidar.pumpradar.domain.analyzer

/**
 * Фильтр искусственной активности (патч §8): риск накрутки 0..100.
 *
 * Индикатор риска, а не юридическое утверждение о манипуляции. Подозрительно:
 * много мелких сделок при слабом продвижении цены; объём в одной-двух сделках;
 * высокая доля повторяющейся мелочи.
 */
object ArtificialActivityDetector {

    /**
     * @param tradeCount30s число сделок за 30с
     * @param largestTradeShare доля крупнейшей сделки в объёме 30с (nullable)
     * @param top3TradeShare доля трёх крупнейших (nullable)
     * @param tinyTradeShare доля мелких сделок по количеству (nullable)
     * @param return60s продвижение цены за 60с, %
     */
    fun risk(
        tradeCount30s: Int,
        largestTradeShare: Double?,
        top3TradeShare: Double?,
        tinyTradeShare: Double?,
        return60s: Double
    ): Int {
        var r = 0.0
        // Много сделок, а цена почти стоит — типичная накрутка активности.
        if (tradeCount30s >= 100 && return60s < 0.20) r += 30.0
        // Основная масса — мелкие сделки.
        if (tinyTradeShare != null && tinyTradeShare > 0.60) r += 25.0
        // Объём держится на одной сделке.
        if (largestTradeShare != null && largestTradeShare > 0.50) r += 20.0
        // …или на двух-трёх.
        if (top3TradeShare != null && top3TradeShare > 0.75) r += 15.0
        return r.coerceIn(0.0, 100.0).toInt()
    }
}
