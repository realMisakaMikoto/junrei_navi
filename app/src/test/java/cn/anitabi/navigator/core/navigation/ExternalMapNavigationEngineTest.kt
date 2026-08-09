package cn.anitabi.navigator.core.navigation

import cn.anitabi.navigator.core.model.Anime
import cn.anitabi.navigator.core.model.CoordinateSystem
import cn.anitabi.navigator.core.model.EndPolicy
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.core.model.NavigationState
import cn.anitabi.navigator.core.model.PilgrimagePoint
import cn.anitabi.navigator.core.model.RouteObjective
import cn.anitabi.navigator.core.model.TourLeg
import cn.anitabi.navigator.core.model.TourPlan
import cn.anitabi.navigator.core.model.TransitExecutionStrategy
import cn.anitabi.navigator.core.model.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalMapNavigationEngineTest {
    @Test
    fun `AMap drive bike walk and transit share the recoverable external state machine`() {
        TravelMode.entries.forEach { mode ->
            val plan = amapPlan(mode)
            val engine = JapanExternalTransitEngine(plan)

            assertEquals(NavigationState.NAVIGATING, engine.start().progress.state)
            assertEquals(
                NavigationState.DWELLING,
                engine.confirmArrival(nowEpochMillis = 1_000L, confirmEarly = true).progress.state,
            )
            assertEquals(
                NavigationState.COMPLETED,
                engine.onTick(nowEpochMillis = 1_000L, nowElapsedRealtimeMillis = 1_000L).progress.state,
            )
        }
    }

    private fun amapPlan(mode: TravelMode): TourPlan {
        val from = GeoPoint(30.0, 120.0)
        val point = PilgrimagePoint("point", "Point", GeoPoint(30.1, 120.1))
        return TourPlan(
            id = "amap-${mode.name}",
            anime = Anime(1L, "Anime"),
            selectedPoints = listOf(point),
            orderedPoints = listOf(point),
            legs = listOf(
                TourLeg(
                    from = from,
                    to = point.coordinate,
                    mode = mode,
                    geometry = emptyList(),
                    steps = emptyList(),
                    distanceMeters = 1.0,
                    durationSeconds = 0.0,
                    source = "AMap",
                    provider = MapProvider.AMAP,
                    coordinateSystem = CoordinateSystem.WGS84,
                    destinationPointId = point.id,
                ),
            ),
            mode = mode,
            objective = RouteObjective.FASTEST,
            endPolicy = EndPolicy.OPEN,
            estimatedDurationSeconds = 0.0,
            attribution = listOf("AMap"),
            dwellMinutes = 0,
            initialStart = from,
            executionStrategy = TransitExecutionStrategy.EXTERNAL_AMAP_MAINLAND,
            mapProvider = MapProvider.AMAP,
            coordinateSystem = CoordinateSystem.WGS84,
            regionDataVersion = "test",
        )
    }
}
