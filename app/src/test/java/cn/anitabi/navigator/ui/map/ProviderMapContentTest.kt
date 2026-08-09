package cn.anitabi.navigator.ui.map

import cn.anitabi.navigator.core.model.Anime
import cn.anitabi.navigator.core.model.CoordinateSystem
import cn.anitabi.navigator.core.model.EndPolicy
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.core.model.RouteObjective
import cn.anitabi.navigator.core.model.TourLeg
import cn.anitabi.navigator.core.model.TourPlan
import cn.anitabi.navigator.core.model.TransitExecutionStrategy
import cn.anitabi.navigator.core.model.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderMapContentTest {
    private val from = GeoPoint(10.0, 20.0)
    private val to = GeoPoint(11.0, 21.0)

    @Test
    fun `Google content accepts only Google WGS84 legs`() {
        val valid = plan(MapProvider.GOOGLE, CoordinateSystem.WGS84, leg(MapProvider.GOOGLE, CoordinateSystem.WGS84))

        assertNull(mapContentMismatch(MapProvider.GOOGLE, valid))
        assertNotNull(mapContentMismatch(MapProvider.AMAP, valid))
        assertNotNull(
            mapContentMismatch(
                MapProvider.GOOGLE,
                valid.copy(legs = listOf(leg(MapProvider.GOOGLE, CoordinateSystem.GCJ02))),
            ),
        )
    }

    @Test
    fun `AMap placeholder endpoints may remain WGS84 but route geometry must be GCJ02`() {
        val placeholder = plan(
            MapProvider.AMAP,
            CoordinateSystem.WGS84,
            leg(MapProvider.AMAP, CoordinateSystem.WGS84, geometry = emptyList()),
        )
        val routed = plan(
            MapProvider.AMAP,
            CoordinateSystem.GCJ02,
            leg(MapProvider.AMAP, CoordinateSystem.GCJ02, geometry = listOf(from, to)),
        )

        assertNull(mapContentMismatch(MapProvider.AMAP, placeholder))
        assertNull(mapContentMismatch(MapProvider.AMAP, routed))
        assertNotNull(
            mapContentMismatch(
                MapProvider.AMAP,
                placeholder.copy(legs = listOf(leg(MapProvider.AMAP, CoordinateSystem.WGS84, listOf(from, to)))),
            ),
        )
        assertNotNull(mapContentMismatch(MapProvider.AMAP, routed.copy(coordinateSystem = CoordinateSystem.WGS84)))
    }

    @Test
    fun `persisted markers use converter while GCJ02 geometry is passed through unchanged`() {
        val convertedInputs = mutableListOf<GeoPoint>()
        val converted = convertPersistedWgs84Points(
            points = listOf(from, to),
            converter = Wgs84ToGcj02Converter { point ->
                convertedInputs += point
                AmapDisplayCoordinate(point.latitude + 0.1, point.longitude + 0.2)
            },
        )
        val route = amapRouteGeometryForDisplay(
            leg(MapProvider.AMAP, CoordinateSystem.GCJ02, geometry = listOf(from, to)),
        )

        assertEquals(listOf(from, to), convertedInputs)
        assertEquals(AmapDisplayCoordinate(10.1, 20.2), converted.first())
        assertEquals(
            listOf(AmapDisplayCoordinate(10.0, 20.0), AmapDisplayCoordinate(11.0, 21.0)),
            route,
        )
    }

    private fun leg(
        provider: MapProvider,
        coordinateSystem: CoordinateSystem,
        geometry: List<GeoPoint> = emptyList(),
    ) = TourLeg(
        from = from,
        to = to,
        mode = TravelMode.WALK,
        geometry = geometry,
        steps = emptyList(),
        distanceMeters = 1.0,
        durationSeconds = 1.0,
        source = provider.name,
        provider = provider,
        coordinateSystem = coordinateSystem,
    )

    private fun plan(
        provider: MapProvider,
        coordinateSystem: CoordinateSystem,
        leg: TourLeg,
    ) = TourPlan(
        id = "tour",
        anime = Anime(1L, "Anime"),
        selectedPoints = emptyList(),
        orderedPoints = emptyList(),
        legs = listOf(leg),
        mode = TravelMode.WALK,
        objective = RouteObjective.FASTEST,
        endPolicy = EndPolicy.OPEN,
        estimatedDurationSeconds = 1.0,
        attribution = emptyList(),
        executionStrategy = if (provider == MapProvider.AMAP) {
            TransitExecutionStrategy.EXTERNAL_AMAP_MAINLAND
        } else {
            TransitExecutionStrategy.IN_APP_GOOGLE_ROUTES
        },
        mapProvider = provider,
        coordinateSystem = coordinateSystem,
        regionDataVersion = "test",
    )
}
