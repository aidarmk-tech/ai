package com.aidar.pumpradar.domain.analyzer

/**
 * Консервативная executable-оценка исхода (ТЗ v2, раздел 0A.13). Исход по
 * last price завышает исполнимость: реально вход по ask + проскальзывание,
 * выход по bid, минус издержки. Здесь round-trip издержки (в % цены) вычитаются
 * из last-price MFE/MAE. Спред и проскальзывание берутся на момент сигнала.
 *
 * Значения комиссии/доп.проскальзывания — пользовательские предположения,
 * а не фактические комиссии Binance.
 */
object ExecutableOutcome {
    const val ASSUMED_FEE_BPS = 10.0
    const val ASSUMED_SLIP_BPS = 5.0
    private const val DEFAULT_SPREAD_BPS = 20.0   // нет данных → консервативно
    private const val DEFAULT_SLIP_PERCENT = 0.1

    /** Полные round-trip издержки в процентах цены. */
    fun haircutPercent(spreadBps: Double?, slippagePercent: Double?): Double {
        val spread = spreadBps ?: DEFAULT_SPREAD_BPS
        val slipBps = (slippagePercent ?: DEFAULT_SLIP_PERCENT) * 100.0   // % → bps
        val entryBps = spread / 2.0 + slipBps + ASSUMED_SLIP_BPS + ASSUMED_FEE_BPS
        val exitBps = spread / 2.0 + ASSUMED_FEE_BPS
        return (entryBps + exitBps) / 100.0                              // bps → %
    }

    fun mfe(mfePercent: Double?, spreadBps: Double?, slippagePercent: Double?): Double? =
        mfePercent?.let { it - haircutPercent(spreadBps, slippagePercent) }

    fun mae(maePercent: Double?, spreadBps: Double?, slippagePercent: Double?): Double? =
        maePercent?.let { it - haircutPercent(spreadBps, slippagePercent) }

    /** +2% раньше −1% с учётом издержек. */
    fun successful(
        mfePercent: Double?, maePercent: Double?,
        spreadBps: Double?, slippagePercent: Double?
    ): Boolean {
        val m = mfe(mfePercent, spreadBps, slippagePercent) ?: return false
        val a = mae(maePercent, spreadBps, slippagePercent) ?: return false
        return m >= 2.0 && a > -1.0
    }
}
