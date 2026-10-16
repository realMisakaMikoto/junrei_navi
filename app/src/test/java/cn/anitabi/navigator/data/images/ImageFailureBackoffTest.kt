package cn.anitabi.navigator.data.images

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ImageFailureBackoffTest {
    @Test fun transientFailureExpiresAndRepeatedFailuresBackOff() {
        val policy = ImageFailureBackoff()
        policy.record("a-h160", ImageFailure.NETWORK, 100)
        assertEquals(5_000, policy.remainingMillis("a-h160", 100))
        assertEquals(0, policy.remainingMillis("a-h160", 5_100))
        policy.record("a-h160", ImageFailure.NETWORK, 5_100)
        assertEquals(10_000, policy.remainingMillis("a-h160", 5_100))
        policy.clear("a-h160")
        assertEquals(0, policy.remainingMillis("a-h160", 5_100))
    }

    @Test fun missingResourceDoesNotRapidlyRetryButManualRetryIsScoped() {
        val policy = ImageFailureBackoff()
        policy.record("a", ImageFailure.RESOURCE, 0)
        policy.record("b", ImageFailure.ACCESS, 0)
        assertEquals(600_000, policy.remainingMillis("a", 0))
        policy.retry(setOf("a"))
        assertEquals(0, policy.remainingMillis("a", 0))
        assertEquals(600_000, policy.remainingMillis("b", 0))
    }

    @Test fun capacityAndMetadataChangesDoNotBecomePermanentBlacklists() {
        val policy = ImageFailureBackoff(capacity = 2)
        listOf("a", "b", "c").forEach { policy.record(it, ImageFailure.NETWORK, 0) }
        assertEquals(2, policy.size)
        assertEquals(0, policy.remainingMillis("a", 0))
        policy.retain(setOf("b-new-version", "c"))
        assertEquals(1, policy.size)
        assertEquals(0, policy.remainingMillis("b-new-version", 0))
        policy.record("b-new-version", ImageFailure.NETWORK, 0)
        policy.record("outside", ImageFailure.NETWORK, 0)
        assertEquals(2, policy.size)
        assertEquals(5_000, policy.remainingMillis("b-new-version", 0))
        assertEquals(0, policy.remainingMillis("outside", 0))
    }

    @Test fun visibleTransientFailuresOverCapacityRespectEveryBackoffDeadline() = runTest {
        val wanted = (0..256).mapTo(linkedSetOf()) { "synthetic-$it-h160" }
        val calls = linkedMapOf<String, MutableList<Long>>()
        val policy = ImageFailureBackoff()
        val retry = launch {
            retryVisibleImages(wanted, policy, cached = { false }, now = { testScheduler.currentTime }) { key ->
                calls.getOrPut(key) { mutableListOf() }.add(testScheduler.currentTime)
                ImageFailure.NETWORK
            }
        }
        try {
            runCurrent()
            assertEquals(wanted, calls.keys)
            advanceTimeBy(4_999)
            runCurrent()
            assertTrue("Every visible failure must wait its first five seconds", calls.values.all { it == listOf(0L) })
            advanceTimeBy(1)
            runCurrent()
            assertTrue(calls.values.all { it == listOf(0L, 5_000L) })
            advanceTimeBy(9_999)
            runCurrent()
            assertTrue("The second transient failure must wait ten more seconds", calls.values.all { it.size == 2 })
            advanceTimeBy(1)
            runCurrent()
            assertTrue(calls.values.all { it == listOf(0L, 5_000L, 15_000L) })
        } finally { retry.cancelAndJoin() }
    }

    @Test fun visiblePermanentFailuresOverCapacityWaitTenMinutes() = runTest {
        val wanted = (0..256).mapTo(linkedSetOf()) { "synthetic-$it-h160" }
        val calls = linkedMapOf<String, MutableList<Long>>()
        val policy = ImageFailureBackoff()
        val retry = launch {
            retryVisibleImages(wanted, policy, cached = { false }, now = { testScheduler.currentTime }) { key ->
                calls.getOrPut(key) { mutableListOf() }.add(testScheduler.currentTime)
                ImageFailure.RESOURCE
            }
        }
        try {
            runCurrent()
            advanceTimeBy(4_999)
            runCurrent()
            assertTrue("Permanent visible failures cannot retry after capacity eviction", calls.values.all { it == listOf(0L) })
            advanceTimeBy(595_000)
            runCurrent()
            assertTrue(calls.values.all { it == listOf(0L) })
            advanceTimeBy(1)
            runCurrent()
            assertTrue(calls.values.all { it == listOf(0L, 600_000L) })
        } finally { retry.cancelAndJoin() }
    }

    @Test fun mixedVisibleFailuresOverCapacityKeepIndependentDeadlinesAndSuccesses() = runTest {
        val wanted = (0..256).mapTo(linkedSetOf()) { "synthetic-$it-h160" }
        val transient = wanted.filterIndexed { index, _ -> index % 2 == 0 }.toSet()
        val calls = linkedMapOf<String, MutableList<Long>>()
        val policy = ImageFailureBackoff()
        val retry = launch {
            retryVisibleImages(wanted, policy, cached = { false }, now = { testScheduler.currentTime }) { key ->
                val attempts = calls.getOrPut(key) { mutableListOf() }
                attempts.add(testScheduler.currentTime)
                if (key !in transient) ImageFailure.ACCESS else if (attempts.size == 1) ImageFailure.NETWORK else null
            }
        }
        try {
            runCurrent()
            advanceTimeBy(4_999)
            runCurrent()
            assertTrue("Overflow must not make either failure category immediately retryable", calls.values.all { it == listOf(0L) })
            advanceTimeBy(1)
            runCurrent()
            assertTrue(transient.all { calls[it] == listOf(0L, 5_000L) })
            assertTrue((wanted - transient).all { calls[it] == listOf(0L) })
            advanceTimeBy(595_000)
            runCurrent()
            assertTrue(transient.all { calls[it] == listOf(0L, 5_000L) })
            assertTrue((wanted - transient).all { calls[it] == listOf(0L, 600_000L) })
        } finally { retry.cancelAndJoin() }
    }

    @Test fun manualRetryAndMetadataChangesAffectOnlyTheCurrentVisibleSet() = runTest {
        val old = "synthetic-a-version1-h160"
        val new = "synthetic-a-version2-h160"
        val remaining = "synthetic-b-h160"
        val removed = "synthetic-outside-h160"
        val policy = ImageFailureBackoff()
        val calls = linkedMapOf<String, MutableList<Long>>()
        var failing = true
        suspend fun load(key: String): ImageFailure? {
            calls.getOrPut(key) { mutableListOf() }.add(testScheduler.currentTime)
            return if (failing) ImageFailure.RESOURCE else null
        }
        var retry = launch {
            retryVisibleImages(linkedSetOf(old, remaining, removed), policy, cached = { false },
                now = { testScheduler.currentTime }, load = ::load)
        }
        try {
            runCurrent()
            assertEquals(3, policy.size)
            retry.cancelAndJoin()
            advanceTimeBy(100)
            failing = false
            policy.retry(setOf(old))
            retry = launch {
                retryVisibleImages(linkedSetOf(old, remaining), policy, cached = { false },
                    now = { testScheduler.currentTime }, load = ::load)
            }
            runCurrent()
            assertEquals(listOf(0L, 100L), calls[old])
            assertEquals(listOf(0L), calls[remaining])
            assertEquals(1, policy.size)
            assertEquals(0L, policy.remainingMillis(removed, testScheduler.currentTime))
            retry.cancelAndJoin()
            advanceTimeBy(100)
            retry = launch {
                retryVisibleImages(linkedSetOf(new, remaining), policy, cached = { false },
                    now = { testScheduler.currentTime }, load = ::load)
            }
            runCurrent()
            assertEquals(listOf(200L), calls[new])
            assertEquals(listOf(0L), calls[remaining])
            assertEquals(1, policy.size)
        } finally { retry.cancelAndJoin() }
    }

    @Test fun cancellationNeverRecordsAFailureAndKeepsThreeLoadSlots() = runTest {
        val policy = ImageFailureBackoff()
        val started = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val wanted = (0..4).mapTo(linkedSetOf()) { "synthetic-$it-h160" }
        val retry = launch {
            retryVisibleImages(wanted, policy, cached = { false }, now = { testScheduler.currentTime }) { key ->
                started += key
                withContext(NonCancellable) { gate.await() }
                ImageFailure.NETWORK
            }
        }
        try {
            runCurrent()
            assertEquals("Only three real loads may run at once", 3, started.size)
            retry.cancel()
            gate.complete(Unit)
            retry.join()
            assertEquals("A late transport failure from cancelled work is not a retry failure", 0, policy.size)
            assertEquals(3, started.size)
        } finally {
            gate.complete(Unit)
            retry.cancelAndJoin()
        }
    }

    @Test fun successfulVisibleLoadsFinishWithoutRefetchingEvictedBitmapEntries() = runTest {
        val wanted = (0..256).mapTo(linkedSetOf()) { "synthetic-$it-h160" }
        val calls = mutableListOf<String>()
        retryVisibleImages(wanted, ImageFailureBackoff(), cached = { false }, now = { testScheduler.currentTime }) { key ->
            calls += key
            null
        }
        assertEquals(wanted, calls.toSet())
        assertEquals(wanted.size, calls.size)
    }
}
