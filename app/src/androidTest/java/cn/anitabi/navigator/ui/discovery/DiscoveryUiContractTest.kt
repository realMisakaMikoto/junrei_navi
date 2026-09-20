package cn.anitabi.navigator.ui.discovery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.anitabi.navigator.core.model.Anime
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.data.discovery.DiscoveryError
import cn.anitabi.navigator.data.discovery.DiscoveryPoint
import cn.anitabi.navigator.data.discovery.DiscoverySnapshot
import cn.anitabi.navigator.data.discovery.DiscoveryState
import cn.anitabi.navigator.data.discovery.DiscoverySubject
import cn.anitabi.navigator.security.AppAppearance
import cn.anitabi.navigator.ui.captureFrontendReview
import cn.anitabi.navigator.ui.theme.AnitabiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Synthetic Compose contracts only: the map slot never constructs a provider SDK map. */
class DiscoveryUiContractTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun phoneStartsCollapsedAndEachDetentKeepsOneContentPanel() {
        val harness = Harness()
        show(harness)

        assertSinglePanel()
        composeRule.onNodeWithTag("discovery-panel-list").assertDoesNotExist()
        capture("discovery-phone-collapsed")
        composeRule.onNodeWithContentDescription(EXPAND).performClick()
        composeRule.onNodeWithTag("discovery-panel-list").assertIsDisplayed()
        capture("discovery-phone-half")
        composeRule.runOnIdle { assertEquals(PanelDetent.HALF, harness.state.panel.presentation.detent) }
        composeRule.onNodeWithContentDescription(EXPAND).performClick()
        composeRule.runOnIdle { assertEquals(PanelDetent.EXPANDED, harness.state.panel.presentation.detent) }
        capture("discovery-phone-expanded")
        composeRule.onNodeWithContentDescription(COLLAPSE).performClick()
        composeRule.onNodeWithContentDescription(COLLAPSE).performClick()
        composeRule.onNodeWithTag("discovery-panel-list").assertDoesNotExist()
        assertSinglePanel()
        composeRule.runOnIdle { assertEquals(1, harness.activeFakeMaps) }
    }

    @Test
    fun overviewSubjectPointAndBackRestorePresentationAndScrolledContent() {
        val harness = Harness()
        show(harness)
        composeRule.onNodeWithContentDescription(EXPAND).performClick()
        composeRule.onNodeWithContentDescription(EXPAND).performClick()
        composeRule.onNodeWithText("\u4f5c\u54c1").performClick()
        composeRule.onNodeWithTag("discovery-panel-list").performScrollToNode(hasText("Synthetic Subject 101"))
        composeRule.onAllNodes(hasText("Synthetic Subject 101") and hasAnyAncestor(hasTestTag("discovery-panel-list")))
            .onFirst().performClick()
        assertSinglePanel()
        composeRule.runOnIdle { assertEquals(DiscoveryPanel.Subject(101), harness.state.panel.current) }
        composeRule.onNodeWithContentDescription(EXPAND).performClick()
        composeRule.onNodeWithTag("discovery-panel-list").performScrollToNode(hasText("Synthetic Point 101-17"))
        val subjectPresentation = composeRule.runOnIdle { harness.state.panel.presentation }
        assertTrue(subjectPresentation.firstVisibleItem > 0)
        capture("discovery-subject-scrolled")

        composeRule.onNodeWithText("Synthetic Point 101-17").performClick()
        assertSinglePanel()
        composeRule.onNodeWithTag("discovery-point-select").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("\u79fb\u51fa\u884c\u7a0b").assertIsDisplayed()
        capture("discovery-point-selected")
        composeRule.onNodeWithContentDescription(BACK).performClick()

        assertSinglePanel()
        composeRule.onNodeWithText("Synthetic Point 101-17").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(subjectPresentation, harness.state.panel.presentation)
            assertEquals(setOf("101::point-17"), harness.selectedIds)
        }
        composeRule.onNodeWithContentDescription(BACK).performClick()
        composeRule.runOnIdle {
            assertEquals(DiscoveryPanel.Overview, harness.state.panel.current)
            assertEquals(PanelDetent.EXPANDED, harness.state.panel.presentation.detent)
        }
        assertSinglePanel()
    }

    @Test
    fun filteringAndExplicitProviderSwitchKeepIndependentTripSelection() {
        val harness = Harness(selected = setOf("101::point-0", "102::point-0"))
        show(harness)
        composeRule.onNodeWithTag("subject-filter-101").performClick().assertIsSelected()
        composeRule.onNodeWithText("\u9ad8\u5fb7\u5730\u56fe").performClick().assertIsSelected()
        composeRule.runOnIdle {
            harness.state = harness.state.copy(visibleIds = setOf("102::point-0"))
        }
        composeRule.onNodeWithTag("subject-filter-101").assertIsDisplayed().assertIsSelected()
        composeRule.onNodeWithText("\u5df2\u9009 2 \u4e2a\u5730\u70b9").assertIsDisplayed()
        composeRule.onNodeWithTag("discovery-plan").assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(setOf("101::point-0", "102::point-0"), harness.selectedIds)
            assertEquals(MapProvider.AMAP, harness.state.provider)
            assertEquals(1, harness.planRequests)
        }
    }

    @Test
    fun viewportCountsChangeWhileSelectedFilterStaysPinnedAndTripSelectionSurvives() {
        val selected = setOf("101::point-0", "102::point-0")
        val harness = Harness(initial = fixture().copy(
            visibleIds = setOf("101::point-0", "101::point-1", "102::point-0"),
        ), selected = selected)
        show(harness)
        composeRule.onNodeWithTag("subject-filter-101").assert(hasText(" \u00b7 2")).performClick().assertIsSelected()
        composeRule.onNodeWithTag("subject-filter-102").assert(hasText(" \u00b7 1"))

        composeRule.runOnIdle { harness.state = harness.state.copy(visibleIds = setOf("101::point-2")) }
        composeRule.onNodeWithTag("subject-filter-101").assert(hasText(" \u00b7 1")).assertIsSelected()
        composeRule.onNodeWithTag("subject-filter-102").assertDoesNotExist()
        composeRule.runOnIdle {
            harness.state = harness.state.copy(visibleIds = setOf("102::point-4", "102::point-5", "102::point-6"))
        }
        val pinned = composeRule.onNodeWithTag("subject-filter-101")
            .assertIsDisplayed().assertIsSelected().assert(hasText(" \u00b7 0")).fetchSemanticsNode().boundsInRoot
        val entering = composeRule.onNodeWithTag("subject-filter-102")
            .assert(hasText(" \u00b7 3")).fetchSemanticsNode().boundsInRoot
        assertTrue("The selected out-of-viewport subject must stay before entering subjects", pinned.left < entering.left)
        composeRule.onNodeWithText("\u5df2\u9009 2 \u4e2a\u5730\u70b9").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(setOf(101L), harness.state.filters)
            assertEquals(selected, harness.selectedIds)
        }

        composeRule.onNodeWithTag("subject-filter-101").performClick()
        composeRule.onNodeWithTag("subject-filter-101").assertDoesNotExist()
        composeRule.onNodeWithTag("subject-filter-102").assertIsDisplayed().assert(hasText(" \u00b7 3"))
        composeRule.runOnIdle { assertTrue(harness.state.filters.isEmpty()); assertEquals(selected, harness.selectedIds) }
    }

    @Test
    fun nearbyRequiresLocationAndOrdersAllAvailablePointsByExplicitStraightLineDistance() {
        val base = fixture()
        val points = listOf(
            base.pointsById.getValue("101::point-2").copy(name = "Far fixture", coordinate = GeoPoint(0.0, .02)),
            base.pointsById.getValue("101::point-0").copy(name = "Near fixture", coordinate = GeoPoint(0.0, .001)),
            base.pointsById.getValue("101::point-1").copy(name = "Middle fixture", coordinate = GeoPoint(0.0, .01)),
        )
        val snapshot = requireNotNull(base.data.snapshot).let { original -> original.copy(
            subjects = listOf(original.subjects.first().copy(pointIds = points.map { it.id })), points = points,
        ) }
        val selected = setOf(points.first().id)
        val harness = Harness(initial = base.copy(
            data = base.data.copy(snapshot = snapshot), pointsById = points.associateBy { it.id },
            visibleIds = setOf(points.first().id), providerChoices = setOf(MapProvider.GOOGLE),
            panel = base.panel.remember(PanelPresentation(PanelDetent.EXPANDED)),
        ), selected = selected)
        show(harness, height = 840.dp)
        composeRule.onNodeWithText("\u9644\u8fd1").assertDoesNotExist()
        composeRule.onNodeWithText("Far fixture").assertIsDisplayed()
        composeRule.onNodeWithText("Near fixture").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("\u5b9a\u4f4d").performClick()
        composeRule.onNodeWithText("\u9644\u8fd1").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(1, harness.locateRequests)
            assertEquals(null, harness.state.location)
            harness.state = harness.state.copy(location = GeoPoint(0.0, 0.0))
        }
        composeRule.onNodeWithText("\u9644\u8fd1").assertIsDisplayed().performClick().assertIsSelected()
        composeRule.onNodeWithText("\u9644\u8fd1\u5730\u70b9").assertIsDisplayed()
        val near = composeRule.onNode(hasText("Near fixture") and hasText("\u76f4\u7ebf 111 \u7c73"))
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val middle = composeRule.onNode(hasText("Middle fixture") and hasText("\u76f4\u7ebf 1.1 \u5343\u7c73"))
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val far = composeRule.onNode(hasText("Far fixture") and hasText("\u76f4\u7ebf 2.2 \u5343\u7c73"))
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Nearby rows must follow distance rather than fixture insertion order", near.top < middle.top && middle.top < far.top)
        composeRule.runOnIdle { assertTrue(harness.state.nearby); assertEquals(selected, harness.selectedIds) }
        capture("discovery-nearby-straight-line")

        composeRule.onNodeWithText("\u89c6\u91ce\u5185\u5730\u70b9").performClick().assertIsSelected()
        composeRule.onNodeWithText("Far fixture").assertIsDisplayed()
        composeRule.onNodeWithText("Near fixture").assertDoesNotExist()
        composeRule.onNodeWithText("Middle fixture").assertDoesNotExist()
        composeRule.runOnIdle { assertFalse(harness.state.nearby); assertEquals(selected, harness.selectedIds) }
    }

    @Test
    fun listModeNeverCreatesMapAndSwitchingModesDisposesTheMapSlot() {
        val harness = Harness(initial = fixture().copy(listMode = true), selected = setOf("101::point-0", "102::point-0"))
        show(harness)
        composeRule.onNodeWithTag("discovery-map").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, harness.totalFakeMaps) }
        composeRule.onNodeWithTag("discovery-plan").assertIsDisplayed().assertIsEnabled()
        capture("discovery-list")
        composeRule.onNodeWithTag("discovery-map-list-toggle").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("discovery-map").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, harness.activeFakeMaps) }
        composeRule.onNodeWithTag("discovery-map-list-toggle").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("discovery-map").assertDoesNotExist()
        composeRule.onNodeWithTag("discovery-plan").assertIsDisplayed().assertIsEnabled()
        composeRule.runOnIdle {
            assertEquals(0, harness.activeFakeMaps)
            assertEquals(1, harness.maximumActiveFakeMaps)
            assertEquals(2, harness.selectedIds.size)
        }
    }

    @Test
    fun shortLandscapeKeepsPlanningAndMapControlsReachable() {
        val harness = Harness(selected = setOf("101::point-0", "102::point-0"))
        show(harness, width = 640.dp, height = 320.dp)
        assertControlsReachable(harness)
        composeRule.onNodeWithContentDescription(EXPAND).assertDoesNotExist()
        assertSinglePanel()
        capture("discovery-landscape")
    }

    @Test
    fun tabletDarkThemeAndLargeFontKeepPlanningAndMapControlsReachable() {
        val harness = Harness(selected = setOf("101::point-0", "102::point-0"))
        show(harness, width = 900.dp, height = 640.dp, fontScale = 1.6f, dark = true)
        assertControlsReachable(harness)
        composeRule.onNodeWithContentDescription(EXPAND).assertDoesNotExist()
        assertSinglePanel()
        capture("discovery-tablet-dark-large-font")
    }

    @Test
    fun phoneLargeFontKeepsCollapsedSummaryAndActionsReachable() {
        val harness = Harness(selected = setOf("101::point-0", "102::point-0"))
        show(harness, fontScale = 1.6f)
        assertControlsReachable(harness)
        capture("discovery-phone-large-font")
        composeRule.onNodeWithContentDescription(EXPAND).assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("discovery-plan").assertIsDisplayed()
        capture("discovery-phone-large-font-half")
    }

    @Test
    fun phoneDarkThemeKeepsSelectedTripAndActionsReachable() {
        val harness = Harness(selected = setOf("101::point-0", "102::point-0"))
        show(harness, dark = true)
        assertControlsReachable(harness)
        capture("discovery-phone-dark")
    }

    @Test
    fun incompleteAndFailedUpdatesExplainAvailableDataWithoutHidingTheIndex() {
        val original = fixture()
        val incomplete = original.data.snapshot!!.copy(loadedPages = emptySet(), endVersionVerified = false)
        val harness = Harness(initial = original.copy(data = DiscoveryState(snapshot = incomplete, initialized = true)))
        show(harness)
        composeRule.onNodeWithText("\u8be6\u60c5\u5c1a\u672a\u5168\u90e8\u52a0\u8f7d").assertIsDisplayed()
        composeRule.onNodeWithTag("discovery-map").assertIsDisplayed()
        composeRule.runOnIdle { harness.state = harness.state.copy(data = harness.state.data.copy(error = DiscoveryError.NETWORK)) }
        composeRule.onNodeWithText("\u66f4\u65b0\u672a\u5b8c\u6210\uff0c\u6b63\u5728\u663e\u793a\u53ef\u7528\u7f13\u5b58").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(EXPAND).performClick()
        composeRule.onNodeWithText("\u66f4\u65b0\u672a\u5b8c\u6210\uff0c\u91cd\u8bd5").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(1, harness.refreshRequests) }
    }

    @Test
    fun missingDetailRemainsSelectableAndSourceLinkOnlyUsesExplicitAction() {
        val base = fixture()
        val point = base.pointsById.getValue("101::point-0").copy(detailsVersion = null)
        val harness = Harness(initial = base.copy(
            pointsById = base.pointsById + (point.id to point),
            panel = base.panel.open(DiscoveryPanel.Point(point.id)).remember(PanelPresentation(PanelDetent.EXPANDED)),
        ))
        show(harness)
        composeRule.onNodeWithText("\u5730\u70b9\u8be6\u60c5\u5c1a\u672a\u52a0\u8f7d\uff0c\u5730\u56fe\u4f4d\u7f6e\u5df2\u53ef\u7528").assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(harness.openedUris.isEmpty()) }
        composeRule.onNodeWithText("\u67e5\u770b\u539f\u59cb\u6765\u6e90").performScrollTo().performClick()
        composeRule.onNodeWithTag("discovery-point-select").assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("https://example.com/synthetic-source"), harness.openedUris)
            assertEquals(setOf(point.id), harness.selectedIds)
        }
    }

    @Test
    fun disablingImagesDoesNotRemoveMissingImagePointsOrTheirSelectionAction() {
        val harness = Harness()
        show(harness)
        chooseDisplayOption("\u5173\u95ed\u56fe\u7247\u6807\u8bb0")
        composeRule.onNodeWithTag("discovery-map-list-toggle").performClick()
        composeRule.onNodeWithText("Synthetic Point 101-0").performClick()
        composeRule.onNodeWithTag("discovery-point-select").assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertFalse(harness.imagesEnabled)
            assertEquals(setOf("101::point-0"), harness.selectedIds)
            assertEquals(0, harness.activeFakeMaps)
        }
    }

    @Test
    fun subjectGroupsCollapseAndEpisodeGroupingKeepsTripSelection() {
        val base = fixture()
        val harness = Harness(
            initial = base.copy(panel = base.panel.open(DiscoveryPanel.Subject(101))
                .remember(PanelPresentation(PanelDetent.EXPANDED))),
            selected = setOf("101::point-0", "102::point-0"),
        )
        show(harness)
        val groupLabel = "Synthetic Group A \u00b7 12"
        composeRule.onNodeWithTag("discovery-panel-list").performScrollToNode(hasText(groupLabel))
        composeRule.onNodeWithText(groupLabel).performClick().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "\u5df2\u6536\u8d77"),
        )
        composeRule.onNodeWithText("Synthetic Point 101-0").assertDoesNotExist()
        capture("discovery-subject-group-collapsed")
        composeRule.onNodeWithText(groupLabel).performClick().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "\u5df2\u5c55\u5f00"),
        )
        composeRule.onNodeWithTag("discovery-panel-list").performScrollToNode(hasText("\u6309\u96c6\u6570"))
        composeRule.onNodeWithText("\u6309\u96c6\u6570").performClick().assertIsSelected()
        composeRule.onNodeWithTag("discovery-panel-list").performScrollToNode(hasText("\u7b2c 1 \u96c6 \u00b7 12"))
        composeRule.onNodeWithText("\u7b2c 1 \u96c6 \u00b7 12").assertIsDisplayed()
        composeRule.runOnIdle {
            assertTrue(harness.state.groupByEpisode)
            assertEquals(setOf("101::point-0", "102::point-0"), harness.selectedIds)
        }
    }

    private fun assertSinglePanel() {
        composeRule.onAllNodesWithTag("discovery-panel").assertCountEquals(1)
    }

    private fun capture(scene: String) {
        composeRule.onNodeWithTag("discovery-screen").captureFrontendReview(scene)
    }

    private fun chooseDisplayOption(label: String) {
        composeRule.onNodeWithContentDescription("\u5730\u56fe\u663e\u793a\u8bbe\u7f6e").performClick()
        composeRule.onNodeWithText(label).performClick()
    }

    private fun assertControlsReachable(harness: Harness) {
        composeRule.onNodeWithTag("discovery-search").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("\u5b9a\u4f4d").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("\u5730\u56fe\u56de\u6b63").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("discovery-plan").assertIsDisplayed().assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(1, harness.searchRequests)
            assertEquals(1, harness.locateRequests)
            assertEquals(1, harness.resetBearingRequests)
            assertEquals(1, harness.planRequests)
        }
    }

    private fun show(harness: Harness, width: Dp = 360.dp, height: Dp = 640.dp, fontScale: Float = 1f, dark: Boolean = false) {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale), LocalUriHandler provides harness.uriHandler) {
                AnitabiTheme(appearance = if (dark) AppAppearance.DARK else AppAppearance.LIGHT) {
                    Box(Modifier.requiredSize(width, height)) { harness.Content(dark) }
                }
            }
        }
    }

    private class Harness(initial: DiscoveryUiState = fixture(), selected: Set<String> = emptySet()) {
        var state by mutableStateOf(initial)
        var selectedIds by mutableStateOf(selected)
        var imagesEnabled by mutableStateOf(true)
        var activeFakeMaps = 0
        var totalFakeMaps = 0
        var maximumActiveFakeMaps = 0
        var planRequests = 0
        var searchRequests = 0
        var locateRequests = 0
        var resetBearingRequests = 0
        var refreshRequests = 0
        val openedUris = mutableListOf<String>()
        val uriHandler = object : UriHandler {
            override fun openUri(uri: String) { openedUris += uri }
        }

        @Composable
        fun Content(dark: Boolean) {
            DiscoveryScreen(
                state = state, selectedIds = selectedIds, privacyReady = true,
                imagesEnabled = imagesEnabled, darkTheme = dark,
                onImagesEnabled = { imagesEnabled = it }, onSearch = { searchRequests++ }, onSettings = {},
                onProvider = { state = state.copy(provider = it) },
                onFilter = { id -> state = state.copy(filters = if (id in state.filters) state.filters - id else state.filters + id) },
                onLocate = { locateRequests++ }, onResetBearing = { resetBearingRequests++ },
                onListMode = { state = state.copy(listMode = it) },
                onBatchMode = { state = state.copy(batchMode = it) },
                onNearby = { state = state.copy(nearby = it) }, onRefresh = { refreshRequests++ },
                onPoint = { point, _ -> state = state.copy(panel = state.panel.open(DiscoveryPanel.Point(point.id))) },
                onSubject = { state = state.copy(panel = state.panel.open(DiscoveryPanel.Subject(it))) },
                onTogglePoint = { point -> selectedIds = if (point.id in selectedIds) selectedIds - point.id else selectedIds + point.id },
                onBackPanel = { state = state.copy(panel = state.panel.back()) },
                onPanelPresentation = { key, presentation -> state = state.copy(panel = state.panel.remember(key, presentation)) },
                onGroupByEpisode = { state = state.copy(groupByEpisode = it) },
                onFitAll = {}, onVisibleIds = {}, onOverlap = {}, onCameraChanged = {}, onManualMove = {},
                onUnavailable = { state = state.copy(listMode = true) },
                onSelectVisible = { selectedIds = selectedIds + state.visibleIds },
                onClearSelection = { selectedIds = emptySet() }, onPlan = { planRequests++ },
                mapContent = { modifier ->
                    DisposableEffect(Unit) {
                        activeFakeMaps++
                        totalFakeMaps++
                        maximumActiveFakeMaps = maxOf(maximumActiveFakeMaps, activeFakeMaps)
                        onDispose { activeFakeMaps-- }
                    }
                    Box(modifier.background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
                        Text("Synthetic map slot", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )
        }
    }

    companion object {
        private const val EXPAND = "\u5c55\u5f00\u9762\u677f"
        private const val COLLAPSE = "\u6536\u8d77\u9762\u677f"
        private const val BACK = "\u8fd4\u56de\u4e0a\u4e00\u5c42"

        private fun fixture(): DiscoveryUiState {
            // No downloaded names, coordinates, images, or provider responses are used.
            val points = listOf(101L, 102L).flatMap { subject -> (0 until 24).map { index ->
                DiscoveryPoint(subject, "point-$index", GeoPoint(0.0, index.toDouble()),
                    name = "Synthetic Point $subject-$index", detailsVersion = "fixture",
                    groupName = if (index < 12) "Synthetic Group A" else "Synthetic Group B",
                    episode = if (index < 12) "1" else "2",
                    source = "Synthetic source", sourceUrl = "https://example.com/synthetic-source")
            } }
            val subjects = listOf(101L, 102L).map { id ->
                DiscoverySubject(Anime(id, "Synthetic Subject $id"), city = "Test City",
                    pointIds = points.filter { it.subjectId == id }.map { it.id })
            }
            val snapshot = DiscoverySnapshot("fixture", 1, 2, subjects, points, loadedPages = setOf(0), endVersionVerified = true)
            return DiscoveryUiState(
                data = DiscoveryState(snapshot = snapshot, initialized = true),
                pointsById = points.associateBy { it.id }, visibleIds = points.map { it.id }.toSet(),
                providerChoices = setOf(MapProvider.GOOGLE, MapProvider.AMAP),
            )
        }
    }
}
