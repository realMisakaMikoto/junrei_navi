package cn.anitabi.navigator.ui

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.anitabi.navigator.MainActivity
import cn.anitabi.navigator.SyntheticDiscoveryFixture
import cn.anitabi.navigator.TEST_REGION_DATA_VERSION
import cn.anitabi.navigator.TestAnitabiApplication
import cn.anitabi.navigator.core.model.EndPolicy
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.core.model.NavigationProgress
import cn.anitabi.navigator.core.model.NavigationState
import cn.anitabi.navigator.core.model.RouteObjective
import cn.anitabi.navigator.core.model.TourLeg
import cn.anitabi.navigator.core.model.TourPlan
import cn.anitabi.navigator.core.model.TransitExecutionStrategy
import cn.anitabi.navigator.core.model.TravelMode
import cn.anitabi.navigator.core.routing.EXTERNAL_AMAP_SOURCE
import cn.anitabi.navigator.navigation.ActiveNavigationStore
import cn.anitabi.navigator.navigation.AndroidLocationProvider
import cn.anitabi.navigator.navigation.NavigationRuntime
import cn.anitabi.navigator.navigation.NavigationRuntimeState
import cn.anitabi.navigator.security.AppAppearance
import cn.anitabi.navigator.security.AppSettingsStore
import cn.anitabi.navigator.ui.discovery.map.DiscoveryCameraPosition
import cn.anitabi.navigator.ui.search.SearchViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real activity/navigation tests with synthetic discovery data and the production list fallback. */
@RunWith(AndroidJUnit4::class)
class AppShellInstrumentedTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val application: TestAnitabiApplication
        get() = ApplicationProvider.getApplicationContext()
    private val settings get() = application.container.appSettingsStore
    private var originalOnboarding = false
    private var originalConsent = false
    private var originalAmapReady = false
    private var originalAppearance = AppAppearance.SYSTEM
    private var originalCamera: DiscoveryCameraPosition? = null

    @Before
    fun prepareSyntheticApp() {
        originalOnboarding = settings.hasCompletedOnboarding()
        originalAppearance = settings.appearance()
        originalCamera = application.container.discoveryPreferences.lastCamera()
        settings.markOnboardingComplete()
        settings.setAppearance(AppAppearance.LIGHT)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            originalConsent = settings.hasCurrentAmapPrivacyConsent()
            originalAmapReady = application.container.amapPrivacyGate.isReady
            settings.setAmapPrivacyConsent(false)
            application.container.amapPrivacyGate.revoke()
        }
        // A saved Amap view without consent takes the real list fallback before any map is usable.
        application.container.discoveryPreferences.saveCamera(
            DiscoveryCameraPosition(GeoPoint(0.0, 0.0), 5f, 0f, 0f, MapProvider.AMAP),
        )
    }

    @After
    fun restoreSettings() {
        settings.setAppearance(originalAppearance)
        application.getSharedPreferences(AppSettingsStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(AppSettingsStore.PREFERENCE_ONBOARDING_COMPLETE, originalOnboarding).commit()
        val camera = originalCamera
        if (camera == null) {
            application.getSharedPreferences("discovery_view", Context.MODE_PRIVATE).edit().clear().commit()
        } else application.container.discoveryPreferences.saveCamera(camera)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            settings.setAmapPrivacyConsent(originalConsent)
            if (originalAmapReady) application.container.amapPrivacyGate.prepareIfAllowed(originalConsent)
            else application.container.amapPrivacyGate.revoke()
        }
        assertEquals(originalAmapReady, application.container.amapPrivacyGate.isReady)
    }

    @Test
    fun completedOnboardingOpensDiscoveryOnEachFreshActivityLaunch() {
        repeat(2) {
            ActivityScenario.launch(MainActivity::class.java).use {
                awaitListHome()
                destination(MAP).assertIsSelected()
                composeRule.onNodeWithTag("onboarding-start").assertDoesNotExist()
                composeRule.onNodeWithTag("discovery-map").assertDoesNotExist()
                composeRule.onRoot().captureFrontendReview("app-home-list")
            }
        }
        assertTrue(settings.hasCompletedOnboarding())
    }

    @Test
    fun destinationsPreserveQueryPointPanelAndSharedSelection() {
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitListHome()
            openPoint(SyntheticDiscoveryFixture.FIRST_POINT_NAME)
            composeRule.onNodeWithTag("discovery-point-select").performClick()
            composeRule.onNodeWithText(REMOVE_FROM_TRIP).assertIsDisplayed()

            destination(SEARCH).performClick().assertIsSelected()
            composeRule.onNodeWithTag("search-screen").assertIsDisplayed()
            composeRule.onNode(hasSetTextAction()).performTextReplacement(SyntheticDiscoveryFixture.FIRST_POINT_NAME)
            Espresso.closeSoftKeyboard()
            awaitSelectedSearchPoint()
            composeRule.onNodeWithText(ONE_SELECTED).assertIsDisplayed()

            destination(TRIPS).performClick().assertIsSelected()
            composeRule.onNodeWithTag("trips-screen").assertIsDisplayed()
            composeRule.onNodeWithText("1 \u90e8\u4f5c\u54c1 \u00b7 1 \u4e2a\u5df2\u9009\u5730\u70b9").assertIsDisplayed()
            composeRule.onRoot().captureFrontendReview("app-trips-selected")

            destination(MAP).performClick().assertIsSelected()
            assertPanel(SyntheticDiscoveryFixture.FIRST_POINT_NAME)
            composeRule.onNodeWithText(REMOVE_FROM_TRIP).assertIsDisplayed()

            destination(SEARCH).performClick()
            composeRule.onNode(hasSetTextAction()).assertTextContains(SyntheticDiscoveryFixture.FIRST_POINT_NAME)
            awaitSelectedSearchPoint()
            composeRule.onNodeWithTag("search-content").performScrollToNode(hasContentDescription(REMOVE_FROM_TRIP))
            composeRule.onNodeWithContentDescription(REMOVE_FROM_TRIP).performClick()
            composeRule.onNodeWithText(ZERO_SELECTED).assertIsDisplayed()

            destination(MAP).performClick()
            assertPanel(SyntheticDiscoveryFixture.FIRST_POINT_NAME)
            composeRule.onNodeWithText(ADD_TO_TRIP).assertIsDisplayed()
        }
    }

    @Test
    fun selectedSourceCoordinatesSurviveRotationAndLateSameIdDiscoveryUpdate() {
        assertFalse(AndroidLocationProvider.hasLocationPermission(application))
        val fixture = SyntheticDiscoveryFixture.snapshot()
        val source = fixture.points.single { it.displayName == SyntheticDiscoveryFixture.FIRST_POINT_NAME }
        val changedCoordinate = fixture.points.single { it.displayName == SyntheticDiscoveryFixture.SECOND_POINT_NAME }.coordinate
        assertTrue("The authored update must challenge coordinate retention", source.coordinate != changedCoordinate)
        val expectedIds = setOf(source.id)
        val expectedCoordinates = mapOf(source.id to source.coordinate)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var originalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            scenario.onActivity { originalOrientation = it.requestedOrientation }
            fun rotate(requested: Int, configuration: Int) {
                scenario.onActivity { it.requestedOrientation = requested }
                composeRule.waitUntil(timeoutMillis = 15_000) {
                    var ready = false
                    scenario.onActivity { activity ->
                        val decor = activity.window.decorView
                        ready = activity.resources.configuration.orientation == configuration &&
                            if (configuration == Configuration.ORIENTATION_LANDSCAPE) decor.width > decor.height
                            else decor.height > decor.width
                    }
                    ready
                }
                awaitListHome()
            }
            try {
                rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
                openPoint(SyntheticDiscoveryFixture.FIRST_POINT_NAME)
                composeRule.onNodeWithTag("discovery-point-select").performClick()
                composeRule.onNodeWithText(REMOVE_FROM_TRIP).assertIsDisplayed()
                var selectedOwner: SearchViewModel? = null
                scenario.onActivity { selectedOwner = ViewModelProvider(it)[SearchViewModel::class.java] }
                fun assertSelectedSnapshot() {
                    scenario.onActivity { activity ->
                        val viewModel = ViewModelProvider(activity)[SearchViewModel::class.java]
                        assertSame("The real activity-scoped selection owner must survive rotation", selectedOwner, viewModel)
                        val state = viewModel.state.value
                        assertEquals(expectedIds, state.selectedPointIds)
                        val coordinates = state.combinedPilgrimageData?.points.orEmpty()
                            .filter { it.id in state.selectedPointIds }.associate { it.id to it.coordinate }
                        assertTrue("Selected source coordinates must remain exactly unchanged", coordinates == expectedCoordinates)
                    }
                    composeRule.onNodeWithText(REMOVE_FROM_TRIP).assertIsDisplayed()
                }
                assertSelectedSnapshot()
                rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
                assertSelectedSnapshot()
                scenario.onActivity { activity ->
                    ViewModelProvider(activity)[SearchViewModel::class.java].selectDiscoveryPoints(
                        fixture.subjects.single().anime,
                        listOf(source.toPilgrimagePoint().copy(id = source.rawId, coordinate = changedCoordinate)),
                    )
                }
                assertSelectedSnapshot()
                rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
                assertSelectedSnapshot()
            } finally {
                scenario.onActivity { it.requestedOrientation = originalOrientation }
            }
        }
    }

    @Test
    fun systemBackReturnsPointToSubjectThenOverviewWithoutLeavingHome() {
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitListHome()
            composeRule.onNodeWithText("\u4f5c\u54c1").performClick()
            composeRule.onNodeWithTag("discovery-panel-list")
                .performScrollToNode(hasText(SyntheticDiscoveryFixture.SUBJECT_NAME))
            composeRule.onAllNodes(
                hasText(SyntheticDiscoveryFixture.SUBJECT_NAME) and hasAnyAncestor(hasTestTag("discovery-panel-list")),
            ).onFirst().performClick()
            assertPanel(SyntheticDiscoveryFixture.SUBJECT_NAME)
            openPoint(SyntheticDiscoveryFixture.SECOND_POINT_NAME)
            composeRule.onNodeWithTag("discovery-point-select").performClick()

            Espresso.pressBack()
            assertPanel(SyntheticDiscoveryFixture.SUBJECT_NAME)
            composeRule.onNodeWithText(ONE_SELECTED).assertIsDisplayed()
            composeRule.onNodeWithTag("discovery-point-select").assertDoesNotExist()

            Espresso.pressBack()
            assertPanel("\u5de1\u793c\u5730\u70b9")
            composeRule.onNodeWithText(ONE_SELECTED).assertIsDisplayed()
            composeRule.onNodeWithContentDescription("\u8fd4\u56de\u4e0a\u4e00\u5c42").assertDoesNotExist()
            destination(MAP).assertIsSelected()
        }
    }

    @Test
    fun returningHomeKeepsActiveNavigationAndTripsReopensIt() {
        // No service is started: the running state only exercises the real navigation UI.
        assertFalse(application.container.amapPrivacyGate.isReady)
        assertFalse(AndroidLocationProvider.hasLocationPermission(application))
        val originalRuntime = NavigationRuntime.state.value
        val originalActiveTourId = ActiveNavigationStore.get(application)
        val fixture = SyntheticDiscoveryFixture.snapshot()
        val points = fixture.points.map { it.toPilgrimagePoint() }
        val plan = TourPlan(
            id = "synthetic-app-shell-active-tour",
            anime = fixture.subjects.single().anime,
            selectedPoints = points,
            orderedPoints = points,
            legs = listOf(TourLeg(
                from = points.first().coordinate, to = points.last().coordinate,
                mode = TravelMode.WALK, geometry = emptyList(), steps = emptyList(),
                distanceMeters = 0.0, durationSeconds = 0.0, source = EXTERNAL_AMAP_SOURCE,
                provider = MapProvider.AMAP, destinationPointId = points.last().id,
            )),
            mode = TravelMode.WALK,
            objective = RouteObjective.FASTEST,
            endPolicy = EndPolicy.OPEN,
            estimatedDurationSeconds = 0.0,
            attribution = emptyList(),
            initialStart = points.first().coordinate,
            state = NavigationState.NAVIGATING,
            executionStrategy = TransitExecutionStrategy.EXTERNAL_AMAP_MAINLAND,
            mapProvider = MapProvider.AMAP,
            regionDataVersion = TEST_REGION_DATA_VERSION,
            externalRouteFallback = true,
        )
        val runtime = NavigationRuntimeState(
            plan = plan,
            progress = NavigationProgress(
                tourId = plan.id, completedPointIds = setOf(points.first().id),
                state = NavigationState.NAVIGATING,
            ),
            instruction = "Synthetic active navigation",
            isRunning = true,
            errorMessage = "Synthetic recoverable navigation state",
        )
        fun assertRuntimePreserved() {
            assertEquals(runtime, NavigationRuntime.state.value)
            assertEquals(plan.id, ActiveNavigationStore.get(application))
        }
        fun assertNavigationVisible() {
            composeRule.waitUntil(timeoutMillis = 15_000) {
                composeRule.onAllNodesWithTag("navigation-control-panel").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("navigation-control-panel").assertIsDisplayed()
            composeRule.onNodeWithText("\u9ad8\u5fb7\u5730\u56fe\u6682\u65f6\u65e0\u6cd5\u52a0\u8f7d").assertIsDisplayed()
            composeRule.onNodeWithText(SyntheticDiscoveryFixture.SUBJECT_NAME).assertIsDisplayed()
            composeRule.onNodeWithTag("main-navigation").assertDoesNotExist()
            assertRuntimePreserved()
        }
        try {
            NavigationRuntime.set(runtime)
            ActivityScenario.launch(MainActivity::class.java).use {
                assertNavigationVisible()
                composeRule.onNodeWithContentDescription("\u8fd4\u56de").performClick()
                awaitListHome()
                destination(MAP).assertIsSelected()
                composeRule.onNodeWithTag("navigation-control-panel").assertDoesNotExist()
                assertRuntimePreserved()

                destination(TRIPS).performClick().assertIsSelected()
                composeRule.onNodeWithTag("trips-screen").assertIsDisplayed()
                composeRule.onNodeWithText("1 / 2 \u4e2a\u5730\u70b9\u5df2\u5b8c\u6210").assertIsDisplayed()
                composeRule.onNodeWithText("\u8fd4\u56de\u5bfc\u822a").performClick()
                assertNavigationVisible()
            }
        } finally {
            NavigationRuntime.set(originalRuntime)
            if (originalActiveTourId == null) ActiveNavigationStore.clear(application)
            else ActiveNavigationStore.set(application, originalActiveTourId)
        }
    }

    @Test
    fun appearanceChangesImmediatelyAndPersistsAcrossFreshLaunch() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitListHome()
            composeRule.onNodeWithContentDescription(SETTINGS).performClick()
            composeRule.onNodeWithTag("about-screen").assertIsDisplayed()
            var activityBeforeChange: MainActivity? = null
            scenario.onActivity { activityBeforeChange = it }

            chooseAppearance(DARK)
            assertEquals(AppAppearance.DARK, settings.appearance())
            assertTrue(backgroundLuminance() < 0.2f)
            scenario.onActivity { activity ->
                assertSame(activityBeforeChange, activity)
                assertFalse(WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars)
            }
            composeRule.onNodeWithTag("about-screen").captureFrontendReview("app-settings-dark")

            chooseAppearance(LIGHT)
            assertEquals(AppAppearance.LIGHT, settings.appearance())
            assertTrue(backgroundLuminance() > 0.7f)
            scenario.onActivity { activity ->
                assertSame(activityBeforeChange, activity)
                assertTrue(WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars)
            }
            chooseAppearance(DARK)
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitListHome()
            composeRule.onNodeWithContentDescription(SETTINGS).performClick()
            composeRule.onNodeWithTag("about-content").performScrollToNode(hasText(DARK))
            composeRule.onNodeWithText(DARK).assertIsSelected()
            assertTrue(backgroundLuminance() < 0.2f)
        }
    }

    private fun awaitListHome() {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithTag("discovery-panel-list").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("discovery-screen").assertIsDisplayed()
        composeRule.onNodeWithTag("discovery-map").assertDoesNotExist()
        composeRule.onAllNodesWithTag("discovery-panel").assertCountEquals(1)
    }

    private fun openPoint(name: String) {
        composeRule.onNodeWithTag("discovery-panel-list").performScrollToNode(hasText(name))
        composeRule.onNodeWithText(name).performClick()
        assertPanel(name)
    }

    private fun assertPanel(title: String) {
        composeRule.onAllNodesWithTag("discovery-panel").assertCountEquals(1)
        composeRule.onNodeWithTag("discovery-panel").assertIsDisplayed().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, title),
        )
    }

    private fun awaitSelectedSearchPoint() {
        composeRule.onNodeWithTag("search-content").performScrollToNode(hasText("\u5730\u56fe\u5185\u641c\u7d22"))
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithContentDescription(REMOVE_FROM_TRIP).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun destination(label: String) = composeRule.onNode(
        hasText(label) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected),
    )

    private fun chooseAppearance(label: String) {
        composeRule.onNodeWithTag("about-content").performScrollToNode(hasText(label))
        composeRule.onNodeWithText(label).performClick().assertIsSelected()
    }

    private fun backgroundLuminance(): Float =
        composeRule.onNodeWithTag("about-screen").captureToImage().toPixelMap()[0, 0].luminance()

    private companion object {
        const val MAP = "\u5730\u56fe"
        const val SEARCH = "\u641c\u7d22"
        const val TRIPS = "\u884c\u7a0b"
        const val SETTINGS = "\u5173\u4e8e\u4e0e\u8bbe\u7f6e"
        const val LIGHT = "\u6d45\u8272\u624b\u5e33"
        const val DARK = "\u6df1\u8272\u624b\u5e33"
        const val ADD_TO_TRIP = "\u52a0\u5165\u884c\u7a0b"
        const val REMOVE_FROM_TRIP = "\u79fb\u51fa\u884c\u7a0b"
        const val ONE_SELECTED = "\u5df2\u9009 1 \u4e2a\u5730\u70b9"
        const val ZERO_SELECTED = "\u5df2\u9009 0 \u4e2a\u5730\u70b9"
    }
}
