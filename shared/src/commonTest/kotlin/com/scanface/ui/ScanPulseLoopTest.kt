package com.scanface.ui

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ScanPulseLoopTest {
    @Test
    fun feedbackIsSpacedAndStopsWhenVisibleWorkIsCancelled() = runTest {
        val pulses = mutableListOf<Long>()
        val feedback = launch { scanPulseLoop { pulses += testScheduler.currentTime } }
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(pulses.size in 2..4, "Scanning uses gentle spaced cues, not continuous vibration.")
        assertTrue(pulses.zipWithNext().all { (first, next) -> next - first >= 600 })
        feedback.cancelAndJoin()
        val count = pulses.size
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(count, pulses.size, "Completion, backgrounding and disposal cancel outstanding cues.")
    }

    @Test
    fun quickWorkDoesNotStackScanPulsesOnTheCaptureFeedback() = runTest {
        var pulses = 0
        val feedback = launch { scanPulseLoop { pulses++ } }
        advanceTimeBy(100)
        feedback.cancelAndJoin()
        advanceTimeBy(2_000)
        assertEquals(0, pulses)
    }
}
