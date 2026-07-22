package com.binancetrader.app

import kotlin.math.abs
import kotlin.math.max

enum class Signal { BUY, SELL, HOLD }

data class StrategyResult(
    val signal: Signal,
    val atr: Double,
    val ema9: Double,
    val ema21: Double,
    val rsi: Double
)

/**
 * Стратегия "EMA Trend + RSI filter + ATR risk":
 *
 * ВХОД (BUY), когда на закрытой свече одновременно:
 *   1. EMA(9) пересекла EMA(21) снизу вверх — начало восходящего импульса;
 *   2. Цена закрытия выше EMA(50) — торгуем только по тренду;
 *   3. RSI(14) в диапазоне 50..70 — импульс подтверждён, но не перекуплен.
 *
 * ВЫХОД (SELL) по первому из событий:
 *   - EMA(9) пересекла EMA(21) сверху вниз (тренд сломался);
 *   - цена коснулась трейлинг-стопа: max(вход, максимум после входа) - 2*ATR(14);
 *   - цена достигла тейк-профита: вход + 3*ATR(14) (риск/прибыль 1:1.5).
 *
 * ATR-стопы адаптируются к волатильности: на спокойном рынке стоп ближе,
 * на волатильном — дальше, чтобы не выбивало шумом.
 */
object Strategy {

    const val EMA_FAST = 9
    const val EMA_SLOW = 21
    const val EMA_TREND = 50
    const val RSI_PERIOD = 14
    const val ATR_PERIOD = 14
    const val STOP_ATR = 2.0
    const val TAKE_ATR = 3.0

    /** Минимум закрытых свечей для расчёта. */
    const val MIN_CANDLES = EMA_TREND + RSI_PERIOD + 5

    fun ema(values: List<Double>, period: Int): List<Double> {
        val out = ArrayList<Double>(values.size)
        val k = 2.0 / (period + 1)
        var prev = values.first()
        for (v in values) {
            prev = v * k + prev * (1 - k)
            out.add(prev)
        }
        return out
    }

    /** RSI по Уайлдеру. */
    fun rsi(closes: List<Double>, period: Int): Double {
        if (closes.size <= period) return 50.0
        var gain = 0.0
        var loss = 0.0
        for (i in 1..period) {
            val d = closes[i] - closes[i - 1]
            if (d > 0) gain += d else loss -= d
        }
        var avgGain = gain / period
        var avgLoss = loss / period
        for (i in period + 1 until closes.size) {
            val d = closes[i] - closes[i - 1]
            avgGain = (avgGain * (period - 1) + max(d, 0.0)) / period
            avgLoss = (avgLoss * (period - 1) + max(-d, 0.0)) / period
        }
        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - 100.0 / (1.0 + rs)
    }

    /** ATR по Уайлдеру. */
    fun atr(candles: List<Candle>, period: Int): Double {
        if (candles.size <= period) return 0.0
        val trs = ArrayList<Double>(candles.size - 1)
        for (i in 1 until candles.size) {
            val c = candles[i]
            val prevClose = candles[i - 1].close
            val tr = max(c.high - c.low, max(abs(c.high - prevClose), abs(c.low - prevClose)))
            trs.add(tr)
        }
        var atr = trs.take(period).average()
        for (i in period until trs.size) {
            atr = (atr * (period - 1) + trs[i]) / period
        }
        return atr
    }

    /** candles — только ЗАКРЫТЫЕ свечи, по возрастанию времени. */
    fun evaluate(candles: List<Candle>): StrategyResult {
        val closes = candles.map { it.close }
        val emaFast = ema(closes, EMA_FAST)
        val emaSlow = ema(closes, EMA_SLOW)
        val emaTrend = ema(closes, EMA_TREND)
        val rsiNow = rsi(closes.takeLast(RSI_PERIOD * 6), RSI_PERIOD)
        val atrNow = atr(candles, ATR_PERIOD)

        val last = closes.size - 1
        val crossedUp = emaFast[last - 1] <= emaSlow[last - 1] && emaFast[last] > emaSlow[last]
        val crossedDown = emaFast[last - 1] >= emaSlow[last - 1] && emaFast[last] < emaSlow[last]
        val aboveTrend = closes[last] > emaTrend[last]

        val signal = when {
            crossedUp && aboveTrend && rsiNow in 50.0..70.0 -> Signal.BUY
            crossedDown -> Signal.SELL
            else -> Signal.HOLD
        }
        return StrategyResult(signal, atrNow, emaFast[last], emaSlow[last], rsiNow)
    }
}
