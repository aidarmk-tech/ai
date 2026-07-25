package com.aidar.pumpradar.service

import com.aidar.pumpradar.domain.analyzer.LongSignalDecider
import com.aidar.pumpradar.domain.analyzer.SignalConfig
import com.aidar.pumpradar.domain.model.Candidate
import com.aidar.pumpradar.domain.model.LiveSignal
import com.aidar.pumpradar.notification.PlanNotificationManager
import com.aidar.pumpradar.notification.PlanNotificationManager.State
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Один экспериментальный paper-слот TRADE_3.
 *
 * Класс не отправляет ордера и не хранит API-ключи. Он выбирает самый сильный
 * текущий LONG_CONTINUATION, фиксирует условную цену входа и сопровождает только
 * один сценарий до цели, защиты, ослабления или 15-минутного горизонта.
 */
@Singleton
class ExperimentalTrade3Observer @Inject constructor(
    private val controller: MonitoringController,
    private val notifier: PlanNotificationManager,
    private val cfg: SignalConfig
) {
    private class Slot(
        val symbol: String,
        val entryPrice: Double,
        val startedAt: Long,
        var maxReturnPercent: Double = 0.0,
        var protected: Boolean = false,
        var weakeningTicks: Int = 0,
        var lastCvd30s: Double? = null
    )

    private var slot: Slot? = null
    private var job: Job? = null
    private val reopenBlockedUntil = HashMap<String, Long>()

    fun start(scope: CoroutineScope) {
        stop()
        job = combine(
            controller.liveSignals,
            controller.candidates,
            controller.paused
        ) { signals, candidates, paused -> Triple(signals, candidates, paused) }
            .onEach { (signals, candidates, paused) ->
                if (!paused) update(signals, candidates, System.currentTimeMillis())
            }
            .launchIn(scope)
    }

    fun stop() {
        job?.cancel()
        job = null
        slot = null
        reopenBlockedUntil.clear()
    }

    private fun update(signals: List<LiveSignal>, candidates: List<Candidate>, now: Long) {
        reopenBlockedUntil.entries.removeAll { it.value <= now }
        val active = slot
        if (active == null) {
            val best = signals
                .asSequence()
                .filter { it.opportunityLabel == LongSignalDecider.LONG_CONTINUATION }
                .filter { it.price > 0.0 }
                .filter { (reopenBlockedUntil[it.symbol] ?: 0L) <= now }
                .maxWithOrNull(compareBy<LiveSignal> { it.score }.thenBy { it.confidenceScore })
                ?: return
            slot = Slot(
                symbol = best.symbol,
                entryPrice = best.price,
                startedAt = now,
                lastCvd30s = best.cvd30s
            )
            notifier.notify(
                best.symbol,
                State.SLOT_OPEN,
                0.0,
                listOf(
                    "Impulse ${best.score}/100, confidence ${best.confidenceScore}/100",
                    "одновременно разрешён только один paper-слот",
                    "реальная сделка не открыта"
                )
            )
            return
        }

        val live = signals.firstOrNull { it.symbol == active.symbol }
        val price = candidates.firstOrNull { it.symbol == active.symbol }?.price
            ?: live?.price
            ?: return
        if (price <= 0.0) return

        val returnPercent = (price / active.entryPrice - 1.0) * 100.0
        if (returnPercent > active.maxReturnPercent) active.maxReturnPercent = returnPercent

        if (returnPercent >= cfg.target3Percent) {
            close(active, now, State.TARGET_3_REACHED, returnPercent)
            return
        }

        if (!active.protected) {
            if (returnPercent <= -cfg.initialStopPercent) {
                close(active, now, State.INITIAL_STOP, returnPercent)
                return
            }
            if (returnPercent >= cfg.protectionActivationPercent) {
                active.protected = true
                active.weakeningTicks = 0
                notifier.notify(active.symbol, State.PROTECT_AFTER_1, returnPercent)
            }
        } else {
            if (returnPercent <= cfg.protectedStopPercent) {
                close(active, now, State.PROTECTED_EXIT, returnPercent)
                return
            }

            val drawdown = (active.maxReturnPercent - returnPercent).coerceAtLeast(0.0)
            val takerWeak = live?.takerBuyRatio30s?.let { it < cfg.minHoldTakerBuyRatio30s } == true
            val cvdWeak = live?.let { current ->
                active.lastCvd30s?.let { previous -> current.cvd30s < previous } == true
            } == true
            active.lastCvd30s = live?.cvd30s ?: active.lastCvd30s

            val weakening = drawdown >= MIN_WEAKENING_DRAWDOWN_PERCENT && (takerWeak || cvdWeak)
            active.weakeningTicks = if (weakening) active.weakeningTicks + 1 else 0
            if (active.weakeningTicks >= cfg.weakeningConfirmTicks) {
                val reasons = buildList {
                    add("откат от максимума %.2f%%".format(drawdown))
                    if (takerWeak) add("taker buy ratio 30с ниже %.0f%%".format(cfg.minHoldTakerBuyRatio30s * 100))
                    if (cvdWeak) add("CVD 30с снижается")
                }
                close(active, now, State.EXIT_WEAKENING, returnPercent, reasons)
                return
            }
        }

        if (now - active.startedAt >= cfg.trade3SlotMinutes * 60_000L) {
            close(active, now, State.HORIZON_EXIT, returnPercent)
        }
    }

    private fun close(
        active: Slot,
        now: Long,
        state: State,
        returnPercent: Double,
        reasons: List<String> = emptyList()
    ) {
        notifier.notify(active.symbol, state, returnPercent, reasons)
        reopenBlockedUntil[active.symbol] = now + REOPEN_BLOCK_MS
        slot = null
    }

    private companion object {
        const val MIN_WEAKENING_DRAWDOWN_PERCENT = 0.35
        const val REOPEN_BLOCK_MS = 30 * 60_000L
    }
}
