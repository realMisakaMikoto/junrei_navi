package cn.anitabi.navigator.ui.discovery

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.core.model.TerritoryRegion
import cn.anitabi.navigator.data.discovery.DiscoveryCache
import cn.anitabi.navigator.data.discovery.DiscoveryRepository
import cn.anitabi.navigator.data.discovery.DiscoverySnapshot
import cn.anitabi.navigator.data.discovery.DiscoverySource
import cn.anitabi.navigator.navigation.CurrentLocationProvider
import cn.anitabi.navigator.navigation.MissingLocationPermissionException
import cn.anitabi.navigator.ui.discovery.map.DiscoveryCameraCommand
import cn.anitabi.navigator.ui.discovery.map.DiscoveryCameraPosition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Synthetic coordinates and virtual time; these are lifecycle/state tests, not GNSS evidence. */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryLocationTrackingTest {
    private val dispatcher = StandardTestDispatcher()
    private val owners = mutableListOf<ViewModelStore>()
    private val gates = mutableListOf<CompletableDeferred<GeoPoint>>()
    private val first = GeoPoint(1.0, 2.0)
    private val second = GeoPoint(2.0, 3.0)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() {
        owners.forEach(ViewModelStore::clear)
        Dispatchers.resetMain()
    }

    @Test fun locateUsesInjectedQualifiedFixAndExposesItsClassifiedProvider() = locationTest {
        var calls = 0
        val vm = owner({ calls++; first }, { TerritoryRegion.MAINLAND_CHINA })
        vm.setMapLocationActive(true)
        vm.locate()
        runCurrent()
        assertEquals(1, calls)
        assertEquals(first, vm.state.value.location)
        assertEquals(MapProvider.AMAP, vm.state.value.locationProvider)
        assertEquals(MapProvider.AMAP, vm.state.value.establishedLocationProvider)
        assertEquals(MapProvider.AMAP, vm.state.value.provider)
        assertEquals(first, (vm.state.value.cameraCommand as DiscoveryCameraCommand.Locate).coordinate)
        assertFalse(vm.state.value.locating)
    }

    @Test fun periodicMovementUpdatesOwnPointWithoutSwitchingMapOrRecentering() = locationTest {
        var calls = 0
        val vm = owner({ if (calls++ == 0) first else second }, {
            if (it == first) TerritoryRegion.OTHER else TerritoryRegion.MAINLAND_CHINA
        })
        vm.setMapLocationActive(true)
        vm.locate()
        runCurrent()
        val command = vm.state.value.cameraCommand!!
        vm.cameraCommandApplied(command.sequence, DiscoveryCameraPosition(first, 15f))
        vm.manualMove()
        val token = vm.state.value.viewportToken
        advanceTimeBy(14_999)
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, calls)
        assertEquals(second, vm.state.value.location)
        assertEquals(MapProvider.AMAP, vm.state.value.locationProvider)
        assertEquals(MapProvider.GOOGLE, vm.state.value.provider)
        assertEquals(MapProvider.GOOGLE, vm.state.value.establishedLocationProvider)
        assertNull(vm.state.value.cameraCommand)
        assertEquals(token, vm.state.value.viewportToken)
    }

    @Test fun mapStopHidesIndicatorAndResumeObtainsFreshFixImmediately() = locationTest {
        var calls = 0
        val vm = owner({ if (calls++ == 0) first else second })
        vm.setMapLocationActive(true)
        vm.locate()
        runCurrent()
        vm.setMapLocationActive(false)
        assertNull(vm.state.value.locationProvider)
        assertEquals(first, vm.state.value.location)
        assertEquals(MapProvider.GOOGLE, vm.state.value.establishedLocationProvider)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(1, calls)
        vm.setMapLocationActive(true)
        runCurrent()
        assertEquals(2, calls)
        assertEquals(second, vm.state.value.location)
        assertEquals(MapProvider.GOOGLE, vm.state.value.locationProvider)
    }

    @Test fun stoppedLocateCannotPublishALateNonCooperativeResult() = locationTest {
        val gate = locationGate()
        val vm = owner({ withContext(NonCancellable) { gate.await() } })
        vm.setMapLocationActive(true)
        vm.locate()
        runCurrent()
        vm.setMapLocationActive(false)
        assertFalse(vm.state.value.locating)
        gate.complete(first)
        runCurrent()
        assertNull(vm.state.value.location)
        assertNull(vm.state.value.locationProvider)
        assertNull(vm.state.value.cameraCommand)
    }

    @Test fun stoppedPollCannotReplaceANewerResumedFix() = locationTest {
        var calls = 0
        val gate = locationGate()
        val vm = owner({ when (calls++) {
            0 -> first
            1 -> withContext(NonCancellable) { gate.await() }
            else -> second
        } })
        vm.setMapLocationActive(true)
        vm.locate()
        runCurrent()
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(2, calls)
        vm.setMapLocationActive(false)
        vm.setMapLocationActive(true)
        runCurrent()
        assertEquals(second, vm.state.value.location)
        gate.complete(first)
        runCurrent()
        assertEquals(second, vm.state.value.location)
        assertEquals(MapProvider.GOOGLE, vm.state.value.locationProvider)
    }

    @Test fun unclassifiedFixNeverBecomesEligibleForAnSdkOrMovesItsCamera() = locationTest {
        val vm = owner({ first }, { null })
        vm.setMapLocationActive(true)
        vm.locate()
        runCurrent()
        assertEquals(first, vm.state.value.location)
        assertNull(vm.state.value.locationProvider)
        assertNull(vm.state.value.cameraCommand)
        assertEquals(MapProvider.GOOGLE, vm.state.value.provider)
        assertNull(vm.state.value.establishedLocationProvider)
    }

    @Test fun permissionFailureClearsIndicatorStopsPollingAndExplicitRetryRecovers() = locationTest {
        var calls = 0
        val vm = owner({ when (calls++) {
            0 -> first
            1 -> throw MissingLocationPermissionException()
            else -> second
        } })
        vm.setMapLocationActive(true)
        vm.locate()
        runCurrent()
        advanceTimeBy(15_000)
        runCurrent()
        assertNull(vm.state.value.location)
        assertNull(vm.state.value.locationProvider)
        assertEquals(MapProvider.GOOGLE, vm.state.value.establishedLocationProvider)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, calls)
        vm.locate()
        runCurrent()
        assertEquals(3, calls)
        assertEquals(second, vm.state.value.location)
        assertEquals(MapProvider.GOOGLE, vm.state.value.locationProvider)
    }

    @Test fun explicitLocateFromListReturnsToMapAfterClassifiedFix() = locationTest {
        val vm = owner({ first })
        vm.setListMode(true)
        vm.setMapLocationActive(false)
        vm.locate()
        runCurrent()
        assertFalse(vm.state.value.listMode)
        assertEquals(first, vm.state.value.location)
        assertEquals(MapProvider.GOOGLE, vm.state.value.locationProvider)
        assertTrue(vm.state.value.cameraCommand is DiscoveryCameraCommand.Locate)
    }

    @Test fun observedPermissionRevocationHidesOwnPointImmediatelyWithoutWaitingForPoll() = locationTest {
        var calls = 0
        val vm = owner({ calls++; first })
        vm.setMapLocationActive(true)
        vm.locate()
        runCurrent()
        vm.initializeLocation(false)
        assertNull(vm.state.value.location)
        assertNull(vm.state.value.locationProvider)
        assertNull(vm.state.value.cameraCommand)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(1, calls)
    }

    @Test fun noPeriodicRequestsBeforeUserOrPermittedInitialLocate() = locationTest {
        var calls = 0
        val vm = owner({ calls++; first })
        vm.setMapLocationActive(true)
        vm.initializeLocation(false)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(0, calls)
        vm.locate()
        runCurrent()
        assertEquals(1, calls)
    }

    @Test fun boundedTimeoutLeavesAnExplicitRetryAvailable() = locationTest {
        var calls = 0
        val gate = locationGate()
        val vm = owner({ if (calls++ == 0) gate.await() else first })
        vm.locate()
        runCurrent()
        advanceTimeBy(8_000)
        runCurrent()
        assertFalse(vm.state.value.locating)
        assertNull(vm.state.value.locationProvider)
        assertTrue(!vm.state.value.message.isNullOrBlank())
        vm.locate()
        runCurrent()
        assertEquals(2, calls)
        assertEquals(first, vm.state.value.location)
    }

    @Test fun backgroundCancelsAndHidesOwnPointUntilFreshForegroundFix() = locationTest {
        var calls = 0
        val vm = owner({ if (calls++ == 0) first else second })
        vm.setMapLocationActive(true)
        vm.locate()
        runCurrent()
        vm.setForeground(false)
        assertNull(vm.state.value.locationProvider)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(1, calls)
        vm.setForeground(true)
        runCurrent()
        assertEquals(2, calls)
        assertEquals(second, vm.state.value.location)
    }

    private fun locationTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try {
            block()
        } finally {
            // Cancel map polling before runTest drains its scheduler, not in JUnit's later @After.
            owners.forEach(ViewModelStore::clear)
            owners.clear()
            gates.forEach { it.complete(first) }
            gates.clear()
            runCurrent()
        }
    }

    private fun locationGate(): CompletableDeferred<GeoPoint> = CompletableDeferred<GeoPoint>().also(gates::add)

    private fun TestScope.owner(
        fresh: suspend () -> GeoPoint,
        classify: (GeoPoint) -> TerritoryRegion? = { TerritoryRegion.OTHER },
    ): DiscoveryViewModel {
        val repository = DiscoveryRepository(object : DiscoverySource {
            override suspend fun index(cacheToken: String): JsonElement = error("No network in location tests")
            override suspend fun page(page: Int, cacheToken: String): JsonElement = error("No network in location tests")
            override suspend fun subject(subjectId: Long): JsonElement = error("No network in location tests")
        }, object : DiscoveryCache {
            override fun read(): DiscoverySnapshot? = null
            override fun write(snapshot: DiscoverySnapshot) = Unit
        }, this, requestIntervalMillis = 0)
        val vm = DiscoveryViewModel(repository, object : DiscoveryCameraStore {
            override fun lastCamera(): DiscoveryCameraPosition? = null
            override fun saveCamera(camera: DiscoveryCameraPosition) = Unit
        }, object : CurrentLocationProvider {
            override suspend fun currentLocation(): GeoPoint = error("Use the injected freshness policy")
        }, classify, SavedStateHandle(), freshLocation = fresh)
        owners += ViewModelStore().apply { put("location", vm) }
        return vm
    }
}
