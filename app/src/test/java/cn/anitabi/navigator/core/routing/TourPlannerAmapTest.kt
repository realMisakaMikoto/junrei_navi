package cn.anitabi.navigator.core.routing

import cn.anitabi.navigator.core.model.Anime
import cn.anitabi.navigator.core.model.CoordinateSystem
import cn.anitabi.navigator.core.model.EndPolicy
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.core.model.PilgrimagePoint
import cn.anitabi.navigator.core.model.RouteObjective
import cn.anitabi.navigator.core.model.TerritoryRegion
import cn.anitabi.navigator.core.model.TourLeg
import cn.anitabi.navigator.core.model.TransitExecutionStrategy
import cn.anitabi.navigator.core.model.TransitRoutingPreference
import cn.anitabi.navigator.core.model.TransitTimeMode
import cn.anitabi.navigator.core.model.TransitTravelMode
import cn.anitabi.navigator.core.model.TravelMode
import cn.anitabi.navigator.core.region.JourneyProviderResolutionException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TourPlannerAmapTest {
    @Test
    fun `routing context rejects provider and CRS mixing`() {
        assertThrows(IllegalArgumentException::class.java) {
            RoutingProviderContext(MapProvider.AMAP, CoordinateSystem.WGS84)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RoutingProviderContext(MapProvider.GOOGLE, CoordinateSystem.GCJ02)
        }
    }

    @Test
    fun `AMap road planning uses backend matrix and route contexts`() = runBlocking {
        val road = RecordingProviderAwareRoad()
        val planner = planner(road = road)

        val plan = planner.planRoad(
            RoadPlanRequest(
                anime = anime,
                selectedPoints = points,
                start = points.first().coordinate,
                startPointId = points.first().id,
                mode = TravelMode.DRIVE,
                objective = RouteObjective.FASTEST,
                endPolicy = EndPolicy.OPEN,
            ),
        )

        assertTrue(road.matrixContexts.isNotEmpty())
        assertTrue(road.directionContexts.isNotEmpty())
        assertTrue((road.matrixContexts + road.directionContexts).all { it == RoutingProviderContext.AMAP })
        assertEquals(MapProvider.AMAP, plan.mapProvider)
        assertEquals(CoordinateSystem.GCJ02, plan.coordinateSystem)
        assertEquals(TransitExecutionStrategy.EXTERNAL_AMAP_MAINLAND, plan.executionStrategy)
        assertTrue(plan.estimatedDurationSeconds > 0.0)
        assertTrue(plan.legs.all { it.provider == MapProvider.AMAP && it.coordinateSystem == CoordinateSystem.GCJ02 })
        assertEquals(points.first().coordinate, plan.legs.first().from)
        assertEquals("TEST_ONLY-region-v1", plan.regionDataVersion)
    }

    @Test
    fun `mixed provider start and destination reject before any backend call`() {
        val road = RecordingProviderAwareRoad()
        val planner = TourPlanner(
            roadProvider = road,
            transitProvider = NeverTransit,
            classifyTerritory = { point ->
                if (point.longitude < 130.0) TerritoryRegion.MAINLAND_CHINA else TerritoryRegion.OTHER
            },
            regionDataVersion = { "TEST_ONLY-region-v1" },
        )
        val mixedPoints = listOf(
            point("china", 30.0, 120.0),
            point("other", 35.0, 139.0),
        )

        assertThrows(JourneyProviderResolutionException.MixedMapProviders::class.java) {
            runBlocking {
                planner.planRoad(
                    RoadPlanRequest(
                        anime = anime,
                        selectedPoints = mixedPoints,
                        start = mixedPoints.first().coordinate,
                        startPointId = mixedPoints.first().id,
                        mode = TravelMode.WALK,
                        objective = RouteObjective.FASTEST,
                        endPolicy = EndPolicy.OPEN,
                    ),
                )
            }
        }

        assertTrue(road.matrixContexts.isEmpty())
        assertTrue(road.directionContexts.isEmpty())
    }

    @Test
    fun `unavailable AMap consent gate rejects before any backend call`() {
        val road = RecordingProviderAwareRoad()
        val planner = TourPlanner(
            roadProvider = road,
            transitProvider = NeverTransit,
            classifyTerritory = { TerritoryRegion.MAINLAND_CHINA },
            regionDataVersion = { "TEST_ONLY-region-v1" },
            isProviderAvailable = { it != MapProvider.AMAP },
        )

        val exception = assertThrows(MapProviderUnavailableException::class.java) {
            runBlocking {
                planner.planRoad(
                    RoadPlanRequest(
                        anime = anime,
                        selectedPoints = points.take(2),
                        start = points.first().coordinate,
                        startPointId = points.first().id,
                        mode = TravelMode.WALK,
                        objective = RouteObjective.FASTEST,
                        endPolicy = EndPolicy.OPEN,
                    ),
                )
            }
        }

        assertEquals(MapProvider.AMAP, exception.provider)
        assertTrue(road.matrixContexts.isEmpty())
        assertTrue(road.directionContexts.isEmpty())
    }

    @Test
    fun `AMap transit preserves supported departure and preference`() = runBlocking {
        val transit = RecordingProviderAwareTransit()
        val planner = planner(transit = transit)

        val plan = planner.planTransit(
            TransitPlanRequest(
                anime = anime,
                selectedPoints = points.take(2),
                start = points.first().coordinate,
                startPointId = points.first().id,
                endPolicy = EndPolicy.OPEN,
                timeMode = TransitTimeMode.DEPART_AT,
                anchorTime = DEPARTURE,
                routingPreference = TransitRoutingPreference.LESS_WALKING,
            ),
        )

        assertEquals(listOf(RoutingProviderContext.AMAP), transit.contexts)
        assertEquals(DEPARTURE, transit.queries.single().departureTime)
        assertEquals(TransitRoutingPreference.LESS_WALKING, transit.queries.single().routingPreference)
        assertEquals(MapProvider.AMAP, plan.mapProvider)
        assertEquals(CoordinateSystem.GCJ02, plan.coordinateSystem)
        assertEquals(points.first().coordinate, plan.legs.single().from)
        assertEquals(points[1].coordinate, plan.legs.single().to)
        assertTrue(plan.legs.single().geometry.isNotEmpty())
    }

    @Test
    fun `AMap transit rejects arrive-by and mode filters before backend calls`() {
        val transit = RecordingProviderAwareTransit()
        val planner = planner(transit = transit)
        val base = TransitPlanRequest(
            anime = anime,
            selectedPoints = points.take(2),
            start = points.first().coordinate,
            startPointId = points.first().id,
            endPolicy = EndPolicy.OPEN,
            timeMode = TransitTimeMode.ARRIVE_BY,
            anchorTime = DEPARTURE,
        )

        assertThrows(IllegalArgumentException::class.java) { runBlocking { planner.planTransit(base) } }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                planner.planTransit(
                    base.copy(
                        timeMode = TransitTimeMode.NOW,
                        transitTravelModes = setOf(TransitTravelMode.BUS),
                    ),
                )
            }
        }
        assertTrue(transit.contexts.isEmpty())
    }

    @Test
    fun `explicit AMap fallback preserves current order as WGS84 legs without route details`() {
        val road = RecordingProviderAwareRoad()
        val classified = mutableListOf<GeoPoint>()
        var readinessChecks = 0
        val planner = TourPlanner(
            roadProvider = road,
            transitProvider = NeverTransit,
            classifyTerritory = { point ->
                classified += point
                TerritoryRegion.MAINLAND_CHINA
            },
            regionDataVersion = { "TEST_ONLY-region-v1" },
            isProviderAvailable = { provider ->
                readinessChecks += 1
                provider == MapProvider.AMAP
            },
        )
        val ordered = listOf(points[1], points[0], points[2])

        val plan = planner.planAmapExternalFallback(
            AmapExternalFallbackRequest(
                anime = anime,
                selectedPoints = points,
                orderedPoints = ordered,
                start = points[1].coordinate,
                startPointId = points[1].id,
                mode = TravelMode.WALK,
                objective = RouteObjective.FASTEST,
                endPolicy = EndPolicy.OPEN,
            ),
        )

        assertEquals(ordered, plan.orderedPoints)
        assertEquals(listOf(points[0].id, points[2].id), plan.legs.map(TourLeg::destinationPointId))
        assertEquals(points[1].coordinate, plan.legs.first().from)
        assertEquals(points[0].coordinate, plan.legs.first().to)
        assertEquals(TransitExecutionStrategy.EXTERNAL_AMAP_MAINLAND, plan.executionStrategy)
        assertEquals(MapProvider.AMAP, plan.mapProvider)
        assertEquals(CoordinateSystem.WGS84, plan.coordinateSystem)
        assertEquals(0.0, plan.estimatedDurationSeconds, 0.0)
        assertNull(plan.departureTime)
        assertNull(plan.arrivalTime)
        assertEquals(listOf(EXTERNAL_AMAP_SOURCE), plan.attribution)
        assertTrue(
            plan.legs.all { leg ->
                leg.provider == MapProvider.AMAP &&
                    leg.coordinateSystem == CoordinateSystem.WGS84 &&
                    leg.geometry.isEmpty() &&
                    leg.steps.isEmpty() &&
                    leg.distanceMeters == 0.0 &&
                    leg.durationSeconds == 0.0
            },
        )
        assertEquals(listOf(points[1].coordinate) + points.map(PilgrimagePoint::coordinate), classified)
        assertEquals(1, readinessChecks)
        assertTrue(road.matrixContexts.isEmpty())
        assertTrue(road.directionContexts.isEmpty())
    }

    @Test
    fun `explicit AMap fallback rechecks readiness and rejects before backend calls`() {
        val road = RecordingProviderAwareRoad()
        val planner = TourPlanner(
            roadProvider = road,
            transitProvider = NeverTransit,
            classifyTerritory = { TerritoryRegion.MAINLAND_CHINA },
            regionDataVersion = { "TEST_ONLY-region-v1" },
            isProviderAvailable = { false },
        )

        val exception = assertThrows(MapProviderUnavailableException::class.java) {
            planner.planAmapExternalFallback(
                AmapExternalFallbackRequest(
                    anime = anime,
                    selectedPoints = points,
                    orderedPoints = points,
                    start = points.first().coordinate,
                    startPointId = points.first().id,
                    mode = TravelMode.DRIVE,
                    objective = RouteObjective.FASTEST,
                    endPolicy = EndPolicy.OPEN,
                ),
            )
        }

        assertEquals(MapProvider.AMAP, exception.provider)
        assertTrue(road.matrixContexts.isEmpty())
        assertTrue(road.directionContexts.isEmpty())
    }

    @Test
    fun `explicit AMap fallback rejects mixed start before backend calls`() {
        val road = RecordingProviderAwareRoad()
        val outsideStart = GeoPoint(35.0, 139.0)
        val planner = TourPlanner(
            roadProvider = road,
            transitProvider = NeverTransit,
            classifyTerritory = { point ->
                if (point == outsideStart) TerritoryRegion.JAPAN else TerritoryRegion.MAINLAND_CHINA
            },
            regionDataVersion = { "TEST_ONLY-region-v1" },
        )

        assertThrows(JourneyProviderResolutionException.MixedMapProviders::class.java) {
            planner.planAmapExternalFallback(
                AmapExternalFallbackRequest(
                    anime = anime,
                    selectedPoints = points,
                    orderedPoints = points,
                    start = outsideStart,
                    mode = TravelMode.BIKE,
                    objective = RouteObjective.FASTEST,
                    endPolicy = EndPolicy.OPEN,
                ),
            )
        }

        assertTrue(road.matrixContexts.isEmpty())
        assertTrue(road.directionContexts.isEmpty())
    }

    @Test
    fun `fallback rebuild reclassifies a substituted device origin before creating legs`() {
        val road = RecordingProviderAwareRoad()
        val outsideStart = GeoPoint(35.0, 139.0)
        val planner = TourPlanner(
            roadProvider = road,
            transitProvider = NeverTransit,
            classifyTerritory = { point ->
                if (point == outsideStart) TerritoryRegion.JAPAN else TerritoryRegion.MAINLAND_CHINA
            },
            regionDataVersion = { "TEST_ONLY-region-v1" },
        )
        val fallback = planner.planAmapExternalFallback(
            AmapExternalFallbackRequest(
                anime = anime,
                selectedPoints = points,
                orderedPoints = points,
                start = points.first().coordinate,
                startPointId = points.first().id,
                mode = TravelMode.WALK,
                objective = RouteObjective.FASTEST,
                endPolicy = EndPolicy.OPEN,
            ),
        )

        assertThrows(JourneyProviderResolutionException.MixedMapProviders::class.java) {
            runBlocking {
                planner.rebuild(
                    fallback.copy(initialStart = outsideStart),
                    fallback.orderedPoints,
                )
            }
        }

        assertTrue(road.matrixContexts.isEmpty())
        assertTrue(road.directionContexts.isEmpty())
    }

    private fun planner(
        road: RecordingProviderAwareRoad = RecordingProviderAwareRoad(),
        transit: TransitJourneyProvider = NeverTransit,
    ) = TourPlanner(
        roadProvider = road,
        transitProvider = transit,
        classifyTerritory = { TerritoryRegion.MAINLAND_CHINA },
        regionDataVersion = { "TEST_ONLY-region-v1" },
    )

    private companion object {
        val anime = Anime(1, "TEST_ONLY")
        val points = listOf(
            point("a", 30.0, 120.0),
            point("b", 30.1, 120.1),
            point("c", 30.2, 120.2),
        )
        const val DEPARTURE = "2026-08-10T09:00:00+08:00"

        fun point(id: String, latitude: Double, longitude: Double) =
            PilgrimagePoint(id, "TEST_ONLY-$id", GeoPoint(latitude, longitude))
    }
}

private class RecordingProviderAwareRoad : ProviderAwareRoadRoutingProvider {
    val matrixContexts = mutableListOf<RoutingProviderContext>()
    val directionContexts = mutableListOf<RoutingProviderContext>()

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
        matrixContexts += context
        val values = List(points.size) { from ->
            List<Double?>(points.size) { to -> if (from == to) 0.0 else (from + to + 1) * 100.0 }
        }
        return TravelMatrix(values, values)
    }

    override suspend fun directions(mode: TravelMode, points: List<GeoPoint>): RoadRoute =
        error("Provider context is required")

    override suspend fun directions(
        mode: TravelMode,
        points: List<GeoPoint>,
        context: RoutingProviderContext,
    ): RoadRoute {
        directionContexts += context
        return RoadRoute(
            points.zipWithNext { from, to ->
                RoadRouteSegment(
                    geometry = listOf(
                        GeoPoint(from.latitude + 0.001, from.longitude + 0.001),
                        GeoPoint(to.latitude + 0.001, to.longitude + 0.001),
                    ),
                    steps = emptyList(),
                    distanceMeters = 1_000.0,
                    durationSeconds = 600.0,
                    provider = context.provider,
                    coordinateSystem = context.responseCoordinateSystem,
                )
            },
        )
    }
}

private class RecordingProviderAwareTransit : ProviderAwareTransitJourneyProvider {
    val contexts = mutableListOf<RoutingProviderContext>()
    val queries = mutableListOf<TransitJourneyQuery>()

    override suspend fun journey(from: GeoPoint, to: GeoPoint, query: TransitJourneyQuery): TransitJourney =
        error("Provider context is required")

    override suspend fun journey(
        from: GeoPoint,
        to: GeoPoint,
        query: TransitJourneyQuery,
        context: RoutingProviderContext,
    ): TransitJourney {
        contexts += context
        queries += query
        return TransitJourney(
            legs = listOf(
                TourLeg(
                    from = from,
                    to = to,
                    mode = TravelMode.TRANSIT,
                    geometry = listOf(
                        GeoPoint(from.latitude + 0.001, from.longitude + 0.001),
                        GeoPoint(to.latitude + 0.001, to.longitude + 0.001),
                    ),
                    steps = emptyList(),
                    distanceMeters = 2_000.0,
                    durationSeconds = 900.0,
                    source = AMAP_WEB_SERVICE_SOURCE,
                    provider = context.provider,
                    coordinateSystem = context.responseCoordinateSystem,
                ),
            ),
            departureTime = query.departureTime ?: error("departure required"),
            arrivalTime = "2026-08-10T09:15:00+08:00",
        )
    }
}

private object NeverTransit : TransitJourneyProvider {
    override suspend fun journey(from: GeoPoint, to: GeoPoint, query: TransitJourneyQuery): TransitJourney =
        error("Transit must not be called")
}
