package com.aidar.pumpradar

import com.aidar.pumpradar.domain.analyzer.BuyerPressure
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Тренд давления покупателей по окнам 5/15/30с (item 4). */
class BuyerPressureTest {

    @Test fun fadingRatioIsDeclining() {
        // 5с < 15с < 30с → давление слабеет.
        assertTrue(BuyerPressure.declining(0.55, 0.65, 0.80, 100.0, 300.0, 800.0))
    }

    @Test fun steadyRatioNotDeclining() {
        assertFalse(BuyerPressure.declining(0.80, 0.80, 0.80, 500.0, 700.0, 900.0))
    }

    @Test fun risingRatioNotDeclining() {
        assertFalse(BuyerPressure.declining(0.85, 0.75, 0.65, 400.0, 300.0, 200.0))
    }

    @Test fun cvdTurnNegativeWhileTbrFadesIsDeclining() {
        // tbr не монотонен (tbrFading=false), но tbr5<tbr30 и краткосрочный CVD ушёл
        // в минус при положительном 30с → срабатывает CVD-ветка.
        assertTrue(BuyerPressure.declining(0.70, 0.68, 0.72, -50.0, 100.0, 500.0))
    }

    @Test fun nullWindowsNotDeclining() {
        assertFalse(BuyerPressure.declining(null, 0.7, 0.8, 0.0, 0.0, 0.0))
    }
}
