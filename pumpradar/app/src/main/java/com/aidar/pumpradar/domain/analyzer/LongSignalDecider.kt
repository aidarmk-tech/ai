package com.aidar.pumpradar.domain.analyzer

import kotlin.math.abs

/**
 * Решение по LONG-сигналу. Поддерживает два качественно разных сценария:
 * 1) раннее продолжение импульса;
 * 2) восстановление после контролируемого отката, пока цена ещё ниже вершины.
 *
 * Оба сценария возвращают LONG_CONTINUATION, поэтому существующий движок и
 * уведомления начинают использовать новую логику без изменения архитектуры.
 */
object LongSignalDecider {

    const val LONG_CONTINUATION = "LONG_CONTINUATION"
    const val STRONG_BUT_LATE = "STRONG_BUT_LATE"
    const val EXHAUSTION_RISK = "EXHAUSTION_RISK"
    const val REVERSAL_WATCH = "REVERSAL_WATCH"
    const val NO_TRADE = "NO_TRADE"

    data class PeakFeatures(
        val distanceFromLocalHighPct: Double = 0.0,
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
        val hardVeto: Boolean,
        val buyerPressureDeclining: Boolean,
        val newHighWithoutCvdHigh: Boolean,
        val peak: PeakFeatures = PeakFeatures(),
        val repeatBlocked: Boolean = false
    )

    data class Decision(
        val label: String,
        val maxTargetPercent: Double,
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
        val slip = i.slippagePercent

        val ppe = abs(r15) / maxOf(abs(i.quoteVolume30s), cfg.epsilon)
        val exhaustionRisk = exhaustionRisk(i, cfg, reasons)
        val entryRisk = entryRisk(i)

        val liquidityOk = i.dataFresh &&
            spread <= cfg.maxSpreadBps &&
            slip != null && slip <= cfg.maxSlippagePercent

        val absorption = volZ > cfg.extremeVolumeZ &&
            (r15 < cfg.minReturn15s || tbr < cfg.minTakerBuyForExtremeVol)
        if (absorption) reasons.add("поглощение: объём есть, цена не продвигается")

        val strictGate = passesStrictGate(i, cfg)
        val retestGate = passesRetestGate(i, cfg)

        // Общие запреты действуют для раннего входа и для ретеста.
        val commonHardVeto = i.hardVeto ||
            r5m > cfg.maxReturn5m ||
            i.cvd30s <= 0.0 ||
            i.cvdSlope < 0.0 ||
            !liquidityOk ||
            absorption ||
            i.peak.lowerHighDetected ||
            !i.peak.breakoutLevelHeld ||
            i.peak.failedHighAttempts >= cfg.maxFailedHighAttempts

        val reversalWatch = tbr <= cfg.reversalTakerBuyRatio30s &&
            r60 <= cfg.reversalReturn60s && i.buyerPressureDeclining

        val highPotential = strictGate &&
            r60 >= cfg.minReturn60sForHighPotential &&
            r5m <= cfg.maxReturn5mForHighTarget &&
            relBtc >= cfg.minRelStrengthForHighTarget

        val label = when {
            i.repeatBlocked -> NO_TRADE
            exhaustionRisk >= cfg.exhaustionBlock -> EXHAUSTION_RISK
            reversalWatch -> REVERSAL_WATCH
            commonHardVeto -> if (i.impulseScore >= 70) STRONG_BUT_LATE else NO_TRADE
            retestGate -> LONG_CONTINUATION
            strictGate -> LONG_CONTINUATION
            i.impulseScore >= 70 -> STRONG_BUT_LATE
            else -> NO_TRADE
        }

        val maxTarget = when {
            label != LONG_CONTINUATION -> cfg.primaryTargetPercent
            highPotential -> {
                reasons.add("ранняя фаза: потенциал 5–7% только при сохранении потока")
                cfg.highPotentialTargetPercent
            }
            retestGate -> {
                reasons.add("контролируемый откат: цена восстановилась до повторной вершины")
                5.0
            }
            else -> cfg.primaryTargetPercent
        }

        return Decision(label, maxTarget, entryRisk, exhaustionRisk, ppe, reasons)
    }

    /** Ранний импульс: сильный поток, небольшое уже пройденное движение, цена у high. */
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
            !((i.volumeZ30s ?: 0.0) > cfg.extremeVolumeZ && r15 < cfg.minReturn15s) &&
            !((i.volumeZ30s ?: 0.0) > cfg.extremeVolumeZ && tbr < cfg.minTakerBuyForExtremeVol) &&
            i.peak.distanceFromLocalHighPct <= cfg.maxDistanceFromHighPct &&
            !i.peak.lowerHighDetected &&
            i.peak.breakoutLevelHeld &&
            i.peak.failedHighAttempts < cfg.maxFailedHighAttempts
    }

    /**
     * Ретест: был откат 0.8–3.5%, текущая цена уже отскочила минимум на 0.35 п.п.,
     * но всё ещё находится ниже старой вершины. Поток покупателей снова положительный.
     */
    private fun passesRetestGate(i: Input, cfg: SignalConfig): Boolean {
        val r15 = i.return15s ?: return false
        val r5m = i.return5m ?: return false
        val tbr = i.takerBuyRatio30s ?: return false
        val relBtc = i.relativeStrengthVsBtc ?: return false
        val spread = i.spreadBps ?: return false
        val slip = i.slippagePercent ?: return false
        val pullback = i.peak.pullbackFromHighPct
        val distance = i.peak.distanceFromLocalHighPct
        val recovered = pullback - distance

        return pullback in cfg.minRetestPullbackPercent..cfg.maxRetestPullbackPercent &&
            distance >= 0.15 &&
            distance <= cfg.maxRetestDistanceFromHighPercent &&
            recovered >= cfg.minRetestRecoveryPercent &&
            r15 >= cfg.minRetestReturn15s &&
            r5m <= cfg.maxReturn5m &&
            tbr >= cfg.minRetestTakerBuyRatio30s &&
            i.cvd30s > 0.0 &&
            i.cvdSlope > 0.0 &&
            relBtc > cfg.minRelativeStrengthVsBtc &&
            i.dataFresh &&
            spread <= cfg.maxSpreadBps &&
            slip <= cfg.maxSlippagePercent &&
            !i.hardVeto &&
            !i.buyerPressureDeclining &&
            !i.newHighWithoutCvdHigh &&
            !i.peak.lowerHighDetected &&
            i.peak.breakoutLevelHeld &&
            i.peak.failedHighAttempts < cfg.maxFailedHighAttempts
    }

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
        if ((i.volumeZ30s ?: 0.0) > cfg.extremeVolumeZ &&
            (i.return15s ?: 0.0) < cfg.minReturn15s
        ) r += 15.0
        return r.coerceIn(0.0, 100.0).toInt()
    }

    private fun entryRisk(i: Input): Int {
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
            else -> (slip * 15.0).coerceIn(0.0, 15.0)
        }
        if (i.repeatBlocked) r += 10.0
        return r.coerceIn(0.0, 100.0).toInt()
    }
}
