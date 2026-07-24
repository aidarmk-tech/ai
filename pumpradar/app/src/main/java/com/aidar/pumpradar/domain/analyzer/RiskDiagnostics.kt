package com.aidar.pumpradar.domain.analyzer

/**
 * Диагностика риск-условий (item 5): какие именно под-условия каждого риск-скора
 * сработали на событии. Реконструируется из сохранённого вектора признаков —
 * это объясняет, почему на TRIGGERED-событиях exhaustionRisk почти всегда 0, а
 * confidence/entryRisk/slippage почти постоянны (сильная фильтрация EARLY_CLEAN).
 *
 * Чистая логика — тестируется. Пороги совпадают с ExhaustionDetector и Entry/
 * Confidence в PumpScoreCalculator.
 */
object RiskDiagnostics {

    /** Упорядоченный список идентификаторов условий (для стабильных счётчиков). */
    val CONDITION_IDS = listOf(
        "EXH_late5m",           // return5m ≥ 8%
        "EXH_cvdDivergence",    // return60s > 0 и cvdSlope < 0
        "EXH_takerLosing",      // return60s > 0 и takerBuyRatio30s < 0.50
        "EXH_spreadWiden",      // spreadBps > 60
        "ENTRY_late",           // return5m ≥ 3% (поздняя стадия)
        "ENTRY_spread",         // spreadBps ≥ 15
        "ENTRY_slippage",       // проскальзывание > 0.10% или нет глубины
        "ENTRY_dataRisk",       // нет объёма Z / нет OBI
        "CONF_volumeReady",     // объём Z готов
        "CONF_depthReady",      // глубина/проскальзывание известны
        "SLIP_nearZero",        // проскальзывание есть, но < 0.001% (почти ноль)
        "SLIP_insufficient"     // проскальзывание null (недостаточно глубины)
    )

    fun firedConditions(
        return5m: Double?,
        return60s: Double?,
        cvdSlope: Double?,
        takerBuyRatio30s: Double?,
        spreadBps: Double?,
        slippagePercent: Double?,
        volumeZ30s: Double?,
        obi10: Double?
    ): Map<String, Boolean> {
        val r5 = return5m ?: 0.0
        val r60 = return60s ?: 0.0
        return linkedMapOf(
            "EXH_late5m" to (r5 >= 8.0),
            "EXH_cvdDivergence" to (r60 > 0.0 && (cvdSlope ?: 0.0) < 0.0),
            "EXH_takerLosing" to (r60 > 0.0 && takerBuyRatio30s != null && takerBuyRatio30s < 0.50),
            "EXH_spreadWiden" to ((spreadBps ?: 0.0) > 60.0),
            "ENTRY_late" to (r5 >= 3.0),
            "ENTRY_spread" to ((spreadBps ?: 0.0) >= 15.0),
            "ENTRY_slippage" to (slippagePercent == null || slippagePercent > 0.10),
            "ENTRY_dataRisk" to (volumeZ30s == null || obi10 == null),
            "CONF_volumeReady" to (volumeZ30s != null),
            "CONF_depthReady" to (slippagePercent != null),
            "SLIP_nearZero" to (slippagePercent != null && kotlin.math.abs(slippagePercent) < 0.001),
            "SLIP_insufficient" to (slippagePercent == null)
        )
    }

    /** Человекочитаемое имя условия для UI. */
    fun ru(id: String): String = when (id) {
        "EXH_late5m" -> "Истощение: поздно (5м ≥ 8%)"
        "EXH_cvdDivergence" -> "Истощение: CVD-дивергенция"
        "EXH_takerLosing" -> "Истощение: покупатели слабеют"
        "EXH_spreadWiden" -> "Истощение: спред расширяется"
        "ENTRY_late" -> "Вход: поздняя стадия (5м ≥ 3%)"
        "ENTRY_spread" -> "Вход: спред ≥ 15 bps"
        "ENTRY_slippage" -> "Вход: проскальзывание/нет глубины"
        "ENTRY_dataRisk" -> "Вход: неполные данные"
        "CONF_volumeReady" -> "Доверие: объём Z готов"
        "CONF_depthReady" -> "Доверие: глубина известна"
        "SLIP_nearZero" -> "Проскальзывание ≈ 0"
        "SLIP_insufficient" -> "Проскальзывание: нет глубины"
        else -> id
    }
}
