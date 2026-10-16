package cn.anitabi.navigator.core.routing

import cn.anitabi.navigator.core.model.Anime
import cn.anitabi.navigator.core.model.EndPolicy
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.PilgrimagePoint
import cn.anitabi.navigator.core.model.RouteObjective
import cn.anitabi.navigator.core.model.TourLeg
import cn.anitabi.navigator.core.model.TransitTimeMode
import cn.anitabi.navigator.core.model.TravelMode
import cn.anitabi.navigator.core.region.JapanRegion
import java.time.OffsetDateTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplicitDraftOrderTest {
    private val points = (1..31).map { PilgrimagePoint("point_$it", "TEST_ONLY", GeoPoint(it.toDouble(), 0.0)) }
    private val requests = mutableListOf<List<GeoPoint>>()
    private val road = object : RoadRoutingProvider {
        override suspend fun matrix(mode: TravelMode, points: List<GeoPoint>, objective: RouteObjective): TravelMatrix =
            error("Explicit user order must not request a matrix")
        override suspend fun directions(mode: TravelMode, points: List<GeoPoint>): RoadRoute {
            requests += points
            return RoadRoute(points.zipWithNext().map { (a, b) -> RoadRouteSegment(listOf(a, b), emptyList(), 10.0, 10.0) })
        }
    }
    private val transit = object : TransitJourneyProvider {
        override suspend fun journey(from: GeoPoint, to: GeoPoint, query: TransitJourneyQuery): TransitJourney {
            requests += listOf(from, to)
            return TransitJourney(
                listOf(TourLeg(from, to, TravelMode.TRANSIT, listOf(from, to), emptyList(), 10.0, 60.0, "TEST_ONLY")),
                query.departureTime!!,
                OffsetDateTime.parse(query.departureTime).plusMinutes(1).toString(),
            )
        }
    }

    @Test fun `explicit road order survives batching without extra matrix requests`() = runTest {
        val order = listOf(points.first()) + points.drop(1).reversed()
        val plan = TourPlanner(road, transit).planRoad(RoadPlanRequest(
            anime = Anime(1, "TEST_ONLY"), selectedPoints = points,
            start = points.first().coordinate, startPointId = points.first().id,
            mode = TravelMode.WALK, objective = RouteObjective.FASTEST, endPolicy = EndPolicy.OPEN,
            manualOrderPointIds = order.map(PilgrimagePoint::id),
        ))
        assertEquals(order, plan.orderedPoints)
        assertTrue(requests.all { it.size <= 12 })
        assertEquals(30, plan.legs.size)
        assertEquals(order.drop(1).map(PilgrimagePoint::coordinate), plan.legs.map { it.to })
    }

    @Test fun `invalid manual membership or pinned endpoints is rejected before route requests`() = runTest {
        val selected = points.take(3)
        val base = RoadPlanRequest(
            anime = Anime(1, "TEST_ONLY"), selectedPoints = selected,
            start = selected.first().coordinate, startPointId = selected.first().id,
            mode = TravelMode.WALK, objective = RouteObjective.FASTEST, endPolicy = EndPolicy.FIXED,
            fixedEndPointId = selected.last().id,
        )
        val invalid = listOf(
            listOf(selected[0].id, selected[1].id),
            listOf(selected[0].id, selected[0].id, selected[2].id),
            selected.map { it.id }.reversed(),
            listOf(selected[0].id, selected[2].id, selected[1].id),
        )
        invalid.forEach { ids ->
            val result = runCatching { TourPlanner(road, transit).planRoad(base.copy(manualOrderPointIds = ids)) }
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        }
        assertTrue(requests.isEmpty())
    }

    @Test fun `explicit transit sequence preserves adjacent requests and local Japan sequence`() = runTest {
        val selected = points.take(3)
        val order = listOf(selected[0], selected[2], selected[1])
        val request = TransitPlanRequest(
            anime = Anime(1, "TEST_ONLY"), selectedPoints = selected,
            start = selected.first().coordinate, startPointId = selected.first().id,
            endPolicy = EndPolicy.OPEN, timeMode = TransitTimeMode.DEPART_AT,
            anchorTime = "2026-09-23T09:00:00Z", manualOrderPointIds = order.map(PilgrimagePoint::id),
        )
        val plan = TourPlanner(road, transit).planTransit(request)
        assertEquals(order, plan.orderedPoints)
        assertEquals(order.zipWithNext().map { (a, b) -> listOf(a.coordinate, b.coordinate) }, requests)
        requests.clear()
        val local = TourPlanner(road, transit, classifyRegion = { JapanRegion.JAPAN }).planTransit(request)
        assertEquals(order, local.orderedPoints)
        assertTrue(requests.isEmpty())
    }
}
