package cn.anitabi.navigator.data.repository

import cn.anitabi.navigator.core.model.Anime
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.PilgrimagePoint
import cn.anitabi.navigator.core.model.PlannerDraft
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    private fun fileStorage() = FilePlannerDraftStorage(temporary.root, dispatcher)
    private fun repository(storage: PlannerDraftStorage): PlannerDraftRepository {
        val scope = CoroutineScope(SupervisorJob() + dispatcher).also(scopes::add)
        return PlannerDraftRepository(storage, scope)
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
