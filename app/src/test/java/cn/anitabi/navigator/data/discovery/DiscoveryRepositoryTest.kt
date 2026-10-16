package cn.anitabi.navigator.data.discovery

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DiscoveryRepositoryTest {
    @Test fun loadsDynamicPagesSeriallyWithOneTokenThenChecksIndexAgain() = runTest {
        val source = FakeSource()
        val repository = DiscoveryRepository(source, MemoryDiscoveryCache(), this, now = { testScheduler.currentTime + 1_000 })
        repository.refresh()!!.join()
        assertEquals(listOf("index", "page0", "page1", "index"), source.requests)
        assertEquals(1, source.tokens.distinct().size)
        assertTrue(repository.state.value.detailsCurrent)
        assertTrue(testScheduler.currentTime >= 3_000)
        repository.refresh()!!.join()
        assertEquals(4, source.requests.size)
        repository.refresh(force = true)!!.join()
        assertEquals(6, source.requests.size)
    }

    @Test fun failedShardNeverBecomesCurrentAndRetriesDespiteRecentIndexCheck() = runTest {
        val source = FakeSource().apply { failPage = 1 }
        val cache = MemoryDiscoveryCache()
        val repository = DiscoveryRepository(source, cache, this, requestIntervalMillis = 0)
        repository.refresh()!!.join()
        assertFalse(repository.state.value.detailsCurrent)
        assertEquals(setOf(0), cache.snapshot!!.loadedPages)
        source.failPage = null
        repository.refresh()!!.join()
        assertTrue(repository.state.value.detailsCurrent)
        assertEquals(1, source.requests.count { it == "page0" })
        assertEquals(2, source.requests.count { it == "page1" })
    }

    @Test fun completeCacheChecksAgainAfterTwentyFourHours() = runTest {
        val source = FakeSource()
        val repository = DiscoveryRepository(
            source, MemoryDiscoveryCache(), this,
            now = { testScheduler.currentTime + 1_000 }, requestIntervalMillis = 0,
        )
        repository.refresh()!!.join()
        repository.refresh()!!.join()
        assertEquals(4, source.requests.size)
        advanceTimeBy(DiscoveryRepository.UPDATE_INTERVAL_MILLIS)
        repository.refresh()!!.join()
        assertEquals(6, source.requests.size)
        assertTrue(repository.state.value.detailsCurrent)
    }

    @Test fun missingPointRowsAndWrongShardCannotSatisfyFreshness() = runTest {
        for (document in listOf(Json.parseToJsonElement("[[3,[],[],100]]"), pageFixture(listOf(1)))) {
            val source = FakeSource().apply { secondPage = document }
            val repository = DiscoveryRepository(source, MemoryDiscoveryCache(), this, requestIntervalMillis = 0)
            repository.refresh()!!.join()
            assertFalse(repository.state.value.detailsCurrent)
            assertEquals(setOf(0), repository.state.value.snapshot!!.loadedPages)
            assertEquals(DiscoveryError.INVALID_DATA, repository.state.value.error)
        }
    }

    @Test fun changedEndIndexCannotClaimConsistentGeneration() = runTest {
        val source = FakeSource().apply { changeOnEnd = true }
        val repository = DiscoveryRepository(source, MemoryDiscoveryCache(), this, requestIntervalMillis = 0)
        repository.refresh()!!.join()
        assertTrue(repository.state.value.detailsComplete)
        assertFalse(repository.state.value.detailsCurrent)
        assertEquals(DiscoveryError.VERSION_CHANGED, repository.state.value.error)
    }

    @Test fun corruptNewIndexDoesNotOverwriteUsableCache() = runTest {
        val old = DiscoveryParser.index(indexFixture())
        val cache = MemoryDiscoveryCache(old)
        val source = FakeSource().apply { invalidIndex = true }
        val repository = DiscoveryRepository(source, cache, this, requestIntervalMillis = 0)
        repository.refresh()!!.join()
        assertEquals(old, cache.snapshot)
        assertEquals(old, repository.state.value.snapshot)
        assertEquals(DiscoveryError.INVALID_DATA, repository.state.value.error)
    }

    @Test fun oldDetailsRemainAvailableButStaleWhenNextGenerationPageFails() = runTest {
        val source = FakeSource()
        val cache = MemoryDiscoveryCache()
        val repository = DiscoveryRepository(source, cache, this, requestIntervalMillis = 0)
        repository.refresh()!!.join()
        val oldVersion = cache.snapshot!!.version
        source.modified = 200
        source.failPage = 1
        repository.refresh(force = true)!!.join()
        assertTrue(repository.state.value.detailsComplete)
        assertFalse(repository.state.value.detailsCurrent)
        assertEquals(oldVersion, cache.snapshot!!.points.last().detailsVersion)
    }

    @Test fun duplicateSubjectClicksShareOneRequestAndCannotMoveCoordinates() = runTest {
        val source = FakeSource().apply { subjectGate = CompletableDeferred() }
        val cache = MemoryDiscoveryCache(DiscoveryParser.index(indexFixture()))
        val repository = DiscoveryRepository(source, cache, this, requestIntervalMillis = 0)
        repository.initialize()
        val originalCoordinate = repository.state.value.snapshot!!.points.first().coordinate
        val first = async { repository.ensureSubjectDetails(1) }
        val second = async { repository.ensureSubjectDetails(1) }
        runCurrent()
        assertEquals(1, source.subjectCalls)
        source.subjectGate!!.complete(Unit)
        first.await()
        second.await()
        assertEquals(originalCoordinate, repository.state.value.snapshot!!.points.first().coordinate)
        assertTrue(repository.state.value.loadingSubjectIds.isEmpty())
        assertTrue(repository.state.value.snapshot!!.points.first().detailsVersion!!.startsWith("api:"))
        repository.ensureSubjectDetails(1)
        assertEquals(1, source.subjectCalls)
    }

    @Test fun backgroundCancelsWorkAndForegroundResumesMissingPages() = runTest {
        val source = FakeSource().apply { firstPageGate = CompletableDeferred() }
        val repository = DiscoveryRepository(source, MemoryDiscoveryCache(), this, requestIntervalMillis = 0)
        val update = repository.refresh()!!
        source.firstPageStarted.await()
        repository.setForeground(false)
        update.join()
        assertTrue(repository.state.value.paused)
        assertFalse(source.requests.contains("page1"))
        source.firstPageGate!!.complete(Unit)
        repository.setForeground(true)
        runCurrent()
        repository.refresh()!!.join()
        assertTrue(repository.state.value.detailsCurrent)
    }

    @Test fun manualRefreshDuringActiveRoundRunsOneFollowupRound() = runTest {
        val source = FakeSource().apply { firstPageGate = CompletableDeferred() }
        val repository = DiscoveryRepository(source, MemoryDiscoveryCache(), this, requestIntervalMillis = 0)
        val update = repository.refresh()!!
        source.firstPageStarted.await()
        repository.refresh(force = true)
        repository.refresh(force = true)
        source.firstPageGate!!.complete(Unit)
        update.join()
        assertEquals(listOf("index", "page0", "page1", "index", "index", "index"), source.requests)
        assertTrue(repository.state.value.detailsCurrent)
        assertFalse(repository.state.value.refreshing)
    }

    @Test fun forcedRefreshSurvivesPauseBeforeWorkerStarts() = runTest {
        val source = FakeSource()
        val repository = DiscoveryRepository(source, MemoryDiscoveryCache(), this, requestIntervalMillis = 0)
        repository.refresh()!!.join()
        val update = repository.refresh(force = true)!!
        repository.setForeground(false)
        update.join()
        repository.setForeground(true)
        repository.refresh()!!.join()
        assertEquals(6, source.requests.size)
        assertTrue(repository.state.value.detailsCurrent)
    }

    @Test fun forcedRefreshSurvivesPauseWhileFreshCacheIndexIsInFlight() = runTest {
        val source = FakeSource()
        val repository = DiscoveryRepository(source, MemoryDiscoveryCache(), this, requestIntervalMillis = 0)
        repository.refresh()!!.join()
        source.indexGate = CompletableDeferred()
        source.indexStarted = CompletableDeferred()
        val update = repository.refresh(force = true)!!
        source.indexStarted.await()
        repository.setForeground(false)
        update.join()
        source.indexGate!!.complete(Unit)
        repository.setForeground(true)
        repository.refresh()!!.join()
        assertEquals(7, source.requests.size)
        assertTrue(repository.state.value.detailsCurrent)
    }

    @Test fun resumeWaitsForCancelledWorkerBeforeStartingAnother() = runTest {
        val source = FakeSource().apply {
            firstPageGate = CompletableDeferred()
            pageCancellationGate = CompletableDeferred()
        }
        val repository = DiscoveryRepository(source, MemoryDiscoveryCache(), this, requestIntervalMillis = 0)
        val update = repository.refresh()!!
        source.firstPageStarted.await()
        repository.setForeground(false)
        runCurrent()
        repository.setForeground(true)
        assertSame(update, repository.refresh(force = true))
        source.firstPageGate!!.complete(Unit)
        source.pageCancellationGate!!.complete(Unit)
        update.join()
        repository.refresh()!!.join()
        assertTrue(repository.state.value.detailsCurrent)
        assertFalse(repository.state.value.refreshing)
    }

    @Test fun abandonedFailedSubjectRequestCanBeRetried() = runTest {
        val source = FakeSource().apply {
            subjectGate = CompletableDeferred()
            failSubject = true
        }
        val cache = MemoryDiscoveryCache(DiscoveryParser.index(indexFixture()))
        val repository = DiscoveryRepository(source, cache, this, requestIntervalMillis = 0)
        repository.initialize()
        val caller = async { repository.ensureSubjectDetails(1) }
        runCurrent()
        assertEquals(1, source.subjectCalls)
        caller.cancelAndJoin()
        source.subjectGate!!.complete(Unit)
        runCurrent()
        source.failSubject = false
        repository.ensureSubjectDetails(1)
        assertEquals(2, source.subjectCalls)
        assertTrue(repository.state.value.loadingSubjectIds.isEmpty())
    }

    @Test fun abandonedCancelledSubjectRequestCanBeRetriedAfterResume() = runTest {
        val source = FakeSource().apply {
            subjectGate = CompletableDeferred()
            invalidIndex = true
        }
        val cache = MemoryDiscoveryCache(DiscoveryParser.index(indexFixture()))
        val repository = DiscoveryRepository(source, cache, this, requestIntervalMillis = 0)
        repository.initialize()
        val caller = async { repository.ensureSubjectDetails(1) }
        runCurrent()
        assertEquals(1, source.subjectCalls)
        caller.cancelAndJoin()
        repository.setForeground(false)
        runCurrent()
        source.subjectGate!!.complete(Unit)
        repository.setForeground(true)
        repository.refresh()!!.join()
        repository.ensureSubjectDetails(1)
        assertEquals(2, source.subjectCalls)
        assertTrue(repository.state.value.loadingSubjectIds.isEmpty())
    }

    private class FakeSource : DiscoverySource {
        val requests = mutableListOf<String>()
        val tokens = mutableListOf<String>()
        var failPage: Int? = null
        var modified = 100L
        var changeOnEnd = false
        var invalidIndex = false
        var subjectCalls = 0
        var failSubject = false
        var subjectGate: CompletableDeferred<Unit>? = null
        var indexGate: CompletableDeferred<Unit>? = null
        var indexStarted = CompletableDeferred<Unit>()
        var firstPageGate: CompletableDeferred<Unit>? = null
        var pageCancellationGate: CompletableDeferred<Unit>? = null
        var secondPage: JsonElement? = null
        val firstPageStarted = CompletableDeferred<Unit>()

        override suspend fun index(cacheToken: String): JsonElement {
            requests.add("index")
            tokens.add(cacheToken)
            indexStarted.complete(Unit)
            indexGate?.await()
            return if (invalidIndex) Json.parseToJsonElement("{}") else indexFixture(
                modified = if (changeOnEnd && requests.size > 1) modified + 1 else modified,
            )
        }

        override suspend fun page(page: Int, cacheToken: String): JsonElement {
            requests.add("page$page")
            tokens.add(cacheToken)
            if (page == 0) {
                firstPageStarted.complete(Unit)
                try {
                    firstPageGate?.await()
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable) { pageCancellationGate?.await() }
                    throw cancelled
                }
            }
            if (page == failPage) throw IOException("Synthetic network failure")
            if (page == 1) secondPage?.let { return it }
            return pageFixture(if (page == 0) listOf(1, 2) else listOf(3))
        }

        override suspend fun subject(subjectId: Long): JsonElement {
            subjectCalls++
            subjectGate?.await()
            if (failSubject) throw IOException("Synthetic network failure")
            return Json.parseToJsonElement("""[{"id":"shared","name":"Synthetic API","geo":[55,66]}]""")
        }
    }
}
