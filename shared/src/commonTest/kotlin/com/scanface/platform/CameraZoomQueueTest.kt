package com.scanface.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CameraZoomQueueTest {
    @Test
    fun releasedGestureStillDrainsItsLatestTargetBeforeCaptureCanResume() {
        val queue = queue()
        val first = assertNotNull(queue.request(1.4f))
        assertNull(queue.request(2f))
        assertNull(queue.request(3.25f))
        assertEquals(first, queue.pending)
        assertEquals(1f, queue.confirmed)
        assertEquals(3.25f, queue.desired)

        // Finger release produces no more calls, but the final ratio must survive.
        val final = assertNotNull(queue.acknowledge(first))
        assertEquals(3.25f, final.ratio)
        assertEquals(1.4f, queue.confirmed)
        assertTrue(queue.unsettled)
        assertNull(queue.acknowledge(final))
        assertEquals(3.25f, queue.confirmed)
        assertFalse(queue.unsettled)
    }

    @Test
    fun reversingToOriginalRatioDoesNotRemoveTheInFlightCaptureBarrier() {
        val queue = queue()
        val outward = assertNotNull(queue.request(3f))
        assertNull(queue.request(1f))
        assertEquals(queue.confirmed, queue.desired)
        assertTrue(queue.unsettled)

        val reverse = assertNotNull(queue.acknowledge(outward))
        assertEquals(1f, reverse.ratio)
        assertEquals(3f, queue.confirmed)
        assertTrue(queue.unsettled)
        assertNull(queue.acknowledge(reverse))
        assertEquals(1f, queue.confirmed)
        assertFalse(queue.unsettled)
    }

    @Test
    fun oldLensCompletionCannotAcknowledgeNewLensWork() {
        val queue = queue()
        val previousLens = assertNotNull(queue.request(4f))
        queue.reset(min = 1f, max = 2f, confirmed = 1f)
        val newLens = assertNotNull(queue.request(1f, force = true))
        assertNotEquals(previousLens.token, newLens.token)
        assertNull(queue.acknowledge(previousLens))
        assertEquals(newLens, queue.pending)
        assertEquals(1f, queue.confirmed)
        assertTrue(queue.unsettled)

        assertNull(queue.acknowledge(newLens))
        assertFalse(queue.unsettled)
    }

    @Test
    fun cancellationDiscardsQueuedTargetsAndRejectsTheirLateCompletion() {
        val queue = queue()
        val cancelled = assertNotNull(queue.request(2f))
        queue.request(4f)
        queue.cancel()
        assertEquals(1f, queue.desired)
        assertNull(queue.pending)
        assertFalse(queue.unsettled)

        val next = assertNotNull(queue.request(1.5f))
        assertNull(queue.acknowledge(cancelled))
        assertEquals(next, queue.pending)
        assertEquals(1f, queue.confirmed)
        queue.acknowledge(next)
        assertEquals(1.5f, queue.confirmed)
    }

    @Test
    fun requestsRespectLensMinimumAndProductMaximum() {
        val queue = CameraZoomQueue()
        queue.reset(min = 1.5f, max = 4f, confirmed = 1f)
        assertEquals(1.5f, queue.confirmed)
        assertNull(queue.request(.5f))
        val capped = assertNotNull(queue.request(9f))
        assertEquals(4f, capped.ratio)
        queue.acknowledge(capped)
        val minimum = assertNotNull(queue.request(-1f))
        assertEquals(1.5f, minimum.ratio)
    }

    @Test
    fun invalidGestureScalesDoNotReplaceAValidPendingTarget() {
        val queue = queue()
        val pending = assertNotNull(queue.request(2f))
        queue.request(3f)
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach {
            assertNull(queue.request(it))
        }
        assertEquals(pending, queue.pending)
        assertEquals(3f, queue.desired)
        assertEquals(3f, assertNotNull(queue.acknowledge(pending)).ratio)
    }

    @Test
    fun forcedBaselineRequiresAcknowledgementEvenAtTheConfirmedRatio() {
        val queue = queue()
        assertNull(queue.request(1f))
        val baseline = assertNotNull(queue.request(1f, force = true))
        assertTrue(queue.unsettled)
        assertNull(queue.acknowledge(baseline))
        assertFalse(queue.unsettled)
    }

    @Test
    fun tinyGestureNoiseDoesNotDispatchAnotherHardwareRequest() {
        val queue = queue()
        assertNull(queue.request(1.0005f))
        assertEquals(1f, queue.desired)
        val request = assertNotNull(queue.request(2f))
        queue.request(2.0005f)
        assertNull(queue.acknowledge(request))
        assertEquals(2f, queue.confirmed)
        assertEquals(2f, queue.desired)
        assertFalse(queue.unsettled)
    }

    private fun queue() = CameraZoomQueue().apply {
        reset(min = 1f, max = 4f, confirmed = 1f)
    }
}
