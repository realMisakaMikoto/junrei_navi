package cn.anitabi.navigator.ui.planner

import cn.anitabi.navigator.core.model.Anime
import cn.anitabi.navigator.core.model.EndPolicy
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.NavigationProgress
import cn.anitabi.navigator.core.model.NavigationState
import cn.anitabi.navigator.core.model.PilgrimagePoint
import cn.anitabi.navigator.core.model.RouteObjective
import cn.anitabi.navigator.core.model.TourLeg
import cn.anitabi.navigator.core.model.TourPlan
import cn.anitabi.navigator.core.model.TransitRoutingPreference
import cn.anitabi.navigator.core.model.TransitTimeMode
import cn.anitabi.navigator.core.model.TransitTravelMode
import cn.anitabi.navigator.core.model.TravelMode
import cn.anitabi.navigator.core.routing.RoadRoute
import cn.anitabi.navigator.core.routing.RoadRouteSegment
import cn.anitabi.navigator.core.routing.RoadRoutingProvider
import cn.anitabi.navigator.core.routing.TourPlanner
import cn.anitabi.navigator.core.routing.TransitJourney
import cn.anitabi.navigator.core.routing.TransitJourneyProvider
import cn.anitabi.navigator.core.routing.TransitJourneyQuery
import cn.anitabi.navigator.core.routing.TravelMatrix
import cn.anitabi.navigator.data.local.TourPlanDao
import cn.anitabi.navigator.data.local.TourPlanEntity
import cn.anitabi.navigator.data.network.ApiException
import cn.anitabi.navigator.data.network.ApiHttpClient
import cn.anitabi.navigator.data.repository.SavedTour
import cn.anitabi.navigator.data.repository.StoredRoutingError
import cn.anitabi.navigator.data.repository.TourRepository
import cn.anitabi.navigator.navigation.CurrentLocationProvider
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SavedTourPlannerTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = Clock.fixed(Instant.parse("2026-09-19T02:00:00Z"), ZoneId.of("Asia/Shanghai"))

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `opening saved planning inputs is passive until user requests refresh`() = runTest(dispatcher) {
        val fixture = fixture()
        val saved = fixture.store(plan())
        fixture.viewModel.configureSaved(saved)
        advanceUntilIdle()
        assertFalse(fixture.viewModel.state.value.isLoading)
        assertNull(fixture.viewModel.state.value.plan)
        assertTrue(fixture.viewModel.state.value.canGenerate)
        assertTrue(fixture.road.requests.isEmpty())
        assertTrue(fixture.transit.queries.isEmpty())
        assertEquals(0, fixture.dao.writes)

        fixture.viewModel.generate()
        advanceUntilIdle()
        assertNotNull(fixture.viewModel.state.value.plan)
        assertEquals(1, fixture.road.requests.size)
    }

    @Test
    fun `saved road refresh keeps original start manual order settings and completed progress`() = runTest(dispatcher) {
        val fixture = fixture()
        val original = plan().copy(state = NavigationState.COMPLETED)
        val progress = NavigationProgress(
            tourId = original.id,
            legIndex = 2,
            completedPointIds = original.selectedPoints.map { it.id }.toSet(),
            state = NavigationState.COMPLETED,
            lastRerouteEpochMillis = 123L,
        )
        val saved = fixture.store(original, progress)
        val entityBefore = fixture.dao.entities.getValue(original.id)

        fixture.viewModel.configureSaved(saved)
        fixture.viewModel.generate()

        val loading = fixture.viewModel.state.value
        assertTrue(loading.isLoading)
        assertNull(loading.plan)
        assertEquals(original.id, loading.restoredTourId)
        assertEquals(NavigationState.COMPLETED, loading.restoredNavigationState)
        assertEquals(original.mode, loading.mode)
        assertEquals(original.objective, loading.objective)
        assertEquals(original.endPolicy, loading.endPolicy)
        assertEquals(original.orderedPoints.last().id, loading.fixedEndPointId)
        assertEquals(original.orderedPoints, loading.draftOrder)
        assertFalse(loading.useCurrentLocation)
        advanceUntilIdle()

        val refreshed = requireNotNull(fixture.viewModel.state.value.plan)
        assertEquals(original.initialStart, refreshed.initialStart)
        assertEquals(original.orderedPoints, refreshed.orderedPoints)
        assertEquals(listOf(original.initialStart) + original.orderedPoints.map { it.coordinate }, fixture.road.requests.single())
        assertEquals(original.id, refreshed.id)
        assertEquals(progress, fixture.repository.get(original.id)?.progress)
        assertEquals(saved.storedTour, fixture.repository.get(original.id)?.storedTour)
        assertEquals(entityBefore, fixture.dao.entities.getValue(original.id))
        assertEquals(0, fixture.dao.writes)
        assertFalse(requireNotNull(fixture.repository.get(original.id)).routeNeedsRefresh)
        assertTrue(refreshed.estimatedDurationSeconds > 0.0)
        assertFalse(fixture.viewModel.state.value.isLoading)
    }

    @Test
    fun `saved transit refresh preserves explicit arrival schedule and preferences`() = runTest(dispatcher) {
        val fixture = fixture()
        val original = plan().copy(
            mode = TravelMode.TRANSIT,
            transitTimeMode = TransitTimeMode.ARRIVE_BY,
            transitAnchorTime = "2026-09-20T10:30:00+08:00",
            transitRoutingPreference = TransitRoutingPreference.FEWER_TRANSFERS,
            transitTravelModes = setOf(TransitTravelMode.BUS),
            dwellMinutes = 23,
        )
        val saved = fixture.store(original)
        val entityBefore = fixture.dao.entities.getValue(original.id)

        fixture.viewModel.configureSaved(saved)
        fixture.viewModel.generate()
        advanceUntilIdle()

        val state = fixture.viewModel.state.value
        val refreshed = requireNotNull(state.plan)
        assertEquals(original.transitTimeMode, state.transitTimeMode)
        assertEquals("2026-09-20", state.transitDate.toString())
        assertEquals("10:30", state.transitTime.toString())
        assertEquals("23", state.dwellMinutesInput)
        assertEquals(original.transitAnchorTime, refreshed.transitAnchorTime)
        assertEquals(original.transitAnchorTime, fixture.transit.queries.first().arrivalTime)
        assertTrue(fixture.transit.queries.all { it.routingPreference == original.transitRoutingPreference })
        assertTrue(fixture.transit.queries.all { it.transitTravelModes == original.transitTravelModes })
        assertEquals(original.orderedPoints, refreshed.orderedPoints)
        assertEquals(saved.storedTour, fixture.repository.get(original.id)?.storedTour)
        assertEquals(entityBefore, fixture.dao.entities.getValue(original.id))
        assertEquals(0, fixture.dao.writes)
    }

    @Test
    fun `warm refresh preserves progress state when cached plan state is older`() = runTest(dispatcher) {
        val fixture = fixture()
        val original = plan()
        val progress = NavigationProgress(
            tourId = original.id,
            completedPointIds = original.selectedPoints.map { it.id }.toSet(),
            state = NavigationState.COMPLETED,
        )
        fixture.repository.save(original, progress)
        val saved = requireNotNull(fixture.repository.get(original.id))
        val entityBefore = fixture.dao.entities.getValue(original.id)
        fixture.dao.writes = 0

        fixture.viewModel.configureSaved(saved)
        fixture.viewModel.generate()
        advanceUntilIdle()

        assertEquals(NavigationState.COMPLETED, fixture.viewModel.state.value.plan?.state)
        assertEquals(progress, fixture.repository.get(original.id)?.progress)
        assertEquals(entityBefore, fixture.dao.entities.getValue(original.id))
        assertEquals(0, fixture.dao.writes)
    }

    @Test
    fun `saved transit now refresh uses current clock without persisting route response`() = runTest(dispatcher) {
        val fixture = fixture()
        val original = plan().copy(mode = TravelMode.TRANSIT, transitTimeMode = TransitTimeMode.NOW)
        fixture.viewModel.configureSaved(fixture.store(original))
        fixture.viewModel.generate()
        advanceUntilIdle()

        assertEquals("2026-09-19T10:00:00+08:00", fixture.transit.queries.first().departureTime)
        val stored = requireNotNull(fixture.repository.get(original.id)).storedTour
        assertEquals(TransitTimeMode.NOW, stored.transitTimeMode)
        assertNull(stored.departureTime)
        assertNull(stored.transitAnchorTime)
        assertNotNull(fixture.viewModel.state.value.plan)
    }

    @Test
    fun `refresh failure keeps preview absent and saved record unchanged and retry uses saved order`() = runTest(dispatcher) {
        val fixture = fixture()
        val original = plan()
        val saved = fixture.store(original)
        val entityBefore = fixture.dao.entities.getValue(original.id)
        fixture.road.failure = ApiException.BackendUnavailable()

        fixture.viewModel.configureSaved(saved)
        fixture.viewModel.generate()
        advanceUntilIdle()

        assertNull(fixture.viewModel.state.value.plan)
        assertFalse(fixture.viewModel.state.value.isLoading)
        assertNotNull(fixture.viewModel.state.value.errorMessage)
        assertEquals(entityBefore, fixture.dao.entities.getValue(original.id))
        assertEquals(0, fixture.dao.writes)

        fixture.road.failure = null
        fixture.viewModel.generate()
        advanceUntilIdle()

        assertEquals(original.orderedPoints, fixture.viewModel.state.value.plan?.orderedPoints)
        assertEquals(original.id, fixture.viewModel.state.value.plan?.id)
        assertEquals(entityBefore, fixture.dao.entities.getValue(original.id))
        assertEquals(0, fixture.dao.writes)
        assertFalse(requireNotNull(fixture.repository.get(original.id)).routeNeedsRefresh)
    }

    @Test
    fun `reconfigure discards even an uncooperative old refresh before saving`() = runTest(dispatcher) {
        val fixture = fixture()
        val saved = fixture.store(plan())
        fixture.road.release = CompletableDeferred()

        fixture.viewModel.configureSaved(saved)
        fixture.viewModel.generate()
        runCurrent()
        assertEquals(1, fixture.road.requests.size)
        fixture.viewModel.configure(Anime(2, "TEST_ONLY_NEW"), saved.plan.selectedPoints)
        fixture.road.release?.complete(Unit)
        advanceUntilIdle()

        assertEquals("TEST_ONLY_NEW", fixture.viewModel.state.value.anime?.name)
        assertNull(fixture.viewModel.state.value.plan)
        assertNull(fixture.viewModel.state.value.restoredTourId)
        assertEquals(0, fixture.dao.writes)
        assertTrue(requireNotNull(fixture.repository.get(saved.plan.id)).routeNeedsRefresh)
    }

    @Test
    fun `in progress refresh directs resume without reusing route leg indices or writing`() = runTest(dispatcher) {
        for (mode in listOf(TravelMode.WALK, TravelMode.TRANSIT)) {
            val fixture = fixture()
            val original = plan().copy(mode = mode)
            val progress = NavigationProgress(
                tourId = original.id,
                legIndex = 5,
                state = NavigationState.DWELLING,
                completedPointIds = setOf(original.orderedPoints.first().id),
                dwellingUntilEpochMillis = 200L,
            )
            fixture.viewModel.configureSaved(fixture.store(original, progress))
            advanceUntilIdle()

            assertNull(fixture.viewModel.state.value.plan)
            assertFalse(fixture.viewModel.state.value.isLoading)
            assertNotNull(fixture.viewModel.state.value.errorMessage)
            assertEquals(NavigationState.DWELLING, fixture.viewModel.state.value.restoredNavigationState)
            assertTrue(fixture.road.requests.isEmpty())
            assertTrue(fixture.transit.queries.isEmpty())
            assertEquals(progress, fixture.repository.get(original.id)?.progress)
            assertEquals(0, fixture.dao.writes)
        }
    }

    @Test
    fun `progress changed during refresh wins over stale restored state`() = runTest(dispatcher) {
        val fixture = fixture()
        val original = plan()
        val saved = fixture.store(original)
        fixture.road.release = CompletableDeferred()
        fixture.viewModel.configureSaved(saved)
        fixture.viewModel.generate()
        runCurrent()
        val newerProgress = NavigationProgress(tourId = original.id, state = NavigationState.ENDED)
        fixture.repository.saveUnresolved(original, newerProgress)
        fixture.dao.writes = 0

        fixture.road.release?.complete(Unit)
        advanceUntilIdle()

        assertNull(fixture.viewModel.state.value.plan)
        assertNotNull(fixture.viewModel.state.value.errorMessage)
        assertEquals(newerProgress, fixture.repository.get(original.id)?.progress)
        assertEquals(0, fixture.dao.writes)
    }

    @Test
    fun `saved order changed during refresh wins over stale route`() = runTest(dispatcher) {
        val fixture = fixture()
        val original = plan()
        val saved = fixture.store(original)
        fixture.road.release = CompletableDeferred()
        fixture.viewModel.configureSaved(saved)
        fixture.viewModel.generate()
        runCurrent()
        val edited = original.copy(orderedPoints = original.orderedPoints.reversed())
        fixture.repository.saveUnresolved(edited)
        val entityAfterEdit = fixture.dao.entities.getValue(original.id)
        fixture.dao.writes = 0

        fixture.road.release?.complete(Unit)
        advanceUntilIdle()

        assertNull(fixture.viewModel.state.value.plan)
        assertNotNull(fixture.viewModel.state.value.errorMessage)
        val current = requireNotNull(fixture.repository.get(original.id))
        assertEquals(edited.orderedPoints, current.plan.orderedPoints)
        assertTrue(current.routeNeedsRefresh)
        assertEquals(entityAfterEdit, fixture.dao.entities.getValue(original.id))
        assertEquals(0, fixture.dao.writes)
    }

    @Test
    fun `runtime progress changed while checking snapshot rejects refresh without writing`() = runTest(dispatcher) {
        val fixture = fixture()
        val original = plan()
        val saved = fixture.store(original)
        val entityBefore = fixture.dao.entities.getValue(original.id)
        val readRelease = CompletableDeferred<Unit>()
        fixture.dao.readRelease = readRelease
        fixture.viewModel.configureSaved(saved)
        fixture.viewModel.generate()
        runCurrent()
        assertTrue(fixture.viewModel.state.value.isLoading)
        assertNull(fixture.dao.readRelease)
        fixture.repository.noteRuntimeProgress(
            NavigationProgress(tourId = original.id, state = NavigationState.NAVIGATING),
        )

        readRelease.complete(Unit)
        advanceUntilIdle()

        assertNull(fixture.viewModel.state.value.plan)
        assertNotNull(fixture.viewModel.state.value.errorMessage)
        assertTrue(requireNotNull(fixture.repository.get(original.id)).routeNeedsRefresh)
        assertEquals(entityBefore, fixture.dao.entities.getValue(original.id))
        assertEquals(0, fixture.dao.writes)
    }

    @Test
    fun `route refresh cannot silently change stored planning choices`() = runTest(dispatcher) {
        val fixture = fixture()
        val saved = fixture.store(plan())
        val original = saved.plan
        val changedPlans = listOf(
            original.copy(orderedPoints = original.orderedPoints.reversed()),
            original.copy(initialStart = original.orderedPoints.first().coordinate),
            original.copy(mode = TravelMode.WALK),
            original.copy(objective = RouteObjective.FASTEST),
            original.copy(dwellMinutes = original.dwellMinutes + 1),
            original.copy(state = NavigationState.ENDED),
        )

        for (changed in changedPlans) {
            assertFalse(fixture.repository.publishRefreshedRouteIfCurrent(saved, changed))
        }

        assertEquals(saved, fixture.repository.get(original.id))
        assertEquals(0, fixture.dao.writes)
    }

    @Test
    fun `unresolved region blocks refresh before provider request`() = runTest(dispatcher) {
        val fixture = fixture()
        val saved = fixture.store(plan()).copy(routingError = StoredRoutingError.REGION_UNRESOLVED)

        fixture.viewModel.configureSaved(saved)
        fixture.viewModel.generate()
        advanceUntilIdle()

        assertNull(fixture.viewModel.state.value.plan)
        assertNotNull(fixture.viewModel.state.value.errorMessage)
        assertTrue(fixture.road.requests.isEmpty())
        assertEquals(0, fixture.dao.writes)
    }

    private fun fixture(): SavedPlannerFixture {
        val road = SavedPlannerRoadProvider()
        val transit = SavedPlannerTransitProvider()
        val dao = SavedPlannerDao()
        val repository = TourRepository(dao, ApiHttpClient.defaultJson)
        return SavedPlannerFixture(
            road = road,
            transit = transit,
            dao = dao,
            repository = repository,
            viewModel = PlannerViewModel(
                planner = TourPlanner(road, transit),
                repository = repository,
                locationProvider = object : CurrentLocationProvider {
                    override suspend fun currentLocation(): GeoPoint = error("Saved start must be preserved")
                },
                clock = clock,
            ),
        )
    }

    private fun plan(): TourPlan {
        val points = (1..3).map { index ->
            PilgrimagePoint("TEST_ONLY_$index", "TEST_ONLY_$index", GeoPoint(index.toDouble(), 0.0))
        }
        return TourPlan(
            id = "TEST_ONLY_SAVED",
            anime = Anime(1, "TEST_ONLY"),
            selectedPoints = points,
            orderedPoints = listOf(points[1], points[0], points[2]),
            legs = emptyList(),
            mode = TravelMode.BIKE,
            objective = RouteObjective.SHORTEST,
            endPolicy = EndPolicy.FIXED,
            estimatedDurationSeconds = 0.0,
            attribution = emptyList(),
            initialStart = GeoPoint(0.0, 0.0),
            transitTimeMode = TransitTimeMode.NOW,
        )
    }
}

private data class SavedPlannerFixture(
    val road: SavedPlannerRoadProvider,
    val transit: SavedPlannerTransitProvider,
    val dao: SavedPlannerDao,
    val repository: TourRepository,
    val viewModel: PlannerViewModel,
) {
    suspend fun store(plan: TourPlan, progress: NavigationProgress? = null): SavedTour {
        repository.saveUnresolved(plan, progress)
        dao.writes = 0
        return requireNotNull(repository.get(plan.id))
    }
}

private class SavedPlannerRoadProvider : RoadRoutingProvider {
    val requests = mutableListOf<List<GeoPoint>>()
    var failure: Exception? = null
    var release: CompletableDeferred<Unit>? = null

    override suspend fun matrix(mode: TravelMode, points: List<GeoPoint>, objective: RouteObjective): TravelMatrix =
        error("Refreshing saved order must not optimize")

    override suspend fun directions(mode: TravelMode, points: List<GeoPoint>): RoadRoute {
        requests += points
        release?.let { withContext(NonCancellable) { it.await() } }
        failure?.let { throw it }
        return RoadRoute(points.zipWithNext().map { (from, to) ->
            RoadRouteSegment(listOf(from, to), emptyList(), 100.0, 60.0)
        })
    }
}

private class SavedPlannerTransitProvider : TransitJourneyProvider {
    val queries = mutableListOf<TransitJourneyQuery>()

    override suspend fun journey(from: GeoPoint, to: GeoPoint, query: TransitJourneyQuery): TransitJourney {
        queries += query
        val departure = query.departureTime ?: OffsetDateTime.parse(query.arrivalTime).minusMinutes(10).toString()
        val arrival = query.arrivalTime ?: OffsetDateTime.parse(query.departureTime).plusMinutes(10).toString()
        return TransitJourney(
            legs = listOf(TourLeg(from, to, TravelMode.TRANSIT, listOf(from, to), emptyList(), 100.0, 600.0, "TEST_ONLY")),
            departureTime = departure,
            arrivalTime = arrival,
        )
    }
}

private class SavedPlannerDao : TourPlanDao {
    val entities = linkedMapOf<String, TourPlanEntity>()
    var writes = 0
    var readRelease: CompletableDeferred<Unit>? = null

    override suspend fun get(id: String): TourPlanEntity? {
        readRelease?.let {
            readRelease = null
            it.await()
        }
        return entities[id]
    }
    override suspend fun getMostRecent(): TourPlanEntity? = entities.values.lastOrNull()
    override suspend fun getIdsMostRecentFirst(): List<String> = entities.keys.toList().asReversed()
    override suspend fun upsert(entity: TourPlanEntity) {
        writes += 1
        entities[entity.id] = entity
    }
    override suspend fun finishLegacyMigration(id: String, storedTourJson: String, updatedAtEpochMillis: Long) = Unit
    override suspend fun recordMigrationError(id: String, message: String) = Unit
    override suspend fun getMostRecentMigrationError(): String? = null
}
