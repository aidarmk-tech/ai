package com.aidar.pumpradar.domain.analyzer

/**
 * Исполнимая (executable) оценка исхода по СЕКУНДНОЙ траектории best bid/ask —
 * с учётом порядка достижения уровней, стороны стакана, спреда, проскальзывания
 * и задержки реакции (0.5 / 2 / 5 c).
 *
 * В отличие от [CalibrationEval] (грубые 5 контрольных точек по mid-цене),
 * здесь путь проходится по времени, вход берётся не мгновенно, а через
 * reactionMs после сигнала, и цель/стоп проверяются по цене, по которой сделку
 * реально можно было бы закрыть:
 *   LONG  — вход по ask (+slippage), выход по bid;
 *   SHORT — вход по bid (−slippage), выход по ask.
 *
 * Это по-прежнему НЕ факт сделки (ордера не отправляются), но честнее, чем
 * вывод по одним MFE/MAE без временной последовательности.
 */
object ExecutablePathEval {

    /** Стандартные задержки реакции для сравнения (ТЗ: 0.5 / 2 / 5 c). */
    val REACTION_MS = longArrayOf(500L, 2_000L, 5_000L)

    /** Точка секундной траектории: смещение от сигнала и лучшие bid/ask. */
    data class PathPoint(val tMs: Long, val bid: Double, val ask: Double) {
        val mid: Double get() = (bid + ask) / 2.0
    }

    enum class Hit { TARGET, STOP, NONE, NO_ENTRY }

    data class Result(
        val hit: Hit,
        val timeToHitMs: Long?,   // от момента входа до срабатывания уровня
        val entryPrice: Double?,  // цена входа с учётом стороны стакана и проскальзывания
        val reactionMs: Long
    ) {
        val target: Boolean get() = hit == Hit.TARGET
    }

    /**
     * @param points секундная траектория, отсортированная по возрастанию tMs; tMs —
     *   смещение относительно сигнала (0 = момент сигнала).
     * @param slippagePercent доп. проскальзывание на входе, % (из симуляции стакана);
     *   null → 0. На выходе консервативно НЕ добавляется (маркировка по касанию bid/ask).
     */
    fun evaluate(
        points: List<PathPoint>,
        side: TradeSide,
        targetPercent: Double,
        stopPercent: Double,
        reactionMs: Long,
        horizonMs: Long,
        slippagePercent: Double? = null
    ): Result {
        if (points.isEmpty()) return Result(Hit.NO_ENTRY, null, null, reactionMs)
        val ordered = points.sortedBy { it.tMs }
        val entry = ordered.firstOrNull { it.tMs >= reactionMs }
            ?: return Result(Hit.NO_ENTRY, null, null, reactionMs)
        if (entry.bid <= 0.0 || entry.ask <= 0.0) {
            return Result(Hit.NO_ENTRY, null, null, reactionMs)
        }
        val slipFrac = (slippagePercent ?: 0.0).coerceAtLeast(0.0) / 100.0
        val entryPrice = when (side) {
            TradeSide.LONG -> entry.ask * (1.0 + slipFrac)   // берём по ask + проскальзывание
            TradeSide.SHORT -> entry.bid * (1.0 - slipFrac)  // продаём по bid − проскальзывание
        }
        if (entryPrice <= 0.0) return Result(Hit.NO_ENTRY, null, null, reactionMs)

        val deadline = entry.tMs + horizonMs
        for (p in ordered) {
            if (p.tMs <= entry.tMs) continue
            if (p.tMs > deadline) break
            if (p.bid <= 0.0 || p.ask <= 0.0) continue
            // Доходность по цене реального закрытия сделки.
            val ret = when (side) {
                TradeSide.LONG -> (p.bid / entryPrice - 1.0) * 100.0   // выход по bid
                TradeSide.SHORT -> (entryPrice / p.ask - 1.0) * 100.0  // откуп по ask
            }
            if (ret >= targetPercent) {
                return Result(Hit.TARGET, p.tMs - entry.tMs, entryPrice, reactionMs)
            }
            if (ret <= -stopPercent) {
                return Result(Hit.STOP, p.tMs - entry.tMs, entryPrice, reactionMs)
            }
        }
        return Result(Hit.NONE, null, entryPrice, reactionMs)
    }
}
