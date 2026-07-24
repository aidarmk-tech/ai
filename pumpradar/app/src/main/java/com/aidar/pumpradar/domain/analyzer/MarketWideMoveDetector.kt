package com.aidar.pumpradar.domain.analyzer

/**
 * Фильтр общерыночного движения (патч §9): риск того, что рост монеты — часть
 * движения всего рынка, а не её собственная аномалия. 0..100.
 *
 * Если растёт почти весь рынок (широкий breadth), а монета не выделяется на его
 * фоне (низкий residual), уникальность сигнала низкая.
 */
object MarketWideMoveDetector {

    /**
     * @param symbolReturn60s рост монеты за 60с, %
     * @param marketMedianReturn60s медиана роста рынка за 60с, %
     * @param breadthPositive доля растущих пар (0..1)
     */
    fun risk(
        symbolReturn60s: Double,
        marketMedianReturn60s: Double,
        breadthPositive: Double
    ): Int {
        val residual = symbolReturn60s - marketMedianReturn60s
        var r = when {
            breadthPositive >= 0.85 -> 40.0
            breadthPositive >= 0.75 -> 25.0
            else -> 0.0
        }
        // Монета не отрывается от рынка.
        r += when {
            residual <= 0.0 -> 30.0
            residual < 0.30 -> 15.0
            else -> 0.0
        }
        return r.coerceIn(0.0, 100.0).toInt()
    }
}
