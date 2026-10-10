package cn.anitabi.navigator.ui.discovery.map

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
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
import androidx.compose.ui.geometry.Rect
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
import androidx.lifecycle.Lifecycle
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
import com.amap.api.maps.model.LatLng as AmapLatLng
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.navigation.NavigationView
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.math.abs

/**
 * Real SDK gesture and real VM selection, with a deliberately stale enabled Compose button.
 * Frozen Compose time controls the calculation race; elapsed timestamps are event ordering only.
 * No GNSS, route, production data, rendering-speed or B1/B2 result is established here.
 * Navigation camera-start/idle callbacks run on Main; no camera is moved from those callbacks.
 * Native Android input preserves frozen Compose time; Compose touch injection advances it explicitly.
 * Checked 2026-10-11 against Navigation SDK callbacks, MainTestClock and Instrumentation.sendPointerSync:
 * https://developers.google.com/maps/documentation/navigation/android-sdk/reference/com/google/android/gms/maps/GoogleMap.OnCameraMoveStartedListener
 * https://developers.google.com/maps/documentation/navigation/android-sdk/reference/com/google/android/gms/maps/GoogleMap.OnCameraIdleListener
 * https://developer.android.com/reference/kotlin/androidx/compose/ui/test/MainTestClock
 * https://developer.android.com/reference/android/app/Instrumentation#sendPointerSync(android.view.MotionEvent)
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
            val restoredCamera = DiscoveryCameraPosition(center, 15f, provider = provider)
            val vm = DiscoveryViewModel(repository, object : DiscoveryCameraStore {
                override fun lastCamera() = DiscoveryCameraPosition(center, 15f, provider = provider)
                override fun saveCamera(camera: DiscoveryCameraPosition) = Unit
            }, object : CurrentLocationProvider {
                override suspend fun currentLocation(): GeoPoint = error("No location in viewport fixture")
            }, { if (provider == MapProvider.AMAP) TerritoryRegion.MAINLAND_CHINA else TerritoryRegion.OTHER }, SavedStateHandle())
            owner.put("native-viewport", vm)
            modelJob = requireNotNull(vm.viewModelScope.coroutineContext[Job])
            Harness(vm, requests, restoredCamera, source).also { harness = it }
        }
        show(value)
        try {
            composeRule.waitUntil(30_000) {
                check(!value.unavailable) { "Actual provider SDK unavailable" }
                value.vm.state.value.canSelectViewport && value.vm.state.value.visibleIds.size == 2
            }
        } catch (failure: Throwable) {
            runCatching { writeReadinessFailure(value, provider) }.onFailure(failure::addSuppressed)
            throw failure
        }
        composeRule.onNodeWithTag(SELECT).assertIsEnabled().performTouchInput { click() }
        composeRule.waitUntil(5_000) { value.click.get() != null }
        assertTrue(requireNotNull(value.click.get()).accepted)
        assertEquals(1, value.selectionCalls.get())
        assertEquals(2, value.selectedMembers.get())
        onMain { value.click.set(null); value.selectionCalls.set(0); value.selectedMembers.set(0) }
        composeRule.onNodeWithTag(SELECT).assertIsEnabled()
        val mapBounds = screenBounds(MAP)
        val buttonCenter = screenBounds(SELECT).center
        val native = onMain { nativeViews(value.root).single() }
        val camera = cameraReader(native)
        val originalCamera = onMain(camera)
        val originalViewport = requireNotNull(value.vm.state.value.viewportSnapshot)
        val acceptedBeforePan = value.acceptedPublications.get()
        composeRule.mainClock.autoAdvance = false
        val frozenClockTime = composeRule.mainClock.currentTime
        value.frozenClockTime.set(frozenClockTime)
        var frozenStage = "native_pan"
        var frozenClockDelta = -1L
        try {
            onMain { value.armed = true }
            val start = Offset(mapBounds.left + mapBounds.width * .75f, mapBounds.top + mapBounds.height * .4f)
            val end = Offset(mapBounds.left + mapBounds.width * .10f, start.y)
            nativeTouch(start, end, steps = 12, stepMillis = 20L)
            assertEquals("Native pan must not advance the frozen Compose clock", frozenClockTime, composeRule.mainClock.currentTime)
            frozenStage = "pan_invalidation"
            composeRule.waitUntil(10_000) {
                value.gestureAt.get() > 0 && !value.vm.state.value.canSelectViewport && onMain(camera) != originalCamera
            }
            // Recomposition is frozen: this is a real enabled button backed by stale area A.
            frozenStage = "stale_enabled_button"
            composeRule.onNodeWithTag(SELECT).assertIsEnabled()
            frozenStage = "stale_native_click"
            nativeTouch(buttonCenter, buttonCenter, steps = 1, stepMillis = 16L)
            assertEquals("Native click must not advance the frozen Compose clock", frozenClockTime, composeRule.mainClock.currentTime)
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
            frozenClockDelta = composeRule.mainClock.currentTime - frozenClockTime
            assertEquals("The entire stale-button phase must keep Compose time frozen", 0L, frozenClockDelta)
        } catch (failure: Throwable) {
            runCatching {
                value.panCameraChangedAtFailure.set(onMain(camera) != originalCamera)
                writeReadinessFailure(value, provider, frozenStage)
            }.onFailure(failure::addSuppressed)
            throw failure
        } finally { composeRule.mainClock.autoAdvance = true }
        try {
            composeRule.waitUntil(30_000) {
                val current = value.vm.state.value
                current.viewportSnapshot?.let { current.viewportIsCurrent(it.token) } == true && current.visibleIds.isEmpty()
            }
        } catch (failure: Throwable) {
            runCatching { writeReadinessFailure(value, provider, "settled_empty_viewport") }.onFailure(failure::addSuppressed)
            throw failure
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
            .put("frozenComposeClockDeltaMillis", frozenClockDelta)
            .put("staleButtonWasEnabled", true).put("nativeViewCount", 1)
            .put("settledEmptyViewport", true).put("sourceRequests", value.requests.get()).toString())
    }

    private fun screenBounds(tag: String): Rect {
        val bounds = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
        val offset = onMain {
            val root = requireNotNull(harness).root
            val screen = IntArray(2).also(root::getLocationOnScreen)
            val window = IntArray(2).also(root::getLocationInWindow)
            Offset((screen[0] - window[0]).toFloat(), (screen[1] - window[1]).toFloat())
        }
        return bounds.translate(offset)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun nativeTouch(start: Offset, end: Offset, steps: Int, stepMillis: Long) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = SystemClock.uptimeMillis()
        var lastPoint = start
        fun send(action: Int, point: Offset, eventTime: Long) {
            lastPoint = point
            val event = MotionEvent.obtain(downTime, eventTime, action, point.x, point.y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            onMain { composeRule.mainClock.scheduler.runCurrent() }
        }
        try {
            send(MotionEvent.ACTION_DOWN, start, downTime)
            repeat(steps) { step ->
                val eventTime = downTime + (step + 1) * stepMillis
                SystemClock.sleep((eventTime - SystemClock.uptimeMillis()).coerceAtLeast(0))
                send(MotionEvent.ACTION_MOVE, start + (end - start) * ((step + 1f) / steps), eventTime)
            }
            send(MotionEvent.ACTION_UP, end, downTime + steps * stepMillis)
        } catch (failure: Throwable) {
            runCatching { send(MotionEvent.ACTION_CANCEL, lastPoint, SystemClock.uptimeMillis()) }
                .onFailure(failure::addSuppressed)
            throw failure
        }
    }

    private fun show(value: Harness) {
        composeRule.setContent {
            val state by value.vm.state.collectAsState()
            val root = LocalView.current.rootView
            val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
            SideEffect { value.root = root; value.hostLifecycle = lifecycle }
            val bottom = with(LocalDensity.current) { 80.dp.roundToPx() }
            val padding = DiscoveryMapPadding(bottom = bottom)
            SideEffect { value.padding = padding }
            AnitabiTheme {
                Box(Modifier.fillMaxSize()) {
                    if (value.showMap) DiscoveryMap(state.mapDataVersion, state.mapPoints, state.provider,
                        privacyReady = true, selectedIds = emptySet(), focusedPointId = null, imagesEnabled = true,
                        darkTheme = false, padding = padding, cameraCommand = state.cameraCommand,
                        onVisibleIdsChanged = {}, onPointClick = {}, onOverlapClick = {},
                        onCameraChanged = { value.cameraChanges.incrementAndGet(); value.vm.cameraChanged(it) },
                        onManualMove = { if (value.armed) value.gestureAt.compareAndSet(0, SystemClock.elapsedRealtimeNanos()); value.vm.manualMove() },
                        onUnavailable = { value.unavailable = true; value.vm.mapUnavailable() },
                        modifier = Modifier.fillMaxSize().testTag(MAP), onCameraCommandApplied = { sequence, camera ->
                            value.ackCameraMatchesExpectedRestore.set(cameraMatches(camera, value.expectedRestore))
                            value.commandsApplied.incrementAndGet(); value.vm.cameraCommandApplied(sequence, camera)
                        },
                        viewportToken = state.viewportToken, onViewportInvalidated = {
                            value.invalidations.incrementAndGet(); value.vm.invalidateViewport(it)
                        },
                        onViewportCalculated = { token, ids ->
                            val current = value.vm.state.value
                            value.publicationAttempts.incrementAndGet(); value.lastPublicationMembers.set(ids.size)
                            value.lastTokenCurrent.set(token == current.viewportToken)
                            value.lastProviderMatches.set(token.provider == current.provider)
                            value.lastDataVersionMatches.set(token.dataVersion == current.mapDataVersion)
                            value.vm.viewportCalculated(token, ids).also {
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

    private fun writeReadinessFailure(value: Harness, provider: MapProvider, stage: String = "initial_ready") {
        val native = onMain { runCatching { nativeViews(value.root).singleOrNull() }.getOrNull() }
        val google = AtomicReference<GoogleMap?>()
        val callback = CountDownLatch(1)
        val accepting = AtomicBoolean(true)
        // Post-failure acquisition only: wait on the instrumentation thread, never Main or Compose time.
        val acquired = if (native is NavigationView) runCatching {
            onMain { native.getMapAsync { map ->
                if (accepting.get()) google.set(map)
                callback.countDown()
            } }
            callback.await(2, TimeUnit.SECONDS)
        }.getOrNull() else null
        accepting.set(false)
        val report = onMain {
            // Navigation Projection/GoogleMap/OnMapReadyCallback checked 2026-10-10:
            // one fresh projection is a camera snapshot; its pixels are map-relative, and map-ready does not prove layout.
            val current = value.vm.state.value
            val views = runCatching { nativeViews(value.root) }.getOrDefault(emptyList())
            val sdk = google.get().takeIf { acquired == true && native in views }
            val amap = (native as? AmapNativeView)?.takeIf { it in views }?.let { runCatching { it.map }.getOrNull() }
            val googleProjection = runCatching { sdk?.projection }.getOrNull()
            val amapProjection = runCatching { amap?.projection }.getOrNull()
            val bounds = runCatching {
                googleProjection?.visibleRegion?.latLngBounds?.let {
                    DiscoveryGeoBounds(it.southwest.latitude, it.southwest.longitude, it.northeast.latitude, it.northeast.longitude)
                } ?: amapProjection?.visibleRegion?.latLngBounds?.let {
                    DiscoveryGeoBounds(it.southwest.latitude, it.southwest.longitude, it.northeast.latitude, it.northeast.longitude)
                }
            }.getOrNull()
            val camera = runCatching {
                sdk?.cameraPosition?.let {
                    DiscoveryCameraPosition(GeoPoint(it.target.latitude, it.target.longitude), it.zoom, it.bearing, it.tilt, provider)
                } ?: amap?.cameraPosition?.let {
                    DiscoveryCameraPosition(GeoPoint(it.target.latitude, it.target.longitude), it.zoom, it.bearing, it.tilt, provider)
                }
            }.getOrNull()
            val members = current.mapPoints.filter { it.provider == provider }
            // This fixture has two identical sources; reuse its already-converted AMap center without another SDK conversion.
            val display = if (provider == MapProvider.GOOGLE || members.all { it.coordinate == value.fixtureSource }) members.map {
                IndexedDiscoveryPoint(it.id, if (provider == MapProvider.GOOGLE) it.coordinate else value.expectedRestore.center)
            } else null
            val projected = if (googleProjection != null || amapProjection != null) runCatching {
                display?.map { point ->
                    googleProjection?.toScreenLocation(LatLng(point.coordinate.latitude, point.coordinate.longitude))?.let {
                        ScreenPoint(it.x.toFloat(), it.y.toFloat())
                    } ?: requireNotNull(amapProjection).toScreenLocation(AmapLatLng(point.coordinate.latitude, point.coordinate.longitude)).let {
                        ScreenPoint(it.x.toFloat(), it.y.toFloat())
                    }
                }
            }.getOrNull() else null
            val content = native?.takeIf { it in views && it.width > 0 && it.height > 0 }?.let {
                value.padding?.content(it.width, it.height)
            }
            val queryCount = if (bounds != null && display != null) runCatching {
                DiscoverySpatialIndex(DiscoveryIndexKey(current.mapDataVersion, provider), display).query(bounds).size
            }.getOrNull() else null
            val observation = JSONObject().put("scope", "failure_time_fresh_projection_and_rebuilt_query_not_original_calculation")
                .put("googleCallbackAcquisition", if (native !is NavigationView) "not_requested" else when (acquired) {
                    true -> "observed"; false -> "timeout"; null -> "request_failed"
                })
                .put("sameNativeViewAtCapture", native != null && native in views)
                .put("nativeWidth", native?.width ?: JSONObject.NULL).put("nativeHeight", native?.height ?: JSONObject.NULL)
                .put("paddingBottomPx", value.padding?.bottom ?: JSONObject.NULL)
                .put("paddingObserved", value.padding != null).put("contentNonEmpty", content?.let { it.right > it.left && it.bottom > it.top } ?: JSONObject.NULL)
                .put("cameraAvailable", camera != null).put("projectionAvailable", googleProjection != null || amapProjection != null)
                .put("boundsAvailable", bounds != null)
                .put("displayCoordinatesAvailable", display != null)
                .put("cameraTargetMatchesInitialRestore", camera?.let { abs(it.center.latitude - value.expectedRestore.center.latitude) < 0.000001 &&
                    abs(it.center.longitude - value.expectedRestore.center.longitude) < 0.000001 } ?: JSONObject.NULL)
                .put("cameraZoomMatchesInitialRestore", camera?.let { abs(it.zoom - value.expectedRestore.zoom) < .001f } ?: JSONObject.NULL)
                .put("cameraBearingMatchesInitialRestore", camera?.let { abs(it.bearing - value.expectedRestore.bearing) < .001f } ?: JSONObject.NULL)
                .put("cameraTiltMatchesInitialRestore", camera?.let { abs(it.tilt - value.expectedRestore.tilt) < .001f } ?: JSONObject.NULL)
                .put("failureCameraMatchesAuthoredGoogleOverview", (if (provider == MapProvider.GOOGLE)
                    camera?.let { cameraMatches(it, AUTHORED_GOOGLE_OVERVIEW) } else null) ?: JSONObject.NULL)
                .put("boundsContainMemberCount", if (bounds != null) display?.count { bounds.contains(it.coordinate) } ?: JSONObject.NULL else JSONObject.NULL)
                .put("projectedMemberCount", projected?.size ?: JSONObject.NULL)
                .put("membersInsideNativeViewport", if (native != null) projected?.count { it.x >= 0f && it.x < native.width && it.y >= 0f && it.y < native.height } ?: JSONObject.NULL else JSONObject.NULL)
                .put("membersInsidePaddedContent", if (content != null) projected?.count(content::contains) ?: JSONObject.NULL else JSONObject.NULL)
                .put("membersNearContentCenter", if (content != null) projected?.count { abs(it.x - content.center.x) <= 3f && abs(it.y - content.center.y) <= 3f } ?: JSONObject.NULL else JSONObject.NULL)
                .put("failureTimeRebuiltQueryMemberCount", queryCount ?: JSONObject.NULL)
            JSONObject().put("provider", provider.name).put("scope", "native_VM_readiness_failure").put("stage", stage)
                .put("observationTiming", "fresh_after_failed_assertion_not_failed_wait_snapshot")
                .put("failureProjectionObservation", observation)
                .put("selectionCalls", value.selectionCalls.get()).put("selectedMembers", value.selectedMembers.get())
                .put("lifecycle", value.hostLifecycle?.currentState?.name ?: JSONObject.NULL).put("nativeViewCount", views.size)
                .put("allNativeViewsAttached", views.isNotEmpty() && views.all { it.isAttachedToWindow })
                .put("allNativeViewsHaveSize", views.isNotEmpty() && views.all { it.width > 0 && it.height > 0 })
                .put("sdkMapCallbackObserved", JSONObject.NULL).put("projectionEligibilityObserved", JSONObject.NULL)
                .put("unavailable", value.unavailable).put("initialized", current.data.initialized)
                .put("indexAvailable", current.data.indexAvailable).put("dataPreparing", current.dataPreparing)
                .put("memberCount", current.pointsById.size).put("mapPointCount", current.mapPoints.size)
                .put("unresolvedCount", current.unresolvedCount).put("listMode", current.listMode)
                .put("locating", current.locating).put("commandPresent", current.cameraCommand != null)
                .put("commandType", current.cameraCommand?.javaClass?.simpleName ?: JSONObject.NULL)
                .put("currentSnapshot", current.viewportSnapshot?.let { current.viewportIsCurrent(it.token) } == true)
                .put("canSelectViewport", current.canSelectViewport).put("gestureObserved", value.gestureAt.get() > 0)
                .put("panCameraChangedAtFailure", value.panCameraChangedAtFailure.get() ?: JSONObject.NULL)
                .put("visibleMemberCount", current.visibleIds.size).put("cameraCallbacks", value.cameraChanges.get())
                .put("commandsApplied", value.commandsApplied.get()).put("invalidations", value.invalidations.get())
                .put("ackCameraMatchesExpectedRestore", value.ackCameraMatchesExpectedRestore.get() ?: JSONObject.NULL)
                .put("testClockAtFreezeMillis", value.frozenClockTime.get().takeIf { it >= 0 } ?: JSONObject.NULL)
                .put("testClockAtFailureMillis", composeRule.mainClock.currentTime)
                .put("clickObservationPresent", value.click.get() != null)
                .put("publicationAttempts", value.publicationAttempts.get()).put("acceptedPublications", value.acceptedPublications.get())
                .put("lastPublicationMembers", value.lastPublicationMembers.get().takeIf { it >= 0 } ?: JSONObject.NULL)
                .put("lastTokenCurrent", value.lastTokenCurrent.get() ?: JSONObject.NULL)
                .put("lastProviderMatches", value.lastProviderMatches.get() ?: JSONObject.NULL)
                .put("lastDataVersionMatches", value.lastDataVersionMatches.get() ?: JSONObject.NULL)
                .put("sourceRequests", value.requests.get())
        }
        val directory = File(requireNotNull(application.getExternalFilesDir(null)), "frontend-review")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, "native-viewport-readiness-${provider.name.lowercase()}.json").writeText(report.toString())
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

    private class Harness(val vm: DiscoveryViewModel, val requests: AtomicInteger,
        val expectedRestore: DiscoveryCameraPosition, val fixtureSource: GeoPoint) {
        lateinit var root: View
        var showMap by mutableStateOf(true)
        @Volatile var unavailable = false
        @Volatile var armed = false
        var hostLifecycle: Lifecycle? = null
        var padding: DiscoveryMapPadding? = null
        val cameraChanges = AtomicInteger()
        val commandsApplied = AtomicInteger()
        val ackCameraMatchesExpectedRestore = AtomicReference<Boolean?>(null)
        val frozenClockTime = AtomicLong(-1)
        val invalidations = AtomicInteger()
        val publicationAttempts = AtomicInteger()
        val lastPublicationMembers = AtomicInteger(-1)
        val lastTokenCurrent = AtomicReference<Boolean?>(null)
        val lastProviderMatches = AtomicReference<Boolean?>(null)
        val lastDataVersionMatches = AtomicReference<Boolean?>(null)
        val panCameraChangedAtFailure = AtomicReference<Boolean?>(null)
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
        val AUTHORED_GOOGLE_OVERVIEW = DiscoveryCameraPosition(GeoPoint(37.0, 138.0), 4.5f)
        fun cameraMatches(camera: DiscoveryCameraPosition, expected: DiscoveryCameraPosition): Boolean =
            abs(camera.center.latitude - expected.center.latitude) < 0.000001 &&
                abs(camera.center.longitude - expected.center.longitude) < 0.000001 &&
                abs(camera.zoom - expected.zoom) < .001f &&
                abs(camera.bearing - expected.bearing) < .001f &&
                abs(camera.tilt - expected.tilt) < .001f
        fun nativeViews(root: View): List<View> = buildList {
            if (root is NavigationView || root is AmapNativeView) add(root)
            else if (root is ViewGroup) repeat(root.childCount) { addAll(nativeViews(root.getChildAt(it))) }
        }
    }
}
