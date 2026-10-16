package cn.anitabi.navigator.data.discovery

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class DiscoveryTraceSectionsTest {
    @Test fun abandonedInlineWorkClosesAsCancelledInsteadOfSuccessfulOrPending() {
        var clock = 0L
        val trace = DiscoveryLoadTrace(DiscoveryLoadTraceConfig(true), { clock++ })
        fun abandon(): Boolean {
            trace.measure(DiscoveryLoadPhase.MARKER_UPDATE) { return false }
        }
        assertFalse(abandon())
        assertEquals(DiscoveryLoadOutcome.CANCELLED, trace.snapshot().events.single().outcome)
        assertTrue(trace.snapshot().pendingSpans.isEmpty())
    }

    @Test fun emptyWorkAndThrownFailureDoNotBecomeSuccessfulTimingSamples() {
        var clock = 0L
        val trace = DiscoveryLoadTrace(DiscoveryLoadTraceConfig(true), { clock++ })
        trace.measure(DiscoveryLoadPhase.NEARBY_SORT, 0) { emptyList<String>() }
        try { trace.measure(DiscoveryLoadPhase.CONVERT) { throw IllegalStateException("Synthetic failure") } }
        catch (_: IllegalStateException) { }
        try { trace.measure(DiscoveryLoadPhase.INDEX_BUILD) { throw CancellationException() } }
        catch (_: CancellationException) { }
        assertEquals(listOf(DiscoveryLoadOutcome.EMPTY, DiscoveryLoadOutcome.FAILED, DiscoveryLoadOutcome.CANCELLED),
            trace.snapshot().events.map { it.outcome })
        assertTrue(trace.snapshot().pendingSpans.isEmpty())
    }
}
