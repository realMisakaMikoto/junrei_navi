package cn.anitabi.navigator.core.routing

import cn.anitabi.navigator.core.model.Anime
import cn.anitabi.navigator.core.model.EndPolicy
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.PilgrimagePoint
import cn.anitabi.navigator.core.model.RouteObjective
import cn.anitabi.navigator.core.model.TourLeg
import cn.anitabi.navigator.core.model.TransitExecutionStrategy
import cn.anitabi.navigator.core.model.TransitTimeMode
import cn.anitabi.navigator.core.model.TravelMode
import cn.anitabi.navigator.core.region.JapanRegion
import java.time.OffsetDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JapanTransitPlanningIntegrationTest {
    private val anime = Anime(1, "Japan transit test")

    @Test
    fun `all Japanese points use external transit without calling either provider`() = runBlocking {
        val road = CountingRoadProvider()
        val transit = CountingTransitProvider()
        val planner = TourPlanner(road, transit, classifyRegion = ::regionByLongitude)
        val selectedPoints = listOf(
            point("tokyo", 35.6762, 139.6503),
            point("sendai", 38.2682, 140.8694),
            point("sapporo", 43.0618, 141.3545),
        )

        val plan = planner.planTransit(
            transitRequest(
                points = selectedPoints,
                start = GeoPoint(35.6895, 139.6917),
                anchorTime = null,
            ),
        )

        assertEquals(TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN, plan.executionStrategy)
        assertEquals(0, road.matrixCalls)
        assertEquals(0, road.directionCalls)
        assertEquals(0, transit.journeyCalls)
        assertEquals(selectedPoints.size, plan.orderedPoints.size)
        assertEquals(selectedPoints.size, plan.legs.size)
        assertTrue(plan.legs.all { leg ->
            leg.geometry.isEmpty() &&
                leg.steps.isEmpty() &&
                leg.durationSeconds == 0.0 &&
                leg.source == EXTERNAL_GOOGLE_MAPS_SOURCE
        })
    }

    @Test
    fun `all non Japanese points retain in app transit planning`() = runBlocking {
        val road = CountingRoadProvider()
        val transit = CountingTransitProvider()
        val planner = TourPlanner(road, transit, classifyRegion = ::regionByLongitude)
        val selectedPoints = listOf(
            point("seoul", 37.5665, 126.9780),
            point("busan", 35.1796, 129.0756),
        )

        val plan = planner.planTransit(transitRequest(points = selectedPoints))

        assertEquals(TransitExecutionStrategy.IN_APP_GOOGLE_ROUTES, plan.executionStrategy)
        assertEquals(0, road.matrixCalls)
        assertEquals(0, road.directionCalls)
        assertEquals(selectedPoints.size, transit.journeyCalls)
        assertTrue(plan.legs.all { it.mode == TravelMode.TRANSIT })
    }

    @Test
    fun `mixed Japanese and non Japanese points fail before either provider`() {
        val road = CountingRoadProvider()
        val transit = CountingTransitProvider()
        val planner = TourPlanner(road, transit, classifyRegion = ::regionByLongitude)
        val japan = point("tokyo", 35.6762, 139.6503)
        val nonJapan = point("seoul", 37.5665, 126.9780)

        listOf(
            listOf(japan, nonJapan),
            listOf(nonJapan, japan),
            listOf(japan, japan.copy(id = "tokyo-2"), nonJapan),
        ).forEach { mixedPoints ->
            val failure = assertThrows(MixedTransitRegionException::class.java) {
                runBlocking {
                    planner.planTransit(transitRequest(points = mixedPoints))
                }
            }

            assertEquals(MIXED_TRANSIT_REGION_MESSAGE, failure.message)
        }

        assertEquals(0, road.matrixCalls)
        assertEquals(0, road.directionCalls)
        assertEquals(0, transit.journeyCalls)
    }

    @Test
    fun `one hundred Japanese points are planned locally without provider calls`() = runBlocking {
        val road = CountingRoadProvider()
        val transit = CountingTransitProvider()
        val planner = TourPlanner(road, transit, classifyRegion = ::regionByLongitude)
        val selectedPoints = (0 until 100).map { index ->
            point(
                id = "japan-$index",
                latitude = 35.0 + index * 0.001,
                longitude = 139.0 + index * 0.001,
            )
        }

        val plan = planner.planTransit(
            transitRequest(
                points = selectedPoints,
                start = GeoPoint(35.0, 139.0),
                anchorTime = null,
            ),
        )

        assertEquals(TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN, plan.executionStrategy)
        assertEquals(100, plan.orderedPoints.size)
        assertEquals(100, plan.legs.size)
        assertEquals(0, road.matrixCalls)
        assertEquals(0, road.directionCalls)
        assertEquals(0, transit.journeyCalls)
    }

    private fun transitRequest(
        points: List<PilgrimagePoint>,
        start: GeoPoint = GeoPoint(37.5, 126.9),
        anchorTime: String? = "2026-08-01T09:00:00+09:00",
    ) = TransitPlanRequest(
        anime = anime,
        selectedPoints = points,
        start = start,
        endPolicy = EndPolicy.OPEN,
        timeMode = TransitTimeMode.NOW,
        anchorTime = anchorTime,
    )

    private fun point(id: String, latitude: Double, longitude: Double) = PilgrimagePoint(
        id = id,
        name = id,
        coordinate = GeoPoint(latitude, longitude),
    )

    private fun regionByLongitude(coordinate: GeoPoint): JapanRegion =
        if (coordinate.longitude >= 130.0) JapanRegion.JAPAN else JapanRegion.NON_JAPAN
}

private class CountingRoadProvider : RoadRoutingProvider {
    var matrixCalls = 0
    var directionCalls = 0

    override suspend fun matrix(
        mode: TravelMode,
        points: List<GeoPoint>,
        objective: RouteObjective,
    ): TravelMatrix {
        matrixCalls += 1
        error("Road matrix must not be called")
    }

    override suspend fun directions(mode: TravelMode, points: List<GeoPoint>): RoadRoute {
        directionCalls += 1
        error("Road directions must not be called")
    }
}

private class CountingTransitProvider : TransitJourneyProvider {
    var journeyCalls = 0

    override suspend fun journey(
        from: GeoPoint,
        to: GeoPoint,
        query: TransitJourneyQuery,
    ): TransitJourney {
        journeyCalls += 1
        val departure = query.departureTime
            ?: OffsetDateTime.parse(requireNotNull(query.arrivalTime)).minusMinutes(10).toString()
        val arrival = query.arrivalTime
            ?: OffsetDateTime.parse(requireNotNull(query.departureTime)).plusMinutes(10).toString()
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
