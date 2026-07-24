package com.aidar.pumpradar.domain.analyzer

/**
 * Анализ изменения давления покупателей (item 4). Сравнивает три окна
 * takerBuyRatio (5с/15с/30с) и CVD: если давление последовательно снижается
 * (короткое окно слабее длинного), это признак истощения → блокировать LONG и
 * повышать exhaustionRisk.
 */
object BuyerPressure {

    /**
     * true, если давление покупателей монотонно слабеет: takerBuyRatio5s <
     * takerBuyRatio15s < takerBuyRatio30s (недавние покупки слабее среднего), и
     * при этом краткосрочный CVD не сильнее долгосрочного тренда.
     */
    fun declining(
        tbr5s: Double?, tbr15s: Double?, tbr30s: Double?,
        cvd5s: Double, cvd15s: Double, cvd30s: Double,
        eps: Double = 0.01
    ): Boolean {
        if (tbr5s == null || tbr15s == null || tbr30s == null) return false
        val tbrFading = tbr5s < tbr15s - eps && tbr15s < tbr30s - eps
        // Доля CVD, набранная за последние 5с относительно 30с, мала → импульс покупок гаснет.
        val cvdFading = cvd30s > 0.0 && cvd5s < 0.0
        return tbrFading || (tbr5s < tbr30s - eps && cvdFading)
    }
}
