package com.lampplayer.tv.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LampCoreTimingTest {
    @Test fun audioClockSurvivesUnsignedFrameCounterAndWrap() {
        val head = AudioFrameCounter()
        assertEquals(2_147_483_648L, head.update(Int.MIN_VALUE))
        assertEquals(4_294_967_294L, head.update(-2))
        assertEquals(4_294_967_298L, head.update(2))
    }

    @Test fun earlyFramesWaitAndLateFramesAreDiscarded() {
        val timing = LampCoreVideoTiming(25f)
        assertEquals(1, timing.plan(1_200_000, 1_000_000, 0, 1f).action)
        assertEquals(10_000_000L, timing.plan(1_010_000, 1_000_000, 0, 1f).timeNs)
        assertEquals(-1, timing.plan(899_999, 1_000_000, 0, 1f).action)
    }

    @Test fun audioStallDoesNotAdvanceVideo() {
        val audioHeadUs = 500_000L
        val timing = LampCoreVideoTiming(25f)
        repeat(10) { assertEquals(1, timing.plan(700_000, audioHeadUs, it * 20_000_000L, 1f).action) }
        assertEquals(0, timing.plan(700_000, 700_000, 200_000_000, 1f).action)
    }

    @Test fun sixtyMillisecondStallDropsBacklogInsteadOfRenderingItEveryTwoMilliseconds() {
        val timing = LampCoreVideoTiming(25f)
        timing.rendered(0, 0)
        // Previously all three frames were inside the 100ms window and rendered immediately.
        assertEquals(-1, timing.plan(40_000, 100_000, 100_000_000, 1f).action)
        val current = timing.plan(80_000, 102_000, 102_000_000, 1f)
        assertEquals(0, current.action)
        timing.rendered(80_000, current.timeNs)
        assertEquals(1, timing.plan(120_000, 104_000, 104_000_000, 1f).action)
        val next = timing.plan(120_000, 126_000, 126_000_000, 1f)
        assertEquals(0, next.action)
        assertTrue(next.timeNs - current.timeNs >= 36_000_000)
    }

    @Test fun longStallAndCoarseAudioHeadKeepRenderCadenceAtRequestedRates() {
        for (rate in listOf(0.5f, 1f, 2f)) {
            val timing = LampCoreVideoTiming(25f)
            val times = mutableListOf<Long>()
            var pts = 0L
            var drops = 0
            // Audio continues during a 300ms video starvation; its head updates every 20ms.
            for (nowMs in 0L..2_000L step 2) {
                if (nowMs in 400L..698L) continue
                val clock = ((nowMs / 20 * 20) * 1000 * rate).toLong()
                val release = timing.plan(pts, clock, nowMs * 1_000_000, rate)
                if (release.action < 0) { drops++; pts += 40_000 }
                else if (release.action == 0) {
                    times += release.timeNs
                    timing.rendered(pts, release.timeNs)
                    pts += 40_000
                }
            }
            assertTrue("stale frames must be discarded at $rate", drops > 0)
            assertTrue("playback must resume at $rate", times.size > 10)
            times.zipWithNext().forEach { (a, b) ->
                assertTrue("burst at rate $rate: ${b - a}", b - a >= (36_000_000 / rate).toLong())
            }
        }
    }

    @Test fun scheduledSurfaceTimeUsesSystemClockAndUserSpeed() {
        val now = 90_000_000_000L
        for (rate in listOf(0.5f, 1f, 2f)) {
            val result = LampCoreVideoTiming(25f).plan(5_010_000, 5_000_000, now, rate)
            if (rate == 0.5f) assertEquals(1, result.action)
            else {
                assertEquals(0, result.action)
                assertEquals(now + (10_000_000 / rate).toLong(), result.timeNs)
            }
        }
    }

    @Test fun droppedFramesDoNotCreateAnExtraLongCadenceDelay() {
        val timing = LampCoreVideoTiming(25f)
        timing.rendered(0, 0)
        for (pts in 40_000L..320_000L step 40_000) {
            assertEquals(-1, timing.plan(pts, 400_000, 400_000_000, 1f).action)
        }
        assertEquals(0, timing.plan(400_000, 400_000, 400_000_000, 1f).action)
    }

    @Test fun duplicateOrBackwardFramesCannotBeRenderedAgain() {
        val timing = LampCoreVideoTiming(25f)
        timing.rendered(500_000, 1_000_000_000)
        assertEquals(-1, timing.plan(500_000, 500_000, 1_010_000_000, 1f).action)
        assertEquals(-1, timing.plan(480_000, 500_000, 1_010_000_000, 1f).action)
    }

    @Test fun audioWaitsForRefillAndScalesTheReserveAtDoubleSpeed() {
        assertFalse(LampCoreTiming.audioReady(1_024, 48_000, 12_000, 1f, false))
        assertTrue(LampCoreTiming.audioReady(5_760, 48_000, 12_000, 1f, false))
        assertFalse(LampCoreTiming.audioReady(5_760, 48_000, 12_000, 2f, false))
        assertTrue(LampCoreTiming.audioReady(9_000, 48_000, 12_000, 2f, false))
    }

    @Test fun smallAudioBuffersAndShortClipsCannotDeadlockAtRefill() {
        assertTrue(LampCoreTiming.audioReady(768, 48_000, 1_024, 2f, false))
        assertTrue(LampCoreTiming.audioReady(512, 48_000, 12_000, 1f, true))
        assertTrue(LampCoreTiming.audioReady(0, 48_000, 12_000, 1f, true))
    }
}
