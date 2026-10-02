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
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Runs the real preparation worker behind an explicit dispatch barrier; no timing sleeps. */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryPreparationRaceTest {
    private val main = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    private val workers = mutableListOf<PreparationBarrier>()

    @Before fun setUp() = Dispatchers.setMain(main)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun oldGeometryCannotBecomeSelectableDuringNewMemberPreparation() = preparationTest {
        val fixture = owner()
        val vm = fixture.vm
        assertTrue(vm.viewportCalculated(vm.state.value.viewportToken, setOf("1::a")))
        val update = fixture.repository.refresh(force = true)!!
        try {
            fixture.worker.awaitTask()
            assertTrue(vm.state.value.dataPreparing)
            val inFlight = vm.state.value.viewportToken
            assertFalse(vm.viewportCalculated(inFlight, setOf("1::a")))
            assertFalse(vm.selectViewport { error("Old geometry cannot select during preparation") })
            releasePreparation(fixture, firstTaskObserved = true)
            assertFalse(vm.state.value.dataPreparing)
            assertEquals(setOf("1::a", "1::b"), vm.state.value.pointsById.keys)
            assertNotEquals(inFlight, vm.state.value.viewportToken)
            assertFalse(vm.viewportCalculated(inFlight, setOf("1::a")))
            assertTrue(vm.viewportCalculated(vm.state.value.viewportToken, setOf("1::a", "1::b")))
        } finally {
            update.cancelAndJoin()
        }
    }

    @Test fun detailOnlyPreparationIssuesFreshTokenWhenGeometryVersionIsUnchanged() = preparationTest {
        val fixture = owner()
        val vm = fixture.vm
        val version = vm.state.value.mapDataVersion
        assertTrue(vm.viewportCalculated(vm.state.value.viewportToken, setOf("1::a")))
        val request = async { fixture.repository.ensureSubjectDetails(1) }
        fixture.worker.awaitTask()
        val inFlight = vm.state.value.viewportToken
        assertTrue(vm.state.value.dataPreparing)
        assertFalse(vm.viewportCalculated(inFlight, setOf("1::a")))
        releasePreparation(fixture, firstTaskObserved = true)
        request.await()
        assertEquals(version, vm.state.value.mapDataVersion)
        assertTrue(vm.state.value.pointsById.getValue("1::a").detailsLoaded)
        assertFalse(vm.state.value.dataPreparing)
        assertNotEquals(inFlight, vm.state.value.viewportToken)
        assertFalse(vm.viewportCalculated(inFlight, setOf("1::a")))
        assertTrue(vm.viewportCalculated(vm.state.value.viewportToken, setOf("1::a")))
    }

    private fun preparationTest(block: suspend TestScope.() -> Unit) = runTest(main, timeout = 10.seconds) {
        try {
            block()
        } finally {
            stores.forEach(ViewModelStore::clear)
            workers.forEach(PreparationBarrier::runAll)
            runCurrent()
        }
    }

    private suspend fun TestScope.releasePreparation(fixture: Fixture, firstTaskObserved: Boolean = false) {
        if (!firstTaskObserved) fixture.worker.awaitTask()
        do {
            fixture.worker.runNext()
            runCurrent()
            if (fixture.vm.state.value.dataPreparing) fixture.worker.awaitTask()
        } while (fixture.vm.state.value.dataPreparing)
    }

    private suspend fun TestScope.owner(): Fixture {
        val worker = PreparationBarrier().also(workers::add)
        val initial = DiscoveryParser.index(indexFixture(expanded = false))
        val repository = DiscoveryRepository(object : DiscoverySource {
            override suspend fun index(cacheToken: String): JsonElement = indexFixture(expanded = true)
            override suspend fun page(page: Int, cacheToken: String): JsonElement = CompletableDeferred<JsonElement>().await()
            override suspend fun subject(subjectId: Long): JsonElement = Json.parseToJsonElement(
                """[{"id":"a","name":"SYNTHETIC_DETAIL"}]""",
            )
        }, object : DiscoveryCache {
            override fun read() = initial
            override fun write(snapshot: DiscoverySnapshot) = Unit
        }, this, requestIntervalMillis = 0)
        val vm = DiscoveryViewModel(repository, object : DiscoveryCameraStore {
            override fun lastCamera(): DiscoveryCameraPosition? = null
            override fun saveCamera(camera: DiscoveryCameraPosition) = Unit
        }, object : CurrentLocationProvider {
            override suspend fun currentLocation(): GeoPoint = error("No location in preparation tests")
        }, { TerritoryRegion.OTHER }, SavedStateHandle(), preparationDispatcher = worker)
        stores += ViewModelStore().apply { put("preparation", vm) }
        val fixture = Fixture(vm, repository, worker)
        releasePreparation(fixture)
        assertEquals(setOf("1::a"), vm.state.value.pointsById.keys)
        return fixture
    }

    private class PreparationBarrier : CoroutineDispatcher() {
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

    private data class Fixture(
        val vm: DiscoveryViewModel,
        val repository: DiscoveryRepository,
        val worker: PreparationBarrier,
    )

    private fun indexFixture(expanded: Boolean): JsonElement {
        val extra = if (expanded) ",\"b\",2.0,3.0,0" else ""
        val modified = if (expanded) 200 else 100
        return Json.parseToJsonElement(
            """[[[1,"SYNTHETIC",0,0,0,0,0,0,0,0,0,0,["a",1.0,2.0,0$extra],0,0,0,0,0]],1,$modified]""",
        )
    }
}
