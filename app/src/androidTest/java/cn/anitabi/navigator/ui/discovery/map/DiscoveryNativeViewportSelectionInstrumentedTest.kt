package cn.anitabi.navigator.ui.discovery.map

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.anitabi.navigator.TestAnitabiApplication
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
import cn.anitabi.navigator.ui.discovery.DiscoveryCameraStore
import cn.anitabi.navigator.ui.discovery.DiscoveryViewModel
import cn.anitabi.navigator.ui.discovery.viewportIsCurrent
import cn.anitabi.navigator.ui.map.OfficialAmapCoordinateConverter
import cn.anitabi.navigator.ui.theme.AnitabiTheme
import com.amap.api.maps.MapView as AmapNativeView
import com.google.android.gms.maps.GoogleMap
import com.google.android.libraries.navigation.NavigationView
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Real SDK gesture and real VM selection, with a deliberately stale enabled Compose button.
 * Frozen Compose time controls the calculation race; elapsed timestamps are event ordering only.
 * No GNSS, route, production data, rendering-speed or B1/B2 result is established here.
 * Navigation camera-start/idle callbacks run on Main; no camera is moved from those callbacks.
 * Checked 2026-10-02 against Navigation SDK OnCameraMoveStartedListener and OnCameraIdleListener:
 * https://developers.google.com/maps/documentation/navigation/android-sdk/reference/com/google/android/gms/maps/GoogleMap.OnCameraMoveStartedListener
 * https://developers.google.com/maps/documentation/navigation/android-sdk/reference/com/google/android/gms/maps/GoogleMap.OnCameraIdleListener
 */
class DiscoveryNativeViewportSelectionInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()
    private val application = ApplicationProvider.getApplicationContext<TestAnitabiApplication>()
    private val owner = ViewModelStore()
    private val repositoryJob = SupervisorJob()
    private var modelJob: Job? = null
    private var harness: Harness? = null
    private var privacyWasReady = false

    @Before fun rememberPrivacy() { privacyWasReady = application.container.amapPrivacyGate.isReady }

    @After fun closeFixture() {
        composeRule.mainClock.autoAdvance = true
        harness?.let { value ->
            onMain { value.showMap = false }
            composeRule.waitForIdle()
        }
        onMain { owner.clear() }
        repositoryJob.cancel()
        runBlocking { withTimeout(15_000) { listOfNotNull(modelJob, repositoryJob).joinAll() } }
        if (!privacyWasReady) onMain { application.container.amapPrivacyGate.revoke() }
    }

    @Test fun googleSdkPanRejectsStaleEnabledViewportButton() = verify(MapProvider.GOOGLE)
    @Test fun amapSdkPanRejectsStaleEnabledViewportButton() = verify(MapProvider.AMAP)

    private fun verify(provider: MapProvider) {
        val source = if (provider == MapProvider.AMAP) GeoPoint(30.0, 110.0) else GeoPoint(0.0, 0.0)
        val value = onMain {
            if (provider == MapProvider.AMAP) assertTrue("AMap privacy/key gate must precede SDK creation",
                application.container.amapPrivacyGate.prepareIfAllowed(true))
            val center = if (provider == MapProvider.AMAP) OfficialAmapCoordinateConverter(application).convert(source)
                .let { GeoPoint(it.latitude, it.longitude) } else source
            val points = listOf("alpha", "beta").map { DiscoveryPoint(901, it, source, detailsVersion = VERSION,
                imageMetadataVersion = 1) }
            val snapshot = DiscoverySnapshot(VERSION, 100, 1,
                listOf(DiscoverySubject(Anime(901, "Synthetic viewport subject"), pointIds = points.map { it.id })),
                points, loadedPages = setOf(0), endVersionVerified = true)
            val requests = AtomicInteger()
            val repository = DiscoveryRepository(object : DiscoverySource {
                override suspend fun index(cacheToken: String): JsonElement { requests.incrementAndGet(); error("Unexpected fixture network") }
                override suspend fun page(page: Int, cacheToken: String): JsonElement { requests.incrementAndGet(); error("Unexpected fixture network") }
                override suspend fun subject(subjectId: Long): JsonElement { requests.incrementAndGet(); error("Unexpected fixture network") }
            }, object : DiscoveryCache {
                override fun read() = snapshot
                override fun write(snapshot: DiscoverySnapshot) = Unit
            }, CoroutineScope(repositoryJob + Dispatchers.IO))
            val vm = DiscoveryViewModel(repository, object : DiscoveryCameraStore {
                override fun lastCamera() = DiscoveryCameraPosition(center, 15f, provider = provider)
                override fun saveCamera(camera: DiscoveryCameraPosition) = Unit
            }, object : CurrentLocationProvider {
                override suspend fun currentLocation(): GeoPoint = error("No location in viewport fixture")
            }, { if (provider == MapProvider.AMAP) TerritoryRegion.MAINLAND_CHINA else TerritoryRegion.OTHER }, SavedStateHandle())
            owner.put("native-viewport", vm)
            modelJob = requireNotNull(vm.viewModelScope.coroutineContext[Job])
            Harness(vm, requests).also { harness = it }
        }
        show(value)
        composeRule.waitUntil(30_000) {
            check(!value.unavailable) { "Actual provider SDK unavailable" }
            value.vm.state.value.canSelectViewport && value.vm.state.value.visibleIds.size == 2
        }
        composeRule.onNodeWithTag(SELECT).assertIsEnabled().performTouchInput { click() }
        composeRule.waitUntil(5_000) { value.click.get() != null }
        assertTrue(requireNotNull(value.click.get()).accepted)
        assertEquals(1, value.selectionCalls.get())
        assertEquals(2, value.selectedMembers.get())
        onMain { value.click.set(null); value.selectionCalls.set(0); value.selectedMembers.set(0) }
        val native = onMain { nativeViews(value.root).single() }
        val camera = cameraReader(native)
        val originalCamera = onMain(camera)
        val originalViewport = requireNotNull(value.vm.state.value.viewportSnapshot)
        val acceptedBeforePan = value.acceptedPublications.get()
        composeRule.mainClock.autoAdvance = false
        try {
            onMain { value.armed = true }
            composeRule.onNodeWithTag(MAP).performTouchInput {
                val start = Offset(width * .75f, height * .4f)
                val end = Offset(width * .10f, height * .4f)
                down(start)
                repeat(12) { step -> moveTo(start + (end - start) * ((step + 1) / 12f), 20L) }
                up()
            }
            composeRule.waitUntil(10_000) {
                value.gestureAt.get() > 0 && !value.vm.state.value.canSelectViewport && onMain(camera) != originalCamera
            }
            // Recomposition is frozen: this is a real enabled button backed by stale area A.
            composeRule.onNodeWithTag(SELECT).assertIsEnabled().performTouchInput { click() }
            composeRule.waitUntil(5_000) { value.click.get() != null }
            val observed = requireNotNull(value.click.get())
            assertTrue(observed.handledAtNanos >= value.gestureAt.get())
            assertFalse("Handler must read the invalidated VM snapshot", observed.snapshotValid)
            assertFalse(observed.eligible)
            assertEquals(0, observed.memberCount)
            assertFalse("The production VM must reject stale enabled UI", observed.accepted)
            assertEquals(0, value.selectionCalls.get())
            assertEquals(0, value.selectedMembers.get())
            assertEquals(acceptedBeforePan, value.acceptedPublications.get())
            assertFalse(onMain { value.vm.viewportCalculated(originalViewport.token, originalViewport.visibleIds) })
        } finally { composeRule.mainClock.autoAdvance = true }
        composeRule.waitUntil(30_000) {
            val current = value.vm.state.value
            current.viewportSnapshot?.let { current.viewportIsCurrent(it.token) } == true && current.visibleIds.isEmpty()
        }
        assertEquals(0, value.selectedMembers.get())
        assertEquals(0, value.requests.get())
        onMain { assertEquals(listOf(native), nativeViews(value.root)) }
        val observed = requireNotNull(value.click.get())
        val directory = File(requireNotNull(application.getExternalFilesDir(null)), "frontend-review").apply { check(isDirectory || mkdirs()) }
        File(directory, "native-viewport-${provider.name.lowercase()}.json").writeText(JSONObject()
            .put("provider", provider.name).put("scope", "synthetic_native_VM_correctness_with_frozen_Compose_clock")
            .put("gestureAtNanos", value.gestureAt.get()).put("handlerAtNanos", observed.handledAtNanos)
            .put("initialAcceptedMembers", 2)
            .put("snapshotValidAtHandler", observed.snapshotValid).put("eligibleAtHandler", observed.eligible)
            .put("memberCountAtHandler", observed.memberCount).put("accepted", observed.accepted)
            .put("selectionCalls", value.selectionCalls.get()).put("selectedMembers", value.selectedMembers.get())
            .put("staleButtonWasEnabled", true).put("nativeViewCount", 1)
            .put("settledEmptyViewport", true).put("sourceRequests", value.requests.get()).toString())
    }

    private fun show(value: Harness) {
        composeRule.setContent {
            val state by value.vm.state.collectAsState()
            val root = LocalView.current.rootView
            SideEffect { value.root = root }
            val bottom = with(LocalDensity.current) { 80.dp.roundToPx() }
            AnitabiTheme {
                Box(Modifier.fillMaxSize()) {
                    if (value.showMap) DiscoveryMap(state.mapDataVersion, state.mapPoints, state.provider,
                        privacyReady = true, selectedIds = emptySet(), focusedPointId = null, imagesEnabled = true,
                        darkTheme = false, padding = DiscoveryMapPadding(bottom = bottom), cameraCommand = state.cameraCommand,
                        onVisibleIdsChanged = {}, onPointClick = {}, onOverlapClick = {},
                        onCameraChanged = value.vm::cameraChanged,
                        onManualMove = { if (value.armed) value.gestureAt.compareAndSet(0, SystemClock.elapsedRealtimeNanos()); value.vm.manualMove() },
                        onUnavailable = { value.unavailable = true; value.vm.mapUnavailable() },
                        modifier = Modifier.fillMaxSize().testTag(MAP), onCameraCommandApplied = value.vm::cameraCommandApplied,
                        viewportToken = state.viewportToken, onViewportInvalidated = { value.vm.invalidateViewport(it) },
                        onViewportCalculated = { token, ids -> value.vm.viewportCalculated(token, ids).also {
                            if (it) value.acceptedPublications.incrementAndGet()
                        } })
                    Button(enabled = state.canSelectViewport, onClick = {
                        val handledAt = SystemClock.elapsedRealtimeNanos()
                        val current = value.vm.state.value
                        val valid = current.viewportSnapshot?.let { current.viewportIsCurrent(it.token) } == true
                        val accepted = value.vm.selectViewport { batch ->
                            value.selectionCalls.incrementAndGet(); value.selectedMembers.addAndGet(batch.size)
                        }
                        value.click.set(ClickObservation(handledAt, valid, current.canSelectViewport, current.visibleIds.size, accepted))
                    }, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).testTag(SELECT)) { Text("Viewport select") }
                }
            }
        }
    }

    private fun cameraReader(native: View): () -> GeoPoint = when (native) {
        is NavigationView -> {
            val sdk = AtomicReference<GoogleMap>()
            onMain { native.getMapAsync { sdk.set(it) } }
            composeRule.waitUntil(10_000) { sdk.get() != null }
            val read: () -> GeoPoint = { requireNotNull(sdk.get()).cameraPosition.target.let { GeoPoint(it.latitude, it.longitude) } }
            read
        }
        is AmapNativeView -> { { native.map.cameraPosition.target.let { GeoPoint(it.latitude, it.longitude) } } }
        else -> error("Expected one actual provider SDK view")
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }

    private class Harness(val vm: DiscoveryViewModel, val requests: AtomicInteger) {
        lateinit var root: View
        var showMap by mutableStateOf(true)
        @Volatile var unavailable = false
        @Volatile var armed = false
        val gestureAt = AtomicLong()
        val acceptedPublications = AtomicInteger()
        val selectionCalls = AtomicInteger()
        val selectedMembers = AtomicInteger()
        val click = AtomicReference<ClickObservation>()
    }

    private data class ClickObservation(val handledAtNanos: Long, val snapshotValid: Boolean,
        val eligible: Boolean, val memberCount: Int, val accepted: Boolean)

    private companion object {
        const val VERSION = "100:synthetic-native-viewport"
        const val MAP = "viewport-native-map"
        const val SELECT = "viewport-select"
        fun nativeViews(root: View): List<View> = buildList {
            if (root is NavigationView || root is AmapNativeView) add(root)
            else if (root is ViewGroup) repeat(root.childCount) { addAll(nativeViews(root.getChildAt(it))) }
        }
    }
}
