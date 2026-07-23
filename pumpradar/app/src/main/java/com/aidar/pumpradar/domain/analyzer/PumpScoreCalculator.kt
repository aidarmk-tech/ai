package com.aidar.pumpradar.domain.analyzer

import com.aidar.pumpradar.core.math.MathUtils
import com.aidar.pumpradar.core.math.MathUtils.linearScore
import com.aidar.pumpradar.domain.model.Candidate
import com.aidar.pumpradar.domain.model.CandidateMetrics
import com.aidar.pumpradar.domain.model.OrderBookMetrics
import com.aidar.pumpradar.domain.model.SignalLevel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Результат трёхосевой оценки (ТЗ v2 разделы 0A.1, 0A.10–0A.12).
 * [impulse] — сила аномалии (бывший Pump Score), [entryRisk] — риск позднего
 * входа/ликвидности, [confidence] — полнота данных. [opportunityLabel] —
 * пользовательская метка, [level] — технический уровень с учётом гейта/потолков.
 */
data class ScoreResult(
    val impulse: Int,
    val entryRisk: Int,
    val confidence: Int,
    val level: SignalLevel,
    val opportunityLabel: String,
    val reasons: List<String>,
    val risks: List<String>,
    val strongAllowed: Boolean
)

/**
 * Трёхосевой калькулятор (ТЗ v2, раздел 0A). Impulse Score — это прежний Pump
 * Score (миграция названия по 0A.1). Дополнительно считает Entry Risk и
 * Confidence и выводит OpportunityLabel; STRONG проходит только через
 * evidence-гейт Price+Flow+Liquidity и потолок по Confidence.
 */
@Singleton
class PumpScoreCalculator @Inject constructor() {

    fun score(
        c: Candidate,
        m: CandidateMetrics?,
        ob: OrderBookMetrics?,
        feedAgeMillis: Long? = null
    ): ScoreResult {
        val reasons = mutableListOf<String>()
        val risks = mutableListOf<String>()
        var total = 0.0

        val r15 = c.return15s ?: 0.0
        val r60 = c.return60s ?: 0.0
        val accel = c.acceleration ?: 0.0
        val r5m = c.return5m ?: 0.0
        val volumeZ = m?.volumeZ30s
        val tbr = m?.takerBuyRatio30s
        val spread = ob?.spreadBps ?: m?.spreadBps
        val slip = ob?.buySlippagePercent

        // ── Impulse Score (0..100), бывший Pump Score (ТЗ 10.x) ──
        var impulse = linearScore(r15, 0.20, 1.20, 8.0) +
            linearScore(r60, 0.50, 3.00, 8.0) +
            linearScore(accel, 0.10, 0.80, 4.0)
        impulse = impulse.coerceAtMost(20.0)
        total += impulse
        if (impulse >= 8) reasons.add("ускорение цены")

        if (volumeZ != null) {
            var vol = linearScore(volumeZ, 1.5, 6.0, 14.0)
            vol += linearScore(m.quoteVolume30s, 5_000.0, 200_000.0, 6.0)
            total += vol.coerceAtMost(20.0)
            if (vol >= 8) reasons.add("аномальный объём (Z=%.1f)".format(volumeZ))
        } else {
            risks.add("недостаточно истории объёма")
        }

        if (m != null) {
            val intensity = linearScore(m.tradesPerSecond, 0.5, 5.0, 7.0) +
                linearScore(m.tradeCount30s.toDouble(), 10.0, 120.0, 3.0)
            total += intensity.coerceAtMost(10.0)
            if (intensity >= 5) reasons.add("рост числа сделок")
        }

        if (tbr != null) {
            var flow = linearScore(tbr, 0.52, 0.75, 10.0)
            if (m.cvdSlope > 0) flow += 5.0
            total += flow.coerceAtMost(15.0)
            if (tbr >= 0.60) reasons.add("покупатели доминируют (%.0f%%)".format(tbr * 100))
        }

        var orderBook = 0.0
        if (ob != null) {
            orderBook += linearScore(ob.obi10, 0.0, 0.60, 7.0)
            orderBook += linearScore(ob.spreadBps, 40.0, 10.0, 4.0)
            slip?.let { orderBook += linearScore(it, 0.80, 0.10, 4.0) }
            total += orderBook.coerceAtMost(15.0)
            if (ob.obi10 >= 0.30) reasons.add("стакан подтверждает (OBI %.2f)".format(ob.obi10))
        } else if (spread != null) {
            total += linearScore(spread, 40.0, 10.0, 4.0)
        }

        if (r5m in 1.0..8.0) {
            total += linearScore(r5m, 1.0, 4.0, 4.0)
            reasons.add("движение за 5 минут")
        }

        val rel = c.relativeStrengthVsBtc
        if (rel != null) {
            total += linearScore(rel, 0.20, 2.00, 3.0)
            if (rel >= 1.0) reasons.add("сильнее BTC")
        }

        val quality = when {
            m != null && m.ready && ob != null -> 5.0
            m != null && spread != null -> 3.0
            else -> 2.0
        }
        total += quality
        if (ob != null && slip == null) risks.add("недостаточная глубина стакана")

        // Штрафы влияют только на Impulse (ТЗ 11.1) — но НЕ на Entry Risk/Confidence.
        if (tbr != null && tbr < 0.52) { total -= 10; risks.add("слабые покупки") }
        if (spread != null && spread >= 50) { total -= 10; risks.add("широкий спред") }
        if (spread != null && spread >= 100) total -= 15
        if (r60 > 0 && m != null && m.cvdSlope < 0) { total -= 12; risks.add("CVD падает при росте цены") }
        if (r5m >= 8) { total -= 10; risks.add("уже вырос на %.1f%% за 5м".format(r5m)) }
        if (r5m >= 12) total -= 10
        if (volumeZ == null) total = total.coerceAtMost(69.0)

        val impulseScore = MathUtils.clampScore(total)

        // ── Entry Risk Score (0..100), ТЗ 0A.10 ──
        val lateRisk = when {
            r5m < 3.0 -> 0.0
            r5m < 8.0 -> linearScore(r5m, 3.0, 8.0, 8.0)
            r5m < 12.0 -> 8.0 + linearScore(r5m, 8.0, 12.0, 7.0)
            r5m < 18.0 -> 15.0 + linearScore(r5m, 12.0, 18.0, 7.0)
            else -> 25.0
        }
        val spreadRisk = spread?.let { linearScore(it, 15.0, 120.0, 15.0) } ?: 0.0
        val slippageRisk = when {
            ob != null && slip == null -> 20.0            // InsufficientDepth
            slip != null -> linearScore(slip, 0.10, 1.00, 20.0)
            else -> 0.0
        }
        var exhaustion = 0.0
        if (r60 > 0 && m != null && m.cvdSlope < 0) exhaustion += 12.0
        if (r5m >= 8.0 && accel < 0) exhaustion += 8.0
        exhaustion = exhaustion.coerceAtMost(20.0)
        // Концентрацию объёма (largestTrade/…) пока не отслеживаем — 0 (0A.10, будущий этап).
        val concentrationRisk = 0.0
        var dataRisk = 0.0
        if (volumeZ == null) dataRisk += 5.0
        if (ob == null) dataRisk += 3.0
        if (m == null || !m.ready) dataRisk += 2.0
        dataRisk = dataRisk.coerceAtMost(10.0)
        val entryRisk = MathUtils.clampScore(
            lateRisk + spreadRisk + slippageRisk + exhaustion + concentrationRisk + dataRisk
        )
        if (lateRisk >= 15) risks.add("поздняя стадия (5м +%.1f%%)".format(r5m))

        // ── Confidence Score (0..100), ТЗ 0A.11 ──
        // Feed freshness / no-gaps / clock — временно предположительные до этапа
        // событийной целостности (Тир 2); отмечено в отчёте.
        val priceReady = when {
            c.return5m != null -> 15.0
            c.return60s != null -> 8.0
            else -> 0.0
        }
        val tradeReady = when {
            m?.ready == true && volumeZ != null -> 20.0
            m != null && m.tradeCount30s >= 3 -> 10.0
            m != null -> 4.0
            else -> 0.0
        }
        val depthReady = when {
            ob != null && slip != null -> 15.0
            ob != null -> 8.0
            else -> 0.0
        }
        // Свежесть фида по возрасту последнего рыночного сообщения (ТЗ 0A.20).
        val feedFreshness = when {
            feedAgeMillis == null -> 12.0            // возраст неизвестен — предположительно
            feedAgeMillis <= 3_000 -> 20.0
            feedAgeMillis <= 10_000 -> linearScore(feedAgeMillis.toDouble(), 10_000.0, 3_000.0, 20.0)
            else -> 4.0
        }
        if (feedAgeMillis != null && feedAgeMillis > 10_000) risks.add("фид отстаёт (%.0fс)".format(feedAgeMillis / 1000.0))
        // Разрыв потока сделок (ТЗ 0A.7) обнуляет «нет пропусков» и снижает Confidence.
        val tradeGap = m?.tradeGap == true
        val noGaps = if (tradeGap) 0.0 else 10.0
        if (tradeGap) risks.add("разрыв потока сделок")
        val priceOk = r15 > 0 || r60 > 0 || accel > 0
        val flowOk = m != null && m.ready && tbr != null && tbr >= 0.52 && volumeZ != null
        val liqOk = ob != null && slip != null && ob.spreadBps < 60.0
        val confirms = listOf(priceOk, flowOk, liqOk).count { it }
        val independentConf = confirms / 3.0 * 10.0
        val clockQuality = 3.0
        val confidence = MathUtils.clampScore(
            priceReady + tradeReady + depthReady + feedFreshness + noGaps +
                independentConf + clockQuality
        )

        // ── Hard veto (ТЗ 11.2) ──
        val depthInsufficient = ob != null && slip == null
        val veto = (spread != null && spread >= 150) ||
            (tbr != null && tbr < 0.45) ||
            r5m >= 25 || c.price <= 0 || depthInsufficient ||
            (slip?.let { it >= 0.8 } == true)
        if (veto) risks.add("запрет сильного сигнала (риск)")

        // ── Уровень: impulse → потолок по Confidence → evidence-гейт (0A.2/0A.11) ──
        val strongEligible = priceOk && flowOk && liqOk && !veto && confidence >= 70
        var cap = when {
            confidence < 40 -> SignalLevel.NORMAL
            confidence < 60 -> SignalLevel.WATCH
            confidence < 70 -> SignalLevel.EARLY
            confidence < 85 -> SignalLevel.STRONG
            else -> SignalLevel.EXTREME
        }
        if (!strongEligible && cap.min >= SignalLevel.STRONG.min) cap = SignalLevel.EARLY
        val byImpulse = SignalLevel.fromScore(impulseScore)
        val level = if (byImpulse.ordinal <= cap.ordinal) byImpulse else cap
        val strongAllowed = level.min >= SignalLevel.STRONG.min

        // ── OpportunityLabel (ТЗ 0A.12) ──
        val label = when {
            confidence < 60 -> "DATA_INCOMPLETE"
            entryRisk >= 70 -> "TOO_LATE"
            impulseScore >= 70 && entryRisk < 40 && confidence >= 70 -> "CONFIRMED"
            impulseScore >= 70 && entryRisk in 40..69 -> "STRONG_BUT_RISKY"
            impulseScore >= 55 && entryRisk < 40 -> "EARLY_CLEAN"
            else -> "WATCH"
        }

        return ScoreResult(
            impulse = impulseScore,
            entryRisk = entryRisk,
            confidence = confidence,
            level = level,
            opportunityLabel = label,
            reasons = reasons,
            risks = risks,
            strongAllowed = strongAllowed
        )
    }
}
