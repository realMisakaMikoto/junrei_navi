package cn.anitabi.navigator.navigation

import cn.anitabi.navigator.core.model.Anime
import cn.anitabi.navigator.core.model.CoordinateSystem
import cn.anitabi.navigator.core.model.EndPolicy
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.core.model.NavigationProgress
import cn.anitabi.navigator.core.model.NavigationState
import cn.anitabi.navigator.core.model.PilgrimagePoint
import cn.anitabi.navigator.core.model.RouteObjective
import cn.anitabi.navigator.core.model.StoredTourV2
import cn.anitabi.navigator.core.model.TourLeg
import cn.anitabi.navigator.core.model.TourPlan
import cn.anitabi.navigator.core.model.TransitExecutionStrategy
import cn.anitabi.navigator.core.model.TravelMode
import cn.anitabi.navigator.data.repository.SavedTour
import cn.anitabi.navigator.data.repository.StoredRoutingError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalMapRecoveryPolicyTest {
    @Test
    fun `AMap road navigation receives the same cold recovery control entry`() {
        val plan = amapPlan(TravelMode.DRIVE)
        val progress = NavigationProgress(plan.id, state = NavigationState.NAVIGATING)
        val saved = SavedTour(
            storedTour = StoredTourV2.from(plan, progress),
            plan = plan,
            progress = progress,
            routeNeedsRefresh = false,
        )

        assertEquals(
            NavigationBootRestoreAction.SHOW_EXTERNAL_JAPAN_CONTROL,
            navigationBootRestoreAction(saved),
        )
        assertEquals(
            "已恢复高德地图外部分段导航，不会自动打开高德地图",
            externalRecoveryInstruction(plan, paused = false),
        )
        assertNull(externalPlanStartMismatch(plan))
    }

    @Test
    fun `unresolved persisted routing stays listed but cannot restore or start`() {
        val plan = amapPlan(TravelMode.WALK).copy(legs = emptyList(), regionDataVersion = null)
        val progress = NavigationProgress(plan.id, state = NavigationState.NAVIGATING)
        val saved = SavedTour(
            storedTour = StoredTourV2.from(plan, progress),
            plan = plan,
            progress = progress,
            routeNeedsRefresh = true,
            routingError = StoredRoutingError.REGION_UNRESOLVED,
        )

        assertEquals(NavigationBootRestoreAction.IGNORE_NON_EXTERNAL, navigationBootRestoreAction(saved))
        assertNotNull(externalPlanStartMismatch(plan))
        assertEquals(
            "无法安全判定地图地区，未启动导航",
            storedRoutingErrorMessage(StoredRoutingError.REGION_UNRESOLVED),
        )
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
            initialStart = from,
            executionStrategy = TransitExecutionStrategy.EXTERNAL_AMAP_MAINLAND,
            mapProvider = MapProvider.AMAP,
            coordinateSystem = CoordinateSystem.WGS84,
            regionDataVersion = "test",
        )
    }
}
