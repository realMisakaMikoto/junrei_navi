package cn.anitabi.navigator.data.discovery

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryLoadTraceTest {
    @Test fun `default recorder reads no clock and invokes no listener`() {
        val trace = DiscoveryLoadTrace(nanoTime = { error("Clock must stay unused") }, listener = { error("Listener must stay unused") })
        assertNull(trace.begin(DiscoveryLoadPhase.INDEX_PARSE))
        trace.mark(DiscoveryLoadPhase.SDK_READY)
        trace.request(DiscoveryLoadEndpoint.IMAGE, DiscoveryLoadOutcome.OBSERVED, receivedBytes = 12)
        trace.increment(DiscoveryLoadCounter.INDEX_REBUILD_COUNT)
        trace.maximum(DiscoveryLoadCounter.JAVA_HEAP_PEAK_BYTES, 128)
        val state = trace.snapshot()
        assertFalse(state.enabled)
        assertNull(state.originNanos)
        assertNull(state.elapsedNanos)
        assertTrue(state.events.isEmpty())
        assertTrue(state.counters.isEmpty())
        assertTrue(state.requests.all { it.completedCount == null && it.receivedBytes == null })
        assertTrue(trace.toJson().contains("\"enabled\":false"))
    }

    @Test fun `overlapping spans keep actual monotonic durations and can only finish once in their owner`() {
        var now = 100L
        val trace = enabled { now }
        val parse = trace.begin(DiscoveryLoadPhase.INDEX_PARSE)
        now = 125
        val convert = trace.begin(DiscoveryLoadPhase.CONVERT)
        assertFalse(enabled { now }.end(parse))
        now = 150
        assertTrue(trace.end(convert))
        now = 180
        assertTrue(trace.end(parse))
        assertFalse(trace.end(parse))
        val state = trace.snapshot()
        assertEquals(listOf(25L, 80L), state.events.map { it.durationNanos })
        assertEquals(listOf(50L, 80L), state.events.map { it.offsetNanos })
        assertTrue(state.pendingSpans.isEmpty())
    }

    @Test fun `unavailable empty failed and pending stages cannot look like successful zero durations`() {
        var now = 0L
        val trace = enabled { now }
        trace.mark(DiscoveryLoadPhase.BASEMAP_RENDER_OBSERVED, DiscoveryLoadOutcome.NOT_OBSERVED)
        trace.mark(DiscoveryLoadPhase.SDK_NETWORK, DiscoveryLoadOutcome.UNSUPPORTED)
        trace.mark(DiscoveryLoadPhase.FIRST_VIEWPORT_POINTS_DRAWN, DiscoveryLoadOutcome.EMPTY, itemCount = 0)
        val fetch = trace.begin(DiscoveryLoadPhase.INDEX_FETCH)
        now = 500
        trace.end(fetch, DiscoveryLoadOutcome.FAILED, DiscoveryLoadError.TIMEOUT)
        trace.begin(DiscoveryLoadPhase.IMAGE_DECODE)
        val state = trace.snapshot()
        assertEquals(listOf(null, null, null, 500L), state.events.map { it.durationNanos })
        assertTrue(state.events.none { it.outcome == DiscoveryLoadOutcome.OBSERVED })
        assertEquals(DiscoveryLoadError.TIMEOUT, state.events.last().error)
        assertEquals(listOf(DiscoveryLoadPendingSpan(DiscoveryLoadPhase.IMAGE_DECODE, 500)), state.pendingSpans)
    }

    @Test fun `bounded recorder exposes dropped events and spans and releases completed slots`() {
        val trace = DiscoveryLoadTrace(DiscoveryLoadTraceConfig(enabled = true, maxEvents = 2, maxSpans = 1), nanoTime = { 0 })
        val open = trace.begin(DiscoveryLoadPhase.CLASSIFY)
        assertNull(trace.begin(DiscoveryLoadPhase.CONVERT))
        repeat(5) { trace.mark(DiscoveryLoadPhase.SHELL_DRAWN) }
        trace.end(open, DiscoveryLoadOutcome.CANCELLED)
        assertTrue(trace.begin(DiscoveryLoadPhase.CONVERT) != null)
        val state = trace.snapshot()
        assertEquals(2, state.events.size)
        assertEquals(listOf(5L, 6L), state.events.map { it.sequence })
        assertEquals(4L, state.droppedEvents)
        assertEquals(1L, state.droppedSpans)
        assertEquals(1, state.pendingSpans.size)
    }

    @Test fun `request counters separate successful transfers cache hits failed unknown bytes and cancellation`() {
        val trace = enabled { 0 }
        trace.request(DiscoveryLoadEndpoint.IMAGE, DiscoveryLoadOutcome.OBSERVED, 120, DiscoveryLoadCache.NETWORK)
        trace.request(DiscoveryLoadEndpoint.IMAGE, DiscoveryLoadOutcome.OBSERVED, 0, DiscoveryLoadCache.DISK)
        trace.request(DiscoveryLoadEndpoint.IMAGE, DiscoveryLoadOutcome.FAILED, error = DiscoveryLoadError.TLS)
        trace.request(DiscoveryLoadEndpoint.IMAGE, DiscoveryLoadOutcome.CANCELLED)
        val state = trace.snapshot()
        val images = state.requests.single { it.endpoint == DiscoveryLoadEndpoint.IMAGE }
        assertEquals(4L, images.completedCount)
        assertEquals(120L, images.receivedBytes)
        assertEquals(2L, images.knownByteCount)
        assertEquals(2L, images.unknownByteCount)
        assertEquals(2L, images.outcomes[DiscoveryLoadOutcome.OBSERVED])
        assertEquals(1L, images.caches[DiscoveryLoadCache.DISK])
        assertEquals(mapOf(DiscoveryLoadError.TLS to 1L), images.errors)
        assertNull(state.requests.single { it.endpoint == DiscoveryLoadEndpoint.SDK_UNOBSERVED }.completedCount)
        assertNull(state.requests.single { it.endpoint == DiscoveryLoadEndpoint.STATIC_INDEX }.receivedBytes)
        assertThrows(IllegalArgumentException::class.java) {
            trace.request(DiscoveryLoadEndpoint.SDK_UNOBSERVED, DiscoveryLoadOutcome.OBSERVED)
        }
    }

    @Test fun `concurrent work counters are lossless while snapshots remain detached`() {
        val trace = enabled { 0 }
        val executor = Executors.newFixedThreadPool(4)
        try {
            val work = (1..4).map { Callable {
                repeat(1_000) { trace.increment(DiscoveryLoadCounter.DISTANCE_COMPUTATION_COUNT) }
                trace.request(DiscoveryLoadEndpoint.STATIC_PAGE, DiscoveryLoadOutcome.OBSERVED, 64)
            } }
            executor.invokeAll(work).forEach { it.get(5, TimeUnit.SECONDS) }
        } finally { executor.shutdownNow() }
        val before = trace.snapshot()
        trace.increment(DiscoveryLoadCounter.DISTANCE_COMPUTATION_COUNT)
        trace.maximum(DiscoveryLoadCounter.JAVA_HEAP_PEAK_BYTES, 1024)
        trace.maximum(DiscoveryLoadCounter.JAVA_HEAP_PEAK_BYTES, 512)
        assertEquals(4_000L, before.counters[DiscoveryLoadCounter.DISTANCE_COMPUTATION_COUNT])
        assertEquals(4_001L, trace.snapshot().counters[DiscoveryLoadCounter.DISTANCE_COMPUTATION_COUNT])
        assertEquals(1024L, trace.snapshot().counters[DiscoveryLoadCounter.JAVA_HEAP_PEAK_BYTES])
        assertEquals(4L, before.requests.single { it.endpoint == DiscoveryLoadEndpoint.STATIC_PAGE }.completedCount)
    }

    @Test fun `scalar JSON has only defined labels and a throwing listener cannot leak its message`() {
        val trace = DiscoveryLoadTrace(DiscoveryLoadTraceConfig(enabled = true), nanoTime = { 0 }, listener = { error("TEST_ONLY_PRIVATE_EXCEPTION") })
        trace.mark(DiscoveryLoadPhase.DISCOVERY_ENTER)
        trace.increment(DiscoveryLoadCounter.CACHE_WRITE_BYTES, 256)
        val encoded = trace.toJson()
        val parsed = Json.parseToJsonElement(encoded).jsonObject
        val event = parsed.getValue("events").jsonArray.single().jsonObject
        assertEquals("DISCOVERY_ENTER", event.getValue("phase").jsonPrimitive.content)
        assertEquals(setOf("sequence", "phase", "offsetNanos", "outcome", "durationNanos", "error", "itemCount"), event.keys)
        assertFalse(encoded.contains("TEST_ONLY_PRIVATE_EXCEPTION"))
        assertEquals(1L, trace.snapshot().listenerFailureCount)
        assertEquals(trace.snapshot(), Json.decodeFromString(DiscoveryLoadSnapshot.serializer(), encoded))
    }

    @Test fun `invalid clock and impossible empty count fail measurement instead of manufacturing success`() {
        var now = 100L
        val trace = enabled { now }
        now = 99
        assertThrows(IllegalStateException::class.java) { trace.mark(DiscoveryLoadPhase.SHELL_DRAWN) }
        now = 100
        assertThrows(IllegalArgumentException::class.java) {
            trace.mark(DiscoveryLoadPhase.FIRST_VIEWPORT_POINTS_DRAWN, DiscoveryLoadOutcome.EMPTY, itemCount = 3)
        }
        assertTrue(trace.snapshot().events.isEmpty())
    }

    private fun enabled(clock: () -> Long) = DiscoveryLoadTrace(DiscoveryLoadTraceConfig(enabled = true), clock)
}
