package cn.anitabi.navigator.data.discovery

/** Monotonic spans may cross dispatchers; this does not call thread-bound Android Trace APIs. */
inline fun <T> DiscoveryLoadTrace.measure(
    phase: DiscoveryLoadPhase,
    itemCount: Long? = null,
    block: () -> T,
): T {
    if (!enabled) return block()
    val span = begin(phase)
    var outcome = DiscoveryLoadOutcome.CANCELLED
    try {
        val result = block()
        outcome = if (itemCount == 0L) DiscoveryLoadOutcome.EMPTY else DiscoveryLoadOutcome.OBSERVED
        return result
    } catch (cancelled: java.util.concurrent.CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        outcome = DiscoveryLoadOutcome.FAILED
        throw failure
    } finally {
        end(span, outcome, if (outcome == DiscoveryLoadOutcome.FAILED) DiscoveryLoadError.UNKNOWN else null, itemCount)
    }
}
