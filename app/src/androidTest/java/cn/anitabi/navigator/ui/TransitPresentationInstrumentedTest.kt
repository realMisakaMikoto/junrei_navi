package cn.anitabi.navigator.ui

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cn.anitabi.navigator.SyntheticDiscoveryFixture
import cn.anitabi.navigator.core.model.EndPolicy
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.core.model.NavigationProgress
import cn.anitabi.navigator.core.model.NavigationState
import cn.anitabi.navigator.core.model.RouteObjective
import cn.anitabi.navigator.core.model.TourLeg
import cn.anitabi.navigator.core.model.TourPlan
import cn.anitabi.navigator.core.model.TransitExecutionStrategy
import cn.anitabi.navigator.core.model.TransitLegDetails
import cn.anitabi.navigator.core.model.TravelMode
import cn.anitabi.navigator.navigation.NavigationRuntimeState
import cn.anitabi.navigator.security.AppAppearance
import cn.anitabi.navigator.ui.navigation.NavigationDetailPanel
import cn.anitabi.navigator.ui.theme.AnitabiTheme
import cn.anitabi.navigator.ui.theme.MapSurfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Presentation/callback fixtures only: no maps, route engine, service, GPS or external activities. */
class TransitPresentationInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun populatedTransitTimelineAndRefreshFailureKeepActionsReachableAtLargeFont() {
        val harness = Harness(plan(TransitExecutionStrategy.IN_APP_GOOGLE_ROUTES))
        show(harness, AppAppearance.DARK, fontScale = 1.5f, external = false)

        composeRule.onNodeWithText(NEXT_INSTRUCTION).assertIsDisplayed()
        composeRule.onNodeWithText(CURRENT_LEG).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(PANEL).captureFrontendReview("transit-current-timeline")
        listOf("Synthetic departure", "Synthetic transfer", "Synthetic arrival").forEach { stop ->
            composeRule.onNodeWithText(stop).performScrollTo().assertIsDisplayed()
        }
        composeRule.onNodeWithText("09:00", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("09:20", substring = true).performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText(REFRESH).performScrollTo().assertIsEnabled().performClick()
        composeRule.onNodeWithText(REFRESH).assertIsNotEnabled()
        composeRule.onNodeWithText(REROUTING, substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(PANEL).captureFrontendReview("transit-refreshing")
        composeRule.runOnIdle {
            harness.state = harness.state.copy(isRerouting = false, errorMessage = REFRESH_FAILURE)
        }
        composeRule.onNodeWithText(REFRESH_FAILURE).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(PANEL).captureFrontendReview("transit-refresh-failure")
        composeRule.onNodeWithText(REFRESH).performScrollTo().assertIsEnabled()
        composeRule.onNodeWithText(ARRIVED).performScrollTo().assertIsEnabled().performClick()
        composeRule.onNodeWithText(END_NAVIGATION).performScrollTo().assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(listOf("refresh", "arrived", "stop"), harness.actions) }
    }

    @Test
    fun japanExternalPausedResumeAndNextLegKeepExplicitCallbacks() {
        verifyExternalControls(
            TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN,
            EXTERNAL_JAPAN, AppAppearance.LIGHT, "japan",
        )
    }

    @Test
    fun amapExternalPausedResumeAndNextLegKeepExplicitCallbacks() {
        verifyExternalControls(
            TransitExecutionStrategy.EXTERNAL_AMAP_MAINLAND,
            EXTERNAL_AMAP, AppAppearance.DARK, "amap",
        )
    }

    private fun verifyExternalControls(
        strategy: TransitExecutionStrategy,
        providerLabel: String,
        appearance: AppAppearance,
        scene: String,
    ) {
        val harness = Harness(plan(strategy))
        harness.state = harness.state.copy(
            isRunning = false, progress = requireNotNull(harness.state.progress).copy(isPaused = true),
        )
        show(harness, appearance, fontScale = 1f, external = true)
        composeRule.onNodeWithText(providerLabel, substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(OPEN_LEG).assertDoesNotExist()
        composeRule.onNodeWithText(RESUME).performScrollTo().assertIsEnabled()
        composeRule.onNodeWithTag(PANEL).captureFrontendReview("external-$scene-paused")
        composeRule.onNodeWithText(RESUME).performClick()
        composeRule.onNodeWithText(OPEN_LEG).performScrollTo().assertIsEnabled().performClick()
        composeRule.onNodeWithText(PAUSE).performScrollTo().assertIsEnabled().performClick()
        composeRule.onNodeWithText(OPEN_LEG).assertDoesNotExist()
        composeRule.onNodeWithText(RESUME).performScrollTo().assertIsEnabled().performClick()
        composeRule.runOnIdle {
            harness.state = harness.state.copy(
                progress = requireNotNull(harness.state.progress).copy(state = NavigationState.NEXT_STOP),
            )
        }
        composeRule.onNodeWithText(OPEN_LEG).assertDoesNotExist()
        composeRule.onNodeWithText(NEXT_LEG).performScrollTo().assertIsEnabled()
        composeRule.onNodeWithTag(PANEL).captureFrontendReview("external-$scene-next-leg")
        composeRule.onNodeWithText(NEXT_LEG).performClick()
        composeRule.onNodeWithText(END_TOUR).performScrollTo().assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("resume", "open", "pause", "resume", "next", "stop"), harness.actions)
        }
    }

    private fun show(harness: Harness, appearance: AppAppearance, fontScale: Float, external: Boolean) {
        composeRule.setContent {
            AnitabiTheme(appearance = appearance) {
                MapSurfaceTheme {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                        NavigationDetailPanel(
                            plan = harness.plan, state = harness.state,
                            onStop = { harness.actions += "stop" },
                            onArrived = { harness.actions += "arrived" },
                            onRefreshTransit = {
                                harness.actions += "refresh"
                                harness.state = harness.state.copy(isRerouting = true)
                            },
                            onOpenExternalLeg = { harness.actions += "open" },
                            onStartNextExternalLeg = { harness.actions += "next" },
                            onPauseExternal = { harness.setPaused(true) },
                            onResumeExternal = { harness.setPaused(false) },
                            onEditFuture = {},
                            modifier = Modifier.requiredSize(360.dp, 320.dp),
                            transitDetailsScrollable = !external, fillAvailableHeight = true,
                        )
                    }
                }
            }
        }
    }

    private class Harness(val plan: TourPlan) {
        val actions = mutableListOf<String>()
        var state by mutableStateOf(NavigationRuntimeState(
            plan = plan, progress = NavigationProgress(
                tourId = plan.id, legIndex = 1, state = NavigationState.NAVIGATING,
            ),
            instruction = NEXT_INSTRUCTION, isRunning = true,
        ))

        // This models only the state delivered back to the panel after its callback; no engine runs.
        fun setPaused(paused: Boolean) {
            actions += if (paused) "pause" else "resume"
            state = state.copy(isRunning = !paused, progress = requireNotNull(state.progress).copy(isPaused = paused))
        }
    }

    private fun plan(strategy: TransitExecutionStrategy): TourPlan {
        val snapshot = SyntheticDiscoveryFixture.snapshot()
        val points = snapshot.points.map { it.toPilgrimagePoint() }
        val provider = if (strategy == TransitExecutionStrategy.EXTERNAL_AMAP_MAINLAND) MapProvider.AMAP else MapProvider.GOOGLE
        val connector = TourLeg(
            from = points.first().coordinate, to = points.first().coordinate, mode = TravelMode.WALK,
            geometry = emptyList(), steps = emptyList(), distanceMeters = 0.0, durationSeconds = 0.0,
            source = "Synthetic presentation fixture", provider = provider,
        )
        val transit = connector.copy(
            to = points.last().coordinate, mode = TravelMode.TRANSIT, destinationPointId = points.last().id,
            transit = TransitLegDetails(
                vehicleMode = "BUS", line = "Synthetic line", direction = "Synthetic direction",
                departureStop = "Synthetic departure", arrivalStop = "Synthetic arrival", stopCount = 3,
                departureTime = "09:00", arrivalTime = "09:20", departurePlatform = "A",
                intermediateStops = listOf("Synthetic transfer"),
            ),
        )
        return TourPlan(
            id = "synthetic-transit-presentation", anime = snapshot.subjects.single().anime,
            selectedPoints = points, orderedPoints = listOf(points.last()), legs = listOf(connector, transit),
            mode = TravelMode.TRANSIT, objective = RouteObjective.FASTEST, endPolicy = EndPolicy.OPEN,
            estimatedDurationSeconds = 0.0, attribution = emptyList(), initialStart = points.first().coordinate,
            executionStrategy = strategy, mapProvider = provider,
        )
    }

    private companion object {
        const val PANEL = "navigation-control-panel"
        const val NEXT_INSTRUCTION = "Synthetic next transit action"
        const val REFRESH_FAILURE = "Synthetic refresh failed; previous segment retained"
        const val CURRENT_LEG = "\u516c\u4ea4\u884c\u7a0b 2/2  \u00b7  Synthetic line"
        const val REFRESH = "\u91cd\u7b97\u5269\u4f59\u516c\u4ea4\u884c\u7a0b"
        const val REROUTING = "\u6b63\u5728\u91cd\u7b97\u5269\u4f59\u8def\u7ebf"
        const val ARRIVED = "\u786e\u8ba4\u5230\u8fbe"
        const val END_NAVIGATION = "\u7ed3\u675f\u5bfc\u822a"
        const val OPEN_LEG = "\u6253\u5f00\u672c\u6bb5"
        const val NEXT_LEG = "\u5f00\u59cb\u4e0b\u4e00\u6bb5"
        const val RESUME = "\u6062\u590d\u884c\u7a0b"
        const val PAUSE = "\u6682\u505c\u884c\u7a0b"
        const val END_TOUR = "\u7ed3\u675f\u884c\u7a0b"
        const val EXTERNAL_JAPAN = "Google Maps \u5916\u90e8\u5206\u6bb5\u516c\u4ea4"
        const val EXTERNAL_AMAP = "\u9ad8\u5fb7\u5730\u56fe\u5916\u90e8\u5206\u6bb5\u516c\u4ea4\u8def\u7ebf"
    }
}
