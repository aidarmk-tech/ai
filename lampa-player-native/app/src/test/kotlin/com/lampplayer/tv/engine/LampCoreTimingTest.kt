package com.lampplayer.tv.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class LampCoreTimingTest {
    @Test fun audioClockSurvivesUnsignedFrameCounterAndWrap() {
        val head = AudioFrameCounter()
        assertEquals(2_147_483_648L, head.update(Int.MIN_VALUE))
        assertEquals(4_294_967_294L, head.update(-2))
        assertEquals(4_294_967_298L, head.update(2))
    }

    @Test fun earlyFramesWaitAndLateFramesAreDiscarded() {
        assertEquals(1, LampCoreTiming.frameAction(1_200_000, 1_000_000))
        assertEquals(0, LampCoreTiming.frameAction(1_010_000, 1_000_000))
        assertEquals(-1, LampCoreTiming.frameAction(899_999, 1_000_000))
    }

    @Test fun audioStallDoesNotAdvanceVideo() {
        val audioHeadUs = 500_000L
        repeat(10) { assertEquals(1, LampCoreTiming.frameAction(700_000, audioHeadUs)) }
        assertEquals(0, LampCoreTiming.frameAction(700_000, 700_000))
    }
}
