package cn.anitabi.navigator.ui.discovery.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryCameraSequenceTest {
    @Test fun dependentProjectionAndPanWaitForMatchingFirstFinish() {
        val sequence = DiscoveryCameraSequence()
        val events = mutableListOf<String>()
        var centered = false
        var panned = false
        sequence.start(listOf(
            DiscoveryCameraSequence.Step({ centered }, { events += "center" }),
            DiscoveryCameraSequence.Step({ events += "project"; panned }, { events += "pan" }),
        )) { events += "acknowledge" }

        assertEquals(listOf("center"), events)
        assertTrue(sequence.isPending)
        repeat(2) { sequence.onCameraFinish() }
        assertEquals("Unrelated finishes must not inspect the dependent projection", listOf("center"), events)

        centered = true
        sequence.onCameraFinish()
        assertEquals(listOf("center", "project", "pan"), events)
        assertTrue(sequence.isPending)
        panned = true
        sequence.onCameraFinish()
        assertEquals(listOf("center", "project", "pan", "project", "acknowledge"), events)
        assertFalse(sequence.isPending)
        sequence.onCameraFinish()
        assertEquals(1, events.count { it == "acknowledge" })
    }

    @Test fun completedNoOpStepsSkipNativeUpdatesAndCompleteExactlyOnce() {
        val sequence = DiscoveryCameraSequence()
        var applied = 0
        var completed = 0
        sequence.start(List(2) { DiscoveryCameraSequence.Step({ true }, { applied++ }) }) {
            assertFalse("Completion must observe an already-cleared request", sequence.isPending)
            completed++
        }
        repeat(3) { sequence.onCameraFinish() }
        assertEquals(0, applied)
        assertEquals(1, completed)
        assertFalse(sequence.isPending)
    }

    @Test fun nonSkippableStepStillRequiresApplyAndNativeFinishWhenAlreadyAtTarget() {
        val sequence = DiscoveryCameraSequence()
        val events = mutableListOf<String>()
        sequence.start(listOf(DiscoveryCameraSequence.Step({ true }, { events += "apply" }, canSkip = false))) {
            events += "acknowledge"
        }
        assertEquals(listOf("apply"), events)
        assertTrue(sequence.isPending)
        sequence.onCameraFinish()
        sequence.onCameraFinish()
        assertEquals(listOf("apply", "acknowledge"), events)
        assertFalse(sequence.isPending)
    }

    @Test fun newRequestReplacesOldContinuationAndIgnoresItsLateFinish() {
        val sequence = DiscoveryCameraSequence()
        val events = mutableListOf<String>()
        var oldFinished = false
        var newFinished = false
        sequence.start(listOf(
            DiscoveryCameraSequence.Step({ oldFinished }, { events += "old-center" }),
            DiscoveryCameraSequence.Step({ events += "old-project"; false }, { events += "old-pan" }),
        )) { events += "old-acknowledge" }
        sequence.start(listOf(DiscoveryCameraSequence.Step({ newFinished }, { events += "new-center" }))) {
            events += "new-acknowledge"
        }

        oldFinished = true
        sequence.onCameraFinish()
        assertEquals(listOf("old-center", "new-center"), events)
        assertTrue(sequence.isPending)
        newFinished = true
        sequence.onCameraFinish()
        sequence.onCameraFinish()
        assertEquals(listOf("old-center", "new-center", "new-acknowledge"), events)
        assertFalse(sequence.isPending)
    }

    @Test fun cancelBeforeFirstFinishPreventsProjectionPanAndAcknowledgement() {
        val sequence = DiscoveryCameraSequence()
        val events = mutableListOf<String>()
        var centered = false
        sequence.start(listOf(
            DiscoveryCameraSequence.Step({ centered }, { events += "center" }),
            DiscoveryCameraSequence.Step({ events += "project"; false }, { events += "pan" }),
        )) { events += "acknowledge" }

        sequence.cancel()
        sequence.cancel()
        centered = true
        sequence.onCameraFinish()
        assertEquals(listOf("center"), events)
        assertFalse(sequence.isPending)
    }

    @Test fun cancelDuringPanPreventsAcknowledgementAndAllowsNextRequest() {
        val sequence = DiscoveryCameraSequence()
        val events = mutableListOf<String>()
        var centered = false
        var panned = false
        sequence.start(listOf(
            DiscoveryCameraSequence.Step({ centered }, { events += "center" }),
            DiscoveryCameraSequence.Step({ panned }, { events += "pan" }),
        )) { events += "cancelled-acknowledge" }
        centered = true
        sequence.onCameraFinish()
        assertEquals(listOf("center", "pan"), events)

        sequence.cancel()
        panned = true
        sequence.onCameraFinish()
        assertFalse(sequence.isPending)
        sequence.start(emptyList()) { events += "new-acknowledge" }
        assertEquals(listOf("center", "pan", "new-acknowledge"), events)
    }

    @Test fun synchronousFinishInsideApplyAdvancesEachStepAndCompletesExactlyOnce() {
        val sequence = DiscoveryCameraSequence()
        val events = mutableListOf<String>()
        var centered = false
        var panned = false
        sequence.start(listOf(
            DiscoveryCameraSequence.Step({ centered }, {
                events += "center"
                centered = true
                sequence.onCameraFinish()
            }),
            DiscoveryCameraSequence.Step({ panned }, {
                events += "pan"
                panned = true
                sequence.onCameraFinish()
            }),
        )) { events += "acknowledge" }

        assertEquals(listOf("center", "pan", "acknowledge"), events)
        assertFalse(sequence.isPending)
        repeat(2) { sequence.onCameraFinish() }
        assertEquals(listOf("center", "pan", "acknowledge"), events)
    }

    @Test fun applyFailureClearsRequestAndPropagatesTheSameException() {
        for (failingStep in 0..1) {
            val sequence = DiscoveryCameraSequence()
            val failure = IllegalStateException("Synthetic camera update failure")
            var firstFinished = false
            var completed = 0
            val steps = listOf(
                DiscoveryCameraSequence.Step({ firstFinished }, { if (failingStep == 0) throw failure }),
                DiscoveryCameraSequence.Step({ false }, { throw failure }),
            )
            val thrown = assertThrows(IllegalStateException::class.java) {
                sequence.start(steps) { completed++ }
                firstFinished = true
                sequence.onCameraFinish()
            }
            assertSame(failure, thrown)
            assertFalse(sequence.isPending)
            sequence.onCameraFinish()
            assertEquals(0, completed)
            sequence.start(emptyList()) { completed++ }
            assertEquals(1, completed)
        }
    }
}
