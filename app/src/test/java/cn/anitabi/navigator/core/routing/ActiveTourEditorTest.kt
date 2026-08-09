package cn.anitabi.navigator.core.routing

import cn.anitabi.navigator.core.model.Anime
import cn.anitabi.navigator.core.model.EndPolicy
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.NavigationProgress
import cn.anitabi.navigator.core.model.NavigationState
import cn.anitabi.navigator.core.model.PilgrimagePoint
import cn.anitabi.navigator.core.model.RouteObjective
import cn.anitabi.navigator.core.model.TourLeg
import cn.anitabi.navigator.core.model.TourPlan
import cn.anitabi.navigator.core.model.TransitExecutionStrategy
import cn.anitabi.navigator.core.model.TransitTimeMode
import cn.anitabi.navigator.core.model.TravelMode
import cn.anitabi.navigator.core.region.JapanRegion
import java.time.OffsetDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveTourEditorTest {
    @Test
    fun `completed points and current target cannot be submitted as future points`() {
        val points = listOf(point("done", 1.0), point("current", 2.0), point("future", 3.0))
        val plan = plan(points = points, mode = TravelMode.WALK)
        val progress = progress(plan, legIndex = 1, completedPointIds = setOf("done"))
        val planner = planner(classifyRegion = { JapanRegion.NON_JAPAN })

        assertThrows(ActiveTourEditException::class.java) {
            runBlocking {
                editActiveTourFuture(
                    currentPlan = plan,
                    currentProgress = progress,
                    orderedFuturePoints = listOf(points[1].copy(name = "changed"), points[2]),
                    planner = planner,
                )
            }
        }
        assertThrows(ActiveTourEditException::class.java) {
            runBlocking {
                editActiveTourFuture(
                    currentPlan = plan,
                    currentProgress = progress,
                    orderedFuturePoints = listOf(points[0], points[2]),
                    planner = planner,
                )
            }
        }
    }

    @Test
    fun `future points can be inserted deleted and reordered without changing active progress`() = runBlocking {
        val road = RecordingRoadProvider()
        val points = listOf(
            point("current", 1.0),
            point("delete", 2.0),
            point("keep", 3.0),
            point("move", 4.0),
        )
        val inserted = point("inserted", 5.0)
        val plan = plan(points = points, mode = TravelMode.WALK)
        val progress = progress(plan, legIndex = 0)
        val originalCurrentLeg = plan.legs.first()

        val result = editActiveTourFuture(
            currentPlan = plan,
            currentProgress = progress,
            orderedFuturePoints = listOf(points[3], inserted, points[2]),
            planner = planner(
                road = road,
                classifyRegion = { JapanRegion.NON_JAPAN },
            ),
        )

        assertEquals(listOf("current", "move", "inserted", "keep"), result.plan.orderedPoints.map { it.id })
        assertEquals(result.plan.orderedPoints.map { it.id }, result.plan.selectedPoints.map { it.id })
        assertEquals(plan.id, result.plan.id)
        assertEquals(progress, result.progress)
        assertEquals(progress.legIndex, result.progress.legIndex)
        assertSame(originalCurrentLeg, result.plan.legs.first())
        assertEquals(
            listOf("current", "move", "inserted", "keep"),
            result.plan.legs.mapNotNull { it.destinationPointId },
        )
        assertEquals(1, road.directionRequests.size)
        assertEquals(
            listOf(points[0].coordinate, points[3].coordinate, inserted.coordinate, points[2].coordinate),
            road.directionRequests.single(),
        )
        assertEquals(0, road.matrixCalls)
    }

    @Test
    fun `road return leg edit never loads transit region data`() = runBlocking {
        val points = listOf(point("visited", 35.0, 139.0))
        val openPlan = plan(points = points, mode = TravelMode.WALK)
        val initialStart = requireNotNull(openPlan.initialStart)
        val returnLeg = TourLeg(
            from = points.single().coordinate,
            to = initialStart,
            mode = TravelMode.WALK,
            geometry = listOf(points.single().coordinate, initialStart),
            steps = emptyList(),
            distanceMeters = 100.0,
            durationSeconds = 60.0,
            source = "original",
            destinationPointId = null,
        )
        val plan = openPlan.copy(endPolicy = EndPolicy.RETURN_TO_START, legs = openPlan.legs + returnLeg)
        val progress = progress(plan, legIndex = plan.legs.lastIndex, completedPointIds = setOf(points.single().id))

        val result = editActiveTourFuture(
            currentPlan = plan,
            currentProgress = progress,
            orderedFuturePoints = emptyList(),
            planner = planner(classifyRegion = { error("Road edits must not classify transit regions") }),
        )

        assertSame(plan, result.plan)
        assertSame(progress, result.progress)
    }

    @Test
    fun `completed points omitted from a rerouted visit order remain selected and completed`() = runBlocking {
        val road = RecordingRoadProvider()
        val done = point("done", 1.0)
        val current = point("current", 2.0)
        val oldFuture = point("old-future", 3.0)
        val inserted = point("inserted", 4.0)
        val original = plan(points = listOf(done, current, oldFuture), mode = TravelMode.WALK)
        val rerouted = original.copy(
            orderedPoints = listOf(current, oldFuture),
            legs = original.legs.drop(1),
        )
        val progress = progress(rerouted, legIndex = 0, completedPointIds = setOf(done.id))

        val result = editActiveTourFuture(
            currentPlan = rerouted,
            currentProgress = progress,
            orderedFuturePoints = listOf(inserted),
            planner = planner(road = road, classifyRegion = { JapanRegion.NON_JAPAN }),
        )

        assertEquals(listOf("done", "current", "inserted"), result.plan.selectedPoints.map { it.id })
        assertEquals(listOf("current", "inserted"), result.plan.orderedPoints.map { it.id })
        assertEquals(setOf("done"), result.progress.completedPointIds)
        assertEquals(0, result.progress.legIndex)
        assertSame(rerouted.legs.first(), result.plan.legs.first())
    }

    @Test
    fun `dwelling recovery sentinel can edit every remaining point without losing completed points`() = runBlocking {
        val road = RecordingRoadProvider()
        val done = point("done", 1.0)
        val first = point("first", 2.0)
        val second = point("second", 3.0)
        val inserted = point("inserted", 4.0)
        val original = plan(points = listOf(done, first, second), mode = TravelMode.WALK)
        val remainingStart = GeoPoint(1.5, 0.0)
        val recovered = original.copy(
            orderedPoints = listOf(first, second),
            legs = listOf(
                leg(remainingStart, first, TravelMode.WALK),
                leg(first.coordinate, second, TravelMode.WALK),
            ),
        )
        val progress = NavigationProgress(
            tourId = recovered.id,
            legIndex = -1,
            completedPointIds = setOf(done.id),
            state = NavigationState.DWELLING,
            dwellingUntilEpochMillis = 10_000L,
        )

        val result = editActiveTourFuture(
            currentPlan = recovered,
            currentProgress = progress,
            orderedFuturePoints = listOf(second, inserted, first),
            planner = planner(road = road, classifyRegion = { JapanRegion.NON_JAPAN }),
        )

        assertEquals(listOf("done", "second", "inserted", "first"), result.plan.selectedPoints.map { it.id })
        assertEquals(listOf("second", "inserted", "first"), result.plan.orderedPoints.map { it.id })
        assertEquals(progress, result.progress)
        assertEquals(
            listOf(remainingStart, second.coordinate, inserted.coordinate, first.coordinate),
            road.directionRequests.single(),
        )
    }

    @Test
    fun `road edit preserves unchanged future prefix and trailing adjacent leg`() = runBlocking {
        val road = RecordingRoadProvider()
        val points = listOf(
            point("current", 1.0),
            point("prefix", 2.0),
            point("first", 3.0),
            point("second", 4.0),
            point("anchor", 5.0),
            point("tail", 6.0),
        )
        val plan = plan(points = points, mode = TravelMode.WALK)

        val result = editActiveTourFuture(
            currentPlan = plan,
            currentProgress = progress(plan, legIndex = 0),
            orderedFuturePoints = listOf(points[1], points[3], points[2], points[4], points[5]),
            planner = planner(road = road, classifyRegion = { JapanRegion.NON_JAPAN }),
        )

        assertSame(plan.legs[0], result.plan.legs[0])
        assertSame(plan.legs[1], result.plan.legs[1])
        assertSame(plan.legs[5], result.plan.legs[5])
        assertEquals(1, road.directionRequests.size)
        assertEquals(
            listOf(
                points[1].coordinate,
                points[3].coordinate,
                points[2].coordinate,
                points[4].coordinate,
            ),
            road.directionRequests.single(),
        )
    }

    @Test
    fun `road insertion rebuilds only new adjacent legs and preserves the unaffected tail`() = runBlocking {
        val road = RecordingRoadProvider()
        val points = listOf(
            point("current", 1.0),
            point("prefix", 2.0),
            point("tail-first", 3.0),
            point("tail-last", 4.0),
        )
        val inserted = point("inserted", 2.5)
        val plan = plan(points = points, mode = TravelMode.WALK)

        val result = editActiveTourFuture(
            currentPlan = plan,
            currentProgress = progress(plan, legIndex = 0),
            orderedFuturePoints = listOf(points[1], inserted, points[2], points[3]),
            planner = planner(
                road = road,
                classifyRegion = { JapanRegion.NON_JAPAN },
            ),
        )

        assertSame(plan.legs[0], result.plan.legs[0])
        assertSame(plan.legs[1], result.plan.legs[1])
        assertSame(plan.legs[3], result.plan.legs.last())
        assertEquals(listOf(points[1].coordinate, inserted.coordinate, points[2].coordinate), road.directionRequests.single())
    }

    @Test
    fun `road return-to-start edit preserves the unaffected final return leg`() = runBlocking {
        val road = RecordingRoadProvider()
        val points = listOf(point("current", 1.0), point("prefix", 2.0), point("last", 3.0))
        val inserted = point("inserted", 2.5)
        val openPlan = plan(points = points, mode = TravelMode.WALK)
        val initialStart = requireNotNull(openPlan.initialStart)
        val returnLeg = TourLeg(
            from = points.last().coordinate,
            to = initialStart,
            mode = TravelMode.WALK,
            geometry = listOf(points.last().coordinate, initialStart),
            steps = emptyList(),
            distanceMeters = 100.0,
            durationSeconds = 60.0,
            source = "original",
            destinationPointId = null,
        )
        val plan = openPlan.copy(endPolicy = EndPolicy.RETURN_TO_START, legs = openPlan.legs + returnLeg)

        val result = editActiveTourFuture(
            currentPlan = plan,
            currentProgress = progress(plan, legIndex = 0),
            orderedFuturePoints = listOf(points[1], inserted, points[2]),
            planner = planner(
                road = road,
                classifyRegion = { JapanRegion.NON_JAPAN },
            ),
        )

        assertSame(returnLeg, result.plan.legs.last())
        assertEquals(initialStart, result.plan.legs.last().to)
        assertEquals(listOf(points[1].coordinate, inserted.coordinate, points[2].coordinate), road.directionRequests.single())
    }

    @Test
    fun `mixed region edit fails before routing and leaves the original plan unchanged`() {
        val road = RecordingRoadProvider()
        val transit = RecordingTransitProvider()
        val japanCurrent = point("current", 35.0, 139.0)
        val japanFuture = point("future", 35.1, 139.1)
        val nonJapan = point("mixed", 37.5, 127.0)
        val plan = plan(
            points = listOf(japanCurrent, japanFuture),
            mode = TravelMode.TRANSIT,
            executionStrategy = TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN,
        )
        val original = plan.copy()
        val planner = planner(
            road = road,
            transit = transit,
            classifyRegion = { coordinate ->
                if (coordinate.longitude >= 130.0) JapanRegion.JAPAN else JapanRegion.NON_JAPAN
            },
        )

        val exception = assertThrows(MixedTransitRegionException::class.java) {
            runBlocking {
                editActiveTourFuture(
                    currentPlan = plan,
                    currentProgress = progress(plan, legIndex = 0),
                    orderedFuturePoints = listOf(nonJapan),
                    planner = planner,
                )
            }
        }

        assertEquals(MIXED_TRANSIT_REGION_MESSAGE, exception.message)
        assertEquals(original, plan)
        assertEquals(0, road.matrixCalls)
        assertTrue(road.directionRequests.isEmpty())
        assertTrue(transit.queries.isEmpty())
    }

    @Test
    fun `Japan future edit stays local and never invokes either routing provider`() = runBlocking {
        val road = RecordingRoadProvider()
        val transit = RecordingTransitProvider()
        val points = listOf(
            point("current", 35.0, 139.0),
            point("second", 35.1, 139.1),
            point("third", 35.2, 139.2),
        )
        val inserted = point("inserted", 35.3, 139.3)
        val plan = plan(
            points = points,
            mode = TravelMode.TRANSIT,
            executionStrategy = TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN,
        )
        val progress = progress(plan, legIndex = 0)

        val result = editActiveTourFuture(
            currentPlan = plan,
            currentProgress = progress,
            orderedFuturePoints = listOf(points[2], inserted, points[1]),
            planner = planner(
                road = road,
                transit = transit,
                classifyRegion = { JapanRegion.JAPAN },
            ),
        )

        assertEquals(listOf("current", "third", "inserted", "second"), result.plan.orderedPoints.map { it.id })
        assertEquals(
            listOf("current", "third", "inserted", "second"),
            result.plan.legs.mapNotNull { it.destinationPointId },
        )
        assertSame(plan.legs.first(), result.plan.legs.first())
        assertTrue(result.plan.legs.drop(1).all { it.source == EXTERNAL_GOOGLE_MAPS_SOURCE })
        assertTrue(result.plan.legs.drop(1).all { it.geometry.isEmpty() && it.steps.isEmpty() })
        assertEquals(plan.id, result.plan.id)
        assertEquals(progress, result.progress)
        assertEquals(0, road.matrixCalls)
        assertTrue(road.directionRequests.isEmpty())
        assertTrue(transit.queries.isEmpty())
    }

    @Test
    fun `fixed end remains last while Japan future points are edited locally`() = runBlocking {
        val road = RecordingRoadProvider()
        val transit = RecordingTransitProvider()
        val points = listOf(
            point("current", 35.0, 139.0),
            point("middle", 35.1, 139.1),
            point("fixed", 35.2, 139.2),
        )
        val inserted = point("inserted", 35.3, 139.3)
        val plan = plan(
            points = points,
            mode = TravelMode.TRANSIT,
            executionStrategy = TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN,
        ).copy(endPolicy = EndPolicy.FIXED)
        val progress = progress(plan, legIndex = 0)
        val planner = planner(
            road = road,
            transit = transit,
            classifyRegion = { JapanRegion.JAPAN },
        )

        listOf(
            listOf(points[1]),
            listOf(points[2], points[1]),
            listOf(points[1], points[2], inserted),
        ).forEach { invalidFuture ->
            assertThrows(ActiveTourEditException::class.java) {
                runBlocking {
                    editActiveTourFuture(plan, progress, invalidFuture, planner)
                }
            }
        }

        val result = editActiveTourFuture(
            currentPlan = plan,
            currentProgress = progress,
            orderedFuturePoints = listOf(points[1], inserted, points[2]),
            planner = planner,
        )
        assertEquals("fixed", result.plan.orderedPoints.last().id)
        assertEquals("fixed", result.plan.legs.last().destinationPointId)
        assertEquals(0, road.matrixCalls)
        assertTrue(road.directionRequests.isEmpty())
        assertTrue(transit.queries.isEmpty())
    }

    @Test
    fun `Japan return to start edit keeps the saved initial start as the final destination`() = runBlocking {
        val road = RecordingRoadProvider()
        val transit = RecordingTransitProvider()
        val points = listOf(
            point("current", 35.0, 139.0),
            point("future", 35.1, 139.1),
        )
        val inserted = point("inserted", 35.2, 139.2)
        val openPlan = plan(
            points = points,
            mode = TravelMode.TRANSIT,
            executionStrategy = TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN,
        )
        val initialStart = requireNotNull(openPlan.initialStart)
        val returnLeg = TourLeg(
            from = points.last().coordinate,
            to = initialStart,
            mode = TravelMode.TRANSIT,
            geometry = emptyList(),
            steps = emptyList(),
            distanceMeters = 100.0,
            durationSeconds = 0.0,
            source = EXTERNAL_GOOGLE_MAPS_SOURCE,
            destinationPointId = null,
        )
        val plan = openPlan.copy(
            endPolicy = EndPolicy.RETURN_TO_START,
            legs = openPlan.legs + returnLeg,
        )

        val result = editActiveTourFuture(
            currentPlan = plan,
            currentProgress = progress(plan, legIndex = 0),
            orderedFuturePoints = listOf(inserted, points[1]),
            planner = planner(
                road = road,
                transit = transit,
                classifyRegion = { JapanRegion.JAPAN },
            ),
        )

        assertEquals(initialStart, result.plan.legs.last().to)
        assertEquals(null, result.plan.legs.last().destinationPointId)
        assertEquals(0, road.matrixCalls)
        assertTrue(road.directionRequests.isEmpty())
        assertTrue(transit.queries.isEmpty())
    }

    @Test
    fun `in app transit routes only the changed future suffix`() = runBlocking {
        val road = RecordingRoadProvider()
        val transit = RecordingTransitProvider()
        val points = listOf(point("current", 1.0), point("future", 2.0))
        val inserted = point("inserted", 3.0)
        val plan = plan(
            points = points,
            mode = TravelMode.TRANSIT,
            executionStrategy = TransitExecutionStrategy.IN_APP_GOOGLE_ROUTES,
        ).copy(
            departureTime = "2026-08-01T08:00:00+09:00",
            transitTimeMode = TransitTimeMode.DEPART_AT,
            transitAnchorTime = "2026-08-01T08:00:00+09:00",
        )
        val progress = progress(plan, legIndex = 0)

        val result = editActiveTourFuture(
            currentPlan = plan,
            currentProgress = progress,
            orderedFuturePoints = listOf(points[1], inserted),
            planner = planner(
                road = road,
                transit = transit,
                classifyRegion = { JapanRegion.NON_JAPAN },
            ),
            transitAnchorTime = "2026-08-01T09:00:00+09:00",
        )

        assertSame(plan.legs.first(), result.plan.legs.first())
        assertSame(plan.legs[1], result.plan.legs[1])
        assertEquals(listOf("current", "future", "inserted"), result.plan.legs.mapNotNull { it.destinationPointId })
        assertEquals(1, transit.queries.size)
        assertEquals("2026-08-01T09:32:00+09:00", transit.queries.single().departureTime)
        assertEquals(3_420.0, result.plan.estimatedDurationSeconds, 0.0)
        assertTrue(road.directionRequests.isEmpty())
        assertEquals(TransitTimeMode.NOW, result.plan.transitTimeMode)
        assertEquals(null, result.plan.transitAnchorTime)
        assertEquals(plan.id, result.plan.id)
        assertEquals(progress, result.progress)
    }

    private fun planner(
        road: RecordingRoadProvider = RecordingRoadProvider(),
        transit: RecordingTransitProvider = RecordingTransitProvider(),
        classifyRegion: (GeoPoint) -> JapanRegion,
    ): TourPlanner = TourPlanner(
        roadProvider = road,
        transitProvider = transit,
        classifyRegion = classifyRegion,
    )

    private fun plan(
        points: List<PilgrimagePoint>,
        mode: TravelMode,
        executionStrategy: TransitExecutionStrategy = TransitExecutionStrategy.IN_APP_GOOGLE_ROUTES,
    ): TourPlan {
        val start = GeoPoint(points.first().coordinate.latitude - 0.1, points.first().coordinate.longitude)
        var from = start
        val legs = points.map { destination ->
            leg(from, destination, mode).also { from = destination.coordinate }
        }
        return TourPlan(
            id = "tour-id",
            anime = Anime(subjectId = 1L, name = "Test"),
            selectedPoints = points,
            orderedPoints = points,
            legs = legs,
            mode = mode,
            objective = RouteObjective.FASTEST,
            endPolicy = EndPolicy.OPEN,
            estimatedDurationSeconds = legs.sumOf(TourLeg::durationSeconds),
            attribution = emptyList(),
            initialStart = start,
            state = NavigationState.NAVIGATING,
            executionStrategy = executionStrategy,
        )
    }

    private fun progress(
        plan: TourPlan,
        legIndex: Int,
        completedPointIds: Set<String> = emptySet(),
    ): NavigationProgress = NavigationProgress(
        tourId = plan.id,
        legIndex = legIndex,
        completedPointIds = completedPointIds,
        state = NavigationState.NAVIGATING,
    )

    private fun point(id: String, latitude: Double, longitude: Double = 0.0) = PilgrimagePoint(
        id = id,
        name = id,
        coordinate = GeoPoint(latitude, longitude),
    )

    private fun leg(from: GeoPoint, destination: PilgrimagePoint, mode: TravelMode) = TourLeg(
        from = from,
        to = destination.coordinate,
        mode = mode,
        geometry = listOf(from, destination.coordinate),
        steps = emptyList(),
        distanceMeters = 100.0,
        durationSeconds = 60.0,
        source = "original",
        destinationPointId = destination.id,
    )
}

private class RecordingRoadProvider : RoadRoutingProvider {
    var matrixCalls = 0
    val directionRequests = mutableListOf<List<GeoPoint>>()

    override suspend fun matrix(
        mode: TravelMode,
        points: List<GeoPoint>,
        objective: RouteObjective,
    ): TravelMatrix {
        matrixCalls += 1
        val values = List(points.size) { List<Double?>(points.size) { 0.0 } }
        return TravelMatrix(durations = values, distances = values)
    }

    override suspend fun directions(mode: TravelMode, points: List<GeoPoint>): RoadRoute {
        directionRequests += points
        return RoadRoute(
            points.zipWithNext().map { (from, to) ->
                RoadRouteSegment(
                    geometry = listOf(from, to),
                    steps = emptyList(),
                    distanceMeters = 100.0,
                    durationSeconds = 60.0,
                )
            },
        )
    }
}

private class RecordingTransitProvider : TransitJourneyProvider {
    val queries = mutableListOf<TransitJourneyQuery>()

    override suspend fun journey(
        from: GeoPoint,
        to: GeoPoint,
        query: TransitJourneyQuery,
    ): TransitJourney {
        queries += query
        val departure = query.departureTime?.let(OffsetDateTime::parse)
            ?: requireNotNull(query.arrivalTime).let(OffsetDateTime::parse).minusMinutes(10)
        val arrival = query.arrivalTime?.let(OffsetDateTime::parse) ?: departure.plusMinutes(10)
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
                    source = "fake",
                ),
            ),
            departureTime = departure.toString(),
            arrivalTime = arrival.toString(),
        )
    }
}
