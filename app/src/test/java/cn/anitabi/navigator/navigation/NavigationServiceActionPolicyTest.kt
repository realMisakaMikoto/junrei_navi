package cn.anitabi.navigator.navigation

import cn.anitabi.navigator.core.model.Anime
import cn.anitabi.navigator.core.model.EndPolicy
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.NavigationProgress
import cn.anitabi.navigator.core.model.NavigationState
import cn.anitabi.navigator.core.model.PilgrimagePoint
import cn.anitabi.navigator.core.model.RouteObjective
import cn.anitabi.navigator.core.model.StoredTourV2
import cn.anitabi.navigator.core.model.TourPlan
import cn.anitabi.navigator.core.model.TransitExecutionStrategy
import cn.anitabi.navigator.core.model.TravelMode
import cn.anitabi.navigator.data.repository.SavedTour
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationServiceActionPolicyTest {
    @Test
    fun `location foreground actions require fine permission before promotion`() {
        assertTrue(
            externalLocationForegroundActionRequiresFinePermission(
                NavigationService.ACTION_PREPARE_HANDOFF,
            ),
        )
        assertTrue(
            externalLocationForegroundActionRequiresFinePermission(
                NavigationService.ACTION_PREPARE_NEXT_HANDOFF,
            ),
        )
        assertTrue(
            externalLocationForegroundActionRequiresFinePermission(
                NavigationService.ACTION_CONFIRM_EXTERNAL_ARRIVAL,
            ),
        )
        assertTrue(
            externalLocationForegroundActionRequiresFinePermission(
                NavigationService.ACTION_RESUME_EXTERNAL,
            ),
        )
    }

    @Test
    fun `pause and end stay available without location foreground promotion`() {
        assertFalse(
            externalLocationForegroundActionRequiresFinePermission(
                NavigationService.ACTION_PAUSE_EXTERNAL,
            ),
        )
        assertFalse(
            externalLocationForegroundActionRequiresFinePermission(
                NavigationService.ACTION_END_EXTERNAL,
            ),
        )
        assertFalse(externalLocationForegroundActionRequiresFinePermission(null))
    }

    @Test
    fun `external start and reload hints require fine location without changing road policy`() {
        listOf(
            NavigationService.ACTION_START,
            NavigationService.ACTION_RELOAD_TOUR,
            null,
        ).forEach { action ->
            assertTrue(locationForegroundRequestRequiresFinePermission(action, requireFineLocation = true))
            assertFalse(locationForegroundRequestRequiresFinePermission(action, requireFineLocation = false))
        }
        assertTrue(
            navigationPlanRequiresFineLocationForeground(
                TravelMode.TRANSIT,
                TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN,
            ),
        )
        listOf(TravelMode.DRIVE, TravelMode.BIKE, TravelMode.WALK, TravelMode.TRANSIT).forEach { mode ->
            assertTrue(
                navigationPlanRequiresFineLocationForeground(
                    mode,
                    TransitExecutionStrategy.EXTERNAL_AMAP_MAINLAND,
                ),
            )
        }
        assertFalse(
            navigationPlanRequiresFineLocationForeground(
                TravelMode.WALK,
                TransitExecutionStrategy.IN_APP_GOOGLE_ROUTES,
            ),
        )
    }

    @Test
    fun `location permission copy preserves Google Japan and identifies AMap`() {
        assertEquals(
            "需要精确定位权限才能继续日本公交行程",
            externalLocationPermissionMessage(
                TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN,
                resume = false,
            ),
        )
        assertEquals(
            "需要精确定位权限才能恢复高德地图外部分段导航",
            externalLocationPermissionMessage(
                TransitExecutionStrategy.EXTERNAL_AMAP_MAINLAND,
                resume = true,
            ),
        )
    }

    @Test
    fun `external transit keeps receiving location fixes while stationary`() {
        assertEquals(0f, navigationLocationUpdateMinDistanceMeters(isExternalJapanTransit = true), 0f)
        assertEquals(2f, navigationLocationUpdateMinDistanceMeters(isExternalJapanTransit = false), 0f)
    }

    @Test
    fun `foreground promotion failure preserves recovery data and stops running flags`() {
        val plan = plan()
        val progress = NavigationProgress(
            tourId = plan.id,
            state = NavigationState.NAVIGATING,
        )
        val previous = NavigationRuntimeState(
            plan = plan,
            progress = progress,
            instruction = "old",
            isRunning = true,
            isRerouting = true,
        )

        val failed = navigationRuntimeAfterForegroundPromotionFailure(previous, "blocked")

        assertFalse(failed.isRunning)
        assertFalse(failed.isRerouting)
        assertEquals("blocked", failed.errorMessage)
        assertEquals("导航已暂停，请打开应用重试", failed.instruction)
        assertSame(plan, failed.plan)
        assertSame(progress, failed.progress)
    }

    @Test
    fun `foreground promotion catches security and restricted-start runtime failures`() {
        listOf<RuntimeException>(SecurityException("permission"), IllegalStateException("background"))
            .forEach { expected ->
                var failures = 0
                val promoted = tryLocationForegroundPromotion(
                    promote = { throw expected },
                    onFailure = { actual ->
                        assertSame(expected, actual)
                        failures += 1
                    },
                )

                assertFalse(promoted)
                assertEquals(1, failures)
        }
    }

    @Test
    fun `paused and terminal external progress hides the overlay immediately`() {
        val navigating = NavigationProgress(tourId = "tour", state = NavigationState.NAVIGATING)

        listOf(
            NavigationState.NAVIGATING,
            NavigationState.ARRIVING,
            NavigationState.DWELLING,
            NavigationState.NEXT_STOP,
        ).forEach { state ->
            assertFalse(externalTransitOverlayMustHideImmediately(navigating.copy(state = state)))
        }
        assertTrue(externalTransitOverlayMustHideImmediately(navigating.copy(isPaused = true)))
        assertTrue(
            externalTransitOverlayMustHideImmediately(
                navigating.copy(state = NavigationState.COMPLETED),
            ),
        )
        assertTrue(
            externalTransitOverlayMustHideImmediately(
                navigating.copy(state = NavigationState.ENDED),
            ),
        )
    }

    @Test
    fun `only the current persisted terminal navigation can be finalized`() {
        val plan = plan()
        val terminal = NavigationProgress(tourId = plan.id, state = NavigationState.COMPLETED)
        fun canFinalize(
            persistedProgress: NavigationProgress = terminal,
            expectedGeneration: Long = 7L,
            currentGeneration: Long = 7L,
            stopping: Boolean = false,
            currentPlan: TourPlan? = plan,
            currentProgress: NavigationProgress? = persistedProgress,
        ): Boolean = shouldFinalizePersistedTerminalNavigation(
                persistedPlan = plan,
                persistedProgress = persistedProgress,
                expectedGeneration = expectedGeneration,
                currentGeneration = currentGeneration,
                stopping = stopping,
                currentPlan = currentPlan,
                currentProgress = currentProgress,
            )

        assertTrue(canFinalize())
        assertTrue(canFinalize(persistedProgress = terminal.copy(state = NavigationState.ENDED)))
        assertFalse(canFinalize(expectedGeneration = 6L))
        assertFalse(canFinalize(stopping = true))
        assertFalse(canFinalize(currentPlan = plan.copy(attribution = listOf("newer"))))
        assertFalse(canFinalize(currentProgress = terminal.copy(state = NavigationState.NAVIGATING)))
        assertFalse(
            canFinalize(
                persistedProgress = terminal.copy(state = NavigationState.NAVIGATING),
            ),
        )
    }

    @Test
    fun `failed reload never stops a different loading or stored tour`() {
        val requested = plan()
        val other = requested.copy(id = "other-tour")
        val progress = NavigationProgress(tourId = requested.id, state = NavigationState.NAVIGATING)

        assertFalse(
            shouldStopAfterReloadFailure(
                requestedPlan = requested,
                requestedProgress = progress,
                expectedRuntimePlan = requested,
                expectedRuntimeProgress = progress,
                latestPersisted = savedTour(requested, progress),
                runtime = NavigationRuntimeState(
                    plan = other,
                    progress = progress.copy(tourId = other.id),
                    isRunning = false,
                ),
                activeTourId = requested.id,
            ),
        )
        assertFalse(
            shouldStopAfterReloadFailure(
                requestedPlan = requested,
                requestedProgress = progress,
                expectedRuntimePlan = requested,
                expectedRuntimeProgress = progress,
                latestPersisted = savedTour(requested, progress),
                runtime = NavigationRuntimeState(plan = requested, progress = progress, isRunning = false),
                activeTourId = other.id,
            ),
        )
    }

    @Test
    fun `failed reload can stop only the unchanged requested tour`() {
        val requested = plan()
        val progress = NavigationProgress(tourId = requested.id, state = NavigationState.NAVIGATING)

        assertTrue(
            shouldStopAfterReloadFailure(
                requestedPlan = requested,
                requestedProgress = progress,
                expectedRuntimePlan = requested,
                expectedRuntimeProgress = progress,
                latestPersisted = savedTour(requested, progress),
                runtime = NavigationRuntimeState(plan = requested, progress = progress, isRunning = false),
                activeTourId = requested.id,
            ),
        )
        val newer = requested.copy(attribution = listOf("newer"))
        assertFalse(
            shouldStopAfterReloadFailure(
                requestedPlan = requested,
                requestedProgress = progress,
                expectedRuntimePlan = requested,
                expectedRuntimeProgress = progress,
                latestPersisted = savedTour(newer, progress),
                runtime = NavigationRuntimeState(plan = requested, progress = progress, isRunning = false),
                activeTourId = requested.id,
            ),
        )
    }

    @Test
    fun `reload progress changes do not make a persisted edit look lost`() {
        val requested = plan().copy(attribution = listOf("edited"))
        val original = plan()
        val completed = NavigationProgress(tourId = requested.id, state = NavigationState.COMPLETED)

        assertTrue(activeEditPlanStillPersisted(requested, savedTour(requested, completed)))
        assertFalse(activeEditPlanStillPersisted(requested, savedTour(original, completed)))
        assertFalse(activeEditPlanStillPersisted(requested, null))
    }

    private fun savedTour(plan: TourPlan, progress: NavigationProgress) = SavedTour(
        storedTour = StoredTourV2.from(plan, progress),
        plan = plan,
        progress = progress,
        routeNeedsRefresh = false,
    )

    private fun plan(): TourPlan {
        val point = PilgrimagePoint("point", "Point", GeoPoint(35.0, 139.0))
        return TourPlan(
            id = "tour",
            anime = Anime(1L, "Test"),
            selectedPoints = listOf(point),
            orderedPoints = listOf(point),
            legs = emptyList(),
            mode = TravelMode.TRANSIT,
            objective = RouteObjective.FASTEST,
            endPolicy = EndPolicy.OPEN,
            estimatedDurationSeconds = 0.0,
            attribution = emptyList(),
            initialStart = point.coordinate,
            executionStrategy = TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN,
        )
    }
}
