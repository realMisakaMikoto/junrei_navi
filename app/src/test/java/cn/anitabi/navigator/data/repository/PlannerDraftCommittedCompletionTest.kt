package cn.anitabi.navigator.data.repository

import cn.anitabi.navigator.core.model.Anime
import cn.anitabi.navigator.core.model.EndPolicy
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.NavigationProgress
import cn.anitabi.navigator.core.model.NavigationState
import cn.anitabi.navigator.core.model.PilgrimagePoint
import cn.anitabi.navigator.core.model.PlannerDraft
import cn.anitabi.navigator.core.model.RouteObjective
import cn.anitabi.navigator.core.model.TourPlan
import cn.anitabi.navigator.core.model.TravelMode
import cn.anitabi.navigator.data.local.TourPlanDao
import cn.anitabi.navigator.data.local.TourPlanEntity
import cn.anitabi.navigator.data.network.ApiHttpClient
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlannerDraftCommittedCompletionTest {
    @Test fun runtimeTerminalCannotClearTheDraftWhileTheFormalCompletionWriteIsUncommitted() = runTest {
        val dao = CompletionTourDao()
        val tours = TourRepository(dao, ApiHttpClient.defaultJson)
        val plan = plan()
        val active = NavigationProgress(tourId = plan.id, state = NavigationState.NAVIGATING)
        tours.saveUnresolved(plan, active)
        val storage = CompletionDraftStorage()
        val drafts = PlannerDraftRepository(storage, backgroundScope, isTourComplete = { id ->
            tours.get(id)?.storedTour?.navigationState in setOf(NavigationState.COMPLETED, NavigationState.ENDED)
        })
        drafts.replace(draft(plan))
        drafts.flush()
        assertTrue(drafts.linkGeneratedTour("draft", drafts.state.value.revision, plan.id))
        drafts.flush()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        dao.beforeWrite = { entered.complete(Unit); release.await() }
        val terminal = active.copy(state = NavigationState.COMPLETED)
        val saving = async { tours.saveProgressOnLatestPlan(plan, active, terminal) }
        entered.await()
        // This is the existing AppShell terminal-progress call, before the Room write has committed.
        val completing = async { drafts.completeGeneratedTour(plan.id, terminal.state) }
        try {
            runCurrent()
            assertNotNull("Uncommitted runtime completion must not remove inputs", drafts.state.value.draft)
            assertNotNull(storage.envelope?.draft)
        } finally { release.complete(Unit) }
        saving.await()
        assertTrue(completing.await())
        assertNull(drafts.state.value.draft)
        assertNull(storage.envelope?.draft)
        assertEquals(NavigationState.COMPLETED, tours.get(plan.id)?.storedTour?.navigationState)
    }

    @Test fun failedFormalCompletionNeverRemovesTheLinkedDraftOrItsDurableInputs() = runTest {
        val dao = CompletionTourDao()
        val tours = TourRepository(dao, ApiHttpClient.defaultJson)
        val plan = plan()
        val active = NavigationProgress(tourId = plan.id, state = NavigationState.NAVIGATING)
        tours.saveUnresolved(plan, active)
        val storage = CompletionDraftStorage()
        val drafts = PlannerDraftRepository(storage, backgroundScope, isTourComplete = { id ->
            tours.get(id)?.storedTour?.navigationState in setOf(NavigationState.COMPLETED, NavigationState.ENDED)
        })
        drafts.replace(draft(plan))
        drafts.flush()
        drafts.linkGeneratedTour("draft", drafts.state.value.revision, plan.id)
        drafts.flush()
        val before = requireNotNull(storage.envelope)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        dao.beforeWrite = { entered.complete(Unit); release.await(); throw IOException("Synthetic failed completion") }
        val terminal = active.copy(state = NavigationState.COMPLETED)
        val saving = async { runCatching { tours.saveProgressOnLatestPlan(plan, active, terminal) } }
        entered.await()
        val completing = async { drafts.completeGeneratedTour(plan.id, terminal.state) }
        try {
            runCurrent()
            assertEquals(before.draft, drafts.state.value.draft)
        } finally { release.complete(Unit) }
        assertTrue(saving.await().exceptionOrNull() is IOException)
        assertFalse(completing.await())
        assertEquals(before, storage.envelope)
        assertEquals(before.draft, drafts.state.value.draft)
        assertEquals(NavigationState.NAVIGATING, tours.get(plan.id)?.storedTour?.navigationState)
    }

    @Test fun persistedChangeSignalWaitsForTheWholeWriteAndIgnoresFailedAndReadOnlyOperations() = runTest {
        val dao = CompletionTourDao()
        val tours = TourRepository(dao, ApiHttpClient.defaultJson)
        val plan = plan()
        val active = NavigationProgress(tourId = plan.id, state = NavigationState.NAVIGATING)
        assertEquals(0L, tours.persistedChanges.value)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        dao.beforeWrite = { entered.complete(Unit); release.await() }
        val saving = async { tours.saveUnresolved(plan, active) }
        entered.await()
        try { assertEquals(0L, tours.persistedChanges.value) } finally { release.complete(Unit) }
        saving.await()
        assertEquals(1L, tours.persistedChanges.value)
        tours.get(plan.id)
        tours.saveProgressOnLatestPlan(plan, active, active)
        assertEquals(1L, tours.persistedChanges.value)
        dao.beforeWrite = { throw IOException("Synthetic failed write") }
        assertTrue(runCatching { tours.saveUnresolved(plan, active.copy(state = NavigationState.COMPLETED)) }.isFailure)
        assertEquals(1L, tours.persistedChanges.value)
        assertEquals(NavigationState.NAVIGATING, tours.get(plan.id)?.storedTour?.navigationState)
    }

    @Test fun compensatedTerminalEditSignalsOnlyTheFinalPersistedNonterminalState() = runTest {
        val dao = CompletionTourDao()
        val tours = TourRepository(dao, ApiHttpClient.defaultJson)
        val plan = plan()
        val active = NavigationProgress(tourId = plan.id, state = NavigationState.NAVIGATING)
        tours.save(plan, active)
        tours.noteRuntimeProgress(active)
        val committed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var writes = 0
        dao.afterWrite = {
            if (++writes == 1) { committed.complete(Unit); release.await() }
        }
        val saving = async {
            tours.saveActiveEditIfCurrent(plan, active, plan.copy(dwellMinutes = 31), active.copy(state = NavigationState.COMPLETED))
        }
        committed.await()
        val advanced = active.copy(state = NavigationState.ARRIVING)
        try {
            assertEquals(1L, tours.persistedChanges.value)
            tours.noteRuntimeProgress(advanced)
        } finally { release.complete(Unit) }
        assertFalse(saving.await())
        assertEquals(2, writes)
        assertEquals(2L, tours.persistedChanges.value)
        val saved = requireNotNull(tours.get(plan.id))
        assertEquals(NavigationState.ARRIVING, saved.storedTour.navigationState)
        assertEquals(plan.dwellMinutes, saved.storedTour.dwellMinutes)
    }

    @Test fun newerInputsRelinkedToTheSameTourInvalidateAnOlderCompletionObservation() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val storage = CompletionDraftStorage()
        val drafts = PlannerDraftRepository(storage, backgroundScope, isTourComplete = {
            entered.complete(Unit); release.await(); true
        })
        val plan = plan()
        drafts.replace(draft(plan))
        drafts.flush()
        drafts.linkGeneratedTour("draft", drafts.state.value.revision, plan.id)
        drafts.flush()
        val completing = async { drafts.completeGeneratedTour(plan.id, NavigationState.COMPLETED) }
        entered.await()
        drafts.update("draft") { it.copy(dwellMinutesInput = "31") }
        drafts.linkGeneratedTour("draft", drafts.state.value.revision, plan.id)
        release.complete(Unit)
        assertFalse(completing.await())
        assertEquals("31", drafts.state.value.draft?.dwellMinutesInput)
        assertEquals(plan.id, drafts.state.value.draft?.generatedTourId)
        drafts.flush()
        assertEquals(drafts.state.value.draft, storage.envelope?.draft)
    }

    @Test fun imageSupplementDuringPersistedCompletionLookupStillWritesTheDurableTombstone() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val storage = CompletionDraftStorage()
        val drafts = PlannerDraftRepository(storage, backgroundScope, isTourComplete = {
            entered.complete(Unit); release.await(); true
        })
        val plan = plan()
        drafts.replace(draft(plan))
        drafts.flush()
        drafts.linkGeneratedTour("draft", drafts.state.value.revision, plan.id)
        drafts.flush()
        val before = requireNotNull(drafts.state.value.draft)
        val completing = async { drafts.completeGeneratedTour(plan.id, NavigationState.COMPLETED) }
        entered.await()
        assertTrue(drafts.supplementPointImages("draft", mapOf("first" to "https://image.anitabi.cn/points/test-only.png")))
        val supplemented = requireNotNull(drafts.state.value.draft)
        assertEquals(before.copy(selectedPoints = before.selectedPoints.map { point ->
            if (point.id == "first") point.copy(imageUrl = "https://image.anitabi.cn/points/test-only.png") else point
        }), supplemented)
        release.complete(Unit)

        assertTrue(completing.await())
        assertNull(drafts.state.value.draft)
        assertNull(storage.envelope?.draft)
        assertNull(PlannerDraftRepository(storage, backgroundScope).awaitLoaded().draft)
    }

    @Test fun unavailableAndTimedOutPersistedStatusKeepTheDurableDraft() = runTest {
        for (timeout in listOf(false, true)) {
            val storage = CompletionDraftStorage()
            val drafts = PlannerDraftRepository(storage, backgroundScope, isTourComplete = {
                if (timeout) awaitCancellation() else throw IOException("Synthetic read failure")
            })
            val plan = plan()
            drafts.replace(draft(plan))
            drafts.flush()
            drafts.linkGeneratedTour("draft", drafts.state.value.revision, plan.id)
            drafts.flush()
            val before = storage.envelope
            assertFalse(drafts.completeGeneratedTour(plan.id, NavigationState.COMPLETED))
            assertEquals(before, storage.envelope)
            assertEquals(before?.draft, drafts.state.value.draft)
        }
    }

    private fun plan(): TourPlan {
        val points = listOf(
            PilgrimagePoint("first", "TEST_ONLY_FIRST", GeoPoint(1.0, 2.0)),
            PilgrimagePoint("second", "TEST_ONLY_SECOND", GeoPoint(1.1, 2.1)),
        )
        return TourPlan(
            id = "TEST_ONLY_COMPLETION", anime = Anime(1, "TEST_ONLY"), selectedPoints = points, orderedPoints = points,
            legs = emptyList(), mode = TravelMode.WALK, objective = RouteObjective.FASTEST, endPolicy = EndPolicy.OPEN,
            estimatedDurationSeconds = 0.0, attribution = emptyList(), initialStart = points.first().coordinate,
        )
    }

    private fun draft(plan: TourPlan) = PlannerDraft(
        draftId = "draft", selectedAnimes = listOf(plan.anime), displayAnime = plan.anime, selectedPoints = plan.selectedPoints,
        transitDate = "2026-09-24", transitTime = "10:00", transitZoneId = "UTC",
    )
}

private class CompletionDraftStorage : PlannerDraftStorage {
    var envelope: PlannerDraftEnvelope? = null
    override suspend fun read(): PlannerDraftRead = envelope?.let { PlannerDraftRead.Loaded(it) }
        ?: PlannerDraftRead.Unavailable(PlannerDraftProblem.MISSING)
    override suspend fun write(envelope: PlannerDraftEnvelope) { this.envelope = envelope }
}

private class CompletionTourDao : TourPlanDao {
    val entities = linkedMapOf<String, TourPlanEntity>()
    var beforeWrite: suspend () -> Unit = {}
    var afterWrite: suspend () -> Unit = {}
    override suspend fun get(id: String): TourPlanEntity? = entities[id]
    override suspend fun getMostRecent(): TourPlanEntity? = entities.values.lastOrNull()
    override suspend fun getIdsMostRecentFirst(): List<String> = entities.keys.toList().asReversed()
    override suspend fun upsert(entity: TourPlanEntity) { beforeWrite(); entities[entity.id] = entity; afterWrite() }
    override suspend fun finishLegacyMigration(id: String, storedTourJson: String, updatedAtEpochMillis: Long) = Unit
    override suspend fun recordMigrationError(id: String, message: String) = Unit
    override suspend fun getMostRecentMigrationError(): String? = null
}
