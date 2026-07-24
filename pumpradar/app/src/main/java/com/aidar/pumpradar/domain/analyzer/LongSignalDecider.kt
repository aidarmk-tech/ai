package com.aidar.pumpradar.domain.analyzer

import kotlin.math.abs

/**
 * Ужесточённое решение по LONG-сигналу (items 1,2,3,5,7,8). Чистая логика,
 * тестируется отдельно. Заменяет «высокий Impulse Score = хороший вход» на
 * пользовательские состояния и жёсткие запреты, снижающие ложные сигналы.
 *
 * Состояния (item 7): LONG_CONTINUATION / STRONG_BUT_LATE / EXHAUSTION_RISK /
 * REVERSAL_WATCH / NO_TRADE. SHORT здесь НЕ открывается — только пометка
 * REVERSAL_WATCH; подтверждение шорта — в PumpReversalShortDetector (SHADOW).
 */
object LongSignalDecider {

    const val LONG_CONTINUATION = "LONG_CONTINUATION"
    const val STRONG_BUT_LATE = "STRONG_BUT_LATE"
    const val EXHAUSTION_RISK = "EXHAUSTION_RISK"
    const val REVERSAL_WATCH = "REVERSAL_WATCH"
    const val NO_TRADE = "NO_TRADE"

    /** Признаки вершины (item 6), считаются PeakTracker'ом. */
    data class PeakFeatures(
        val distanceFromLocalHighPct: Double = 0.0,   // % ниже локального максимума (0 = на максимуме)
        val secondsSinceLocalHigh: Long = 0,
        val pullbackFromHighPct: Double = 0.0,
        val failedHighAttempts: Int = 0,
        val lowerHighDetected: Boolean = false,
        val breakoutLevelHeld: Boolean = true
    )

    data class Input(
        val return15s: Double?,
        val return60s: Double?,
        val return5m: Double?,
        val takerBuyRatio30s: Double?,
        val cvd30s: Double,
        val cvdSlope: Double,
        val volumeZ30s: Double?,
        val quoteVolume30s: Double,
        val relativeStrengthVsBtc: Double?,
        val spreadBps: Double?,
        val slippagePercent: Double?,
        val impulseScore: Int,
        val dataFresh: Boolean,
        val hardVeto: Boolean,                 // hard veto из PumpScoreCalculator
        val buyerPressureDeclining: Boolean,   // item 4 (BuyerPressureTracker)
        val newHighWithoutCvdHigh: Boolean,    // item 5 (цена обновила максимум, CVD — нет)
        val peak: PeakFeatures = PeakFeatures(),
        val repeatBlocked: Boolean = false     // item 10 (governor)
    )

    data class Decision(
        val label: String,
        val maxTargetPercent: Double,          // до какой цели допускаем (1.0 обычно, 3.0 при строгих условиях)
        val entryRisk: Int,
        val exhaustionRisk: Int,
        val priceProgressEfficiency: Double,
        val reasons: List<String>
    )

    fun decide(i: Input, cfg: SignalConfig = SignalConfig()): Decision {
        val reasons = ArrayList<String>()
        val r15 = i.return15s ?: 0.0
        val r60 = i.return60s ?: 0.0
        val r5m = i.return5m ?: 0.0
        val tbr = i.takerBuyRatio30s ?: 0.0
        val relBtc = i.relativeStrengthVsBtc ?: -1.0
        val volZ = i.volumeZ30s ?: 0.0
        val spread = i.spreadBps ?: Double.MAX_VALUE
        val slip = i.slippagePercent ?: 0.0

        // Объём сам по себе НЕ положительный признак (item 3): эффективность
        // продвижения цены на единицу объёма.
        val ppe = abs(r15) / maxOf(abs(i.quoteVolume30s), cfg.epsilon)

        val exhaustionRisk = exhaustionRisk(i, cfg, reasons)
        val entryRisk = entryRisk(i, cfg)

        val liquidityOk = i.dataFresh && spread <= cfg.maxSpreadBps && slip <= cfg.maxSlippagePercent

        // Поглощение (item 3): экстремальный объём при слабом продвижении цены/слабом потоке.
        val absorption = volZ > cfg.extremeVolumeZ &&
            (r15 < cfg.minReturn15s || tbr < cfg.minTakerBuyForExtremeVol)
        if (absorption) reasons.add("поглощение: объём есть, цена не идёт")

        // Жёсткие запреты LONG (item 2).
        val hardLongVeto = i.hardVeto ||
            tbr < cfg.hardVetoTakerBuyRatio30s ||
            r15 < cfg.minReturn15s ||
            r60 < cfg.minReturn60s ||
            r5m > cfg.maxReturn5m ||
            i.cvd30s <= 0.0 ||
            i.cvdSlope < 0.0 ||
            !liquidityOk ||
            absorption ||
            // Вершина (item 6).
            i.peak.distanceFromLocalHighPct > cfg.maxDistanceFromHighPct ||
            i.peak.lowerHighDetected ||
            !i.peak.breakoutLevelHeld ||
            i.peak.failedHighAttempts >= cfg.maxFailedHighAttempts

        // REVERSAL_WATCH (item 8): давление покупателей выдыхается.
        val reversalWatch = tbr <= cfg.reversalTakerBuyRatio30s &&
            r60 <= cfg.reversalReturn60s && i.buyerPressureDeclining

        // Приоритет состояний.
        val label = when {
            i.repeatBlocked -> NO_TRADE
            exhaustionRisk >= cfg.exhaustionBlock -> EXHAUSTION_RISK
            reversalWatch -> REVERSAL_WATCH
            hardLongVeto -> if (i.impulseScore >= 70) STRONG_BUT_LATE else NO_TRADE
            passesStrictGate(i, cfg) -> LONG_CONTINUATION
            i.impulseScore >= 70 -> STRONG_BUT_LATE
            else -> NO_TRADE
        }

        // Целевой ярус: до 3% только при самых строгих условиях (item 1).
        val highTargetOk = r5m <= cfg.maxReturn5mForHighTarget &&
            relBtc >= cfg.minRelStrengthForHighTarget
        val maxTarget = if (label == LONG_CONTINUATION && highTargetOk)
            (cfg.extraTargetsPercent.maxOrNull() ?: cfg.primaryTargetPercent)
        else cfg.primaryTargetPercent

        return Decision(label, maxTarget, entryRisk, exhaustionRisk, ppe, reasons)
    }

    /** Строгий гейт LONG_CONTINUATION (item 1). */
    private fun passesStrictGate(i: Input, cfg: SignalConfig): Boolean {
        val r15 = i.return15s ?: return false
        val r60 = i.return60s ?: return false
        val r5m = i.return5m ?: return false
        val tbr = i.takerBuyRatio30s ?: return false
        val relBtc = i.relativeStrengthVsBtc ?: return false
        val spread = i.spreadBps ?: return false
        val slip = i.slippagePercent ?: return false
        return tbr >= cfg.minTakerBuyRatio30s &&
            r15 >= cfg.minReturn15s &&
            r60 >= cfg.minReturn60s &&
            r5m <= cfg.maxReturn5m &&
            i.cvd30s > 0.0 &&
            i.cvdSlope > 0.0 &&
            relBtc > cfg.minRelativeStrengthVsBtc &&
            i.dataFresh &&
            spread <= cfg.maxSpreadBps &&
            slip <= cfg.maxSlippagePercent &&
            !i.hardVeto &&
            // объёмные запреты (item 2)
            !((i.volumeZ30s ?: 0.0) > cfg.extremeVolumeZ && r15 < cfg.minReturn15s) &&
            !((i.volumeZ30s ?: 0.0) > cfg.extremeVolumeZ && tbr < cfg.minTakerBuyForExtremeVol) &&
            // вершина (item 6)
            i.peak.distanceFromLocalHighPct <= cfg.maxDistanceFromHighPct &&
            !i.peak.lowerHighDetected &&
            i.peak.breakoutLevelHeld &&
            i.peak.failedHighAttempts < cfg.maxFailedHighAttempts
    }

    /** Exhaustion Risk (item 5): реально варьируется по признакам истощения. */
    private fun exhaustionRisk(i: Input, cfg: SignalConfig, reasons: ArrayList<String>): Int {
        var r = 0.0
        val r60 = i.return60s ?: 0.0
        val r5m = i.return5m ?: 0.0
        if (i.buyerPressureDeclining) { r += 25.0; reasons.add("покупатели слабеют") }
        if (r60 > 0.0 && i.cvdSlope < 0.0) { r += 20.0; reasons.add("CVD-дивергенция") }
        if (i.newHighWithoutCvdHigh) { r += 20.0; reasons.add("новый максимум без нового CVD") }
        if (!i.peak.breakoutLevelHeld) { r += 15.0; reasons.add("breakout потерян") }
        if (i.peak.lowerHighDetected) { r += 15.0; reasons.add("lower high") }
        if ((i.spreadBps ?: 0.0) > 60.0) r += 10.0
        if (r5m >= 8.0) r += 15.0
        // Огромный объём без продвижения цены.
        if ((i.volumeZ30s ?: 0.0) > cfg.extremeVolumeZ && (i.return15s ?: 0.0) < cfg.minReturn15s) r += 15.0
        return r.coerceIn(0.0, 100.0).toInt()
    }

    /** Entry Risk (item 5): поздняя стадия, удалённость/откат от максимума, ликвидность, повтор. */
    private fun entryRisk(i: Input, cfg: SignalConfig): Int {
        var r = 0.0
        val r5m = i.return5m ?: 0.0
        r += when {
            r5m < 1.5 -> 0.0
            r5m < 3.0 -> 10.0
            r5m < 6.0 -> 20.0
            else -> 30.0
        }
        r += (i.peak.distanceFromLocalHighPct * 20.0).coerceIn(0.0, 20.0)
        r += (i.peak.pullbackFromHighPct * 10.0).coerceIn(0.0, 15.0)
        if (i.peak.secondsSinceLocalHigh in 1..300) r += 5.0
        val spread = i.spreadBps ?: 0.0
        r += ((spread - 15.0) / 105.0 * 15.0).coerceIn(0.0, 15.0)
        val slip = i.slippagePercent
        r += when {
            slip == null -> 15.0
            else -> (slip / 1.0 * 15.0).coerceIn(0.0, 15.0)
        }
        if (i.repeatBlocked) r += 10.0
        return r.coerceIn(0.0, 100.0).toInt()
    }
}
