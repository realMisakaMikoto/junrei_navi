package cn.anitabi.navigator.data.repository

import cn.anitabi.navigator.core.model.Anime
import cn.anitabi.navigator.core.model.CoordinateSystem
import cn.anitabi.navigator.core.model.EndPolicy
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.NavigationProgress
import cn.anitabi.navigator.core.model.NavigationState
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.core.model.PilgrimagePoint
import cn.anitabi.navigator.core.model.RouteObjective
import cn.anitabi.navigator.core.model.StoredTourV2
import cn.anitabi.navigator.core.model.TourLeg
import cn.anitabi.navigator.core.model.TourPlan
import cn.anitabi.navigator.core.model.TransitExecutionStrategy
import cn.anitabi.navigator.core.model.TerritoryRegion
import cn.anitabi.navigator.core.model.TravelMode
import cn.anitabi.navigator.core.routing.EXTERNAL_AMAP_SOURCE
import cn.anitabi.navigator.core.routing.isAmapExternalFallback
import cn.anitabi.navigator.data.local.TourPlanDao
import cn.anitabi.navigator.data.local.TourPlanEntity
import cn.anitabi.navigator.data.network.ApiHttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TourRepositoryTest {
    @Test
    fun `resolved tour preserves exact progress in the same process`() = runBlocking {
        val repository = TourRepository(FakeTourPlanDao(), ApiHttpClient.defaultJson, now = { 123L })
        val resolved = fixturePlan()
        val exactProgress = NavigationProgress(
            tourId = resolved.id,
            legIndex = 3,
            stepIndex = 7,
            completedPointIds = setOf("first"),
            state = NavigationState.NAVIGATING,
        )

        repository.save(resolved, exactProgress)

        val restored = requireNotNull(repository.get(resolved.id))
        assertFalse(restored.routeNeedsRefresh)
        assertEquals(exactProgress, restored.progress)
    }

    @Test
    fun `explicit AMap fallback remains resolved only with empty WGS84 detail-free legs`() = runBlocking {
        val repository = mainlandRepository(FakeTourPlanDao())
        val fallback = amapFallbackPlan()

        repository.save(fallback)

        val restored = requireNotNull(repository.get(fallback.id))
        assertFalse(restored.routeNeedsRefresh)
        assertEquals(fallback, restored.plan)
        assertTrue(restored.plan.isAmapExternalFallback())
    }

    @Test
    fun `AMap WGS84 fallback marker never permits synthesized route detail`() = runBlocking {
        val repository = mainlandRepository(FakeTourPlanDao())
        val invalid = amapFallbackPlan().let { fallback ->
            fallback.copy(
                legs = fallback.legs.map { leg -> leg.copy(distanceMeters = 1.0) },
            )
        }

        repository.save(invalid)

        val restored = requireNotNull(repository.get(invalid.id))
        assertTrue(restored.routeNeedsRefresh)
        assertTrue(restored.plan.legs.isEmpty())
    }

    @Test
    fun `normal AMap route remains resolved only as GCJ02`() = runBlocking {
        val acceptedRepository = mainlandRepository(FakeTourPlanDao())
        val routed = amapRoutedPlan()
        acceptedRepository.save(routed)
        assertFalse(requireNotNull(acceptedRepository.get(routed.id)).routeNeedsRefresh)

        val invalidRepository = mainlandRepository(FakeTourPlanDao())
        val mislabeled = routed.copy(
            coordinateSystem = CoordinateSystem.WGS84,
            legs = routed.legs.map { it.copy(coordinateSystem = CoordinateSystem.WGS84) },
        )
        invalidRepository.save(mislabeled)

        val restored = requireNotNull(invalidRepository.get(mislabeled.id))
        assertTrue(restored.routeNeedsRefresh)
        assertTrue(restored.plan.legs.isEmpty())
    }

    @Test
    fun `cold reload keeps normal AMap route distinct from explicit fallback`() = runBlocking {
        val routedDao = FakeTourPlanDao()
        val routed = amapRoutedPlan()
        mainlandRepository(routedDao).save(routed)
        val coldRouted = requireNotNull(mainlandRepository(routedDao).get(routed.id))

        val fallbackDao = FakeTourPlanDao()
        val fallback = amapFallbackPlan()
        mainlandRepository(fallbackDao).save(fallback)
        val coldFallback = requireNotNull(mainlandRepository(fallbackDao).get(fallback.id))

        assertEquals(CoordinateSystem.GCJ02, routed.coordinateSystem)
        assertFalse(routed.externalRouteFallback)
        assertFalse(coldRouted.plan.isAmapExternalFallback())
        assertTrue(coldRouted.routeNeedsRefresh)
        assertTrue(fallback.externalRouteFallback)
        assertTrue(coldFallback.plan.isAmapExternalFallback())
        assertTrue(coldFallback.routeNeedsRefresh)
        assertEquals(CoordinateSystem.WGS84, coldFallback.plan.coordinateSystem)
    }

    @Test
    fun `saving unresolved tour evicts resolved route and exact progress`() = runBlocking {
        val dao = FakeTourPlanDao()
        val repository = TourRepository(dao, ApiHttpClient.defaultJson, now = { 123L })
        val resolved = fixturePlan()
        repository.save(
            resolved,
            NavigationProgress(
                tourId = resolved.id,
                legIndex = 3,
                stepIndex = 7,
                state = NavigationState.NAVIGATING,
            ),
        )
        assertFalse(requireNotNull(repository.get(resolved.id)).routeNeedsRefresh)

        val unresolved = resolved.copy(legs = emptyList(), estimatedDurationSeconds = 0.0)
        repository.saveUnresolved(
            unresolved,
            NavigationProgress(
                tourId = resolved.id,
                completedPointIds = setOf("first"),
                state = NavigationState.PLANNED,
            ),
        )

        val restored = requireNotNull(repository.get(resolved.id))
        assertTrue(restored.routeNeedsRefresh)
        assertTrue(restored.plan.legs.isEmpty())
        assertEquals(setOf("first"), restored.progress?.completedPointIds)
        assertEquals(NavigationState.PLANNED, restored.progress?.state)
        assertEquals(0, restored.progress?.legIndex)
        assertEquals(0, restored.progress?.stepIndex)
    }

    @Test
    fun `cold unresolved rollback accepts deterministic start normalization`() = runBlocking {
        val dao = FakeTourPlanDao()
        val plan = fixturePlan()
        val persistedProgress = NavigationProgress(
            tourId = plan.id,
            state = NavigationState.NAVIGATING,
        )
        TourRepository(dao, ApiHttpClient.defaultJson, now = { 123L }).save(plan, persistedProgress)
        val repository = TourRepository(dao, ApiHttpClient.defaultJson, now = { 123L })
        val recovered = requireNotNull(repository.get(plan.id))
        val activeProgress = requireNotNull(recovered.progress).copy(
            completedPointIds = setOf("first"),
        )
        val rollback = activeProgress.copy(
            state = NavigationState.PLANNED,
        )

        repository.saveUnresolvedProgressOnLatestPlan(
            basePlan = recovered.plan,
            expectedProgress = activeProgress,
            updatedProgress = rollback,
        )

        val restored = requireNotNull(repository.get(plan.id))
        assertTrue(restored.routeNeedsRefresh)
        assertTrue(restored.plan.legs.isEmpty())
        assertEquals(rollback, restored.progress)
    }

    @Test
    fun `warm progress save accepts deterministic start normalization`() = runBlocking {
        val repository = TourRepository(FakeTourPlanDao(), ApiHttpClient.defaultJson, now = { 123L })
        val plan = fixturePlan()
        val persistedProgress = NavigationProgress(
            tourId = plan.id,
            state = NavigationState.NAVIGATING,
        )
        val activeProgress = persistedProgress.copy(completedPointIds = setOf("first"))
        val arrivingProgress = activeProgress.copy(state = NavigationState.ARRIVING)
        repository.save(plan, persistedProgress)
        repository.noteRuntimeProgress(arrivingProgress)

        repository.saveProgressOnLatestPlan(
            basePlan = plan,
            expectedProgress = activeProgress,
            updatedProgress = arrivingProgress,
        )

        assertEquals(arrivingProgress, requireNotNull(repository.get(plan.id)).progress)
    }

    @Test
    fun `warm progress save still rejects a missing non-start completion`() = runBlocking {
        val repository = TourRepository(FakeTourPlanDao(), ApiHttpClient.defaultJson, now = { 123L })
        val plan = fixturePlan()
        val persistedProgress = NavigationProgress(
            tourId = plan.id,
            state = NavigationState.NAVIGATING,
        )
        val staleExpected = persistedProgress.copy(completedPointIds = setOf("second"))
        val failure = runCatching {
            repository.save(plan, persistedProgress)
            repository.saveProgressOnLatestPlan(
                basePlan = plan,
                expectedProgress = staleExpected,
                updatedProgress = staleExpected.copy(state = NavigationState.ARRIVING),
            )
        }.exceptionOrNull()

        assertTrue(failure is ConcurrentTourUpdateException)
        assertEquals(persistedProgress, requireNotNull(repository.get(plan.id)).progress)
    }

    @Test
    fun `cancellation after a repository write starts completes database and cache publication`() = runBlocking {
        val upsertGate = CompletableDeferred<Unit>()
        val dao = FakeTourPlanDao(upsertGate)
        val repository = TourRepository(dao, ApiHttpClient.defaultJson, now = { 123L })
        val resolved = fixturePlan()
        val progress = NavigationProgress(
            tourId = resolved.id,
            legIndex = 3,
            stepIndex = 7,
            state = NavigationState.NAVIGATING,
        )
        val saveJob = launch {
            repository.save(resolved, progress)
        }
        dao.upsertStarted.await()

        saveJob.cancel()
        assertFalse(saveJob.isCompleted)
        upsertGate.complete(Unit)
        saveJob.cancelAndJoin()

        val restored = requireNotNull(repository.get(resolved.id))
        assertFalse(restored.routeNeedsRefresh)
        assertEquals(resolved, restored.plan)
        assertEquals(progress, restored.progress)
    }

    @Test
    fun `writer cancelled while waiting for the repository mutex never reaches dao`() = runBlocking {
        val upsertGate = CompletableDeferred<Unit>()
        val dao = FakeTourPlanDao(upsertGate)
        val repository = TourRepository(dao, ApiHttpClient.defaultJson, now = { 123L })
        val plan = fixturePlan()
        val before = NavigationProgress(tourId = plan.id, state = NavigationState.NAVIGATING)
        val firstSave = launch { repository.save(plan, before) }
        dao.upsertStarted.await()
        val waitingSave = launch {
            repository.save(
                plan.copy(attribution = listOf("cancelled")),
                before.copy(legIndex = 1),
            )
        }

        waitingSave.cancelAndJoin()
        upsertGate.complete(Unit)
        firstSave.join()

        assertEquals(1, dao.upsertCount)
        val restored = requireNotNull(repository.get(plan.id))
        assertEquals(plan, restored.plan)
        assertEquals(before, restored.progress)
    }

    @Test
    fun `cancellation after dao commit cannot leave stale resolved caches`() = runBlocking {
        val dao = FakeTourPlanDao()
        val repository = TourRepository(dao, ApiHttpClient.defaultJson, now = { 123L })
        val plan = fixturePlan()
        val before = NavigationProgress(tourId = plan.id, state = NavigationState.NAVIGATING)
        val advanced = before.copy(legIndex = 1, completedPointIds = setOf("first"))
        repository.save(plan, before)
        val gate = dao.blockNextUpsertAfterCommit()

        val saveJob = launch { repository.save(plan, advanced) }
        gate.committed.await()
        saveJob.cancel()
        gate.release.complete(Unit)
        saveJob.cancelAndJoin()

        val restored = requireNotNull(repository.get(plan.id))
        assertFalse(restored.routeNeedsRefresh)
        assertEquals(plan, restored.plan)
        assertEquals(advanced, restored.progress)
    }

    @Test
    fun `dao failure after durable write evicts stale warm caches`() = runBlocking {
        val dao = FakeTourPlanDao()
        val repository = TourRepository(dao, ApiHttpClient.defaultJson, now = { 123L })
        val plan = fixturePlan()
        val before = NavigationProgress(tourId = plan.id, state = NavigationState.NAVIGATING)
        val advanced = before.copy(legIndex = 1, completedPointIds = setOf("first"))
        repository.save(plan, before)
        val gate = dao.blockNextUpsertAfterCommit()
        var failure: Throwable? = null

        val saveJob = launch {
            failure = runCatching { repository.save(plan, advanced) }.exceptionOrNull()
        }
        gate.committed.await()
        gate.release.completeExceptionally(IllegalStateException("post-commit failure"))
        saveJob.join()

        assertTrue(failure is IllegalStateException)
        val restored = requireNotNull(repository.get(plan.id))
        assertTrue(restored.routeNeedsRefresh)
        assertTrue(restored.plan.legs.isEmpty())
        assertEquals(advanced, restored.progress)
    }

    @Test
    fun `runtime progress advancing during active edit rolls back the edit and wins`() = runBlocking {
        val dao = FakeTourPlanDao()
        val repository = TourRepository(dao, ApiHttpClient.defaultJson, now = { 123L })
        val plan = fixturePlan()
        val edited = plan.copy(attribution = listOf("edited"))
        val before = NavigationProgress(tourId = plan.id, state = NavigationState.NAVIGATING)
        val advanced = before.copy(legIndex = 1, completedPointIds = setOf("first"))
        repository.save(plan, before)
        repository.noteRuntimeProgress(before)
        val gate = dao.blockNextUpsertAfterCommit()
        var editSaved: Boolean? = null

        val editJob = launch {
            editSaved = repository.saveActiveEditIfCurrent(
                expectedPlan = plan,
                expectedProgress = before,
                updatedPlan = edited,
                updatedProgress = before,
            )
        }
        gate.committed.await()
        repository.noteRuntimeProgress(advanced)
        gate.release.complete(Unit)
        editJob.join()

        assertFalse(requireNotNull(editSaved))
        val restored = requireNotNull(repository.get(plan.id))
        assertEquals(plan.attribution, restored.plan.attribution)
        assertEquals(advanced, restored.progress)
        assertEquals(plan, repository.saveProgressOnLatestPlan(plan, before, advanced))
    }

    @Test
    fun `active edit compare and save rejects a stale navigation progress`() = runBlocking {
        val repository = TourRepository(FakeTourPlanDao(), ApiHttpClient.defaultJson, now = { 123L })
        val plan = fixturePlan()
        val before = NavigationProgress(tourId = plan.id, state = NavigationState.NAVIGATING)
        val advanced = before.copy(legIndex = 1, completedPointIds = setOf("first"))
        repository.save(plan, before)
        repository.saveProgressOnLatestPlan(plan, before, advanced)

        val saved = repository.saveActiveEditIfCurrent(
            expectedPlan = plan,
            expectedProgress = before,
            updatedPlan = plan.copy(attribution = listOf("edited")),
            updatedProgress = before,
        )

        assertFalse(saved)
        val restored = requireNotNull(repository.get(plan.id))
        assertEquals(plan.attribution, restored.plan.attribution)
        assertEquals(advanced, restored.progress)
    }

    @Test
    fun `new runtime progress rolls back a future edit that committed first`() = runBlocking {
        val repository = TourRepository(FakeTourPlanDao(), ApiHttpClient.defaultJson, now = { 123L })
        val plan = fixturePlan()
        val edited = plan.copy(attribution = listOf("edited"))
        val before = NavigationProgress(tourId = plan.id, state = NavigationState.NAVIGATING)
        val advanced = before.copy(legIndex = 1, completedPointIds = setOf("first"))
        repository.save(plan, before)
        assertTrue(
            repository.saveActiveEditIfCurrent(
                expectedPlan = plan,
                expectedProgress = before,
                updatedPlan = edited,
                updatedProgress = before,
            ),
        )
        repository.noteRuntimeProgress(advanced)

        val committedPlan = repository.saveProgressOnLatestPlan(plan, before, advanced)

        assertEquals(plan, committedPlan)
        val restored = requireNotNull(repository.get(plan.id))
        assertEquals(plan.attribution, restored.plan.attribution)
        assertEquals(advanced, restored.progress)
    }

    @Test
    fun `latest runtime progress wins when the queued save is one transition behind`() = runBlocking {
        val repository = TourRepository(FakeTourPlanDao(), ApiHttpClient.defaultJson, now = { 123L })
        val plan = fixturePlan()
        val edited = plan.copy(attribution = listOf("edited"))
        val before = NavigationProgress(tourId = plan.id, state = NavigationState.NAVIGATING)
        val firstUpdate = before.copy(legIndex = 1, completedPointIds = setOf("first"))
        val latestUpdate = firstUpdate.copy(stepIndex = 1, state = NavigationState.ARRIVING)
        repository.save(plan, before)
        assertTrue(
            repository.saveActiveEditIfCurrent(
                expectedPlan = plan,
                expectedProgress = before,
                updatedPlan = edited,
                updatedProgress = before,
            ),
        )
        repository.noteRuntimeProgress(latestUpdate)

        assertEquals(plan, repository.saveProgressOnLatestPlan(plan, before, firstUpdate))
        assertEquals(plan, repository.saveProgressOnLatestPlan(plan, firstUpdate, latestUpdate))

        val restored = requireNotNull(repository.get(plan.id))
        assertEquals(plan.attribution, restored.plan.attribution)
        assertEquals(latestUpdate, restored.progress)
    }

    @Test
    fun `progress commit result reports a terminal runtime that supersedes the queued update`() = runBlocking {
        val repository = TourRepository(FakeTourPlanDao(), ApiHttpClient.defaultJson, now = { 123L })
        val plan = fixturePlan()
        val edited = plan.copy(attribution = listOf("edited"))
        val before = NavigationProgress(tourId = plan.id, state = NavigationState.NAVIGATING)
        val queuedUpdate = before.copy(legIndex = 1, completedPointIds = setOf("first"))
        val terminal = queuedUpdate.copy(state = NavigationState.COMPLETED)
        repository.save(plan, before)
        assertTrue(
            repository.saveActiveEditIfCurrent(
                expectedPlan = plan,
                expectedProgress = before,
                updatedPlan = edited,
                updatedProgress = before,
            ),
        )
        repository.noteRuntimeProgress(terminal)

        val committed = repository.saveProgressResultOnLatestPlan(plan, before, queuedUpdate)

        assertEquals(plan, committed.plan)
        assertEquals(terminal, committed.progress)
        val restored = requireNotNull(repository.get(plan.id))
        assertEquals(plan, restored.plan)
        assertEquals(terminal, restored.progress)
    }

    @Test
    fun `cold recovered active edit compares the persisted snapshot when resolved caches are empty`() = runBlocking {
        val dao = FakeTourPlanDao()
        val repository = TourRepository(dao, ApiHttpClient.defaultJson, now = { 123L })
        val plan = fixturePlan()
        val before = NavigationProgress(tourId = plan.id, state = NavigationState.NAVIGATING)
        val edited = plan.copy(attribution = listOf("edited after recovery"))
        dao.seed(storedEntity(plan, before))

        val saved = repository.saveActiveEditIfCurrent(
            expectedPlan = plan,
            expectedProgress = before,
            updatedPlan = edited,
            updatedProgress = before,
        )

        assertTrue(saved)
        val restored = requireNotNull(repository.get(plan.id))
        assertEquals(edited.attribution, restored.plan.attribution)
        assertEquals(before, restored.progress)
    }

    @Test
    fun `cold active edit rejects newer persisted progress and plan fields`() = runBlocking {
        val plan = fixturePlan()
        val before = NavigationProgress(tourId = plan.id, state = NavigationState.NAVIGATING)
        val advanced = before.copy(legIndex = 1, completedPointIds = setOf("first"))

        listOf(
            storedEntity(plan, advanced),
            storedEntity(plan.copy(objective = RouteObjective.SHORTEST), before),
        ).forEach { newerEntity ->
            val dao = FakeTourPlanDao().also { it.seed(newerEntity) }
            val repository = TourRepository(dao, ApiHttpClient.defaultJson, now = { 123L })

            val saved = repository.saveActiveEditIfCurrent(
                expectedPlan = plan,
                expectedProgress = before,
                updatedPlan = plan.copy(attribution = listOf("stale edit")),
                updatedProgress = before,
            )

            assertFalse(saved)
            assertEquals(newerEntity.storedTourJson, dao.get(plan.id)?.storedTourJson)
        }
    }

    @Test
    fun `v0_2_2 snapshot without execution strategy or leg index can be edited after recovery`() = runBlocking {
        val dao = FakeTourPlanDao()
        val repository = TourRepository(dao, ApiHttpClient.defaultJson, now = { 123L })
        val plan = fixturePlan()
        val progress = NavigationProgress(tourId = plan.id, state = NavigationState.NAVIGATING)
        val legacyStored = StoredTourV2.from(plan, progress).copy(
            executionStrategy = null,
            activeLegIndex = null,
        )
        dao.seed(storedEntity(legacyStored))

        val saved = repository.saveActiveEditIfCurrent(
            expectedPlan = plan,
            expectedProgress = progress,
            updatedPlan = plan.copy(attribution = listOf("legacy edit")),
            updatedProgress = progress,
        )

        assertTrue(saved)
    }

    @Test
    fun `missing approved region asset preserves and lists old stored tour fail closed`() = runBlocking {
        val dao = FakeTourPlanDao()
        val old = StoredTourV2.from(
            fixturePlan(),
            NavigationProgress(tourId = "tour", state = NavigationState.NAVIGATING),
        ).copy(
            executionStrategy = TransitExecutionStrategy.IN_APP_GOOGLE_ROUTES,
            mapProvider = null,
            regionDataVersion = null,
        )
        dao.seed(storedEntity(old))
        val repository = TourRepository(
            dao = dao,
            json = ApiHttpClient.defaultJson,
            classifyTerritory = { null },
            regionDataVersion = { null },
        )

        val restored = requireNotNull(repository.get(old.id))

        assertEquals(old, restored.storedTour)
        assertTrue(restored.routeNeedsRefresh)
        assertEquals(StoredRoutingError.REGION_UNRESOLVED, restored.routingError)
        assertTrue(restored.plan.legs.isEmpty())
        assertEquals(NavigationState.NAVIGATING, restored.progress?.state)
    }

    @Test
    fun `old Google China tour is reclassified to AMap without rewriting stored record`() = runBlocking {
        val dao = FakeTourPlanDao()
        val old = StoredTourV2.from(fixturePlan(), null).copy(
            executionStrategy = TransitExecutionStrategy.IN_APP_GOOGLE_ROUTES,
            mapProvider = null,
            regionDataVersion = null,
        )
        dao.seed(storedEntity(old))
        val repository = TourRepository(
            dao = dao,
            json = ApiHttpClient.defaultJson,
            classifyTerritory = { TerritoryRegion.MAINLAND_CHINA },
            regionDataVersion = { "TEST_ONLY-v1" },
        )

        val restored = requireNotNull(repository.get(old.id))

        assertEquals(old, restored.storedTour)
        assertEquals(TransitExecutionStrategy.EXTERNAL_AMAP_MAINLAND, restored.plan.executionStrategy)
        assertEquals(MapProvider.AMAP, restored.plan.mapProvider)
        assertEquals("TEST_ONLY-v1", restored.plan.regionDataVersion)
        assertNull(restored.routingError)
        assertTrue(restored.routeNeedsRefresh)
    }

    @Test
    fun `old mixed provider tour remains visible with safe routing error`() = runBlocking {
        val dao = FakeTourPlanDao()
        val old = StoredTourV2.from(fixturePlan(), null).copy(mapProvider = null, regionDataVersion = null)
        dao.seed(storedEntity(old))
        val repository = TourRepository(
            dao = dao,
            json = ApiHttpClient.defaultJson,
            classifyTerritory = { point ->
                if (point.latitude < 1.5) TerritoryRegion.MAINLAND_CHINA else TerritoryRegion.OTHER
            },
            regionDataVersion = { "TEST_ONLY-v1" },
        )

        val restored = requireNotNull(repository.get(old.id))

        assertEquals(old, restored.storedTour)
        assertEquals(StoredRoutingError.MIXED_MAP_PROVIDERS, restored.routingError)
        assertTrue(restored.routeNeedsRefresh)
    }

    private fun unresolvedEntity(plan: TourPlan): TourPlanEntity {
        val progress = NavigationProgress(tourId = plan.id, state = NavigationState.PLANNED)
        return storedEntity(plan, progress)
    }

    private fun storedEntity(plan: TourPlan, progress: NavigationProgress): TourPlanEntity {
        return storedEntity(StoredTourV2.from(plan, progress))
    }

    private fun storedEntity(stored: StoredTourV2): TourPlanEntity {
        return TourPlanEntity(
            id = stored.id,
            storedTourJson = ApiHttpClient.defaultJson.encodeToString(StoredTourV2.serializer(), stored),
            legacyPlanJson = null,
            legacyProgressJson = null,
            migrationError = null,
            routeNeedsRefresh = true,
            updatedAtEpochMillis = 123L,
        )
    }

    private fun mainlandRepository(dao: TourPlanDao): TourRepository = TourRepository(
        dao = dao,
        json = ApiHttpClient.defaultJson,
        now = { 123L },
        classifyTerritory = { TerritoryRegion.MAINLAND_CHINA },
        regionDataVersion = { TEST_REGION_DATA_VERSION },
    )

    private fun amapRoutedPlan(): TourPlan = fixturePlan().let { plan ->
        plan.copy(
            legs = plan.legs.map { leg ->
                leg.copy(
                    provider = MapProvider.AMAP,
                    coordinateSystem = CoordinateSystem.GCJ02,
                )
            },
            attribution = listOf("AMap Web Service"),
            executionStrategy = TransitExecutionStrategy.EXTERNAL_AMAP_MAINLAND,
            mapProvider = MapProvider.AMAP,
            coordinateSystem = CoordinateSystem.GCJ02,
            regionDataVersion = TEST_REGION_DATA_VERSION,
            externalRouteFallback = false,
        )
    }

    private fun amapFallbackPlan(): TourPlan = amapRoutedPlan().let { plan ->
        plan.copy(
            legs = plan.legs.map { leg ->
                leg.copy(
                    geometry = emptyList(),
                    steps = emptyList(),
                    distanceMeters = 0.0,
                    durationSeconds = 0.0,
                    source = EXTERNAL_AMAP_SOURCE,
                    coordinateSystem = CoordinateSystem.WGS84,
                )
            },
            estimatedDurationSeconds = 0.0,
            attribution = listOf(EXTERNAL_AMAP_SOURCE),
            coordinateSystem = CoordinateSystem.WGS84,
            externalRouteFallback = true,
        )
    }

    private fun fixturePlan(): TourPlan {
        val first = PilgrimagePoint("first", "First", GeoPoint(1.0, 1.0))
        val second = PilgrimagePoint("second", "Second", GeoPoint(2.0, 2.0))
        return TourPlan(
            id = "tour",
            anime = Anime(1, "Test"),
            selectedPoints = listOf(first, second),
            orderedPoints = listOf(first, second),
            legs = listOf(
                TourLeg(
                    from = first.coordinate,
                    to = second.coordinate,
                    mode = TravelMode.WALK,
                    geometry = listOf(first.coordinate, second.coordinate),
                    steps = emptyList(),
                    distanceMeters = 100.0,
                    durationSeconds = 60.0,
                    source = "test",
                    destinationPointId = second.id,
                ),
            ),
            mode = TravelMode.WALK,
            objective = RouteObjective.FASTEST,
            endPolicy = EndPolicy.OPEN,
            estimatedDurationSeconds = 60.0,
            attribution = listOf("test"),
            initialStart = first.coordinate,
        )
    }

    private companion object {
        const val TEST_REGION_DATA_VERSION = "TEST_ONLY-region-v1"
    }
}

private class FakeTourPlanDao(
    private val upsertGate: CompletableDeferred<Unit>? = null,
) : TourPlanDao {
    data class PostCommitGate(
        val committed: CompletableDeferred<Unit>,
        val release: CompletableDeferred<Unit>,
    )

    private val entities = linkedMapOf<String, TourPlanEntity>()
    val upsertStarted = CompletableDeferred<Unit>()
    private var nextPostCommitGate: PostCommitGate? = null
    var upsertCount: Int = 0
        private set

    override suspend fun get(id: String): TourPlanEntity? = entities[id]

    override suspend fun getMostRecent(): TourPlanEntity? =
        entities.values.maxByOrNull(TourPlanEntity::updatedAtEpochMillis)

    override suspend fun getIdsMostRecentFirst(): List<String> = entities.values
        .sortedWith(compareByDescending<TourPlanEntity> { it.updatedAtEpochMillis }.thenByDescending { it.id })
        .map(TourPlanEntity::id)

    override suspend fun upsert(entity: TourPlanEntity) {
        upsertCount += 1
        upsertStarted.complete(Unit)
        upsertGate?.await()
        entities[entity.id] = entity
        nextPostCommitGate?.also { gate ->
            nextPostCommitGate = null
            gate.committed.complete(Unit)
            gate.release.await()
        }
    }

    fun blockNextUpsertAfterCommit(): PostCommitGate = PostCommitGate(
        committed = CompletableDeferred(),
        release = CompletableDeferred(),
    ).also { nextPostCommitGate = it }

    fun seed(entity: TourPlanEntity) {
        entities[entity.id] = entity
    }

    override suspend fun finishLegacyMigration(id: String, storedTourJson: String, updatedAtEpochMillis: Long) {
        error("Not used")
    }

    override suspend fun recordMigrationError(id: String, message: String) {
        error("Not used")
    }

    override suspend fun getMostRecentMigrationError(): String? = null
}
