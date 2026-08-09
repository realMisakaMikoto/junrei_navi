package cn.anitabi.navigator.ui.planner

import cn.anitabi.navigator.core.model.Anime
import cn.anitabi.navigator.core.model.CoordinateSystem
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.core.model.PilgrimagePoint
import cn.anitabi.navigator.core.model.RouteObjective
import cn.anitabi.navigator.core.model.TourLeg
import cn.anitabi.navigator.core.model.TransitExecutionStrategy
import cn.anitabi.navigator.core.model.TerritoryRegion
import cn.anitabi.navigator.core.model.TransitTimeMode
import cn.anitabi.navigator.core.model.TravelMode
import cn.anitabi.navigator.core.routing.RoadRoute
import cn.anitabi.navigator.core.routing.RoadRoutingProvider
import cn.anitabi.navigator.core.routing.ProviderAwareRoadRoutingProvider
import cn.anitabi.navigator.core.routing.RoutingProviderContext
import cn.anitabi.navigator.core.routing.MapProviderUnavailableException
import cn.anitabi.navigator.core.routing.NoRouteException
import cn.anitabi.navigator.core.routing.TourPlanner
import cn.anitabi.navigator.core.routing.TransitJourney
import cn.anitabi.navigator.core.routing.TransitJourneyProvider
import cn.anitabi.navigator.core.routing.TransitJourneyQuery
import cn.anitabi.navigator.core.routing.TransitRideUnavailableException
import cn.anitabi.navigator.core.routing.TransitSegmentUnavailableException
import cn.anitabi.navigator.core.routing.TravelMatrix
import cn.anitabi.navigator.core.region.JourneyProviderResolutionException
import cn.anitabi.navigator.data.local.TourPlanDao
import cn.anitabi.navigator.data.local.TourPlanEntity
import cn.anitabi.navigator.data.network.ApiHttpClient
import cn.anitabi.navigator.data.network.ApiException
import cn.anitabi.navigator.data.repository.TourRepository
import cn.anitabi.navigator.navigation.CurrentLocationProvider
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlannerViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `reconfigure cancels old transit planning without saving or overwriting new selection`() =
        runTest(dispatcher) {
            val transit = BlockingTransitProvider()
            val dao = RecordingTourPlanDao()
            val repository = TourRepository(dao, ApiHttpClient.defaultJson)
            val viewModel = PlannerViewModel(
                planner = TourPlanner(UnusedRoadProvider(), transit),
                repository = repository,
                locationProvider = object : CurrentLocationProvider {
                    override suspend fun currentLocation(): GeoPoint = GeoPoint(0.0, 0.0)
                },
                clock = Clock.fixed(Instant.parse("2026-07-31T04:36:47Z"), ZoneId.of("Asia/Shanghai")),
            )
            viewModel.configure(Anime(1, "Old"), points("old"))
            viewModel.setMode(TravelMode.TRANSIT)
            viewModel.setTransitSchedule(
                TransitTimeMode.NOW,
                viewModel.state.value.transitDate,
                viewModel.state.value.transitTime,
            )

            viewModel.generate()
            runCurrent()
            assertTrue(transit.started.isCompleted)

            viewModel.configure(Anime(2, "New"), points("new"))
            transit.release.complete(Unit)
            advanceUntilIdle()

            assertEquals("New", viewModel.state.value.anime?.name)
            assertFalse(viewModel.state.value.isLoading)
            assertNull(viewModel.state.value.plan)
            assertTrue(dao.entities.isEmpty())
            assertEquals(1, transit.calls)
        }

    @Test
    fun `eligible AMap backend failure offers but never activates fallback until explicit action`() =
        runTest(dispatcher) {
            val road = FailingAmapRoadProvider(ApiException.BackendUnavailable())
            val dao = RecordingTourPlanDao()
            val viewModel = PlannerViewModel(
                planner = TourPlanner(
                    roadProvider = road,
                    transitProvider = UnusedTransitProvider,
                    classifyTerritory = { TerritoryRegion.MAINLAND_CHINA },
                    regionDataVersion = { "TEST_ONLY-region-v1" },
                    isProviderAvailable = { true },
                ),
                repository = TourRepository(dao, ApiHttpClient.defaultJson),
                locationProvider = FixedLocationProvider,
            )
            viewModel.configure(Anime(1, "TEST_ONLY"), points("amap").take(2))

            viewModel.generate()
            advanceUntilIdle()

            val failedCalls = road.calls
            assertTrue(failedCalls > 0)
            assertNull(viewModel.state.value.plan)
            assertTrue(viewModel.state.value.amapExternalFallbackAvailable)
            assertTrue(dao.entities.isEmpty())

            viewModel.useAmapExternalFallback()
            advanceUntilIdle()

            val fallback = requireNotNull(viewModel.state.value.plan)
            assertEquals(failedCalls, road.calls)
            assertEquals(TransitExecutionStrategy.EXTERNAL_AMAP_MAINLAND, fallback.executionStrategy)
            assertEquals(MapProvider.AMAP, fallback.mapProvider)
            assertEquals(CoordinateSystem.WGS84, fallback.coordinateSystem)
            assertTrue(fallback.externalRouteFallback)
            assertEquals(0.0, fallback.estimatedDurationSeconds, 0.0)
            assertTrue(
                fallback.legs.all {
                    it.geometry.isEmpty() &&
                        it.steps.isEmpty() &&
                        it.distanceMeters == 0.0 &&
                        it.durationSeconds == 0.0
                },
            )
            assertFalse(viewModel.state.value.amapExternalFallbackAvailable)
            assertEquals(1, dao.entities.size)
        }

    @Test
    fun `AMap fallback click rechecks privacy key readiness without another backend call`() =
        runTest(dispatcher) {
            var ready = true
            val road = FailingAmapRoadProvider(ApiException.QuotaExhausted())
            val viewModel = PlannerViewModel(
                planner = TourPlanner(
                    roadProvider = road,
                    transitProvider = UnusedTransitProvider,
                    classifyTerritory = { TerritoryRegion.MAINLAND_CHINA },
                    regionDataVersion = { "TEST_ONLY-region-v1" },
                    isProviderAvailable = { ready },
                ),
                repository = TourRepository(RecordingTourPlanDao(), ApiHttpClient.defaultJson),
                locationProvider = FixedLocationProvider,
            )
            viewModel.configure(Anime(1, "TEST_ONLY"), points("amap").take(2))
            viewModel.generate()
            advanceUntilIdle()
            val failedCalls = road.calls
            assertTrue(viewModel.state.value.amapExternalFallbackAvailable)

            ready = false
            viewModel.useAmapExternalFallback()
            advanceUntilIdle()

            assertNull(viewModel.state.value.plan)
            assertEquals(failedCalls, road.calls)
            assertFalse(viewModel.state.value.amapExternalFallbackAvailable)
            assertTrue(viewModel.state.value.errorMessage.orEmpty().contains("关于"))
        }

    @Test
    fun `same backend failure never offers AMap fallback for Google journey`() = runTest(dispatcher) {
        val road = FailingAmapRoadProvider(ApiException.BackendUnavailable())
        val viewModel = PlannerViewModel(
            planner = TourPlanner(
                roadProvider = road,
                transitProvider = UnusedTransitProvider,
                classifyTerritory = { TerritoryRegion.OTHER },
            ),
            repository = TourRepository(RecordingTourPlanDao(), ApiHttpClient.defaultJson),
            locationProvider = FixedLocationProvider,
        )
        viewModel.configure(Anime(1, "TEST_ONLY"), points("google").take(2))

        viewModel.generate()
        advanceUntilIdle()

        assertNull(viewModel.state.value.plan)
        assertFalse(viewModel.state.value.amapExternalFallbackAvailable)
    }

    @Test
    fun `fallback eligibility is limited to transient quota rate and no-route failures`() {
        val eligible = listOf(
            ApiException.BackendUnavailable(),
            ApiException.Network(IOException("TEST_ONLY")),
            ApiException.Server(503),
            ApiException.UpstreamUnavailable(),
            ApiException.QuotaExhausted(),
            ApiException.RateLimited(),
            ApiException.NoRoute(),
            NoRouteException("TEST_ONLY"),
            TransitSegmentUnavailableException(
                segmentNumber = 1,
                segmentCount = 1,
                from = GeoPoint(1.0, 1.0),
                to = GeoPoint(2.0, 2.0),
            ),
            TransitRideUnavailableException(),
        )
        val excluded = listOf(
            ApiException.InvalidArgument(),
            ApiException.NotFound(),
            ApiException.MixedMapProviders(),
            ApiException.RegionUnresolved(),
            ApiException.RegionDataOutdated(),
            ApiException.InvalidResponse(IllegalStateException("CRS mismatch")),
            ApiException.InvalidCredentials(),
            MapProviderUnavailableException(MapProvider.AMAP),
            JourneyProviderResolutionException.MixedMapProviders(),
            JourneyProviderResolutionException.RegionUnresolved(),
        )

        eligible.forEach { failure -> assertTrue(failure.javaClass.name, isAmapExternalFallbackFailure(failure)) }
        excluded.forEach { failure -> assertFalse(failure.javaClass.name, isAmapExternalFallbackFailure(failure)) }
    }

    private fun points(prefix: String): List<PilgrimagePoint> = (1..3).map { index ->
        PilgrimagePoint("$prefix-$index", "$prefix $index", GeoPoint(index.toDouble(), 0.0))
    }
}

private class BlockingTransitProvider : TransitJourneyProvider {
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var calls = 0

    override suspend fun journey(
        from: GeoPoint,
        to: GeoPoint,
        query: TransitJourneyQuery,
    ): TransitJourney {
        calls += 1
        started.complete(Unit)
        release.await()
        val departure = requireNotNull(query.departureTime)
        val arrival = java.time.OffsetDateTime.parse(departure).plusMinutes(10).toString()
        return TransitJourney(
            legs = listOf(
                TourLeg(
                    from = from,
                    to = to,
                    mode = TravelMode.TRANSIT,
                    geometry = listOf(from, to),
                    steps = emptyList(),
                    distanceMeters = 100.0,
                    durationSeconds = 600.0,
                    source = "test",
                ),
            ),
            departureTime = departure,
            arrivalTime = arrival,
        )
    }
}

private class UnusedRoadProvider : RoadRoutingProvider {
    override suspend fun matrix(
        mode: TravelMode,
        points: List<GeoPoint>,
        objective: RouteObjective,
    ): TravelMatrix = error("Not used")

    override suspend fun directions(mode: TravelMode, points: List<GeoPoint>): RoadRoute = error("Not used")
}

private class FailingAmapRoadProvider(
    private val failure: ApiException,
) : ProviderAwareRoadRoutingProvider {
    var calls = 0
        private set

    override suspend fun matrix(
        mode: TravelMode,
        points: List<GeoPoint>,
        objective: RouteObjective,
    ): TravelMatrix = error("Provider context is required")

    override suspend fun matrix(
        mode: TravelMode,
        points: List<GeoPoint>,
        objective: RouteObjective,
        context: RoutingProviderContext,
    ): TravelMatrix {
        calls += 1
        throw failure
    }

    override suspend fun directions(mode: TravelMode, points: List<GeoPoint>): RoadRoute =
        error("Provider context is required")

    override suspend fun directions(
        mode: TravelMode,
        points: List<GeoPoint>,
        context: RoutingProviderContext,
    ): RoadRoute {
        calls += 1
        throw failure
    }
}

private object UnusedTransitProvider : TransitJourneyProvider {
    override suspend fun journey(
        from: GeoPoint,
        to: GeoPoint,
        query: TransitJourneyQuery,
    ): TransitJourney = error("Transit must not be called")
}

private object FixedLocationProvider : CurrentLocationProvider {
    override suspend fun currentLocation(): GeoPoint = GeoPoint(30.0, 120.0)
}

private class RecordingTourPlanDao : TourPlanDao {
    val entities = linkedMapOf<String, TourPlanEntity>()

    override suspend fun get(id: String): TourPlanEntity? = entities[id]
    override suspend fun getMostRecent(): TourPlanEntity? = entities.values.lastOrNull()
    override suspend fun getIdsMostRecentFirst(): List<String> = entities.values
        .sortedWith(compareByDescending<TourPlanEntity> { it.updatedAtEpochMillis }.thenByDescending { it.id })
        .map(TourPlanEntity::id)
    override suspend fun upsert(entity: TourPlanEntity) {
        entities[entity.id] = entity
    }
    override suspend fun finishLegacyMigration(id: String, storedTourJson: String, updatedAtEpochMillis: Long) = Unit
    override suspend fun recordMigrationError(id: String, message: String) = Unit
    override suspend fun getMostRecentMigrationError(): String? = null
}
