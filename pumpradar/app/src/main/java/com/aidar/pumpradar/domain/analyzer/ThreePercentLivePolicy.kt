package com.aidar.pumpradar.domain.analyzer

/** Чистая политика подтверждения ослабления после активации защиты на +1%. */
object ThreePercentLivePolicy {

    data class Input(
        val currentReturnPercent: Double,
        val maxReturnPercent: Double,
        val takerBuyRatio5s: Double?,
        val takerBuyRatio15s: Double?,
        val takerBuyRatio30s: Double?,
        val cvdSlope: Double?
    )

    data class Assessment(
        val flowWeak: Boolean,
        val drawdownFromPeakPercent: Double,
        val reasons: List<String>
    )

    fun assess(i: Input, cfg: SignalConfig = SignalConfig()): Assessment {
        val reasons = ArrayList<String>()
        val t5Weak = i.takerBuyRatio5s?.let { it < cfg.minHoldTakerBuyRatio5s } == true
        val t15Weak = i.takerBuyRatio15s?.let { it < cfg.minHoldTakerBuyRatio15s } == true
        val t30Weak = i.takerBuyRatio30s?.let { it < cfg.minHoldTakerBuyRatio30s } == true
        val cvdWeak = i.cvdSlope?.let { it <= 0.0 } == true
        if (t5Weak) reasons.add("агрессивные покупки 5с ослабли")
        if (t15Weak) reasons.add("агрессивные покупки 15с ослабли")
        if (t30Weak) reasons.add("агрессивные покупки 30с ослабли")
        if (cvdWeak) reasons.add("наклон CVD стал неположительным")
        val drawdown = (i.maxReturnPercent - i.currentReturnPercent).coerceAtLeast(0.0)
        if (drawdown >= MIN_CONFIRM_DRAWDOWN) reasons.add("откат от максимума %.2f%%".format(drawdown))
        return Assessment(
            flowWeak = (t5Weak || t15Weak || t30Weak || cvdWeak) &&
                drawdown >= MIN_CONFIRM_DRAWDOWN,
            drawdownFromPeakPercent = drawdown,
            reasons = reasons
        )
    }

    private const val MIN_CONFIRM_DRAWDOWN = 0.35
}
