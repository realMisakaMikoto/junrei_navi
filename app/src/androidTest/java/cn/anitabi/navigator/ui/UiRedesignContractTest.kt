package cn.anitabi.navigator.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import cn.anitabi.navigator.core.model.Anime
import cn.anitabi.navigator.core.model.EndPolicy
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.NavigationProgress
import cn.anitabi.navigator.core.model.NavigationState
import cn.anitabi.navigator.core.model.PilgrimagePoint
import cn.anitabi.navigator.core.model.RouteObjective
import cn.anitabi.navigator.core.model.RouteStep
import cn.anitabi.navigator.core.model.TourLeg
import cn.anitabi.navigator.core.model.TourPlan
import cn.anitabi.navigator.core.model.TransitExecutionStrategy
import cn.anitabi.navigator.core.model.TravelMode
import cn.anitabi.navigator.data.repository.PilgrimageData
import cn.anitabi.navigator.navigation.NavigationRuntimeState
import cn.anitabi.navigator.telemetry.TelemetryConsent
import cn.anitabi.navigator.telemetry.TelemetryConsentController
import cn.anitabi.navigator.telemetry.TelemetryConsentStore
import cn.anitabi.navigator.telemetry.TelemetryRuntime
import cn.anitabi.navigator.ui.about.AboutScreen
import cn.anitabi.navigator.ui.navigation.NavigationDetailPanel
import cn.anitabi.navigator.ui.planner.PlannerSettingsScreen
import cn.anitabi.navigator.ui.planner.PlannerUiState
import cn.anitabi.navigator.ui.planner.RoutePreviewDetails
import cn.anitabi.navigator.ui.planner.UnavailableRouteEndpoint
import cn.anitabi.navigator.ui.planner.UnavailableRouteSegment
import cn.anitabi.navigator.ui.search.PilgrimageSelectionScreen
import cn.anitabi.navigator.ui.search.SearchScreen
import cn.anitabi.navigator.ui.search.SearchUiState
import cn.anitabi.navigator.ui.theme.AnitabiTheme
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class UiRedesignContractTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun searchScreen_keepsSearchActionAndSelectedWorkSummary() {
        var searched = false
        val data = pilgrimageData()

        composeRule.setContent {
            AnitabiTheme {
                SearchScreen(
                    state = SearchUiState(
                        query = "巡礼作品",
                        searchResults = listOf(data.anime),
                        selectedAnimeData = mapOf(data.anime.subjectId to data),
                    ),
                    onQueryChange = {},
                    onSearch = { searched = true },
                    onAnimeToggle = {},
                    onOpenSelection = {},
                    onOpenAbout = {},
                )
            }
        }

        composeRule.onNodeWithTag("search-screen").assertIsDisplayed()
        composeRule.onNodeWithText("已选作品").assertIsDisplayed()
        composeRule.onNodeWithText("搜索 Bangumi").performClick()
        composeRule.runOnIdle { assertTrue(searched) }
    }

    @Test
    fun searchScreen_shortLandscapeHeightKeepsFooterAndResultsReachable() {
        val data = pilgrimageData()

        composeRule.setContent {
            AnitabiTheme {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp),
                ) {
                    SearchScreen(
                        state = SearchUiState(
                            query = "巡礼作品",
                            searchResults = listOf(data.anime),
                            selectedAnimeData = mapOf(data.anime.subjectId to data),
                        ),
                        onQueryChange = {},
                        onSearch = {},
                        onAnimeToggle = {},
                        onOpenSelection = {},
                        onOpenAbout = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag("search-selection-footer").assertIsDisplayed()
        composeRule.onNodeWithTag("search-content").performScrollToNode(hasText("Bangumi #7"))
        composeRule.onNodeWithText("Bangumi #7").assertIsDisplayed()
        composeRule.onNodeWithTag("search-selection-footer").assertIsDisplayed()
    }

    @Test
    fun pointSelection_forcedListModeNeverCreatesMapAndKeepsPlanningAction() {
        val data = pilgrimageData()
        var toggledPointId: String? = null
        var planned = false

        composeRule.setContent {
            AnitabiTheme {
                PilgrimageSelectionScreen(
                    state = SearchUiState(
                        selectedAnimeData = mapOf(data.anime.subjectId to data),
                        selectedPointIds = setOf("7::p1", "7::p2"),
                        showList = true,
                        selectionOpen = true,
                    ),
                    onBack = {},
                    onTogglePoint = { toggledPointId = it },
                    onBoundsChanged = {},
                    onSelectVisible = {},
                    onClearSelection = {},
                    onShowList = {},
                    onMapUnavailable = {},
                    onPlan = { planned = true },
                    forceListMode = true,
                )
            }
        }

        composeRule.onNodeWithTag("point-selection-screen").assertIsDisplayed()
        composeRule.onNodeWithText("名场面一").performClick()
        composeRule.runOnIdle { assertEquals("7::p1", toggledPointId) }
        composeRule.onNodeWithText("规划路线").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertTrue(planned) }
    }

    @Test
    fun plannerSettings_centersModeContentAndKeepsGenerateRouteAction() {
        var generated = false
        val data = pilgrimageData()

        composeRule.setContent {
            AnitabiTheme {
                PlannerSettingsScreen(
                    state = PlannerUiState(
                        anime = data.anime,
                        selectedPoints = data.points,
                        transitDate = LocalDate.of(2026, 8, 1),
                        transitTime = LocalTime.NOON,
                    ),
                    onBack = {},
                    onModeChange = {},
                    onObjectiveChange = {},
                    onEndPolicyChange = {},
                    onStartChange = {},
                    onUseCurrentLocation = {},
                    onFixedEndChange = {},
                    onTransitScheduleChange = { _, _, _ -> },
                    onTransitPreferenceChange = {},
                    onTransitTravelModeToggle = {},
                    onDwellChange = {},
                    onGenerate = { generated = true },
                )
            }
        }

        composeRule.onNodeWithTag("planner-settings-screen").assertIsDisplayed()
        composeRule.onNodeWithText("出行方式").assertIsDisplayed()
        val driveCardBounds = composeRule
            .onNodeWithTag("planner-mode-DRIVE")
            .fetchSemanticsNode()
            .boundsInRoot
        val driveLabelBounds = composeRule
            .onNodeWithText("驾车", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(abs(driveCardBounds.center.y - driveLabelBounds.center.y) <= 2f)
        composeRule.onNodeWithText("生成路线").performClick()
        composeRule.runOnIdle { assertTrue(generated) }
    }

    @Test
    fun plannerSettings_requiresExplicitClickForAmapFallback() {
        val data = pilgrimageData()
        var fallbackSelected = false

        composeRule.setContent {
            AnitabiTheme {
                PlannerSettingsScreen(
                    state = PlannerUiState(
                        anime = data.anime,
                        selectedPoints = data.points,
                        transitDate = LocalDate.of(2026, 8, 1),
                        transitTime = LocalTime.NOON,
                        errorMessage = "路线服务暂时不可用",
                        amapExternalFallbackAvailable = true,
                    ),
                    onBack = {},
                    onModeChange = {},
                    onObjectiveChange = {},
                    onEndPolicyChange = {},
                    onStartChange = {},
                    onUseCurrentLocation = {},
                    onFixedEndChange = {},
                    onTransitScheduleChange = { _, _, _ -> },
                    onTransitPreferenceChange = {},
                    onTransitTravelModeToggle = {},
                    onDwellChange = {},
                    onGenerate = {},
                    onUseAmapExternalFallback = { fallbackSelected = true },
                )
            }
        }

        composeRule.runOnIdle { assertEquals(false, fallbackSelected) }
        composeRule.onNodeWithTag("planner-use-amap-external-fallback")
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle { assertTrue(fallbackSelected) }
    }

    @Test
    fun plannerNoRouteDetails_showExactEndpointsAndGoogleMapsAction() {
        val origin = PilgrimagePoint("origin", "测试起点", GeoPoint(12.345678, -98.765432))
        val destination = PilgrimagePoint("destination", "测试终点", GeoPoint(-1.25, 2.5))
        val unavailableSegment = UnavailableRouteSegment(
            segmentNumber = 1,
            segmentCount = 2,
            origin = UnavailableRouteEndpoint(origin.name, origin.coordinate),
            destination = UnavailableRouteEndpoint(destination.name, destination.coordinate),
        )
        var openedSegment: UnavailableRouteSegment? = null

        composeRule.setContent {
            AnitabiTheme {
                PlannerSettingsScreen(
                    state = PlannerUiState(
                        anime = Anime(subjectId = 8, name = "Route Detail Test"),
                        selectedPoints = listOf(origin, destination),
                        mode = TravelMode.TRANSIT,
                        transitDate = LocalDate.of(2026, 8, 1),
                        transitTime = LocalTime.NOON,
                        errorMessage = "第 1/2 段未找到路线",
                        unavailableRouteSegment = unavailableSegment,
                    ),
                    onBack = {},
                    onModeChange = {},
                    onObjectiveChange = {},
                    onEndPolicyChange = {},
                    onStartChange = {},
                    onUseCurrentLocation = {},
                    onFixedEndChange = {},
                    onTransitScheduleChange = { _, _, _ -> },
                    onTransitPreferenceChange = {},
                    onTransitTravelModeToggle = {},
                    onDwellChange = {},
                    onGenerate = {},
                    onOpenGoogleMaps = {
                        openedSegment = it
                        false
                    },
                )
            }
        }

        composeRule.onNodeWithTag("planner-no-route-details-action").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("planner-no-route-details-sheet").assertIsDisplayed()
        composeRule.onNodeWithText("测试起点").assertIsDisplayed()
        composeRule.onNodeWithText("纬度 12.345678").assertIsDisplayed()
        composeRule.onNodeWithText("经度 -98.765432").assertIsDisplayed()
        composeRule.onNodeWithText("测试终点").assertIsDisplayed()
        composeRule.onNodeWithText("纬度 -1.250000").assertIsDisplayed()
        composeRule.onNodeWithText("经度 2.500000").assertIsDisplayed()
        composeRule.onNodeWithTag("planner-open-google-maps-route")
            .performScrollTo()
            .performClick()
        composeRule.runOnIdle { assertEquals(unavailableSegment, openedSegment) }
        composeRule.onNodeWithTag("planner-google-maps-launch-error")
            .assertIsDisplayed()
            .assertTextContains("无法打开 Google 地图或网页链接")
    }

    @Test
    fun routePreviewDetails_keepsSummaryOrderAndPrimaryAction() {
        val plan = tourPlan()
        var started = false

        composeRule.setContent {
            AnitabiTheme {
                RoutePreviewDetails(
                    state = PlannerUiState(
                        anime = plan.anime,
                        selectedPoints = plan.selectedPoints,
                        plan = plan,
                        draftOrder = plan.orderedPoints,
                    ),
                    plan = plan,
                    transitSections = emptyList(),
                    onMove = { _, _ -> },
                    onApplyOrder = {},
                    onStartNavigation = { started = true },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        composeRule.onNodeWithTag("route-preview-details").assertIsDisplayed()
        composeRule.onNodeWithText("巡礼点顺序").assertIsDisplayed()
        composeRule.onNodeWithText("开始连续导航").performClick()
        composeRule.runOnIdle { assertTrue(started) }
    }

    @Test
    fun aboutScreen_keepsTelemetryOptInExplicitAndImmediate() {
        val store = FakeTelemetryConsentStore()
        val runtime = FakeTelemetryRuntime()
        val controller = TelemetryConsentController(store, runtime)

        composeRule.setContent {
            AnitabiTheme {
                AboutScreen(onBack = {}, telemetryConsentController = controller)
            }
        }

        composeRule.onNodeWithTag("about-screen").assertIsDisplayed()
        composeRule.onNodeWithText("匿名使用分析").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertTrue(store.consent.analyticsEnabled)
            assertTrue(runtime.analyticsEnabled)
        }
    }

    @Test
    fun navigationControlPanel_keepsImmediateStopAndArrivalActions() {
        val plan = tourPlan()
        var stopped = false
        var arrived = false
        val state = NavigationRuntimeState(
            plan = plan,
            progress = NavigationProgress(
                tourId = plan.id,
                state = NavigationState.NAVIGATING,
            ),
            instruction = "沿道路继续前行",
            remainingDistanceMeters = 850.0,
            isRunning = true,
        )

        composeRule.setContent {
            AnitabiTheme {
                NavigationDetailPanel(
                    plan = plan,
                    state = state,
                    onStop = { stopped = true },
                    onArrived = { arrived = true },
                    onRefreshTransit = {},
                    onOpenExternalLeg = {},
                    onStartNextExternalLeg = {},
                    onPauseExternal = {},
                    onResumeExternal = {},
                    onEditFuture = {},
                    modifier = Modifier.fillMaxSize(),
                    transitDetailsScrollable = false,
                    fillAvailableHeight = true,
                )
            }
        }

        composeRule.onNodeWithTag("navigation-control-panel").assertIsDisplayed()
        composeRule.onNodeWithText("确认到达").assertIsEnabled().performClick()
        composeRule.onNodeWithText("结束导航").performClick()
        composeRule.runOnIdle {
            assertTrue(arrived)
            assertTrue(stopped)
        }
    }

    @Test
    fun japanTransitDwelling_exposesEarlyLeaveAsAnImmediateUserAction() {
        val basePlan = tourPlan()
        val plan = basePlan.copy(
            mode = TravelMode.TRANSIT,
            legs = basePlan.legs.map { it.copy(mode = TravelMode.TRANSIT) },
            executionStrategy = TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN,
        )
        var leftEarly = false
        val state = NavigationRuntimeState(
            plan = plan,
            progress = NavigationProgress(
                tourId = plan.id,
                state = NavigationState.DWELLING,
                dwellingUntilEpochMillis = Long.MAX_VALUE,
            ),
            instruction = "正在停留",
            isRunning = true,
        )

        composeRule.setContent {
            AnitabiTheme {
                NavigationDetailPanel(
                    plan = plan,
                    state = state,
                    onStop = {},
                    onArrived = {},
                    onRefreshTransit = {},
                    onOpenExternalLeg = {},
                    onStartNextExternalLeg = { leftEarly = true },
                    onPauseExternal = {},
                    onResumeExternal = {},
                    onEditFuture = {},
                    modifier = Modifier.fillMaxSize(),
                    transitDetailsScrollable = false,
                    fillAvailableHeight = true,
                )
            }
        }

        composeRule.onNodeWithText("提前离开").assertIsDisplayed().assertIsEnabled().performClick()
        composeRule.runOnIdle { assertTrue(leftEarly) }
    }

    private fun pilgrimageData(): PilgrimageData {
        val anime = Anime(subjectId = 7, name = "Pilgrimage Story", nameCn = "巡礼物语")
        return PilgrimageData(
            anime = anime,
            points = listOf(
                PilgrimagePoint("p1", "名场面一", GeoPoint(35.0116, 135.7681)),
                PilgrimagePoint("p2", "名场面二", GeoPoint(35.0122, 135.7692)),
            ),
            expectedPointCount = 2,
        )
    }

    private fun tourPlan(): TourPlan {
        val data = pilgrimageData()
        val leg = TourLeg(
            from = data.points[0].coordinate,
            to = data.points[1].coordinate,
            mode = TravelMode.WALK,
            geometry = data.points.map(PilgrimagePoint::coordinate),
            steps = listOf(RouteStep("继续直行", 900.0, 720.0)),
            distanceMeters = 900.0,
            durationSeconds = 720.0,
            source = "local-test",
            destinationPointId = data.points[1].id,
        )
        return TourPlan(
            id = "ui-contract-tour",
            anime = data.anime,
            selectedPoints = data.points,
            orderedPoints = data.points,
            legs = listOf(leg),
            mode = TravelMode.WALK,
            objective = RouteObjective.FASTEST,
            endPolicy = EndPolicy.OPEN,
            estimatedDurationSeconds = 2520.0,
            attribution = listOf("Local UI fixture"),
        )
    }

    private class FakeTelemetryConsentStore : TelemetryConsentStore {
        var consent = TelemetryConsent()

        override fun telemetryConsent(): TelemetryConsent = consent

        override fun setAnalyticsConsent(enabled: Boolean) {
            consent = consent.copy(analyticsEnabled = enabled)
        }

        override fun setCrashlyticsConsent(enabled: Boolean) {
            consent = consent.copy(crashlyticsEnabled = enabled)
        }
    }

    private class FakeTelemetryRuntime : TelemetryRuntime {
        var analyticsEnabled = false

        override fun setAnalyticsCollectionEnabled(enabled: Boolean) {
            analyticsEnabled = enabled
        }

        override fun resetAnalyticsData() = Unit

        override fun setCrashlyticsCollectionEnabled(enabled: Boolean) = Unit

        override fun deleteUnsentCrashReports() = Unit
    }
}
