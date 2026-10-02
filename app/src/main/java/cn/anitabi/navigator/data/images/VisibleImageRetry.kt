package cn.anitabi.navigator.data.images

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.coroutineContext

/** The caller owns real fetching/decoding; null means that load completed successfully. */
internal suspend fun retryVisibleImages(
    wanted: Set<String>,
    failures: ImageFailureBackoff,
    cached: (String) -> Boolean,
    now: () -> Long,
    load: suspend (String) -> ImageFailure?,
) {
    failures.retain(wanted)
    val slots = Semaphore(3)
    // Successful images stay completed even if the caller's bitmap LRU evicts them.
    val completed = wanted.filterTo(hashSetOf(), cached)
    while (true) {
        coroutineScope {
            wanted.forEach { key ->
                if (key in completed || failures.remainingMillis(key, now()) > 0) return@forEach
                launch { slots.withPermit {
                    val failure = load(key)
                    coroutineContext.ensureActive()
                    if (failure == null) {
                        completed.add(key)
                        failures.clear(key)
                    } else failures.record(key, failure, now())
                } }
            }
        }
        val remaining = wanted - completed
        if (remaining.isEmpty()) break
        delay(remaining.minOf { failures.remainingMillis(it, now()) }.coerceAtLeast(1_000))
    }
}
