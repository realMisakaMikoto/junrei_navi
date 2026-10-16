package cn.anitabi.navigator.data.discovery

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** All identities, labels, image references and coordinates in this fixture are synthetic. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DiscoveryConsistencyRegressionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun pageFirstLateApiCannotDowngradeVerifiedDetailsAcrossTwentyRounds() = runTest {
        assertLateApiPreservesPage(apiDocument("first", "second"))
    }

    @Test fun pageFirstLateApiFolderCannotRemoveVerifiedMemberAcrossTwentyRounds() = runTest {
        assertLateApiPreservesPage(Json.parseToJsonElement("""[{"id":"first","isFolder":true}]"""))
    }

    private suspend fun TestScope.assertLateApiPreservesPage(response: JsonElement) {
        val brokenRounds = mutableListOf<Int>()
        repeat(20) { round ->
            val initial = indexWithOtherSubjectVerified()
            val source = ControlledSource().apply {
                firstPageGate = Gate()
                firstSubjectGate = Gate()
                subjectDocuments = listOf(response)
            }
            val repository = repository(source, MemoryDiscoveryCache(initial))
            repository.initialize()
            val update = repository.refresh(force = true)!!
            source.firstPageGate!!.started.await()
            val subject = async { repository.ensureSubjectDetails(1) }
            runCurrent()
            assertEquals(setOf(1L), repository.state.value.loadingSubjectIds)
            source.firstPageGate!!.release.complete(Unit)
            source.firstSubjectGate!!.started.await()
            val verified = repository.state.first { 0 in it.snapshot!!.loadedPages }.snapshot!!
            assertEquals(verified.version, verified.points.first().detailsVersion)
            source.firstSubjectGate!!.release.complete(Unit)
            subject.await()
            update.join()
            val actual = repository.state.value.snapshot!!
            if (actual.points != verified.points || actual.subjects != verified.subjects || !actual.detailsCurrent) {
                brokenRounds += round
            }
            assertEquals(initial.points.last(), actual.points.last())
            assertEquals(1, source.subjectCalls)
            assertEquals(1, source.maximumConcurrentRequests)
        }
        assertEquals("A late API result changed authoritative page content in these rounds", emptyList<Int>(), brokenRounds)
    }

    @Test fun apiFirstPageLaterEstablishesAuthoritativeDetailsAcrossTwentyRounds() = runTest {
        repeat(20) {
            val source = ControlledSource().apply { firstSubjectGate = Gate() }
            val repository = repository(source, MemoryDiscoveryCache(indexWithOtherSubjectVerified()))
            repository.initialize()
            val subject = async { repository.ensureSubjectDetails(1) }
            source.firstSubjectGate!!.started.await()
            val update = repository.refresh(force = true)!!
            runCurrent()
            source.firstSubjectGate!!.release.complete(Unit)
            subject.await()
            update.join()
            val actual = repository.state.value.snapshot!!
            assertTrue(actual.detailsCurrent)
            assertEquals(listOf("Page first", "Page second"), actual.points.filter { it.subjectId == 1L }.map { it.name })
            assertEquals(1, source.subjectCalls)
            assertEquals(1, source.maximumConcurrentRequests)
        }
    }

    @Test fun apiCapturedBeforeNewGenerationCannotWriteAfterIndexCommitAcrossTwentyRounds() = runTest {
        repeat(20) {
            val initial = indexWithOtherSubjectVerified()
            val source = ControlledSource().apply {
                indexDocument = consistencyIndex(modified = 200)
                firstIndexGate = Gate()
                firstPageGate = Gate()
                firstSubjectGate = Gate()
            }
            val repository = repository(source, MemoryDiscoveryCache(initial))
            repository.initialize()
            val update = repository.refresh(force = true)!!
            source.firstIndexGate!!.started.await()
            val subject = async { repository.ensureSubjectDetails(1) }
            runCurrent()
            assertEquals(setOf(1L), repository.state.value.loadingSubjectIds)
            source.firstIndexGate!!.release.complete(Unit)
            source.firstSubjectGate!!.started.await()
            repository.state.first { it.snapshot!!.version != initial.version }
            source.firstSubjectGate!!.release.complete(Unit)
            subject.await()
            source.firstPageGate!!.started.await()
            assertTrue(repository.state.value.snapshot!!.points.none { it.name?.startsWith("API") == true })
            source.firstPageGate!!.release.complete(Unit)
            update.join()
            val actual = repository.state.value.snapshot!!
            assertTrue(actual.detailsCurrent)
            assertTrue(actual.points.none { it.name?.startsWith("API") == true })
            assertEquals(initial.points.map { it.coordinate }, actual.points.map { it.coordinate })
        }
    }

    @Test fun unverifiedApiFolderCannotDiscardIndexMemberBeforePageValidation() = runTest {
        val initial = indexWithOtherSubjectVerified()
        val source = ControlledSource().apply {
            subjectDocuments = listOf(Json.parseToJsonElement("""[{"id":"first","isFolder":true}]"""))
        }
        val repository = repository(source, MemoryDiscoveryCache(initial))
        repository.initialize()
        repository.ensureSubjectDetails(1)
        assertEquals(initial.points.map { it.id }, repository.state.value.snapshot!!.points.map { it.id })
        repository.refresh(force = true)!!.join()
        assertEquals(fullyVerifiedIndex().points, repository.state.value.snapshot!!.points)
        assertTrue(repository.state.value.detailsCurrent)
    }

    @Test fun sameGenerationRefreshRepairsLoadedPageWithDowngradedPointVersion() = runTest {
        val verified = fullyVerifiedIndex()
        val poisoned = verified.copy(points = verified.points.mapIndexed { index, point ->
            if (index == 0) point.copy(name = "Legacy API", detailsVersion = "api:${verified.version}") else point
        })
        assertLegacyCacheRecovers(poisoned, verified)
    }

    @Test fun sameGenerationRefreshRestoresIndexMemberRemovedByLegacyApiFolder() = runTest {
        val verified = fullyVerifiedIndex()
        val removedId = verified.points.first().id
        val poisoned = verified.copy(
            points = verified.points.filterNot { it.id == removedId },
            subjects = verified.subjects.map { it.copy(pointIds = it.pointIds.filterNot { id -> id == removedId }) },
        )
        assertLegacyCacheRecovers(poisoned, verified)
    }

    private suspend fun TestScope.assertLegacyCacheRecovers(poisoned: DiscoverySnapshot, verified: DiscoverySnapshot) {
        val directory = temporary.newFolder()
        val storage = File(directory, "discovery").apply { mkdirs() }
        // Write a historical payload directly: future validation must not make this fixture self-healing.
        File(storage, "current.json").writeText(Json.encodeToString(DiscoverySnapshot.serializer(), poisoned))
        val source = ControlledSource()
        val repository = repository(source, FileDiscoveryCache(directory))
        repository.initialize()
        repository.refresh(force = true)!!.join()
        val actual = repository.state.value.snapshot!!
        assertEquals("The suspicious page must be fetched again within the same generation", 1, source.pageCalls.count { it == 0 })
        assertEquals(verified.version, actual.version)
        assertEquals(verified.points, actual.points)
        assertEquals(verified.subjects, actual.subjects)
        assertTrue(actual.detailsCurrent)
        assertEquals(actual, FileDiscoveryCache(directory).read())
    }

    @Test fun emptySuccessfulSubjectResponseRemainsRetryableAndSecondCallCompletes() = runTest {
        assertIncompleteSubjectCanRetry(Json.parseToJsonElement("[]"), expectedFirstCount = 0)
    }

    @Test fun partialSuccessfulSubjectResponseRemainsRetryableAndPreservesExistingContent() = runTest {
        assertIncompleteSubjectCanRetry(apiDocument("first"), expectedFirstCount = 1)
    }

    private suspend fun TestScope.assertIncompleteSubjectCanRetry(first: JsonElement, expectedFirstCount: Int) {
        val initial = indexWithOtherSubjectVerified()
        val source = ControlledSource().apply { subjectDocuments = listOf(first, apiDocument("first", "second")) }
        val repository = repository(source, MemoryDiscoveryCache(initial))
        repository.initialize()
        repository.ensureSubjectDetails(1)
        val partial = repository.state.value.snapshot!!
        assertEquals(expectedFirstCount, partial.points.count { it.subjectId == 1L && it.detailsLoaded })
        assertEquals(initial.points.last(), partial.points.last())
        assertFalse(partial.detailsCurrent)
        repository.ensureSubjectDetails(1)
        assertEquals("HTTP success with missing indexed rows must not suppress an explicit retry", 2, source.subjectCalls)
        val completed = repository.state.value.snapshot!!
        assertTrue(completed.points.filter { it.subjectId == 1L }.all { it.detailsVersion == "api:${completed.version}" })
        assertEquals(initial.points.map { it.coordinate }, completed.points.map { it.coordinate })
        assertFalse(completed.detailsCurrent)
        repository.ensureSubjectDetails(1)
        assertEquals("A complete API response does not need another identical request", 2, source.subjectCalls)
    }

    @Test fun duplicateApiRowsFailWithoutPoisoningRetryState() = runTest {
        val source = ControlledSource().apply {
            subjectDocuments = listOf(apiDocument("first", "first"), apiDocument("first", "second"))
        }
        val initial = indexWithOtherSubjectVerified()
        val repository = repository(source, MemoryDiscoveryCache(initial))
        repository.initialize()
        repository.ensureSubjectDetails(1)
        assertEquals(DiscoveryError.INVALID_DATA, repository.state.value.error)
        assertEquals(initial, repository.state.value.snapshot)
        repository.ensureSubjectDetails(1)
        assertEquals(2, source.subjectCalls)
        assertTrue(repository.state.value.loadingSubjectIds.isEmpty())
    }

    @Test fun repeatedClicksDeduplicateAndCancellingOneWaiterPreservesOtherWaiter() = runTest {
        val source = ControlledSource().apply { firstSubjectGate = Gate() }
        val repository = repository(source, MemoryDiscoveryCache(indexWithOtherSubjectVerified()))
        repository.initialize()
        val first = async { repository.ensureSubjectDetails(1) }
        val second = async { repository.ensureSubjectDetails(1) }
        source.firstSubjectGate!!.started.await()
        runCurrent()
        first.cancelAndJoin()
        assertEquals(1, source.subjectCalls)
        source.firstSubjectGate!!.release.complete(Unit)
        second.await()
        assertEquals(1, source.subjectCalls)
        assertTrue(repository.state.value.snapshot!!.detailsComplete)
        assertTrue(repository.state.value.loadingSubjectIds.isEmpty())
    }

    @Test fun backgroundCancelsSubjectAndExplicitRetryResumesWithoutConcurrentRequests() = runTest {
        val source = ControlledSource().apply {
            firstSubjectGate = Gate()
            failIndex = true
        }
        val repository = repository(source, MemoryDiscoveryCache(indexWithOtherSubjectVerified()))
        repository.initialize()
        val first = async { repository.ensureSubjectDetails(1) }
        source.firstSubjectGate!!.started.await()
        repository.setForeground(false)
        first.join()
        assertTrue(first.isCancelled)
        assertTrue(repository.state.value.loadingSubjectIds.isEmpty())
        repository.ensureSubjectDetails(1)
        assertEquals(1, source.subjectCalls)
        repository.setForeground(true)
        repository.refresh()!!.join()
        repository.ensureSubjectDetails(1)
        assertEquals(2, source.subjectCalls)
        assertEquals(1, source.maximumConcurrentRequests)
        assertTrue(repository.state.value.snapshot!!.detailsComplete)
    }

    @Test fun differentSubjectsShareRateLimitedSerialQueue() = runTest {
        val source = ControlledSource().apply { firstSubjectGate = Gate() }
        val repository = DiscoveryRepository(
            source, MemoryDiscoveryCache(DiscoveryParser.index(consistencyIndex())), this,
            now = { testScheduler.currentTime + 1_000 }, requestIntervalMillis = 1_000,
        )
        repository.initialize()
        val first = async { repository.ensureSubjectDetails(1) }
        source.firstSubjectGate!!.started.await()
        val second = async { repository.ensureSubjectDetails(2) }
        runCurrent()
        assertEquals(setOf(1L, 2L), repository.state.value.loadingSubjectIds)
        assertEquals(1, source.subjectCalls)
        source.firstSubjectGate!!.release.complete(Unit)
        first.await()
        second.await()
        assertEquals(2, source.subjectCalls)
        assertEquals(1, source.maximumConcurrentRequests)
        assertTrue(testScheduler.currentTime >= 1_000)
        assertTrue(repository.state.value.loadingSubjectIds.isEmpty())
    }

    @Test fun legacyCompletionFlagsDoNotSuppressMissingSubjectRows() = runTest {
        val initial = indexWithOtherSubjectVerified().copy(currentSubjectIds = setOf(1L, 2L))
        val source = ControlledSource()
        val repository = repository(source, MemoryDiscoveryCache(initial))
        repository.initialize()
        repository.ensureSubjectDetails(1)
        assertEquals(1, source.subjectCalls)
        assertTrue(repository.state.value.snapshot!!.points.all { it.detailsLoaded })
    }

    @Test fun legacyNullImageRefreshIsIndependentOfVerifiedDetailsAndLoadedPages() = runTest {
        val verified = fullyVerifiedIndex()
        val legacy = verified.copy(points = verified.points.map { point ->
            if (point.subjectId == 1L) point.copy(imageUrl = null, imageMetadataVersion = 0) else point
        })
        val directory = temporary.newFolder()
        FileDiscoveryCache(directory).write(legacy)
        val source = ControlledSource()
        val repository = repository(source, FileDiscoveryCache(directory))
        repository.initialize()
        assertTrue(repository.state.value.detailsCurrent)
        repository.ensureSubjectDetails(1)
        val restored = repository.state.value.snapshot!!
        assertEquals(1, source.subjectCalls)
        assertTrue(restored.detailsCurrent)
        assertEquals(verified.loadedPages, restored.loadedPages)
        assertEquals(verified.points.map { it.name }, restored.points.map { it.name })
        assertEquals(verified.points.map { it.coordinate }, restored.points.map { it.coordinate })
        assertTrue(restored.points.all { it.imageUrl != null })
        assertTrue(restored.points.all { it.detailsVersion == restored.version })
        assertTrue(restored.points.all { it.imageMetadataVersion == DISCOVERY_IMAGE_METADATA_VERSION })
        assertEquals(restored, FileDiscoveryCache(directory).read())
        repository.ensureSubjectDetails(1)
        assertEquals(1, source.subjectCalls)
    }

    @Test fun verifiedImageSurvivesLegacyMetadataCheckAndApiMissingImage() = runTest {
        val verified = fullyVerifiedIndex()
        val legacy = verified.copy(points = verified.points.map { it.copy(imageMetadataVersion = 0) })
        val source = ControlledSource().apply {
            subjectDocuments = listOf(Json.parseToJsonElement("""[{"id":"first"},{"id":"second"}]"""))
        }
        val repository = repository(source, MemoryDiscoveryCache(legacy))
        repository.initialize()
        repository.ensureSubjectDetails(1)
        val restored = repository.state.value.snapshot!!
        assertEquals(verified.points.map { it.imageUrl }, restored.points.map { it.imageUrl })
        assertEquals(verified.points.map { it.name }, restored.points.map { it.name })
        assertTrue(restored.detailsCurrent)
        assertTrue(restored.points.filter { it.subjectId == 1L }.all {
            it.imageMetadataVersion == DISCOVERY_IMAGE_METADATA_VERSION
        })
    }

    @Test fun newlyObservedImageAbsenceDoesNotRequestTheSameMetadataForever() = runTest {
        val source = ControlledSource().apply {
            subjectDocuments = listOf(Json.parseToJsonElement("""[{"id":"first"},{"id":"second"}]"""))
        }
        val repository = repository(source, MemoryDiscoveryCache(indexWithOtherSubjectVerified()))
        repository.initialize()
        repository.ensureSubjectDetails(1)
        repository.ensureSubjectDetails(1)
        assertEquals(1, source.subjectCalls)
        assertTrue(repository.state.value.snapshot!!.points.filter { it.subjectId == 1L }.all {
            it.imageUrl == null && it.imageMetadataVersion == DISCOVERY_IMAGE_METADATA_VERSION
        })
    }

    @Test fun cacheReadInvalidatesLoadedPageWhosePointWasDowngraded() {
        val verified = fullyVerifiedIndex()
        val poisoned = verified.copy(points = verified.points.mapIndexed { index, point ->
            if (index == 0) point.copy(detailsVersion = "api:${verified.version}") else point
        })
        val directory = temporary.newFolder()
        val storage = File(directory, "discovery").apply { mkdirs() }
        File(storage, "current.json").writeText(Json.encodeToString(DiscoverySnapshot.serializer(), poisoned))
        val restored = FileDiscoveryCache(directory).read()!!
        assertEquals(setOf(1), restored.loadedPages)
        assertFalse(restored.endVersionVerified)
        assertEquals(poisoned.points, restored.points)
    }

    @Test fun indexedSubjectWithoutPointsHasNothingToRetry() = runTest {
        val initial = DiscoveryParser.index(Json.parseToJsonElement(
            """[[[1,"Synthetic empty",0,0,0,0,0,0,0,0,0,0,[],0,0,0,0,0]],1,100]""",
        ))
        val source = ControlledSource()
        val repository = repository(source, MemoryDiscoveryCache(initial))
        repository.initialize()
        repository.ensureSubjectDetails(1)
        assertEquals(0, source.subjectCalls)
        assertEquals(initial, repository.state.value.snapshot)
    }

    private fun TestScope.repository(source: DiscoverySource, cache: DiscoveryCache) = DiscoveryRepository(
        source, cache, this, now = { testScheduler.currentTime + 1_000 }, requestIntervalMillis = 0,
    )

    private fun indexWithOtherSubjectVerified(): DiscoverySnapshot {
        val index = DiscoveryParser.index(consistencyIndex())
        return DiscoveryParser.merge(index, DiscoveryParser.page(consistencyPage(1))).copy(loadedPages = setOf(1))
    }

    private fun fullyVerifiedIndex(): DiscoverySnapshot {
        val initial = indexWithOtherSubjectVerified()
        return DiscoveryParser.merge(initial, DiscoveryParser.page(consistencyPage(0))).copy(
            loadedPages = setOf(0, 1), endVersionVerified = true, checkedAtMillis = 1_000,
        )
    }

    private class Gate {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        suspend fun await() {
            started.complete(Unit)
            release.await()
        }
    }

    private class ControlledSource : DiscoverySource {
        var indexDocument = consistencyIndex()
        var subjectDocuments = listOf(apiDocument("first", "second"))
        var firstIndexGate: Gate? = null
        var firstPageGate: Gate? = null
        var firstSubjectGate: Gate? = null
        var failIndex = false
        var indexCalls = 0
        var subjectCalls = 0
        val pageCalls = mutableListOf<Int>()
        var maximumConcurrentRequests = 0
        private var activeRequests = 0

        private suspend fun <T> measured(block: suspend () -> T): T {
            activeRequests++
            maximumConcurrentRequests = maxOf(maximumConcurrentRequests, activeRequests)
            return try { block() } finally { activeRequests-- }
        }

        override suspend fun index(cacheToken: String): JsonElement = measured {
            indexCalls++
            if (indexCalls == 1) firstIndexGate?.await()
            if (failIndex) throw IOException("Synthetic index failure")
            indexDocument
        }

        override suspend fun page(page: Int, cacheToken: String): JsonElement = measured {
            pageCalls += page
            if (pageCalls.size == 1) firstPageGate?.await()
            consistencyPage(page)
        }

        override suspend fun subject(subjectId: Long): JsonElement = measured {
            subjectCalls++
            if (subjectCalls == 1) firstSubjectGate?.await()
            if (subjectId == 2L) apiDocument("other") else subjectDocuments[minOf(subjectCalls - 1, subjectDocuments.lastIndex)]
        }
    }

    companion object {
        private fun consistencyIndex(modified: Long = 100): JsonElement = Json.parseToJsonElement(
            """[[[1,"Synthetic one",0,0,0,0,0,0,0,0,0,0,["first",1.125,2.25,0,"second",1.25,2.5,0],0,0,0,0,0],[2,"Synthetic two",0,0,0,0,0,0,0,0,0,0,["other",1.5,2.75,0],0,0,0,0,0]],1,$modified]""",
        )

        private fun consistencyPage(page: Int): JsonElement {
            val ids = if (page == 0) listOf("first", "second") else listOf("other")
            val rows = ids.joinToString(",") {
                """["$it","Page $it",0,0,0,0,"/points/synthetic-page.jpg",0,0,0,"Page detail",0,0,0,0]"""
            }
            return Json.parseToJsonElement("[[${page + 1},[],[$rows],100]]")
        }

        private fun apiDocument(vararg ids: String): JsonElement = Json.parseToJsonElement(ids.joinToString(",", "[", "]") {
            """{"id":"$it","name":"API $it","image":"/points/synthetic-api.jpg","geo":[9,9]}"""
        })
    }
}
