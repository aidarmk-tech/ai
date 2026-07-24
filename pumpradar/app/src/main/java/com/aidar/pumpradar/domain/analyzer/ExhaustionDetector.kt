package com.aidar.pumpradar.domain.analyzer

/**
 * Exhaustion Filter (патч §6): риск истощения импульса 0..100.
 *
 * Работает по доступным на тике признакам. Признаки, требующие истории
 * (новый ценовой максимум без нового максимума CVD, верхний фитиль,
 * быстрое восстановление ask-глубины), добавятся вместе с RetestDetector,
 * который хранит параметры первого импульса.
 */
object ExhaustionDetector {

    /**
     * @param return5m рост за 5 минут, %
     * @param return60s рост за 60с, %
     * @param cvdSlope наклон CVD (последние 30с − предыдущие 30с)
     * @param takerBuyRatio30s доля агрессивных покупок (nullable)
     * @param spreadBps спред, bps (nullable)
     */
    fun risk(
        return5m: Double,
        return60s: Double,
        cvdSlope: Double,
        takerBuyRatio30s: Double?,
        spreadBps: Double?
    ): Int {
        var r = 0.0
        // Поздняя стадия по ходу за 5 минут.
        r += when {
            return5m >= 18.0 -> 40.0
            return5m >= 12.0 -> 30.0
            return5m >= 8.0 -> 20.0
            else -> 0.0
        }
        // Цена растёт, а покупательский поток слабеет (дивергенция CVD).
        if (return60s > 0.0 && cvdSlope < 0.0) r += 25.0
        // Покупатели теряют контроль.
        if (return60s > 0.0 && takerBuyRatio30s != null && takerBuyRatio30s < 0.50) r += 20.0
        // Спред расширяется — уход ликвидности.
        if ((spreadBps ?: 0.0) > 60.0) r += 15.0
        return r.coerceIn(0.0, 100.0).toInt()
    }
}
