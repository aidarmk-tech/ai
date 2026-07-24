#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")

def write(rel: str, text: str) -> None:
    (ROOT / rel).write_text(text, encoding="utf-8")

def replace_once(rel: str, old: str, new: str) -> None:
    text = read(rel)
    if old not in text:
        raise RuntimeError(f"Pattern not found in {rel}:\n{old[:240]}")
    write(rel, text.replace(old, new, 1))

replace_once(
    "pumpradar/app/src/main/java/com/aidar/pumpradar/domain/analyzer/SignalConfig.kt",
    '''    val maxReturn5mForHighTarget: Double = 1.50,
    val minRelStrengthForHighTarget: Double = 0.80,
''',
    '''    val maxReturn5mForHighTarget: Double = 1.50,
    val minRelStrengthForHighTarget: Double = 0.80,
    val minReturn60sForHighPotential: Double = 1.00,
    val highPotentialTargetPercent: Double = 7.00,
'''
)
replace_once(
    "pumpradar/app/src/main/java/com/aidar/pumpradar/domain/analyzer/SignalConfig.kt",
    '    val extraTargetsPercent: List<Double> = listOf(1.5, 2.0, 3.0),',
    '    val extraTargetsPercent: List<Double> = listOf(1.5, 2.0, 3.0, 5.0, 7.0),'
)

replace_once(
    "pumpradar/app/src/main/java/com/aidar/pumpradar/domain/analyzer/LongSignalDecider.kt",
    '''    const val LONG_CONTINUATION = "LONG_CONTINUATION"
    const val STRONG_BUT_LATE = "STRONG_BUT_LATE"
''',
    '''    const val LONG_CONTINUATION = "LONG_CONTINUATION"
    const val EARLY_HIGH_POTENTIAL = "EARLY_HIGH_POTENTIAL"
    const val RETEST_CONFIRMED = "RETEST_CONFIRMED"
    const val STRONG_BUT_LATE = "STRONG_BUT_LATE"
'''
)
replace_once(
    "pumpradar/app/src/main/java/com/aidar/pumpradar/domain/analyzer/LongSignalDecider.kt",
    '''        // Приоритет состояний.
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
''',
    '''        // Редкая ранняя конфигурация: поток подтверждён, но 5-минутное
        // движение ещё не ушло далеко. Это потенциал, а не обещание результата.
        val highTargetOk = tbr >= cfg.minTakerBuyRatio30s &&
            r15 >= cfg.minReturn15s &&
            r60 >= cfg.minReturn60sForHighPotential &&
            r5m <= cfg.maxReturn5mForHighTarget &&
            relBtc >= cfg.minRelStrengthForHighTarget

        val strictGate = passesStrictGate(i, cfg)
        val label = when {
            i.repeatBlocked -> NO_TRADE
            exhaustionRisk >= cfg.exhaustionBlock -> EXHAUSTION_RISK
            reversalWatch -> REVERSAL_WATCH
            hardLongVeto -> if (i.impulseScore >= 70) STRONG_BUT_LATE else NO_TRADE
            strictGate && highTargetOk -> EARLY_HIGH_POTENTIAL
            strictGate -> LONG_CONTINUATION
            i.impulseScore >= 70 -> STRONG_BUT_LATE
            else -> NO_TRADE
        }

        if (label == EARLY_HIGH_POTENTIAL) {
            reasons.add("ранняя фаза: высокая относительная сила, потенциал 5–7% только при сохранении потока")
        }
        val maxTarget = if (label == EARLY_HIGH_POTENTIAL)
            cfg.highPotentialTargetPercent
        else cfg.primaryTargetPercent
'''
)

write(
    "pumpradar/app/src/main/java/com/aidar/pumpradar/domain/analyzer/RetestDetector.kt",
    r'''package com.aidar.pumpradar.domain.analyzer

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Контролируемый откат после импульса. Подтверждает восстановление спроса
 * до повторного достижения старого максимума, чтобы сигнал не был поздним.
 */
@Singleton
class RetestDetector @Inject constructor() {
    enum class State { IDLE, IMPULSE, PULLBACK, COOLDOWN }

    data class Input(
        val now: Long,
        val price: Double,
        val cvd: Double,
        val cvdSlope: Double,
        val takerBuyRatio: Double?,
        val spreadBps: Double?,
        val slippagePercent: Double?,
        val confidence: Int,
        val entryRisk: Int,
        val isImpulse: Boolean,
        val return15s: Double? = null,
        val takerBuyRatio15s: Double? = null,
        val cvd15s: Double = 0.0
    )

    private class Row {
        var state = State.IDLE
        var impulseStartPrice = 0.0
        var impulseHighPrice = 0.0
        var pullbackLowPrice = 0.0
        var pullbackStartTime = 0L
        var impulseCvdHigh = 0.0
        var cooldownUntil = 0L
    }

    private val rows = HashMap<String, Row>()
    private val lock = Any()

    fun retain(symbols: Set<String>) = synchronized(lock) { rows.keys.retainAll(symbols) }
    fun clear() = synchronized(lock) { rows.clear() }

    fun update(symbol: String, i: Input): Boolean = synchronized(lock) {
        val row = rows.getOrPut(symbol) { Row() }
        when (row.state) {
            State.COOLDOWN -> {
                if (i.now >= row.cooldownUntil) row.state = State.IDLE
                false
            }
            State.IDLE -> {
                if (i.isImpulse && i.price > 0.0) {
                    row.state = State.IMPULSE
                    row.impulseStartPrice = i.price
                    row.impulseHighPrice = i.price
                    row.impulseCvdHigh = i.cvd
                }
                false
            }
            State.IMPULSE -> {
                if (i.price > row.impulseHighPrice) row.impulseHighPrice = i.price
                if (i.cvd > row.impulseCvdHigh) row.impulseCvdHigh = i.cvd
                val drawdown = pct(row.impulseHighPrice, i.price)
                if (drawdown in MIN_PULLBACK..MAX_PULLBACK && i.price > row.impulseStartPrice) {
                    row.state = State.PULLBACK
                    row.pullbackLowPrice = i.price
                    row.pullbackStartTime = i.now
                } else if (drawdown > MAX_PULLBACK || i.price <= row.impulseStartPrice) {
                    reset(row)
                }
                false
            }
            State.PULLBACK -> {
                val dur = i.now - row.pullbackStartTime
                if (i.price < row.pullbackLowPrice) row.pullbackLowPrice = i.price
                val drawdown = pct(row.impulseHighPrice, i.price)
                if (drawdown > MAX_PULLBACK || i.price <= row.impulseStartPrice || dur > MAX_PULLBACK_MS) {
                    reset(row)
                    return@synchronized false
                }

                val range = row.impulseHighPrice - row.pullbackLowPrice
                val recovered = i.price - row.pullbackLowPrice
                val recoveryFraction = if (range > 0.0) recovered / range else 0.0
                val bouncePct = if (row.pullbackLowPrice > 0.0)
                    recovered / row.pullbackLowPrice * 100.0 else 0.0
                val stillBeforeOldHigh =
                    i.price <= row.impulseHighPrice * (1.0 - MIN_DISTANCE_BELOW_HIGH)

                val confirmed =
                    dur >= MIN_PULLBACK_MS &&
                    bouncePct >= MIN_BOUNCE_PCT &&
                    recoveryFraction in MIN_RECOVERY_FRACTION..MAX_RECOVERY_FRACTION &&
                    stillBeforeOldHigh &&
                    (i.return15s ?: 0.0) >= MIN_RETURN15S &&
                    (i.takerBuyRatio15s ?: i.takerBuyRatio ?: 0.0) >= MIN_TAKER_BUY_15S &&
                    i.cvd15s > 0.0 && i.cvdSlope > 0.0 && i.cvd > 0.0 &&
                    i.cvd >= MIN_CVD_RETAINED * row.impulseCvdHigh &&
                    (i.spreadBps ?: Double.MAX_VALUE) <= MAX_SPREAD_BPS &&
                    (i.slippagePercent ?: Double.MAX_VALUE) <= MAX_SLIPPAGE &&
                    i.confidence >= MIN_CONFIDENCE && i.entryRisk <= MAX_ENTRY_RISK

                if (confirmed) {
                    row.state = State.COOLDOWN
                    row.cooldownUntil = i.now + COOLDOWN_MS
                    return@synchronized true
                }
                false
            }
        }
    }

    private fun reset(row: Row) {
        row.state = State.IDLE
        row.impulseStartPrice = 0.0
        row.impulseHighPrice = 0.0
        row.pullbackLowPrice = 0.0
        row.pullbackStartTime = 0L
        row.impulseCvdHigh = 0.0
    }

    private fun pct(from: Double, to: Double): Double =
        if (from > 0.0) (from - to) / from * 100.0 else 0.0

    private companion object {
        const val MIN_PULLBACK = 0.8
        const val MAX_PULLBACK = 3.5
        const val MIN_PULLBACK_MS = 5_000L
        const val MAX_PULLBACK_MS = 180_000L
        const val MIN_BOUNCE_PCT = 0.35
        const val MIN_RECOVERY_FRACTION = 0.30
        const val MAX_RECOVERY_FRACTION = 0.80
        const val MIN_DISTANCE_BELOW_HIGH = 0.001
        const val MIN_RETURN15S = 0.15
        const val MIN_TAKER_BUY_15S = 0.60
        const val MIN_CVD_RETAINED = 0.40
        const val MAX_SPREAD_BPS = 40.0
        const val MAX_SLIPPAGE = 0.40
        const val MIN_CONFIDENCE = 75
        const val MAX_ENTRY_RISK = 45
        const val COOLDOWN_MS = 5 * 60_000L
    }
}
'''
)

engine = "pumpradar/app/src/main/java/com/aidar/pumpradar/service/MonitoringEngine.kt"
replace_once(engine, '            retestDetector.update(\n', '            val retestConfirmed = retestDetector.update(\n')
replace_once(
    engine,
    '''                    confidence = res.confidence, entryRisk = res.entryRisk,
                    isImpulse = res.level.ordinal >= SignalLevel.EARLY.ordinal
''',
    '''                    confidence = res.confidence, entryRisk = res.entryRisk,
                    isImpulse = res.level.ordinal >= SignalLevel.EARLY.ordinal,
                    return15s = c.return15s,
                    takerBuyRatio15s = metrics?.takerBuyRatio15s,
                    cvd15s = metrics?.cvd15s ?: 0.0
'''
)
replace_once(engine, '            val decision = LongSignalDecider.decide(\n', '            val repeatBlocked = !signalGovernor.allow(c.symbol)\n            val decision = LongSignalDecider.decide(\n')
replace_once(
    engine,
    '''                    peak = peak.features,
                    repeatBlocked = !signalGovernor.allow(c.symbol)
''',
    '''                    peak = peak.features,
                    repeatBlocked = repeatBlocked
'''
)
replace_once(
    engine,
    '''            val label = decision.label
            val live = LiveSignal(
''',
    '''            val retestAllowed = retestConfirmed &&
                !repeatBlocked && dataFresh &&
                decision.exhaustionRisk < signalConfig.exhaustionBlock &&
                res.artificialRisk < 70 && res.marketWideRisk < 70
            val label = if (retestAllowed)
                LongSignalDecider.RETEST_CONFIRMED
            else decision.label
            val decisionReasons = buildList {
                addAll(decision.reasons)
                if (retestAllowed) add("контролируемый откат: спрос восстановился до повторной вершины")
            }
            val live = LiveSignal(
'''
)
replace_once(engine, '                stage = stageOf(res.level, metrics?.ready == true),\n', '                stage = if (label == LongSignalDecider.RETEST_CONFIRMED)\n                    "RETEST"\n                else stageOf(res.level, metrics?.ready == true),\n')
replace_once(engine, '                reasons = res.reasons,\n', '                reasons = (res.reasons + decisionReasons).distinct(),\n')
replace_once(
    engine,
    '''        val prev = emitted[c.symbol]
        val cooldownMs = 15 * 60_000L
        val shouldPersist = prev == null || lvl.ordinal > prev.first || now - prev.second >= cooldownMs
        if (!shouldPersist) return
        emitted[c.symbol] = lvl.ordinal to now
''',
    '''        val prev = emitted[c.symbol]
        val rank = emissionRank(live)
        val cooldownMs = 15 * 60_000L
        val shouldPersist = prev == null || rank > prev.first || now - prev.second >= cooldownMs
        if (!shouldPersist) return
        emitted[c.symbol] = rank to now
'''
)
replace_once(
    engine,
    '''            if (live.opportunityLabel == LongSignalDecider.LONG_CONTINUATION) {
                signalGovernor.recordSignal(c.symbol, now)
            }
''',
    '''            if (live.opportunityLabel in NOTIFY_LABELS) {
                signalGovernor.recordSignal(c.symbol, now)
            }
'''
)
replace_once(
    engine,
    '''    private fun stageOf(level: SignalLevel, ready: Boolean): String = when {
''',
    '''    private fun emissionRank(signal: LiveSignal): Int = when (signal.opportunityLabel) {
        LongSignalDecider.RETEST_CONFIRMED -> 100
        LongSignalDecider.EARLY_HIGH_POTENTIAL -> 90
        LongSignalDecider.LONG_CONTINUATION -> 80
        else -> SignalLevel.valueOf(signal.level).ordinal
    }

    private fun stageOf(level: SignalLevel, ready: Boolean): String = when {
'''
)
replace_once(
    engine,
    '''        val NOTIFY_LABELS = setOf(LongSignalDecider.LONG_CONTINUATION)
        const val ALGO_VERSION = "3.0.0"
''',
    '''        val NOTIFY_LABELS = setOf(
            LongSignalDecider.LONG_CONTINUATION,
            LongSignalDecider.EARLY_HIGH_POTENTIAL,
            LongSignalDecider.RETEST_CONFIRMED
        )
        const val ALGO_VERSION = "3.1.0"
'''
)

notif = "pumpradar/app/src/main/java/com/aidar/pumpradar/notification/SignalNotificationManager.kt"
replace_once(notif, '        val levelOrd = levelOrdinal(signal.level)\n        val last = lastBySymbol[signal.symbol]\n', '        val signalRank = notificationRank(signal)\n        val last = lastBySymbol[signal.symbol]\n')
replace_once(notif, '            levelOrd > last.level -> true\n', '            signalRank > last.level -> true\n')
replace_once(notif, '        lastBySymbol[signal.symbol] = Last(levelOrd, now)\n', '        lastBySymbol[signal.symbol] = Last(signalRank, now)\n')
replace_once(
    notif,
    '''        val emoji = when (signal.opportunityLabel) {
            "TOO_LATE", "STRONG_BUT_RISKY", "EXHAUSTION" -> "🔴"
            "CONFIRMED" -> "🟢"; "EARLY_CLEAN" -> "🟠"; else -> "🔵"
        }
''',
    '''        val emoji = when (signal.opportunityLabel) {
            "TOO_LATE", "STRONG_BUT_RISKY", "EXHAUSTION", "EXHAUSTION_RISK" -> "🔴"
            "RETEST_CONFIRMED" -> "🟣"
            "EARLY_HIGH_POTENTIAL" -> "🟢"
            "LONG_CONTINUATION", "CONFIRMED" -> "🟠"
            else -> "🔵"
        }
'''
)
replace_once(
    notif,
    '''            signal.return60s?.let { append(" · 1м %+.1f%%".format(it)) }
''',
    '''            signal.return60s?.let { append(" · 1м %+.1f%%".format(it)) }
            when (signal.opportunityLabel) {
                "EARLY_HIGH_POTENTIAL" -> append(" · потенциал 5–7%, фиксировать частями")
                "RETEST_CONFIRMED" -> append(" · восстановление после отката")
            }
'''
)
replace_once(
    notif,
    '''    private fun levelOrdinal(level: String): Int = when (level) {
''',
    '''    private fun notificationRank(signal: LiveSignal): Int = when (signal.opportunityLabel) {
        "RETEST_CONFIRMED" -> 100
        "EARLY_HIGH_POTENTIAL" -> 90
        "LONG_CONTINUATION" -> 80
        else -> levelOrdinal(signal.level)
    }

    private fun levelOrdinal(level: String): Int = when (level) {
'''
)

replace_once(
    "pumpradar/app/src/main/java/com/aidar/pumpradar/feature/coin/CoinDetailScreen.kt",
    '    "EARLY_CLEAN" -> "РАННИЙ ЧИСТЫЙ"\n',
    '    "EARLY_CLEAN" -> "РАННИЙ ЧИСТЫЙ"\n    "EARLY_HIGH_POTENTIAL" -> "РАННИЙ ИМПУЛЬС — ВЫСОКИЙ ПОТЕНЦИАЛ"\n    "LONG_CONTINUATION" -> "ПРОДОЛЖЕНИЕ ИМПУЛЬСА"\n'
)

write(
    "pumpradar/app/src/test/java/com/aidar/pumpradar/RetestDetectorTest.kt",
    r'''package com.aidar.pumpradar

import com.aidar.pumpradar.domain.analyzer.RetestDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetestDetectorTest {
    private val d = RetestDetector()

    private fun input(
        now: Long, price: Double, cvd: Double = 2000.0, cvdSlope: Double = 50.0,
        tbr: Double? = 0.70, spread: Double = 20.0, slip: Double = 0.20,
        conf: Int = 80, risk: Int = 20, impulse: Boolean = true,
        return15s: Double? = 0.30, tbr15: Double? = 0.68, cvd15: Double = 300.0
    ) = RetestDetector.Input(
        now, price, cvd, cvdSlope, tbr, spread, slip, conf, risk, impulse,
        return15s, tbr15, cvd15
    )

    @Test fun confirmsRecoveryBeforeOldHigh() {
        assertFalse(d.update("X", input(0, 100.0, cvd = 1000.0)))
        assertFalse(d.update("X", input(1000, 105.0, cvd = 2000.0)))
        assertFalse(d.update("X", input(2000, 102.5, cvd = 1800.0)))
        assertFalse(d.update("X", input(4000, 102.7, cvd = 1820.0)))
        assertTrue(d.update("X", input(8000, 103.5, cvd = 1900.0)))
        assertFalse(d.update("X", input(9000, 104.0, cvd = 2000.0)))
    }

    @Test fun recoveryNearOldHighIsTooLate() {
        d.update("Y", input(0, 100.0, cvd = 1000.0))
        d.update("Y", input(1000, 105.0, cvd = 2000.0))
        d.update("Y", input(2000, 102.5, cvd = 1800.0))
        assertFalse(d.update("Y", input(8000, 104.8, cvd = 2300.0)))
    }

    @Test fun tooDeepPullbackResets() {
        d.update("Z", input(0, 100.0, cvd = 1000.0))
        d.update("Z", input(1000, 105.0, cvd = 2000.0))
        d.update("Z", input(2000, 102.5, cvd = 1800.0))
        d.update("Z", input(3000, 96.0, cvd = 1500.0))
        assertFalse(d.update("Z", input(9000, 103.5, cvd = 2500.0)))
    }

    @Test fun weakShortTermBuysNotConfirmed() {
        d.update("W", input(0, 100.0, cvd = 1000.0))
        d.update("W", input(1000, 105.0, cvd = 2000.0))
        d.update("W", input(2000, 102.5, cvd = 1800.0))
        assertFalse(d.update("W", input(8000, 103.5, cvd = 1900.0, tbr15 = 0.50, cvd15 = -50.0)))
    }
}
'''
)
replace_once(
    "pumpradar/app/src/test/java/com/aidar/pumpradar/LongSignalDeciderTest.kt",
    '''        assertEquals(LongSignalDecider.LONG_CONTINUATION, d.label)
        assertEquals(3.0, d.maxTargetPercent, 1e-9)   // строгие условия → цель до 3%
''',
    '''        assertEquals(LongSignalDecider.EARLY_HIGH_POTENTIAL, d.label)
        assertEquals(7.0, d.maxTargetPercent, 1e-9)
'''
)

print("PumpRadar early/retest patch applied successfully.")
