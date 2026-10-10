package cn.anitabi.navigator.ui.discovery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import cn.anitabi.navigator.core.model.Anime
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.TerritoryRegion
import cn.anitabi.navigator.data.discovery.DiscoveryCache
import cn.anitabi.navigator.data.discovery.DiscoveryPoint
import cn.anitabi.navigator.data.discovery.DiscoveryRepository
import cn.anitabi.navigator.data.discovery.DiscoverySnapshot
import cn.anitabi.navigator.data.discovery.DiscoverySource
import cn.anitabi.navigator.data.discovery.DiscoverySubject
import cn.anitabi.navigator.navigation.CurrentLocationProvider
import cn.anitabi.navigator.security.AppAppearance
import cn.anitabi.navigator.ui.discovery.map.DiscoveryCameraPosition
import cn.anitabi.navigator.ui.theme.AnitabiTheme
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Real VM and screen; one viewport publication, cached synthetic data, and no SDK map. */
class DiscoveryViewportToolbarLayoutInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()
    private val owner = ViewModelStore()
    private val repositoryJob = SupervisorJob()
    private var modelJob: Job? = null
    private var showScreen by mutableStateOf(true)
    private var contentSet = false

    @After fun closeFixture() {
        if (contentSet) {
            onMain { showScreen = false }
            composeRule.waitForIdle()
        }
        onMain { owner.clear() }
        repositoryJob.cancel()
        runBlocking { withTimeout(15_000) { listOfNotNull(modelJob, repositoryJob).joinAll() } }
    }

    @Test fun oneViewportPublicationSurvivesToolbarLayoutAtNormalFont() = verify(1f)
    @Test fun oneViewportPublicationSurvivesToolbarLayoutAtDoubleFont() = verify(2f)

    private fun verify(fontScale: Float) {
        val requests = AtomicInteger()
        val point = DiscoveryPoint(901, "alpha", GeoPoint(0.0, 0.0), detailsVersion = VERSION,
            imageMetadataVersion = 1)
        val snapshot = DiscoverySnapshot(VERSION, 100, 1,
            listOf(DiscoverySubject(Anime(901, LABEL), pointIds = listOf(point.id))), listOf(point),
            loadedPages = setOf(0), checkedAtMillis = System.currentTimeMillis(), endVersionVerified = true)
        val vm = onMain {
            val repository = DiscoveryRepository(object : DiscoverySource {
                override suspend fun index(cacheToken: String): JsonElement = unexpectedRequest(requests)
                override suspend fun page(page: Int, cacheToken: String): JsonElement = unexpectedRequest(requests)
                override suspend fun subject(subjectId: Long): JsonElement = unexpectedRequest(requests)
            }, object : DiscoveryCache {
                override fun read() = snapshot
                override fun write(snapshot: DiscoverySnapshot) = Unit
            }, CoroutineScope(repositoryJob + Dispatchers.IO))
            DiscoveryViewModel(repository, object : DiscoveryCameraStore {
                override fun lastCamera(): DiscoveryCameraPosition? = null
                override fun saveCamera(camera: DiscoveryCameraPosition) = Unit
            }, object : CurrentLocationProvider {
                override suspend fun currentLocation(): GeoPoint = error("No location in toolbar fixture")
            }, { TerritoryRegion.OTHER }, SavedStateHandle()).also {
                owner.put("toolbar-layout", it)
                modelJob = requireNotNull(it.viewModelScope.coroutineContext[Job])
            }
        }
        runBlocking { withTimeout(15_000) { vm.state.first {
            it.data.initialized && !it.dataPreparing && it.mapPoints.size == 1 && it.data.detailsCurrent
        } } }
        val invalidations = mutableListOf<DiscoveryViewportInvalidation>()
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                AnitabiTheme(appearance = AppAppearance.LIGHT) {
                    if (showScreen) {
                        val state by vm.state.collectAsState()
                        Box(Modifier.requiredSize(360.dp, 720.dp)) {
                            DiscoveryScreen(state, selectedIds = emptySet(), privacyReady = true,
                                imagesEnabled = false, darkTheme = false, onImagesEnabled = {},
                                onSearch = {}, onSettings = {}, onProvider = vm::selectProvider,
                                onFilter = vm::toggleFilter, onLocate = {}, onResetBearing = vm::resetBearing,
                                onListMode = vm::setListMode, onBatchMode = vm::setBatchMode,
                                onNearby = vm::setNearby, onRefresh = vm::refresh,
                                onPoint = { entry, fromMap -> vm.openPoint(entry.id, fromMap) },
                                onSubject = { vm.openSubject(it) }, onTogglePoint = {}, onBackPanel = vm::backPanel,
                                onPanelPresentation = vm::rememberPanel, onGroupByEpisode = vm::setGroupByEpisode,
                                onFitAll = { vm.fitAll(it) }, onVisibleIds = {}, onOverlap = vm::openOverlap,
                                onCameraChanged = vm::cameraChanged, onManualMove = vm::manualMove,
                                onUnavailable = vm::mapUnavailable, onSelectVisible = {},
                                onClearSelection = {}, onPlan = {}, mapContent = { Box(it) },
                                onMapDetached = vm::mapDetached, onCameraCommandApplied = vm::cameraCommandApplied,
                                onViewportInvalidated = { invalidations += it; vm.invalidateViewport(it) },
                                onViewportCalculated = vm::viewportCalculated)
                        }
                    }
                }
            }
        }
        contentSet = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("subject-filter-901").assertDoesNotExist()
        val baseline = composeRule.runOnIdle {
            assertTrue("Initial geometry must settle before publication", invalidations.isNotEmpty())
            assertTrue(invalidations.all { it == DiscoveryViewportInvalidation.LAYOUT })
            assertFalse(vm.state.value.canSelectViewport)
            invalidations.size
        }
        val published = composeRule.runOnIdle {
            vm.state.value.viewportToken.also { token ->
                assertTrue("The settled current viewport must be accepted", vm.viewportCalculated(token, setOf(point.id)))
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            val current = vm.state.value
            val after = invalidations.drop(baseline)
            assertEquals("No source request is permitted", 0, requests.get())
            assertTrue("One publication must survive measured layout; later invalidations=$after",
                current.viewportSnapshot?.let { current.viewportIsCurrent(it.token) } == true)
            assertEquals(published, current.viewportToken)
            assertEquals(setOf(point.id), current.visibleIds)
            assertTrue(current.canSelectViewport)
            assertTrue("Publishing viewport chips must not change their own geometry", after.isEmpty())
        }
        val chip = composeRule.onNodeWithTag("subject-filter-901").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val text = composeRule.onNodeWithText(LABEL, useUnmergedTree = true).assertIsDisplayed()
        val label = text.fetchSemanticsNode().boundsInRoot
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.size)
        assertFalse("Chip text must fit its measured height at fontScale=$fontScale", layouts.single().didOverflowHeight)
        val widthDiagnostic = layouts.single().let { layout ->
            "size=${layout.size}, paragraphWidth=${layout.multiParagraph.width}, constraints=${layout.layoutInput.constraints}, " +
                "lines=${(0 until layout.lineCount).map { line ->
                    "${layout.getLineLeft(line)}..${layout.getLineRight(line)}:ellipsis=${layout.isLineEllipsized(line)}"
                }}, label=$label, chip=$chip"
        }
        layouts.single().let { layout ->
            val lines = 0 until layout.lineCount
            assertTrue("Chip text must fit its measured width at fontScale=$fontScale; $widthDiagnostic",
                lines.all { layout.getLineLeft(it) >= 0f && layout.getLineRight(it) <= layout.size.width })
            assertTrue("Chip text must not be ellipsized at fontScale=$fontScale; $widthDiagnostic",
                lines.none(layout::isLineEllipsized))
            assertEquals("The whole chip label must be visible at fontScale=$fontScale; $widthDiagnostic",
                layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
        }
        assertTrue(chip.height >= 48f)
        assertTrue("The measured chip must contain its label", chip.left <= label.left && chip.right >= label.right &&
            chip.top <= label.top && chip.bottom >= label.bottom)
    }

    private fun unexpectedRequest(requests: AtomicInteger): Nothing {
        requests.incrementAndGet()
        error("Unexpected source request in toolbar fixture")
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }

    private companion object {
        const val VERSION = "100:synthetic-toolbar-layout"
        const val LABEL = "Fixture"
    }
}
