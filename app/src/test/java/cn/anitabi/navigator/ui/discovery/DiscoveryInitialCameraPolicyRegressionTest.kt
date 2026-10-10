package cn.anitabi.navigator.ui.discovery

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
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
import cn.anitabi.navigator.ui.discovery.map.DiscoveryCameraCommand
import cn.anitabi.navigator.ui.discovery.map.DiscoveryCameraPosition
import cn.anitabi.navigator.ui.discovery.map.shouldUseInitialDiscoveryOverview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Production overview policy and real VM acknowledgement; no SDK scheduling or native overwrite is proved here. */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryInitialCameraPolicyRegressionTest {
    private val dispatcher = StandardTestDispatcher()
    private val owners = mutableListOf<ViewModelStore>()
    private val ownerJobs = mutableListOf<Job>()
    private val saved = DiscoveryCameraPosition(GeoPoint(0.0, 0.0), 15f)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() {
        runTest(dispatcher) {
            owners.forEach(ViewModelStore::clear)
            ownerJobs.joinAll()
        }
        Dispatchers.resetMain()
    }

    @Test fun acknowledgedInitialRestoreMustNotBecomeAnImplicitOverview() = runTest(dispatcher) {
        val vm = model(saved)
        val command = vm.state.value.cameraCommand as DiscoveryCameraCommand.Restore
        assertEquals(saved, command.camera)
        val consumedSequence = command.sequence
        vm.cameraCommandApplied(consumedSequence, command.camera)
        assertNull("The real acknowledgement clears the pending command", vm.state.value.cameraCommand)
        assertFalse("An acknowledged explicit camera must win even if first configuration follows its receipt",
            shouldUseInitialDiscoveryOverview(false, vm.state.value.cameraCommand, vm.state.value.provider, consumedSequence))
    }

    @Test fun firstGoogleMapWithoutSavedCameraUsesOverview() = runTest(dispatcher) {
        val vm = model(null)
        assertNull(vm.state.value.cameraCommand)
        assertTrue(shouldUseInitialDiscoveryOverview(false, vm.state.value.cameraCommand, vm.state.value.provider, null))
    }

    @Test fun pendingInitialRestoreBlocksOverview() = runTest(dispatcher) {
        val vm = model(saved)
        assertTrue(vm.state.value.cameraCommand is DiscoveryCameraCommand.Restore)
        assertFalse(shouldUseInitialDiscoveryOverview(false, vm.state.value.cameraCommand, vm.state.value.provider, null))
    }

    @Test fun nonGoogleMapDoesNotUseGoogleOverview() {
        assertFalse(shouldUseInitialDiscoveryOverview(false, null, MapProvider.AMAP, null))
    }

    @Test fun alreadyInitializedMapDoesNotUseOverview() {
        assertFalse(shouldUseInitialDiscoveryOverview(true, null, MapProvider.GOOGLE, null))
    }

    private fun TestScope.model(camera: DiscoveryCameraPosition?): DiscoveryViewModel {
        val version = "100:synthetic-camera-policy"
        val point = DiscoveryPoint(901, "alpha", saved.center, detailsVersion = version, imageMetadataVersion = 1)
        val snapshot = DiscoverySnapshot(version, 100, 1,
            listOf(DiscoverySubject(Anime(901, "SYNTHETIC"), pointIds = listOf(point.id))), listOf(point),
            loadedPages = setOf(0), endVersionVerified = true, checkedAtMillis = System.currentTimeMillis())
        val repository = DiscoveryRepository(object : DiscoverySource {
            override suspend fun index(cacheToken: String): JsonElement = error("No network in initial-camera policy tests")
            override suspend fun page(page: Int, cacheToken: String): JsonElement = error("No network in initial-camera policy tests")
            override suspend fun subject(subjectId: Long): JsonElement = error("No network in initial-camera policy tests")
        }, object : DiscoveryCache {
            override fun read() = snapshot
            override fun write(snapshot: DiscoverySnapshot) = Unit
        }, backgroundScope)
        val vm = DiscoveryViewModel(repository, object : DiscoveryCameraStore {
            override fun lastCamera() = camera
            override fun saveCamera(camera: DiscoveryCameraPosition) = Unit
        }, object : CurrentLocationProvider {
            override suspend fun currentLocation(): GeoPoint = error("No location in initial-camera policy tests")
        }, { TerritoryRegion.OTHER }, SavedStateHandle(), preparationDispatcher = dispatcher)
        owners += ViewModelStore().apply { put("initial-camera-policy", vm) }
        ownerJobs += requireNotNull(vm.viewModelScope.coroutineContext[Job])
        return vm
    }
}
