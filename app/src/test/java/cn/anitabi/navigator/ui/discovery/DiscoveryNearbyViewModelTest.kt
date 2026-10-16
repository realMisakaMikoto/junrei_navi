package cn.anitabi.navigator.ui.discovery

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.core.model.TerritoryRegion
import cn.anitabi.navigator.core.routing.TourOptimizer
import cn.anitabi.navigator.data.discovery.DiscoveryCache
import cn.anitabi.navigator.data.discovery.DiscoveryLoadCounter
import cn.anitabi.navigator.data.discovery.DiscoveryLoadTrace
import cn.anitabi.navigator.data.discovery.DiscoveryLoadTraceConfig
import cn.anitabi.navigator.data.discovery.DiscoveryParser
import cn.anitabi.navigator.data.discovery.DiscoveryRepository
import cn.anitabi.navigator.data.discovery.DiscoverySnapshot
import cn.anitabi.navigator.data.discovery.DiscoverySource
import cn.anitabi.navigator.navigation.CurrentLocationProvider
import cn.anitabi.navigator.ui.discovery.map.DiscoveryCameraPosition
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Real Repository/VM with synthetic data and an explicit CPU dispatch barrier, never timing sleeps. */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryNearbyViewModelTest {
    private val main = StandardTestDispatcher()
    private val owners = mutableListOf<ViewModelStore>()
    private val ownerJobs = mutableListOf<Job>()
    private val workers = mutableListOf<NearbyBarrier>()
    private val origin = GeoPoint(1.0, 2.0)
    private val moved = GeoPoint(1.005, 2.0)

    @Before fun setUp() = Dispatchers.setMain(main)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun cameraViewportProviderPanelsAndSameLocationDoNotRepeatSorting() = nearbyTest {
        val fixture = fixture()
        val vm = fixture.vm
        val initial = vm.state.value.currentNearby!!
        vm.manualMove()
        vm.cameraChanged(DiscoveryCameraPosition(GeoPoint(3.0, 4.0), 15f))
        assertTrue(vm.viewportCalculated(vm.state.value.viewportToken, setOf("1::a")))
        vm.setListMode(true)
        vm.setListMode(false)
        vm.selectProvider(MapProvider.AMAP)
        vm.setBatchMode(true)
        vm.setGroupByEpisode(true)
        vm.rememberPanel(PanelPresentation(PanelDetent.EXPANDED))
        vm.locate()
        runCurrent()
        assertSame(initial, vm.state.value.currentNearby)
        assertCounts(fixture, 1, 3)
        vm.setNearby(false)
        runCurrent()
        assertNull(vm.state.value.currentNearby)
        vm.setNearby(true)
        runCurrent()
        assertSame(initial, vm.state.value.currentNearby)
        assertFalse(vm.state.value.nearbyPreparing)
        assertCounts(fixture, 1, 3)
    }

    @Test fun loadingAndMetadataArrivalReuseDistancesAndExposeFreshDisplayFields() = nearbyTest {
        val fixture = fixture()
        val vm = fixture.vm
        val initial = vm.state.value.currentNearby!!
        val request = async { fixture.repository.ensureSubjectDetails(1) }
        fixture.subjectStarted.await()
        runCurrent()
        assertEquals(setOf(1L), vm.state.value.data.loadingSubjectIds)
        assertSame(initial, vm.state.value.currentNearby)
        assertCounts(fixture, 1, 3)
        fixture.subjectResult.complete(Json.parseToJsonElement(
            """[{"id":"a","name":"SYNTHETIC_NEW","image":"/images/points/synthetic.jpg"},{"id":"b","name":"SYNTHETIC_B"}]""",
        ))
        request.await()
        vm.state.first { !it.dataPreparing && it.pointsById["1::a"]?.name == "SYNTHETIC_NEW" }
        runCurrent()
        assertTrue(vm.state.value.data.loadingSubjectIds.isEmpty())
        assertSame(initial, vm.state.value.currentNearby)
        val displayed = vm.state.value.pointsById.getValue(initial.entries.first().pointId)
        assertEquals("SYNTHETIC_NEW", displayed.displayName)
        assertEquals("https://image.anitabi.cn/points/synthetic.jpg", displayed.imageUrl)
        assertCounts(fixture, 1, 3)
    }

    @Test fun sameGeometryNewGenerationAndCheckedAtDoNotRepeatSorting() = nearbyTest {
        val fixture = fixture()
        val vm = fixture.vm
        val initial = vm.state.value.currentNearby!!
        val oldVersion = vm.state.value.data.snapshot!!.version
        val update = fixture.repository.refresh(force = true)!!
        try {
            fixture.pageStarted.await()
            vm.state.first { !it.dataPreparing && it.data.snapshot?.version != oldVersion }
            runCurrent()
            assertSame(initial, vm.state.value.currentNearby)
            assertNotEquals(100L, vm.state.value.data.snapshot!!.checkedAtMillis)
            assertCounts(fixture, 1, 3)
        } finally { update.cancelAndJoin() }
    }

    @Test fun relevantLocationAndFiltersRecalculateAndHidePreviousDistancesWhilePending() = nearbyTest {
        val fixture = fixture()
        fixture.location.set(moved)
        fixture.vm.locate()
        runCurrent()
        assertTrue(fixture.vm.state.value.nearbyPreparing)
        assertNull(fixture.vm.state.value.currentNearby)
        finishSort(fixture)
        assertEquals(moved, fixture.vm.state.value.currentNearby!!.key.location)
        assertCounts(fixture, 2, 6)
        fixture.vm.toggleFilter(2)
        runCurrent()
        assertNull(fixture.vm.state.value.currentNearby)
        finishSort(fixture)
        assertEquals(listOf("2::c"), fixture.vm.state.value.currentNearby!!.entries.map { it.pointId })
        assertCounts(fixture, 3, 7)
        fixture.vm.clearFilters()
        runCurrent()
        finishSort(fixture)
        assertEquals(3, fixture.vm.state.value.currentNearby!!.entries.size)
        assertCounts(fixture, 4, 10)
    }

    @Test fun coordinateAndMemberRefreshRecalculatesTheCompleteCatalogue() = nearbyTest {
        val fixture = fixture(changedGeometry = true)
        val update = fixture.repository.refresh(force = true)!!
        try {
            fixture.pageStarted.await()
            fixture.vm.state.first { !it.dataPreparing && it.pointsById.size == 4 }
            assertNull(fixture.vm.state.value.currentNearby)
            finishSort(fixture)
            val state = fixture.vm.state.value
            assertEquals(state.pointsById.keys, state.currentNearby!!.entries.map { it.pointId }.toSet())
            state.currentNearby!!.entries.forEach { entry ->
                assertEquals(TourOptimizer.haversineMeters(origin, state.pointsById.getValue(entry.pointId).coordinate), entry.distanceMeters, 0.0)
            }
            assertCounts(fixture, 2, 7)
        } finally { update.cancelAndJoin() }
    }

    @Test fun completedOldWorkerCannotPublishBeforeKeyCollectorProcessesANewFilter() = nearbyTest {
        val fixture = fixture()
        val published = mutableListOf<DiscoveryNearbyResult?>()
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.vm.state.collect { published += it.nearbyResult }
        }
        try {
            fixture.location.set(moved)
            fixture.vm.locate()
            runCurrent()
            fixture.worker.awaitTask()
            val obsoleteKey = fixture.vm.state.value.nearbyKey!!
            fixture.worker.runNext()
            // A has finished on the CPU dispatcher, but its Main continuation has not resumed.
            fixture.vm.toggleFilter(2)
            runCurrent()
            assertTrue(published.none { it?.key == obsoleteKey })
            assertNull(fixture.vm.state.value.currentNearby)
            finishSort(fixture)
            assertEquals(setOf(2L), fixture.vm.state.value.currentNearby!!.key.filters)
            assertEquals(listOf("2::c"), fixture.vm.state.value.currentNearby!!.entries.map { it.pointId })
        } finally { observer.cancelAndJoin() }
    }

    @Test fun disablingNearbyCancelsQueuedWorkAndDoesNotPublishItsResult() = nearbyTest {
        val fixture = fixture()
        fixture.location.set(moved)
        fixture.vm.locate()
        runCurrent()
        fixture.worker.awaitTask()
        fixture.vm.setNearby(false)
        runCurrent()
        fixture.worker.runNext()
        runCurrent()
        assertNull(fixture.vm.state.value.currentNearby)
        assertCounts(fixture, 1, 3)
        fixture.vm.setNearby(true)
        runCurrent()
        finishSort(fixture)
        assertEquals(moved, fixture.vm.state.value.currentNearby!!.key.location)
        assertCounts(fixture, 2, 6)
    }

    @Test fun clearingLocationCancelsQueuedWorkAndExplicitLocationCanRestartIt() = nearbyTest {
        val fixture = fixture()
        fixture.location.set(moved)
        fixture.vm.locate()
        runCurrent()
        fixture.worker.awaitTask()
        fixture.vm.initializeLocation(false)
        runCurrent()
        fixture.worker.runNext()
        runCurrent()
        assertNull(fixture.vm.state.value.location)
        assertNull(fixture.vm.state.value.currentNearby)
        assertFalse(fixture.vm.state.value.nearbyPreparing)
        assertCounts(fixture, 1, 3)
        fixture.vm.locate()
        runCurrent()
        finishSort(fixture)
        assertEquals(moved, fixture.vm.state.value.currentNearby!!.key.location)
        assertCounts(fixture, 2, 6)
    }

    private fun nearbyTest(block: suspend TestScope.() -> Unit) = runTest(main, timeout = 10.seconds) {
        try { block() } finally {
            owners.forEach(ViewModelStore::clear)
            workers.forEach(NearbyBarrier::runAll)
            runCurrent()
            ownerJobs.joinAll()
        }
    }

    private suspend fun TestScope.finishSort(fixture: Fixture) {
        fixture.worker.awaitTask()
        fixture.worker.runNext()
        runCurrent()
        assertFalse(fixture.vm.state.value.nearbyPreparing)
        assertTrue(fixture.vm.state.value.currentNearby != null)
    }

    private fun assertCounts(fixture: Fixture, sorts: Long, distances: Long) {
        assertEquals(sorts, fixture.trace.snapshot().counters[DiscoveryLoadCounter.NEARBY_SORT_COUNT])
        assertEquals(distances, fixture.trace.snapshot().counters[DiscoveryLoadCounter.DISTANCE_COMPUTATION_COUNT])
    }

    private suspend fun TestScope.fixture(changedGeometry: Boolean = false): Fixture {
        val worker = NearbyBarrier().also(workers::add)
        val trace = DiscoveryLoadTrace(DiscoveryLoadTraceConfig(enabled = true))
        val subjectStarted = CompletableDeferred<Unit>()
        val subjectResult = CompletableDeferred<JsonElement>()
        val pageStarted = CompletableDeferred<Unit>()
        val initial = DiscoveryParser.index(indexDocument(100, false)).copy(checkedAtMillis = 100)
        val location = AtomicReference(origin)
        val repository = DiscoveryRepository(object : DiscoverySource {
            override suspend fun index(cacheToken: String) = indexDocument(200, changedGeometry)
            override suspend fun page(page: Int, cacheToken: String): JsonElement {
                pageStarted.complete(Unit)
                return CompletableDeferred<JsonElement>().await()
            }
            override suspend fun subject(subjectId: Long): JsonElement {
                subjectStarted.complete(Unit)
                return subjectResult.await()
            }
        }, object : DiscoveryCache {
            override fun read() = initial
            override fun write(snapshot: DiscoverySnapshot) = Unit
        }, backgroundScope, now = { 1_000 }, requestIntervalMillis = 0)
        repository.initialize()
        val vm = DiscoveryViewModel(repository, object : DiscoveryCameraStore {
            override fun lastCamera(): DiscoveryCameraPosition? = null
            override fun saveCamera(camera: DiscoveryCameraPosition) = Unit
        }, object : CurrentLocationProvider {
            override suspend fun currentLocation() = location.get()
        }, { TerritoryRegion.OTHER }, SavedStateHandle(), trace = trace,
            preparationDispatcher = main, nearbyDispatcher = worker)
        owners += ViewModelStore().apply { put("nearby", vm) }
        ownerJobs += requireNotNull(vm.viewModelScope.coroutineContext[Job])
        vm.state.first { !it.dataPreparing && it.pointsById.size == 3 }
        vm.locate()
        runCurrent()
        vm.setNearby(true)
        runCurrent()
        val fixture = Fixture(vm, repository, worker, trace, location, subjectStarted, subjectResult, pageStarted)
        finishSort(fixture)
        return fixture
    }

    private class NearbyBarrier : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        private val dispatched = Channel<Unit>(Channel.UNLIMITED)
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.addLast(block)
            check(dispatched.trySend(Unit).isSuccess)
        }
        suspend fun awaitTask() { dispatched.receive() }
        fun runNext() { tasks.removeFirst().run() }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    private data class Fixture(val vm: DiscoveryViewModel, val repository: DiscoveryRepository,
        val worker: NearbyBarrier, val trace: DiscoveryLoadTrace, val location: AtomicReference<GeoPoint>,
        val subjectStarted: CompletableDeferred<Unit>, val subjectResult: CompletableDeferred<JsonElement>,
        val pageStarted: CompletableDeferred<Unit>)

    private fun indexDocument(modified: Int, changed: Boolean): JsonElement {
        val firstLatitude = if (changed) "1.015" else "1.01"
        val extra = if (changed) ",\"d\",1.04,2.0,0" else ""
        return Json.parseToJsonElement(
            """[[[1,"SYNTHETIC_A",0,0,0,0,0,0,0,0,0,0,["a",$firstLatitude,2.0,0,"b",1.02,2.0,0$extra],0,0,0,0,0],[2,"SYNTHETIC_B",0,0,0,0,0,0,0,0,0,0,["c",1.03,2.0,0],0,0,0,0,0]],2,$modified]""",
        )
    }
}
