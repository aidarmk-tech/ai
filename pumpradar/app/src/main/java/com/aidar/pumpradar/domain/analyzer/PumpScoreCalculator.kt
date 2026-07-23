package com.aidar.pumpradar.domain.analyzer

import com.aidar.pumpradar.core.math.MathUtils
import com.aidar.pumpradar.core.math.MathUtils.linearScore
import com.aidar.pumpradar.domain.model.Candidate
import com.aidar.pumpradar.domain.model.CandidateMetrics
import com.aidar.pumpradar.domain.model.SignalLevel
import javax.inject.Inject
import javax.inject.Singleton

data class ScoreResult(
    val score: Int,
    val level: SignalLevel,
    val reasons: List<String>,
    val risks: List<String>,
    val strongAllowed: Boolean
)

/**
 * Pump Score 0..100 (ТЗ раздел 10–11). Этап 3: без стакана (depth), поэтому
 * компонент Order book ограничен спредом; OBI/проскальзывание добавятся позже.
 */
@Singleton
class PumpScoreCalculator @Inject constructor() {

    fun score(c: Candidate, m: CandidateMetrics?): ScoreResult {
        val reasons = mutableListOf<String>()
        val risks = mutableListOf<String>()
        var total = 0.0

        // 1. Price impulse (0..20)
        val r15 = c.return15s ?: 0.0
        val r60 = c.return60s ?: 0.0
        val accel = c.acceleration ?: 0.0
        var impulse = linearScore(r15, 0.20, 1.20, 8.0) +
            linearScore(r60, 0.50, 3.00, 8.0) +
            linearScore(accel, 0.10, 0.80, 4.0)
        impulse = impulse.coerceAtMost(20.0)
        total += impulse
        if (impulse >= 8) reasons.add("ускорение цены")

        // 2. Trade volume anomaly (0..20)
        val volumeZ = m?.volumeZ30s
        if (volumeZ != null) {
            var vol = linearScore(volumeZ, 1.5, 6.0, 14.0)
            vol += linearScore(m.quoteVolume30s, 5_000.0, 200_000.0, 6.0)
            total += vol.coerceAtMost(20.0)
            if (vol >= 8) reasons.add("аномальный объём (Z=%.1f)".format(volumeZ))
        } else {
            risks.add("недостаточно истории объёма")
        }

        // 3. Trade intensity (0..10)
        if (m != null) {
            val intensity = linearScore(m.tradesPerSecond, 0.5, 5.0, 7.0) +
                linearScore(m.tradeCount30s.toDouble(), 10.0, 120.0, 3.0)
            total += intensity.coerceAtMost(10.0)
            if (intensity >= 5) reasons.add("рост числа сделок")
        }

        // 4. Aggressive buy flow (0..15)
        val tbr = m?.takerBuyRatio30s
        if (tbr != null) {
            var flow = linearScore(tbr, 0.52, 0.75, 10.0)
            if (m.cvdSlope > 0) flow += 5.0
            total += flow.coerceAtMost(15.0)
            if (tbr >= 0.60) reasons.add("покупатели доминируют (%.0f%%)".format(tbr * 100))
        }

        // 5. Order book (0..15) — только спред без depth (макс ~4).
        val spread = m?.spreadBps
        if (spread != null) {
            total += linearScore(spread, 40.0, 10.0, 4.0) // убывающая шкала
        }

        // 6. Breakout (0..10) — упрощённо через 5-минутный ход.
        val r5m = c.return5m ?: 0.0
        if (r5m in 1.0..8.0) {
            total += linearScore(r5m, 1.0, 4.0, 4.0)
            reasons.add("движение за 5 минут")
        }

        // 7. Relative strength vs BTC (0..5)
        val rel = c.relativeStrengthVsBtc
        if (rel != null) {
            total += linearScore(rel, 0.20, 2.00, 3.0)
            if (rel >= 1.0) reasons.add("сильнее BTC")
        }

        // 8. Data quality (0..5)
        val quality = when {
            m != null && m.ready && spread != null -> 5.0
            m != null && spread != null -> 3.0
            else -> 2.0
        }
        total += quality

        // ── Штрафы (ТЗ 11.1) ──
        if (tbr != null && tbr < 0.52) { total -= 10; risks.add("слабые покупки") }
        if (spread != null && spread >= 50) { total -= 10; risks.add("широкий спред") }
        if (spread != null && spread >= 100) total -= 15
        if (r60 > 0 && m != null && m.cvdSlope < 0) { total -= 12; risks.add("CVD падает при росте цены") }
        if (r5m >= 8) { total -= 10; risks.add("уже вырос на %.1f%% за 5м".format(r5m)) }
        if (r5m >= 12) total -= 10

        // Warmup: без истории объёма ограничиваем сверху (ТЗ 8.2).
        if (volumeZ == null) total = total.coerceAtMost(69.0)

        // ── Hard veto для сильного уведомления (ТЗ 11.2) ──
        val veto = (spread != null && spread >= 150) ||
            (tbr != null && tbr < 0.45) ||
            r5m >= 25 || c.price <= 0
        val strongAllowed = !veto
        if (veto) risks.add("запрет сильного сигнала (риск)")

        val finalScore = MathUtils.clampScore(total)
        var level = SignalLevel.fromScore(finalScore)
        if (!strongAllowed && level.min >= SignalLevel.STRONG.min) {
            level = SignalLevel.EARLY
        }
        return ScoreResult(finalScore, level, reasons, risks, strongAllowed)
    }
}
