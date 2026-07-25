package com.aidar.pumpradar.domain.analyzer

/**
 * Офлайн-оценка плана сопровождения LONG до движения +3%.
 *
 * План:
 * 1) первоначальный защитный стоп −0.75%;
 * 2) после достижения +1% фиксируется 20% условной позиции;
 * 3) стоп остатка переносится на +0.15%;
 * 4) оставшиеся 80% сопровождаются до +3% либо до защитного выхода.
 *
 * Комиссии и проскальзывание здесь не вычитаются: они рассчитываются отдельно,
 * поскольку зависят от тарифа и фактического исполнения. Ордера не отправляются.
 */
object ThreePercentPlanEvaluator {

    enum class Result {
        TARGET_3,
        PROTECTED_EXIT,
        STOP_BEFORE_PROTECTION,
        HORIZON_EXIT,
        DATA_INCOMPLETE
    }

    data class Config(
        val protectionActivationPercent: Double = 1.0,
        val finalTargetPercent: Double = 3.0,
        val initialStopPercent: Double = -0.75,
        val protectedStopPercent: Double = 0.15,
        val partialFraction: Double = 0.20
    )

    data class Evaluation(
        val result: Result,
        val activationTimeMs: Long? = null,
        val targetTimeMs: Long? = null,
        val exitTimeMs: Long? = null,
        val grossReturnPercent: Double? = null
    )

    fun evaluate(
        samples: List<Pair<Long, Double>>,
        cfg: Config = Config()
    ): Evaluation {
        if (samples.isEmpty()) return Evaluation(Result.DATA_INCOMPLETE)
        require(cfg.partialFraction in 0.0..1.0)

        val ordered = samples.sortedBy { it.first }
        var activatedAt: Long? = null

        for ((time, returnPct) in ordered) {
            if (activatedAt == null) {
                if (returnPct <= cfg.initialStopPercent) {
                    return Evaluation(
                        result = Result.STOP_BEFORE_PROTECTION,
                        exitTimeMs = time,
                        grossReturnPercent = cfg.initialStopPercent
                    )
                }
                if (returnPct >= cfg.protectionActivationPercent) {
                    activatedAt = time
                    if (returnPct >= cfg.finalTargetPercent) {
                        return target(time, activatedAt, cfg)
                    }
                }
            } else {
                if (returnPct >= cfg.finalTargetPercent) {
                    return target(time, activatedAt, cfg)
                }
                if (returnPct <= cfg.protectedStopPercent) {
                    val gross = blended(
                        cfg.protectionActivationPercent,
                        cfg.protectedStopPercent,
                        cfg.partialFraction
                    )
                    return Evaluation(
                        result = Result.PROTECTED_EXIT,
                        activationTimeMs = activatedAt,
                        exitTimeMs = time,
                        grossReturnPercent = gross
                    )
                }
            }
        }

        val last = ordered.last()
        val gross = if (activatedAt == null) {
            last.second
        } else {
            blended(cfg.protectionActivationPercent, last.second, cfg.partialFraction)
        }
        return Evaluation(
            result = Result.HORIZON_EXIT,
            activationTimeMs = activatedAt,
            exitTimeMs = last.first,
            grossReturnPercent = gross
        )
    }

    private fun target(time: Long, activatedAt: Long, cfg: Config): Evaluation =
        Evaluation(
            result = Result.TARGET_3,
            activationTimeMs = activatedAt,
            targetTimeMs = time,
            exitTimeMs = time,
            grossReturnPercent = blended(
                cfg.protectionActivationPercent,
                cfg.finalTargetPercent,
                cfg.partialFraction
            )
        )

    private fun blended(partialReturn: Double, remainderReturn: Double, partialFraction: Double): Double =
        partialFraction * partialReturn + (1.0 - partialFraction) * remainderReturn
}
