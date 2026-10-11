package cn.anitabi.navigator.ui.discovery

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.TerritoryRegion
import cn.anitabi.navigator.data.discovery.DiscoveryCache
import cn.anitabi.navigator.data.discovery.DiscoveryParser
import cn.anitabi.navigator.data.discovery.DiscoveryRepository
import cn.anitabi.navigator.data.discovery.DiscoverySnapshot
import cn.anitabi.navigator.data.discovery.DiscoverySource
import cn.anitabi.navigator.navigation.CurrentLocationProvider
import cn.anitabi.navigator.ui.discovery.map.DiscoveryCameraPosition
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Production parser/repository/VM; explicit worker dispatch barriers, no timing sleeps. */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryInitialPreparationProgressTest {
    private val main = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    private val workers = mutableListOf<WorkerBarrier>()

    @Before fun setUp() = Dispatchers.setMain(main)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun loadingFlagsDoNotCancelUsefulInitialClassification() = scenario {
        val fixture = owner()
        val request = async { fixture.repository.ensureSubjectDetails(1) }
        runCurrent()
        assertTrue(1L in fixture.repository.state.value.loadingSubjectIds)
        fixture.worker.runOne()
        runCurrent()
        assertEquals("The first scheduled classification must still make progress", 1, fixture.classifications.get())
        fixture.detail.complete(detail())
        request.await()
        settle(fixture)
        assertEquals(1, fixture.classifications.get())
        assertEquals("SYNTHETIC_DETAIL", fixture.vm.state.value.pointsById.getValue("1::a").displayName)
    }

    @Test fun metadataSnapshotDoesNotReplaceQueuedInitialClassification() = scenario {
        val fixture = owner()
        fixture.detail.complete(detail())
        val request = async { fixture.repository.ensureSubjectDetails(1) }
        request.await()
        runCurrent()
        assertTrue(fixture.repository.state.value.snapshot!!.points.single().detailsLoaded)
        fixture.worker.runOne()
        runCurrent()
        assertEquals("Metadata must preserve the queued coordinate work", 1, fixture.classifications.get())
        settle(fixture)
        assertEquals(1, fixture.classifications.get())
        assertEquals("SYNTHETIC_DETAIL", fixture.vm.state.value.pointsById.getValue("1::a").displayName)
    }

    @Test fun changedGenerationStillRejectsTheQueuedOldClassification() = scenario {
        val fixture = owner()
        val refresh = fixture.repository.refresh(force = true)!!
        fixture.repository.state.first { it.snapshot?.modified == 200L }
        runCurrent()
        assertEquals(200L, fixture.repository.state.value.snapshot!!.modified)
        fixture.worker.runOne()
        runCurrent()
        assertEquals(0, fixture.classifications.get())
        assertTrue(fixture.vm.state.value.mapPoints.isEmpty())
        settle(fixture)
        assertEquals(GeoPoint(3.0, 4.0), fixture.vm.state.value.pointsById.getValue("1::a").coordinate)
        refresh.cancelAndJoin()
    }

    @Test fun sameGenerationCoordinateRepairCannotPublishTheCorruptOldGeometry() = scenario {
        val fixture = owner(sameGenerationRepair = true)
        val originalVersion = fixture.repository.state.value.snapshot!!.version
        val refresh = fixture.repository.refresh(force = true)!!
        fixture.repository.state.first { it.snapshot!!.points.single().coordinate == GeoPoint(3.0, 4.0) }
        runCurrent()
        assertEquals(originalVersion, fixture.repository.state.value.snapshot!!.version)
        fixture.worker.runOne()
        runCurrent()
        assertTrue(fixture.vm.state.value.mapPoints.isEmpty())
        settle(fixture)
        assertEquals(GeoPoint(3.0, 4.0), fixture.vm.state.value.pointsById.getValue("1::a").coordinate)
        refresh.cancelAndJoin()
    }

    @Test fun clearingTheVmOwnerCancelsQueuedSharedCoordinateWork() = scenario {
        val fixture = owner()
        stores.last().clear()
        fixture.worker.runOne()
        runCurrent()
        assertEquals(0, fixture.classifications.get())
        assertTrue(fixture.vm.state.value.mapPoints.isEmpty())
    }

    private fun scenario(block: suspend TestScope.() -> Unit) = runTest(main, timeout = 10.seconds) {
        try {
            block()
        } finally {
            stores.forEach(ViewModelStore::clear)
            workers.forEach(WorkerBarrier::runAll)
            runCurrent()
        }
    }

    private fun TestScope.settle(fixture: Fixture) {
        repeat(20) {
            fixture.worker.runAll()
            runCurrent()
            if (!fixture.vm.state.value.dataPreparing && fixture.vm.state.value.mapPoints.isNotEmpty()) return
        }
        error("Preparation never settled after finite dispatch barriers")
    }

    private suspend fun TestScope.owner(sameGenerationRepair: Boolean = false): Fixture {
        val worker = WorkerBarrier().also(workers::add)
        val classifications = AtomicInteger()
        val detail = CompletableDeferred<JsonElement>()
        val canonical = DiscoveryParser.index(index(100, 3.0, 4.0))
        val initial = if (sameGenerationRepair) canonical.copy(
            points = canonical.points.map { it.copy(coordinate = GeoPoint(1.0, 2.0)) },
        ) else DiscoveryParser.index(index(100, 1.0, 2.0))
        val repository = DiscoveryRepository(object : DiscoverySource {
            override suspend fun index(cacheToken: String) = index(if (sameGenerationRepair) 100 else 200, 3.0, 4.0)
            override suspend fun page(page: Int, cacheToken: String): JsonElement = CompletableDeferred<JsonElement>().await()
            override suspend fun subject(subjectId: Long) = detail.await()
        }, object : DiscoveryCache {
            override fun read() = initial
            override fun write(snapshot: DiscoverySnapshot) = Unit
        }, this, requestIntervalMillis = 0)
        repository.initialize()
        val vm = DiscoveryViewModel(repository, object : DiscoveryCameraStore {
            override fun lastCamera(): DiscoveryCameraPosition? = null
            override fun saveCamera(camera: DiscoveryCameraPosition) = Unit
        }, object : CurrentLocationProvider {
            override suspend fun currentLocation(): GeoPoint = error("No location in preparation fixture")
        }, { classifications.incrementAndGet(); TerritoryRegion.OTHER }, SavedStateHandle(), preparationDispatcher = worker)
        stores += ViewModelStore().apply { put("initial-preparation", vm) }
        worker.awaitTask()
        assertEquals(0, classifications.get())
        return Fixture(vm, repository, worker, classifications, detail)
    }

    private class WorkerBarrier : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        private val dispatched = Channel<Unit>(Channel.UNLIMITED)
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.addLast(block)
            check(dispatched.trySend(Unit).isSuccess)
        }
        suspend fun awaitTask() { dispatched.receive() }
        fun runOne() { tasks.removeFirst().run() }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    private data class Fixture(val vm: DiscoveryViewModel, val repository: DiscoveryRepository,
        val worker: WorkerBarrier, val classifications: AtomicInteger, val detail: CompletableDeferred<JsonElement>)

    private fun detail() = Json.parseToJsonElement("""[{"id":"a","name":"SYNTHETIC_DETAIL"}]""")

    private fun index(modified: Int, latitude: Double, longitude: Double) = Json.parseToJsonElement(
        """[[[1,"SYNTHETIC",0,0,0,0,0,0,0,0,0,0,["a",$latitude,$longitude,0],0,0,0,0,0]],1,$modified]""",
    )
}
