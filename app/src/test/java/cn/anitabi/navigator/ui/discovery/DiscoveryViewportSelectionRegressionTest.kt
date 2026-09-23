package cn.anitabi.navigator.ui.discovery

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import cn.anitabi.navigator.core.model.Anime
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.core.model.TerritoryRegion
import cn.anitabi.navigator.data.discovery.DiscoveryCache
import cn.anitabi.navigator.data.discovery.DiscoveryPoint
import cn.anitabi.navigator.data.discovery.DiscoveryRepository
import cn.anitabi.navigator.data.discovery.DiscoverySnapshot
import cn.anitabi.navigator.data.discovery.DiscoverySource
import cn.anitabi.navigator.data.discovery.DiscoverySubject
import cn.anitabi.navigator.navigation.CurrentLocationProvider
import cn.anitabi.navigator.ui.discovery.map.DiscoveryCameraPosition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Synthetic data, production ViewModel; assertions run before any new viewport result. */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryViewportSelectionRegressionTest {
    private val dispatcher = StandardTestDispatcher()
    private val owners = mutableListOf<ViewModelStore>()
    private val location = CompletableDeferred<GeoPoint>()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() {
        owners.forEach(ViewModelStore::clear)
        Dispatchers.resetMain()
    }

    @Test fun manualPanImmediatelyInvalidatesTheOldArea() = runTest(dispatcher) {
        val vm = owner()
        vm.manualMove()
        assertTrue(vm.state.value.visibleIds.isEmpty())
    }

    @Test fun locatingImmediatelyInvalidatesBeforeLocationResult() = runTest(dispatcher) {
        val vm = owner()
        vm.locate()
        assertTrue(vm.state.value.locating)
        assertTrue(vm.state.value.visibleIds.isEmpty())
    }

    @Test fun changingFilterImmediatelyInvalidatesTheOldArea() = runTest(dispatcher) {
        val vm = owner()
        vm.toggleFilter(1)
        assertTrue(vm.state.value.visibleIds.isEmpty())
    }

    @Test fun listModeDoesNotRetainTheLastMapArea() = runTest(dispatcher) {
        val vm = owner()
        vm.setListMode(true)
        assertTrue(vm.state.value.visibleIds.isEmpty())
    }

    @Test fun panelDetentChangeImmediatelyInvalidatesCoveredArea() = runTest(dispatcher) {
        val vm = owner()
        vm.rememberPanel(PanelPresentation(PanelDetent.EXPANDED))
        assertTrue(vm.state.value.visibleIds.isEmpty())
    }

    @Test fun disposingMapInvalidatesEvenWithoutSavedCamera() = runTest(dispatcher) {
        val vm = owner()
        vm.mapDetached()
        assertTrue(vm.state.value.visibleIds.isEmpty())
    }

    @Test fun resettingBearingInvalidatesBeforeCameraCompletion() = runTest(dispatcher) {
        val vm = owner()
        vm.resetBearing()
        assertTrue(vm.state.value.visibleIds.isEmpty())
    }

    @Test fun providerSwitchAlreadyClearsOldArea() = runTest(dispatcher) {
        val vm = owner()
        vm.selectProvider(MapProvider.AMAP)
        assertEquals(MapProvider.AMAP, vm.state.value.provider)
        assertTrue(vm.state.value.visibleIds.isEmpty())
    }

    @Test fun oldCalculationCannotReenableSelectionAfterAnyInvalidation() = runTest(dispatcher) {
        for (reason in DiscoveryViewportInvalidation.entries) {
            val vm = owner()
            val old = vm.state.value.viewportToken
            val next = vm.invalidateViewport(reason)
            assertNotEquals(old, next)
            assertFalse(vm.viewportCalculated(old, setOf("1::a")))
            var selections = 0
            assertFalse(vm.selectViewport { selections++ })
            assertEquals(0, selections)
            assertFalse(vm.state.value.canSelectViewport)
        }
    }

    @Test fun panThenImmediateClickCannotSelectCapturedOldState() = runTest(dispatcher) {
        val vm = owner()
        val displayedState = vm.state.value
        assertTrue(displayedState.canSelectViewport)
        vm.manualMove()
        var selections = 0
        assertFalse(vm.selectViewport { selections++ })
        assertEquals(0, selections)
        assertTrue(displayedState.canSelectViewport)
        assertFalse(vm.viewportCalculated(displayedState.viewportToken, displayedState.visibleIds))
    }

    @Test fun currentResultProducesOneAtomicBatchWithOriginalCoordinates() = runTest(dispatcher) {
        val vm = owner()
        val expected = vm.state.value.pointsById.getValue("1::a")
        var calls = 0
        assertTrue(vm.selectViewport { batch ->
            calls++
            assertEquals(1, batch.size)
            assertEquals(expected.subjectId, batch.single().first.subjectId)
            assertEquals(expected.rawId, batch.single().second.id)
            assertEquals(expected.coordinate, batch.single().second.coordinate)
        })
        assertEquals(1, calls)
    }

    @Test fun allVisibleSubjectsArePassedToOneSelectionUpdate() = runTest(dispatcher) {
        val original = viewportFixture()
        val second = original.points.single().copy(subjectId = 2, rawId = "b")
        val multi = original.copy(
            subjects = original.subjects + DiscoverySubject(Anime(2, "SYNTHETIC_SECOND"), pointIds = listOf(second.id)),
            points = original.points + second,
        )
        val vm = owner(multi)
        var calls = 0
        assertTrue(vm.selectViewport { batch ->
            calls++
            assertEquals(listOf(1L, 2L), batch.map { it.first.subjectId })
            assertEquals(multi.points.map { it.coordinate }, batch.map { it.second.coordinate })
        })
        assertEquals(1, calls)
    }

    @Test fun panelListScrollDoesNotInvalidateUnchangedMapGeometry() = runTest(dispatcher) {
        val vm = owner()
        val token = vm.state.value.viewportToken
        vm.rememberPanel(PanelPresentation(PanelDetent.COLLAPSED, firstVisibleItem = 12, scrollOffset = 8))
        assertEquals(token, vm.state.value.viewportToken)
        assertTrue(vm.state.value.canSelectViewport)
    }

    @Test fun mutableWorkerSetCannotChangePublishedSelection() = runTest(dispatcher) {
        val vm = owner()
        val ids = mutableSetOf("1::a")
        assertTrue(vm.viewportCalculated(vm.state.value.viewportToken, ids))
        ids.clear()
        assertEquals(setOf("1::a"), vm.state.value.visibleIds)
        assertThrows(UnsupportedOperationException::class.java) {
            (vm.state.value.visibleIds as MutableSet<String>).clear()
        }
    }

    @Test fun forgedProviderDataOrUnknownMemberResultsAreRejected() = runTest(dispatcher) {
        val vm = owner()
        val token = vm.invalidateViewport(DiscoveryViewportInvalidation.CAMERA)
        assertFalse(vm.viewportCalculated(token.copy(provider = MapProvider.AMAP), setOf("1::a")))
        assertFalse(vm.viewportCalculated(token.copy(dataVersion = "other"), setOf("1::a")))
        assertFalse(vm.viewportCalculated(token, setOf("1::missing")))
        assertFalse(vm.state.value.canSelectViewport)
    }

    @Test fun filterRoundTripDoesNotMakeOldTokenValidAgain() = runTest(dispatcher) {
        val vm = owner()
        val old = vm.state.value.viewportToken
        vm.toggleFilter(1)
        vm.toggleFilter(1)
        assertTrue(vm.state.value.filters.isEmpty())
        assertFalse(vm.viewportCalculated(old, setOf("1::a")))
        assertTrue(vm.state.value.viewportToken.filterRevision > old.filterRevision)
    }

    @Test fun listReturnRequiresFreshProjectionBeforeSelection() = runTest(dispatcher) {
        val vm = owner()
        val old = vm.state.value.viewportToken
        vm.setListMode(true)
        assertFalse(vm.viewportCalculated(vm.state.value.viewportToken, setOf("1::a")))
        vm.setListMode(false)
        assertFalse(vm.selectViewport { error("Old viewport must not be selected") })
        assertFalse(vm.viewportCalculated(old, setOf("1::a")))
        assertTrue(vm.viewportCalculated(vm.state.value.viewportToken, setOf("1::a")))
        assertTrue(vm.state.value.canSelectViewport)
    }

    @Test fun focusingPointBlocksSelectionUntilCommandSettlesAndFreshResultArrives() = runTest(dispatcher) {
        val vm = owner()
        vm.openPoint("1::a")
        val command = vm.state.value.cameraCommand!!
        assertFalse(vm.viewportCalculated(vm.state.value.viewportToken, setOf("1::a")))
        assertFalse(vm.selectViewport { error("Pending focus must not select") })
        vm.cameraCommandApplied(command.sequence, DiscoveryCameraPosition(GeoPoint(1.0, 2.0), 15f))
        assertFalse(vm.selectViewport { error("Command completion needs a fresh result") })
        assertTrue(vm.viewportCalculated(vm.state.value.viewportToken, setOf("1::a")))
        assertTrue(vm.state.value.canSelectViewport)
    }

    @Test fun sameGenerationRestoredMemberIsClassifiedAndAdvancesGeometryVersion() {
        val initial = viewportFixture()
        val prepared = prepareDiscoveryData(initial, null, { TerritoryRegion.OTHER })
        val restored = initial.copy(points = initial.points + initial.points.single().copy(rawId = "b"))
        var classifications = 0
        val repaired = prepareDiscoveryData(restored, prepared, { classifications++; TerritoryRegion.OTHER })
        assertEquals(1, classifications)
        assertEquals(setOf("1::a", "1::b"), repaired.mapPoints.map { it.id }.toSet())
        assertNotEquals(prepared.mapDataVersion, repaired.mapDataVersion)
    }

    @Test fun sameGenerationCoordinateRepairReclassifiesAndAdvancesGeometryVersion() {
        val initial = viewportFixture()
        val prepared = prepareDiscoveryData(initial, null, { TerritoryRegion.OTHER })
        val restored = initial.copy(points = initial.points.map { it.copy(coordinate = GeoPoint(2.0, 3.0)) })
        val repaired = prepareDiscoveryData(restored, prepared, { TerritoryRegion.MAINLAND_CHINA })
        assertEquals(MapProvider.AMAP, repaired.mapPoints.single().provider)
        assertNotEquals(prepared.mapDataVersion, repaired.mapDataVersion)
    }

    private suspend fun TestScope.owner(snapshot: DiscoverySnapshot = viewportFixture()): DiscoveryViewModel {
        val repository = DiscoveryRepository(object : DiscoverySource {
            override suspend fun index(cacheToken: String): JsonElement = error("No network in viewport tests")
            override suspend fun page(page: Int, cacheToken: String): JsonElement = error("No network in viewport tests")
            override suspend fun subject(subjectId: Long): JsonElement = error("No network in viewport tests")
        }, object : DiscoveryCache {
            override fun read() = snapshot
            override fun write(snapshot: DiscoverySnapshot) = Unit
        }, this)
        val vm = DiscoveryViewModel(repository, object : DiscoveryCameraStore {
            override fun lastCamera(): DiscoveryCameraPosition? = null
            override fun saveCamera(camera: DiscoveryCameraPosition) = Unit
        }, object : CurrentLocationProvider {
            override suspend fun currentLocation(): GeoPoint = location.await()
        }, { TerritoryRegion.OTHER }, SavedStateHandle())
        owners += ViewModelStore().apply { put("viewport", vm) }
        vm.state.first { it.mapPoints.size == snapshot.points.size }
        val ids = snapshot.points.map { it.id }.toSet()
        assertTrue(vm.viewportCalculated(vm.state.value.viewportToken, ids))
        assertEquals(ids, vm.state.value.visibleIds)
        return vm
    }

    private fun viewportFixture(): DiscoverySnapshot {
        val version = "100:synthetic-viewport"
        val point = DiscoveryPoint(1, "a", GeoPoint(1.0, 2.0), detailsVersion = version, imageMetadataVersion = 1)
        return DiscoverySnapshot(version, 100, 1,
            listOf(DiscoverySubject(Anime(1, "SYNTHETIC"), pointIds = listOf(point.id))), listOf(point),
            loadedPages = setOf(0), endVersionVerified = true)
    }
}
