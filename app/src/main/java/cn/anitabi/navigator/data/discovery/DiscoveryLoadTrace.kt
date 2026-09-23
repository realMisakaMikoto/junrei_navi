package cn.anitabi.navigator.data.discovery

import java.util.ArrayDeque
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class DiscoveryLoadPhase {
    LAUNCH, DISCOVERY_ENTER, SHELL_DRAWN, SDK_VIEW_CREATED, SDK_READY, BASEMAP_RENDER_OBSERVED,
    CACHE_READ, INDEX_FETCH, INDEX_PARSE, REQUEST_QUEUE_WAIT, PAGE_FETCH, SUBJECT_FETCH,
    CLASSIFY, CONVERT, INDEX_BUILD, FIRST_VIEWPORT_POINTS_DRAWN, VIEWPORT_SELECTION_READY,
    FIRST_VISIBLE_IMAGE, VISIBLE_IMAGES_SETTLED, DETAILS_SYNC_COMPLETE, CACHE_WRITE,
    SNAPSHOT_LOCK_WAIT, NEARBY_SORT, IMAGE_DECODE, MARKER_UPDATE, MAIN_THREAD_WORK, SDK_NETWORK,
}

@Serializable
enum class DiscoveryLoadOutcome { OBSERVED, EMPTY, FAILED, CANCELLED, NOT_OBSERVED, UNSUPPORTED }

@Serializable
enum class DiscoveryLoadEndpoint { STATIC_INDEX, STATIC_PAGE, SUBJECT_DETAILS, IMAGE, SDK_UNOBSERVED }

@Serializable
enum class DiscoveryLoadCache { NETWORK, MEMORY, DISK, UNKNOWN }

@Serializable
enum class DiscoveryLoadError {
    DNS, TLS, TIMEOUT, NETWORK, DENIED, NOT_FOUND, RATE_LIMITED, SERVER, HTTP_OTHER,
    INVALID_DATA, DECODE, STORAGE, SDK, REGION_UNAVAILABLE, UNKNOWN,
}

@Serializable
enum class DiscoveryLoadCounter {
    INDEX_REBUILD_COUNT, DISTANCE_COMPUTATION_COUNT, NEARBY_SORT_COUNT, CACHE_WRITE_COUNT,
    CACHE_WRITE_BYTES, MARKER_UPDATE_COUNT, IMAGE_DECODE_COUNT, IMAGE_REUSE_COUNT,
    CANCELLED_WORK_COUNT, RESTARTED_WORK_COUNT, SDK_VIEW_CREATE_COUNT, SDK_VIEW_RELEASE_COUNT,
    FRAME_COUNT, FRAME_TOTAL_DURATION_NANOS, FIRST_DRAW_FRAME_COUNT, FRAME_REPORT_DROPPED_COUNT,
    JAVA_HEAP_SAMPLE_COUNT, JAVA_HEAP_PEAK_BYTES, NATIVE_HEAP_SAMPLE_COUNT, NATIVE_HEAP_PEAK_BYTES,
    PSS_SAMPLE_COUNT, PSS_PEAK_BYTES,
}

data class DiscoveryLoadTraceConfig(val enabled: Boolean = false, val maxEvents: Int = 512, val maxSpans: Int = 64) {
    init { require(maxEvents in 1..16_384 && maxSpans in 1..1_024) }
}

@Serializable
data class DiscoveryLoadEvent(
    /** Local ordering only; never a data-source, device or user identifier. */
    val sequence: Long,
    val phase: DiscoveryLoadPhase,
    val offsetNanos: Long,
    val outcome: DiscoveryLoadOutcome,
    /** Actual attempted span time; only OBSERVED durations belong in successful-stage statistics. */
    val durationNanos: Long? = null,
    val error: DiscoveryLoadError? = null,
    val itemCount: Long? = null,
)

@Serializable
data class DiscoveryLoadPendingSpan(val phase: DiscoveryLoadPhase, val startOffsetNanos: Long)

@Serializable
data class DiscoveryLoadRequestSummary(
    val endpoint: DiscoveryLoadEndpoint,
    /** Null means unobserved, not zero requests. Counts cover completed attempts, including failures. */
    val completedCount: Long? = null,
    val outcomes: Map<DiscoveryLoadOutcome, Long> = emptyMap(),
    val caches: Map<DiscoveryLoadCache, Long> = emptyMap(),
    val errors: Map<DiscoveryLoadError, Long> = emptyMap(),
    val receivedBytes: Long? = null,
    val knownByteCount: Long = 0,
    val unknownByteCount: Long = 0,
)

@Serializable
data class DiscoveryLoadSnapshot(
    val schemaVersion: Int = 1,
    val enabled: Boolean,
    val originNanos: Long?,
    val elapsedNanos: Long?,
    val events: List<DiscoveryLoadEvent>,
    val pendingSpans: List<DiscoveryLoadPendingSpan>,
    /** Missing keys are unobserved; an explicit zero is a measured zero. */
    val counters: Map<DiscoveryLoadCounter, Long>,
    val requests: List<DiscoveryLoadRequestSummary>,
    val droppedEvents: Long,
    val droppedSpans: Long,
    val listenerFailureCount: Long,
)

/**
 * Local, opt-in recorder; constructing the default instance performs no clock or listener calls.
 * Inject an enabled config only from the profiling harness. There is no file/network/telemetry sink.
 * Callers choose outcomes from real observations; SDK readiness never implies basemap rendering.
 * One instance is one measurement interval. Span tokens cannot finish another instance's work.
 */
class DiscoveryLoadTrace(
    private val config: DiscoveryLoadTraceConfig = DiscoveryLoadTraceConfig(),
    private val nanoTime: () -> Long = System::nanoTime,
    private val listener: ((DiscoveryLoadEvent) -> Unit)? = null,
) {
    class Span internal constructor(
        internal val phase: DiscoveryLoadPhase,
        internal val startOffsetNanos: Long,
    )

    private val lock = Any()
    private val origin = if (config.enabled) nanoTime() else null
    private var lastOffset = 0L
    private var sequence = 0L
    private val events = ArrayDeque<DiscoveryLoadEvent>()
    private val spans = linkedSetOf<Span>()
    private val counters = linkedMapOf<DiscoveryLoadCounter, Long>()
    private val requests = linkedMapOf<DiscoveryLoadEndpoint, DiscoveryLoadRequestSummary>()
    private var droppedEvents = 0L
    private var droppedSpans = 0L
    private var listenerFailures = 0L

    fun begin(phase: DiscoveryLoadPhase): Span? {
        if (!config.enabled) return null
        return synchronized(lock) {
            if (spans.size >= config.maxSpans) { droppedSpans += 1; return@synchronized null }
            Span(phase, offset()).also(spans::add)
        }
    }

    fun end(
        span: Span?,
        outcome: DiscoveryLoadOutcome = DiscoveryLoadOutcome.OBSERVED,
        error: DiscoveryLoadError? = null,
        itemCount: Long? = null,
    ): Boolean {
        if (!config.enabled || span == null) return false
        val event = synchronized(lock) {
            if (span !in spans) return false
            validate(outcome, error, itemCount)
            val now = offset()
            spans.remove(span)
            append(span.phase, now, outcome, if (outcome.isUnavailable()) null else now - span.startOffsetNanos, error, itemCount)
        }
        notifyListener(event)
        return true
    }

    /** Milestones have a timestamp, not an invented zero-length successful span. */
    fun mark(
        phase: DiscoveryLoadPhase,
        outcome: DiscoveryLoadOutcome = DiscoveryLoadOutcome.OBSERVED,
        error: DiscoveryLoadError? = null,
        itemCount: Long? = null,
    ) {
        if (!config.enabled) return
        val event = synchronized(lock) {
            validate(outcome, error, itemCount)
            append(phase, offset(), outcome, null, error, itemCount)
        }
        notifyListener(event)
    }

    /** Record once per completed app-owned request; SDK private networking remains unobserved. */
    fun request(
        endpoint: DiscoveryLoadEndpoint,
        outcome: DiscoveryLoadOutcome,
        receivedBytes: Long? = null,
        cache: DiscoveryLoadCache = DiscoveryLoadCache.UNKNOWN,
        error: DiscoveryLoadError? = null,
    ) {
        if (!config.enabled) return
        require(endpoint != DiscoveryLoadEndpoint.SDK_UNOBSERVED && !outcome.isUnavailable())
        require(receivedBytes == null || receivedBytes >= 0)
        validate(outcome, error, null)
        synchronized(lock) {
            val current = requests[endpoint] ?: DiscoveryLoadRequestSummary(endpoint)
            requests[endpoint] = current.copy(
                completedCount = (current.completedCount ?: 0) + 1,
                outcomes = current.outcomes.incremented(outcome),
                caches = current.caches.incremented(cache),
                errors = if (outcome == DiscoveryLoadOutcome.FAILED) current.errors.incremented(error ?: DiscoveryLoadError.UNKNOWN) else current.errors,
                receivedBytes = receivedBytes?.let { (current.receivedBytes ?: 0) + it } ?: current.receivedBytes,
                knownByteCount = current.knownByteCount + if (receivedBytes != null) 1 else 0,
                unknownByteCount = current.unknownByteCount + if (receivedBytes == null) 1 else 0,
            )
        }
    }

    fun increment(counter: DiscoveryLoadCounter, amount: Long = 1) {
        if (!config.enabled) return
        require(amount >= 0)
        synchronized(lock) { counters[counter] = Math.addExact(counters[counter] ?: 0, amount) }
    }

    fun maximum(counter: DiscoveryLoadCounter, value: Long) {
        if (!config.enabled) return
        require(value >= 0)
        synchronized(lock) { counters[counter] = maxOf(counters[counter] ?: 0, value) }
    }

    fun snapshot(): DiscoveryLoadSnapshot = synchronized(lock) {
        DiscoveryLoadSnapshot(
            enabled = config.enabled, originNanos = origin, elapsedNanos = if (config.enabled) offset() else null,
            events = events.toList(), pendingSpans = spans.map { DiscoveryLoadPendingSpan(it.phase, it.startOffsetNanos) },
            counters = counters.toMap(), requests = DiscoveryLoadEndpoint.entries.map { requests[it] ?: DiscoveryLoadRequestSummary(it) },
            droppedEvents = droppedEvents, droppedSpans = droppedSpans, listenerFailureCount = listenerFailures,
        )
    }

    /** Only the closed scalar schema above can be exported; never accepts caller-supplied tags. */
    fun toJson(): String = json.encodeToString(DiscoveryLoadSnapshot.serializer(), snapshot())

    private fun offset(): Long {
        val value = nanoTime() - requireNotNull(origin)
        check(value >= lastOffset) { "Discovery trace requires a monotonic clock" }
        lastOffset = value
        return value
    }

    private fun append(
        phase: DiscoveryLoadPhase, now: Long, outcome: DiscoveryLoadOutcome,
        durationNanos: Long?, error: DiscoveryLoadError?, itemCount: Long?,
    ): DiscoveryLoadEvent {
        if (events.size == config.maxEvents) { events.removeFirst(); droppedEvents += 1 }
        return DiscoveryLoadEvent(++sequence, phase, now, outcome, durationNanos, error, itemCount).also(events::addLast)
    }

    private fun notifyListener(event: DiscoveryLoadEvent) {
        try { listener?.invoke(event) } catch (_: Exception) { synchronized(lock) { listenerFailures += 1 } }
    }

    private fun validate(outcome: DiscoveryLoadOutcome, error: DiscoveryLoadError?, itemCount: Long?) {
        require(itemCount == null || itemCount >= 0)
        require(outcome != DiscoveryLoadOutcome.EMPTY || itemCount == null || itemCount == 0L)
        require(error == null || outcome == DiscoveryLoadOutcome.FAILED)
    }

    private companion object { val json = Json { encodeDefaults = true } }
}

private fun DiscoveryLoadOutcome.isUnavailable() =
    this == DiscoveryLoadOutcome.NOT_OBSERVED || this == DiscoveryLoadOutcome.UNSUPPORTED

private fun <T> Map<T, Long>.incremented(key: T): Map<T, Long> = this + (key to ((this[key] ?: 0) + 1))
