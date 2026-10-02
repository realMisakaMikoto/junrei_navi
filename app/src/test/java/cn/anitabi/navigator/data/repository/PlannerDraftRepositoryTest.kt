package cn.anitabi.navigator.data.repository

import cn.anitabi.navigator.core.model.Anime
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.PilgrimagePoint
import cn.anitabi.navigator.core.model.PlannerDraft
import cn.anitabi.navigator.core.model.NavigationState
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class PlannerDraftRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val scopes = mutableListOf<CoroutineScope>()

    @After fun tearDown() { scopes.forEach { it.cancel() } }

    @Test fun `atomic file round trip preserves large snapshots order images and zone without route fields`() = runTest(dispatcher) {
        val storage = fileStorage()
        val repo = repository(storage)
        val original = draft(count = 10_000).let { it.copy(manualOrderPointIds = it.manualOrderPointIds.reversed()) }
        repo.replace(original)
        assertTrue(repo.flush(original.draftId))

        val restored = repository(storage).awaitLoaded()
        assertEquals(original, restored.draft)
        val encoded = File(temporary.root, "current.json").readText()
        listOf("legs", "geometry", "steps", "estimatedDurationSeconds", "navigationState").forEach {
            assertFalse(encoded.contains("\"$it\""))
        }
        assertFalse(File(temporary.root, "current.tmp").exists())
    }

    @Test fun `clear writes tombstone and stray interrupted temporary file cannot resurrect draft`() = runTest(dispatcher) {
        val storage = fileStorage()
        val repo = repository(storage)
        repo.replace(draft())
        assertTrue(repo.flush())
        val previousBytes = File(temporary.root, "current.json").readBytes()
        repo.clear()
        assertTrue(repo.flush())
        File(temporary.root, "current.tmp").writeBytes(previousBytes)
        val restored = repository(storage).awaitLoaded()
        assertNull(restored.draft)
        assertNull(restored.problem)
        assertTrue(restored.revision > 0)
    }

    @Test fun `missing corrupt and unsupported schema are distinct actionable states`() = runTest(dispatcher) {
        val storage = fileStorage()
        assertEquals(PlannerDraftRead.Unavailable(PlannerDraftProblem.MISSING), storage.read())
        File(temporary.root, "current.json").writeText("{broken")
        assertEquals(PlannerDraftRead.Unavailable(PlannerDraftProblem.CORRUPT), storage.read())
        File(temporary.root, "current.json").writeText("{\"schemaVersion\":99,\"revision\":1,\"draft\":null}")
        assertEquals(PlannerDraftRead.Unavailable(PlannerDraftProblem.UNSUPPORTED), storage.read())
    }

    @Test fun `valid JSON with point ownership inconsistent with its subjects is corrupt`() = runTest(dispatcher) {
        val storage = fileStorage()
        storage.write(PlannerDraftEnvelope(revision = 1, draft = draft()))
        val path = File(temporary.root, "current.json")
        path.writeText(path.readText().replace("1::point_1", "2::point_1"))
        assertEquals(PlannerDraftRead.Unavailable(PlannerDraftProblem.CORRUPT), storage.read())
        storage.write(PlannerDraftEnvelope(revision = 2, draft = draft().copy(selectedAnimes = listOf(Anime(1, "TEST_ONLY"), Anime(2, "TEST_ONLY")))))
        path.writeText(path.readText().replace("1::point_1", "point_1"))
        assertEquals(PlannerDraftRead.Unavailable(PlannerDraftProblem.CORRUPT), storage.read())
    }

    @Test fun `new editing wins over an old read that completes late including its higher revision`() = runTest(dispatcher) {
        val storage = fileStorage()
        storage.write(PlannerDraftEnvelope(revision = 100, draft = draft("old")))
        val captured = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val delayed = object : PlannerDraftStorage {
            override suspend fun read(): PlannerDraftRead {
                val old = storage.read()
                captured.complete(Unit)
                release.await()
                return old
            }
            override suspend fun write(envelope: PlannerDraftEnvelope) = storage.write(envelope)
        }
        val repo = repository(delayed)
        captured.await()
        repo.replace(draft("new"))
        release.complete(Unit)
        assertTrue(repo.flush("new"))
        assertEquals("new", repository(storage).awaitLoaded().draft?.draftId)
        assertTrue(repo.state.value.revision > 100)
    }

    @Test fun `clear during delayed old read remains cleared after durable recovery`() = runTest(dispatcher) {
        val release = CompletableDeferred<Unit>()
        val storage = fileStorage()
        storage.write(PlannerDraftEnvelope(revision = 8, draft = draft()))
        val delayed = object : PlannerDraftStorage {
            override suspend fun read(): PlannerDraftRead {
                val old = storage.read()
                release.await()
                return old
            }
            override suspend fun write(envelope: PlannerDraftEnvelope) = storage.write(envelope)
        }
        val repo = repository(delayed)
        runCurrent()
        repo.clear()
        release.complete(Unit)
        assertTrue(repo.flush())
        assertNull(repository(storage).awaitLoaded().draft)
    }

    @Test fun `fixed checkpoint window coalesces edits but continuous editing still reaches disk`() = runTest(dispatcher) {
        val writes = mutableListOf<PlannerDraftEnvelope>()
        val storage = fileStorage()
        val recording = object : PlannerDraftStorage {
            override suspend fun read() = storage.read()
            override suspend fun write(envelope: PlannerDraftEnvelope) {
                storage.write(envelope)
                writes += envelope
            }
        }
        val repo = repository(recording)
        repo.replace(draft())
        runCurrent()
        repeat(10) { index ->
            repo.update("draft") { it.copy(dwellMinutesInput = index.toString()) }
            advanceTimeBy(100)
            runCurrent()
        }
        assertTrue(writes.size in 2..5)
        assertTrue(writes.zipWithNext().all { (a, b) -> a.revision < b.revision })
        assertTrue(repo.flush("draft"))
        assertEquals("9", repository(storage).awaitLoaded().draft?.dwellMinutesInput)
    }

    @Test fun `flush waits for a newer edit while a prior disk write is in flight`() = runTest(dispatcher) {
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val storage = fileStorage()
        var first = true
        val delayed = object : PlannerDraftStorage {
            override suspend fun read() = storage.read()
            override suspend fun write(envelope: PlannerDraftEnvelope) {
                if (first) { first = false; started.complete(Unit); release.await() }
                storage.write(envelope)
            }
        }
        val repo = repository(delayed)
        repo.replace(draft())
        var completed = false
        val flush = launch { completed = repo.flush("draft") }
        started.await()
        repo.update("draft") { it.copy(dwellMinutesInput = "33") }
        assertFalse(completed)
        release.complete(Unit)
        flush.join()
        assertTrue(completed)
        assertEquals("33", repository(storage).awaitLoaded().draft?.dwellMinutesInput)
    }

    @Test fun `failed flush retains memory and reports failure then explicit retry persists`() = runTest(dispatcher) {
        val storage = fileStorage()
        var fail = true
        val failing = object : PlannerDraftStorage {
            override suspend fun read() = storage.read()
            override suspend fun write(envelope: PlannerDraftEnvelope) {
                if (fail) throw IOException("TEST_ONLY")
                storage.write(envelope)
            }
        }
        val repo = repository(failing)
        repo.replace(draft())
        assertFalse(repo.flush("draft"))
        assertEquals(PlannerDraftProblem.WRITE_FAILED, repo.state.value.problem)
        assertEquals("draft", repo.state.value.draft?.draftId)
        fail = false
        assertTrue(repo.flush("draft"))
        assertNull(repo.state.value.problem)
        assertEquals("draft", repository(storage).awaitLoaded().draft?.draftId)
        advanceUntilIdle()
    }

    @Test fun `formal tour binding is durable and only matching terminal progress clears its draft`() = runTest(dispatcher) {
        val storage = fileStorage()
        val repo = repository(storage) { it == "TEST_ONLY_TOUR" }
        repo.replace(draft())
        assertTrue(repo.flush())
        assertTrue(repo.linkGeneratedTour("draft", repo.state.value.revision, "TEST_ONLY_TOUR"))
        assertTrue(repo.flush())
        assertEquals("TEST_ONLY_TOUR", repository(storage).awaitLoaded().draft?.generatedTourId)
        assertFalse(repo.completeGeneratedTour("TEST_ONLY_TOUR", NavigationState.NAVIGATING))
        assertFalse(repo.completeGeneratedTour("TEST_ONLY_OTHER", NavigationState.COMPLETED))
        assertTrue(repo.completeGeneratedTour("TEST_ONLY_TOUR", NavigationState.COMPLETED))
        assertNull(repository(storage).awaitLoaded().draft)
    }

    @Test fun `input edit invalidates the saved generation and stale revision cannot attach it again`() = runTest(dispatcher) {
        val repo = repository(fileStorage())
        repo.replace(draft())
        repo.flush()
        val oldRevision = repo.state.value.revision
        repo.linkGeneratedTour("draft", oldRevision, "TEST_ONLY_TOUR")
        repo.update("draft") { it.copy(dwellMinutesInput = "41") }
        assertNull(repo.state.value.draft?.generatedTourId)
        assertFalse(repo.linkGeneratedTour("draft", oldRevision, "TEST_ONLY_TOUR"))
        assertFalse(repo.completeGeneratedTour("TEST_ONLY_TOUR", NavigationState.ENDED))
        assertEquals("41", repo.state.value.draft?.dwellMinutesInput)
        // Replacing selection snapshots also invalidates a link even if draftId stays unchanged.
        repo.linkGeneratedTour("draft", repo.state.value.revision, "TEST_ONLY_NEW_TOUR")
        val linked = requireNotNull(repo.state.value.draft)
        repo.replace(linked.copy(selectedPoints = linked.selectedPoints.map { it.copy(coordinate = GeoPoint(3.0, 4.0)) }))
        assertNull(repo.state.value.draft?.generatedTourId)
        assertFalse(repo.completeGeneratedTour("TEST_ONLY_NEW_TOUR", NavigationState.COMPLETED))
    }

    @Test fun `image only replacement retains durable formal generation association`() = runTest(dispatcher) {
        val storage = fileStorage()
        val repo = repository(storage)
        val original = draft().copy(selectedPoints = draft().selectedPoints.map { it.copy(imageUrl = null) })
        repo.replace(original)
        repo.flush()
        assertTrue(repo.linkGeneratedTour("draft", repo.state.value.revision, "TEST_ONLY_TOUR"))
        val linked = requireNotNull(repo.state.value.draft)
        val supplemented = linked.copy(selectedPoints = linked.selectedPoints.map {
            it.copy(imageUrl = "https://image.anitabi.cn/points/test-only.png?v=2")
        })

        repo.replace(supplemented)

        assertEquals(supplemented, repo.state.value.draft)
        assertTrue(repo.flush())
        assertEquals(supplemented, repository(storage).awaitLoaded().draft)
    }

    @Test fun `generation may link after image only revision without losing input ownership`() = runTest(dispatcher) {
        val repo = repository(fileStorage())
        val original = draft().copy(selectedPoints = draft().selectedPoints.map { it.copy(imageUrl = null) })
        repo.replace(original)
        repo.flush()
        val capturedRevision = repo.state.value.revision
        repo.replace(original.copy(selectedPoints = original.selectedPoints.map {
            it.copy(imageUrl = "https://image.anitabi.cn/points/test-only.png?v=2")
        }))

        assertTrue(repo.linkGeneratedTour("draft", capturedRevision, "TEST_ONLY_TOUR"))
        assertEquals("TEST_ONLY_TOUR", repo.state.value.draft?.generatedTourId)
    }

    @Test fun `narrow image supplement retains every input and existing valid reference durably`() = runTest(dispatcher) {
        val storage = fileStorage()
        val repo = repository(storage)
        val original = draft().let { value -> value.copy(
            selectedPoints = value.selectedPoints.mapIndexed { index, point ->
                if (index == 0) point.copy(imageUrl = null) else point
            },
            manualOrderPointIds = value.manualOrderPointIds.reversed(),
            dwellMinutesInput = "37",
        ) }
        repo.replace(original)
        repo.flush()
        assertTrue(repo.linkGeneratedTour("draft", repo.state.value.revision, "TEST_ONLY_TOUR"))
        val before = requireNotNull(repo.state.value.draft)
        val revision = repo.state.value.revision

        assertTrue(repo.supplementPointImages("draft", mapOf(
            "1::point_1" to "https://image.anitabi.cn/images/user/test-only/upload.png?v=2",
            "1::point_2" to "https://image.anitabi.cn/user/test-only/replacement.png",
            "1::absent" to "https://image.anitabi.cn/user/test-only/unselected.png",
        )))

        val expected = before.copy(selectedPoints = before.selectedPoints.mapIndexed { index, point ->
            if (index == 0) point.copy(imageUrl = "https://image.anitabi.cn/user/test-only/upload.png?v=2") else point
        })
        assertEquals(expected, repo.state.value.draft)
        assertTrue(repo.state.value.revision > revision)
        assertTrue(repo.flush())
        assertEquals(expected, repository(storage).awaitLoaded().draft)
    }

    @Test fun `image supplement rejects stale ownership and unsafe or unknown point references without publishing`() = runTest(dispatcher) {
        val repo = repository(fileStorage())
        val original = draft().copy(selectedPoints = draft().selectedPoints.map { it.copy(imageUrl = null) })
        repo.replace(original)
        repo.flush()
        val before = repo.state.value
        assertFalse(repo.supplementPointImages("old-draft", mapOf("1::point_1" to "https://image.anitabi.cn/points/test-only.png")))
        assertTrue(repo.supplementPointImages("draft", mapOf(
            "1::point_1" to "https://unapproved.invalid/test-only.png",
            "1::absent" to "https://image.anitabi.cn/points/test-only.png",
        )))
        assertEquals(before, repo.state.value)
        repo.clear()
        assertFalse(repo.supplementPointImages("draft", mapOf("1::point_1" to "https://image.anitabi.cn/points/test-only.png")))
        assertNull(repo.state.value.draft)
    }

    @Test fun `restoring the same inputs after an edit does not revive an old generation revision`() = runTest(dispatcher) {
        val repo = repository(fileStorage())
        val original = draft()
        repo.replace(original)
        repo.flush()
        val captured = repo.state.value.revision
        repo.update("draft") { it.copy(dwellMinutesInput = "41") }
        repo.replace(original)

        assertFalse(repo.linkGeneratedTour("draft", captured, "TEST_ONLY_OLD_TOUR"))
        assertFalse(repo.linkGeneratedTour("draft", repo.state.value.revision + 1, "TEST_ONLY_FUTURE_TOUR"))
        assertTrue(repo.linkGeneratedTour("draft", repo.state.value.revision, "TEST_ONLY_NEW_TOUR"))
        assertFalse(repo.linkGeneratedTour("draft", captured, "TEST_ONLY_OLD_TOUR"))
        assertEquals("TEST_ONLY_NEW_TOUR", repo.state.value.draft?.generatedTourId)
    }

    @Test fun `late startup revision reconciliation advances generation ownership barrier`() = runTest(dispatcher) {
        val storage = fileStorage()
        storage.write(PlannerDraftEnvelope(revision = 100, draft = draft("old")))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val delayed = object : PlannerDraftStorage {
            override suspend fun read(): PlannerDraftRead {
                val result = storage.read()
                started.complete(Unit)
                release.await()
                return result
            }
            override suspend fun write(envelope: PlannerDraftEnvelope) = storage.write(envelope)
        }
        val repo = repository(delayed)
        started.await()
        repo.replace(draft())
        val beforeReconciliation = repo.state.value.revision
        release.complete(Unit)
        repo.awaitLoaded()
        assertTrue(repo.state.value.revision > 100)
        assertFalse(repo.linkGeneratedTour("draft", beforeReconciliation, "TEST_ONLY_OLD_TOUR"))
        assertTrue(repo.linkGeneratedTour("draft", repo.state.value.revision, "TEST_ONLY_NEW_TOUR"))
    }

    @Test fun `saved replan source identity alone never grants permission to clear the draft`() = runTest(dispatcher) {
        val repo = repository(fileStorage())
        repo.replace(draft().copy(sourceTourId = "TEST_ONLY_OLD_TOUR"))
        repo.flush()
        assertFalse(repo.completeGeneratedTour("TEST_ONLY_OLD_TOUR", NavigationState.COMPLETED))
        assertEquals("draft", repo.state.value.draft?.draftId)
    }

    @Test fun `new draft created during completion fsync wins on disk`() = runTest(dispatcher) {
        val storage = fileStorage()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val delayed = object : PlannerDraftStorage {
            override suspend fun read() = storage.read()
            override suspend fun write(envelope: PlannerDraftEnvelope) {
                if (envelope.draft == null) { started.complete(Unit); release.await() }
                storage.write(envelope)
            }
        }
        val repo = repository(delayed) { it == "TEST_ONLY_TOUR" }
        repo.replace(draft())
        repo.flush()
        repo.linkGeneratedTour("draft", repo.state.value.revision, "TEST_ONLY_TOUR")
        repo.flush()
        val finish = launch { assertTrue(repo.completeGeneratedTour("TEST_ONLY_TOUR", NavigationState.ENDED)) }
        started.await()
        repo.replace(draft("new-draft"))
        release.complete(Unit)
        finish.join()
        assertEquals("new-draft", repository(storage).awaitLoaded().draft?.draftId)
    }

    @Test fun `startup reconciles a completed linked tour before exposing restored inputs`() = runTest(dispatcher) {
        val storage = fileStorage()
        storage.write(PlannerDraftEnvelope(revision = 4, draft = draft().copy(generatedTourId = "TEST_ONLY_TOUR")))
        var lookups = 0
        val repo = repository(storage) { id -> lookups += 1; id == "TEST_ONLY_TOUR" }
        val loaded = repo.awaitLoaded()
        assertNull(loaded.draft)
        assertEquals(5L, loaded.revision)
        assertEquals(1, lookups)
        assertNull((storage.read() as PlannerDraftRead.Loaded).envelope.draft)
    }

    @Test fun `late startup completion lookup cannot clear new editing`() = runTest(dispatcher) {
        val storage = fileStorage()
        storage.write(PlannerDraftEnvelope(revision = 9, draft = draft().copy(generatedTourId = "TEST_ONLY_TOUR")))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repo = repository(storage) { started.complete(Unit); release.await(); true }
        started.await()
        repo.replace(draft("new-draft"))
        release.complete(Unit)
        assertTrue(repo.flush("new-draft"))
        assertEquals("new-draft", repository(storage).awaitLoaded().draft?.draftId)
    }

    @Test fun `missing or unavailable completion status preserves the linked draft and lookup is bounded`() = runTest(dispatcher) {
        val storage = fileStorage()
        val linked = draft().copy(generatedTourId = "TEST_ONLY_TOUR")
        storage.write(PlannerDraftEnvelope(revision = 2, draft = linked))
        assertEquals(linked, repository(storage) { false }.awaitLoaded().draft)
        assertEquals(linked, repository(storage) { throw IOException("TEST_ONLY") }.awaitLoaded().draft)
        assertEquals(linked, repository(storage) { awaitCancellation() }.awaitLoaded().draft)
        assertEquals(linked, (storage.read() as PlannerDraftRead.Loaded).envelope.draft)
    }

    @Test fun `failed completion write can retry its tombstone without resurrecting inputs`() = runTest(dispatcher) {
        val storage = fileStorage()
        var failCompletion = true
        val failing = object : PlannerDraftStorage {
            override suspend fun read() = storage.read()
            override suspend fun write(envelope: PlannerDraftEnvelope) {
                if (envelope.draft == null && failCompletion) throw IOException("TEST_ONLY")
                storage.write(envelope)
            }
        }
        val repo = repository(failing) { it == "TEST_ONLY_TOUR" }
        repo.replace(draft())
        repo.flush()
        repo.linkGeneratedTour("draft", repo.state.value.revision, "TEST_ONLY_TOUR")
        repo.flush()
        assertFalse(repo.completeGeneratedTour("TEST_ONLY_TOUR", NavigationState.COMPLETED))
        assertEquals(PlannerDraftProblem.WRITE_FAILED, repo.state.value.problem)
        assertNull(repo.state.value.draft)
        failCompletion = false
        assertTrue(repo.completeGeneratedTour("TEST_ONLY_TOUR", NavigationState.COMPLETED))
        assertNull(repository(storage).awaitLoaded().draft)
    }

    private fun fileStorage() = FilePlannerDraftStorage(temporary.root, dispatcher)
    private fun repository(storage: PlannerDraftStorage, isComplete: suspend (String) -> Boolean = { false }): PlannerDraftRepository {
        val scope = CoroutineScope(SupervisorJob() + dispatcher).also(scopes::add)
        return PlannerDraftRepository(storage, scope, isTourComplete = isComplete)
    }

    private fun draft(id: String = "draft", count: Int = 3): PlannerDraft {
        val anime = Anime(1, "TEST_ONLY")
        return PlannerDraft(
            draftId = id,
            selectedAnimes = listOf(anime),
            displayAnime = anime,
            selectedPoints = (1..count).map {
                PilgrimagePoint("1::point_$it", "TEST_ONLY_$it", GeoPoint(1.0, 2.0), "https://image.anitabi.cn/images/points/test.png?v=1")
            },
            transitDate = "2026-09-23",
            transitTime = "08:30",
            transitZoneId = "Asia/Tokyo",
        )
    }
}
