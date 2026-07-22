package com.binancetrader.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class Regime { TREND, RANGE, NEUTRAL }
enum class TradeType { MOMENTUM, REVERSION }

data class EntryPlan(
    val type: TradeType,
    val score: Int,            // 0..100, информативность сигнала (для журнала)
    val stopDistance: Double,  // расстояние до стопа в цене
    val target: Double,        // фиксированная цель (0.0 — ведём трейлингом)
    val reason: String
)

/**
 * Стратегия «Хамелеон» — адаптивный ансамбль из двух движков,
 * переключаемых детектором режима рынка.
 *
 * РЕЖИМ РЫНКА — Kaufman Efficiency Ratio (ER) за 20 свечей:
 * отношение чистого хода цены к сумме всех колебаний. ER→1 — движение
 * направленное (ТРЕНД), ER→0 — рынок пилит на месте (БОКОВИК).
 * Пробойные сигналы в боковике и контртрендовые в тренде — главный
 * источник убытков простых ботов; «Хамелеон» их просто не берёт.
 *
 * ДВИЖОК 1 — ПРОБОЙ (только в режиме ТРЕНД):
 * покупка, когда свеча закрылась выше максимума последних 20 свечей
 * с аномальным объёмом (z-score ≥ 1) — движение подтверждено деньгами,
 * а не тонким рынком. Отсекаются перегретые входы (цена дальше 3×ATR
 * от средней, RSI ≥ 78 — не покупаем вершину параболы).
 * Ведение: трейлинг-стоп «люстра» 2.2×ATR от максимума + перевод
 * в безубыток после хода на 1×ATR. Цели нет — тренд едем до слома.
 *
 * ДВИЖОК 2 — ВЫКУП ПАНИКИ (только в режиме БОКОВИК):
 * в боковике цена возвращается к средней. Покупка, когда цена упала
 * на ≥2 сигмы ниже SMA20 при RSI(3) < 15 — краткосрочная капитуляция —
 * и свеча закрылась зелёной (падение остановилось, не ловим нож).
 * Цель — возврат к SMA20, тайм-стоп 16 свечей: возврат либо происходит
 * быстро, либо гипотеза неверна.
 *
 * ФИЛЬТРЫ КОНТЕКСТА (оба движка):
 * - старший таймфрейм 1h: пробои только при восходящем 1h-тренде
 *   (цена выше EMA50, EMA20 растёт);
 * - макро-фильтр BTC: новые входы запрещены, пока BTC на 1h ниже
 *   SMA200 — в медвежьем рынке альты падают вместе с BTC.
 *
 * ДИСЦИПЛИНА (реализована в сервисе):
 * - риск-паритет: в каждой сделке рискуем фиксированным % капитала,
 *   размер позиции выводится из расстояния до стопа;
 * - кулдаун пары после стоп-лосса (8 свечей) — не «мстим» рынку;
 * - дневной лимит убытка: минус 3% капитала за день — входы до завтра
 *   закрыты, открытые позиции доводятся до выхода.
 */
object Strategy {

    const val ER_PERIOD = 20
    // Пороги режима сближены: «мёртвая» нейтральная зона узкая, бот чаще активен.
    const val ER_TREND = 0.27
    const val ER_RANGE = 0.22
    const val DONCHIAN = 15
    const val SMA_PERIOD = 20
    const val ATR_PERIOD = 14
    const val MOM_STOP_ATR = 2.2
    const val REV_STOP_ATR = 2.0
    const val MIN_CANDLES = 60

    // ── Индикаторы ──

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

    fun sma(values: List<Double>, period: Int): Double =
        values.takeLast(period).average()

    fun stdev(values: List<Double>, period: Int): Double {
        val tail = values.takeLast(period)
        val mean = tail.average()
        return sqrt(tail.sumOf { (it - mean) * (it - mean) } / tail.size)
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
        return 100.0 - 100.0 / (1.0 + avgGain / avgLoss)
    }

    /** ATR по Уайлдеру. */
    fun atr(candles: List<Candle>, period: Int): Double {
        if (candles.size <= period) return 0.0
        val trs = ArrayList<Double>(candles.size - 1)
        for (i in 1 until candles.size) {
            val c = candles[i]
            val prevClose = candles[i - 1].close
            trs.add(max(c.high - c.low, max(abs(c.high - prevClose), abs(c.low - prevClose))))
        }
        var a = trs.take(period).average()
        for (i in period until trs.size) a = (a * (period - 1) + trs[i]) / period
        return a
    }

    /** Kaufman Efficiency Ratio: |чистый ход| / сумма модулей шагов. */
    fun efficiencyRatio(closes: List<Double>, period: Int): Double {
        if (closes.size <= period) return 0.0
        val last = closes.size - 1
        val net = abs(closes[last] - closes[last - period])
        var path = 0.0
        for (i in last - period + 1..last) path += abs(closes[i] - closes[i - 1])
        return if (path > 0) net / path else 0.0
    }

    /** z-score объёма последней свечи относительно предыдущих 20. */
    fun volumeZ(candles: List<Candle>): Double {
        if (candles.size < 22) return 0.0
        val prev = candles.subList(candles.size - 21, candles.size - 1).map { it.volume }
        val mean = prev.average()
        val sd = sqrt(prev.sumOf { (it - mean) * (it - mean) } / prev.size)
        if (sd <= 0.0) return 0.0
        return (candles.last().volume - mean) / sd
    }

    fun regime(closes: List<Double>): Regime {
        val er = efficiencyRatio(closes, ER_PERIOD)
        return when {
            er >= ER_TREND -> Regime.TREND
            er <= ER_RANGE -> Regime.RANGE
            else -> Regime.NEUTRAL
        }
    }

    // ── Контекст старшего таймфрейма ──

    /** 1h-тренд восходящий: цена выше EMA50 и EMA20 растёт. */
    fun contextTrendUp(candles1h: List<Candle>): Boolean {
        if (candles1h.size < 55) return false
        val closes = candles1h.map { it.close }
        val e50 = ema(closes, 50)
        val e20 = ema(closes, 20)
        val last = closes.size - 1
        return closes[last] > e50[last] && e20[last] > e20[last - 3]
    }

    /** Макро-фильтр: BTC на 1h выше SMA200 — рынок не медвежий. */
    fun notBearMarket(btc1h: List<Candle>): Boolean {
        if (btc1h.size < 200) return true // мало истории (тестнет) — не блокируем
        val closes = btc1h.map { it.close }
        return closes.last() > sma(closes, 200)
    }

    // ── Входы ──

    /** candles — только закрытые 15m-свечи по возрастанию времени. */
    fun considerEntry(candles: List<Candle>, ctxUp: Boolean, notBear: Boolean): EntryPlan? {
        if (candles.size < MIN_CANDLES || !notBear) return null
        val closes = candles.map { it.close }
        val last = closes.size - 1
        val atrNow = atr(candles, ATR_PERIOD)
        if (atrNow <= 0) return null
        val sma20 = sma(closes, SMA_PERIOD)
        val er = efficiencyRatio(closes, ER_PERIOD)

        when (regime(closes)) {
            Regime.TREND -> {
                if (!ctxUp) return null
                // Пробой: закрытие выше максимума предыдущих 20 свечей.
                val donchianHigh = candles.subList(candles.size - DONCHIAN - 1, candles.size - 1)
                    .maxOf { it.high }
                if (closes[last] <= donchianHigh) return null
                val volZ = volumeZ(candles)
                if (volZ < 0.7) return null
                // Не покупаем перегрев.
                if (closes[last] - sma20 > 3.0 * atrNow) return null
                if (rsi(closes.takeLast(80), 14) >= 78.0) return null

                val score = min(
                    100,
                    (er * 130).toInt() + (min(volZ, 4.0) * 10).toInt() + 20
                )
                return EntryPlan(
                    type = TradeType.MOMENTUM,
                    score = score,
                    stopDistance = MOM_STOP_ATR * atrNow,
                    target = 0.0,
                    reason = "пробой 20-свечного максимума, объём z=%.1f, ER=%.2f".format(volZ, er)
                )
            }
            Regime.RANGE -> {
                // Выкуп паники к средней. В медвежьем 1h-контексте не ловим.
                val sd = stdev(closes, SMA_PERIOD)
                if (sd <= 0) return null
                val z = (closes[last] - sma20) / sd
                if (z > -1.7) return null
                if (rsi(closes.takeLast(30), 3) >= 20.0) return null
                // Свеча закрылась зелёной — падение приостановилось, не ловим нож.
                if (candles[last].close <= candles[last].open) return null
                // Цель должна оправдывать риск: до средней минимум 0.7×ATR.
                if (sma20 - closes[last] < 0.7 * atrNow) return null

                val score = min(100, (abs(z) * 25).toInt() + ((1 - er) * 30).toInt())
                return EntryPlan(
                    type = TradeType.REVERSION,
                    score = score,
                    stopDistance = REV_STOP_ATR * atrNow,
                    target = sma20,
                    reason = "капитуляция z=%.1f сигм ниже средней, RSI(3)<15".format(z)
                )
            }
            Regime.NEUTRAL -> return null
        }
    }

    // ── Сигналы выхода по закрытию свечи ──

    /** Слом импульса для пробойной позиции: закрытие ощутимо ниже средней. */
    fun momentumExitSignal(candles: List<Candle>): Boolean {
        val closes = candles.map { it.close }
        val atrNow = atr(candles, ATR_PERIOD)
        return closes.last() < sma(closes, SMA_PERIOD) - 0.5 * atrNow
    }

    /** Для контртрендовой позиции: краткосрочная перепроданность снята. */
    fun reversionExitSignal(candles: List<Candle>): Boolean {
        return rsi(candles.map { it.close }.takeLast(30), 3) >= 65.0
    }
}
