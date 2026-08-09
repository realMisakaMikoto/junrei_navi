package cn.anitabi.navigator.navigation

import cn.anitabi.navigator.core.model.Anime
import cn.anitabi.navigator.core.model.CoordinateSystem
import cn.anitabi.navigator.core.model.EndPolicy
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.core.model.PilgrimagePoint
import cn.anitabi.navigator.core.model.RouteObjective
import cn.anitabi.navigator.core.model.TourLeg
import cn.anitabi.navigator.core.model.TourPlan
import cn.anitabi.navigator.core.model.TransitExecutionStrategy
import cn.anitabi.navigator.core.model.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalHandoffPolicyTest {
    private val from = GeoPoint(30.0, 120.0)
    private val destination = PilgrimagePoint("destination", "目的地 / A", GeoPoint(30.1, 120.1))

    @Test
    fun `all AMap modes hand off WGS84 endpoints with exact mode metadata`() {
        TravelMode.entries.forEach { mode ->
            val plan = amapPlan(mode, emptyList(), CoordinateSystem.WGS84)
            val leg = plan.legs.single()

            assertNull(externalHandoffMismatch(plan, leg))
            assertEquals("当前位置" to destination.name, externalHandoffNames(plan, 0))
            val ready = response(plan, leg).readyHandoff()
            assertEquals(mode, ready?.travelMode)
            assertEquals(from, ready?.originWgs84)
            assertEquals(destination.coordinate, ready?.destinationWgs84)
        }
    }

    @Test
    fun `AMap route geometry is accepted only when already GCJ02`() {
        val valid = amapPlan(TravelMode.DRIVE, listOf(from, destination.coordinate), CoordinateSystem.GCJ02)
        val invalid = amapPlan(TravelMode.DRIVE, listOf(from, destination.coordinate), CoordinateSystem.WGS84)

        assertNull(externalHandoffMismatch(valid, valid.legs.single()))
        assertNotNull(externalHandoffMismatch(invalid, invalid.legs.single()))
        assertNotNull(
            externalHandoffMismatch(
                valid.copy(coordinateSystem = CoordinateSystem.WGS84),
                valid.legs.single(),
            ),
        )
        assertNotNull(
            externalHandoffMismatch(
                valid.copy(mapProvider = MapProvider.GOOGLE),
                valid.legs.single(),
            ),
        )
    }

    @Test
    fun `malformed or in-app service metadata cannot launch an external map`() {
        val plan = amapPlan(TravelMode.WALK, emptyList(), CoordinateSystem.WGS84)
        val leg = plan.legs.single()
        val valid = response(plan, leg)

        assertNull(valid.copy(executionStrategy = "UNKNOWN").readyHandoff())
        assertNull(valid.copy(travelMode = "UNKNOWN").readyHandoff())
        assertNull(valid.copy(originName = " ").readyHandoff())
        assertNull(
            valid.copy(executionStrategy = TransitExecutionStrategy.IN_APP_GOOGLE_ROUTES.name)
                .readyHandoff(),
        )
    }

    private fun response(plan: TourPlan, leg: TourLeg) = TransitHandoffServiceResponse(
        message = null,
        expectedLegIndex = 0,
        originLatitude = leg.from.latitude,
        originLongitude = leg.from.longitude,
        destinationLatitude = leg.to.latitude,
        destinationLongitude = leg.to.longitude,
        executionStrategy = plan.executionStrategy.name,
        travelMode = leg.mode.name,
        originName = "当前位置",
        destinationName = destination.name,
    )

    private fun amapPlan(
        mode: TravelMode,
        geometry: List<GeoPoint>,
        coordinateSystem: CoordinateSystem,
    ) = TourPlan(
        id = "amap-${mode.name}",
        anime = Anime(1L, "Anime"),
        selectedPoints = listOf(destination),
        orderedPoints = listOf(destination),
        legs = listOf(
            TourLeg(
                from = from,
                to = destination.coordinate,
                mode = mode,
                geometry = geometry,
                steps = emptyList(),
                distanceMeters = 1.0,
                durationSeconds = 0.0,
                source = "AMap",
                provider = MapProvider.AMAP,
                coordinateSystem = coordinateSystem,
                destinationPointId = destination.id,
            ),
        ),
        mode = mode,
        objective = RouteObjective.FASTEST,
        endPolicy = EndPolicy.OPEN,
        estimatedDurationSeconds = 0.0,
        attribution = listOf("AMap"),
        executionStrategy = TransitExecutionStrategy.EXTERNAL_AMAP_MAINLAND,
        mapProvider = MapProvider.AMAP,
        coordinateSystem = coordinateSystem,
        regionDataVersion = "test",
    )
}
