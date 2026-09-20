package cn.anitabi.navigator.ui

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.anitabi.navigator.SyntheticDiscoveryFixture
import cn.anitabi.navigator.TestAnitabiApplication
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.core.model.TerritoryRegion
import cn.anitabi.navigator.navigation.CurrentLocationProvider
import cn.anitabi.navigator.ui.discovery.DiscoveryPreferences
import cn.anitabi.navigator.ui.discovery.DiscoveryUiState
import cn.anitabi.navigator.ui.discovery.DiscoveryViewModel
import cn.anitabi.navigator.ui.discovery.map.DiscoveryCameraCommand
import cn.anitabi.navigator.ui.discovery.map.DiscoveryCameraPosition
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real startup state/persistence with injected location; no Activity, SDK, network or GPS calls. */
@RunWith(AndroidJUnit4::class)
class DiscoveryStartupInstrumentedTest {
    private val application = ApplicationProvider.getApplicationContext<TestAnitabiApplication>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val preferences = DiscoveryPreferences(application)
    private val store = ViewModelStore()
    private var originalCamera: DiscoveryCameraPosition? = null
    private val points = SyntheticDiscoveryFixture.snapshot().points

    @Before
    fun rememberView() {
        originalCamera = preferences.lastCamera()
    }

    @After
    fun restoreViewAndClearModel() {
        onMain { store.clear() }
        originalCamera?.let(preferences::saveCamera) ?: application
            .getSharedPreferences("discovery_view", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun deniedPermissionRetainsSavedProviderCameraWithoutRequestingLocation() {
        val saved = savedCamera()
        preferences.saveCamera(saved)
        val location = DeferredLocation()
        val model = model(location)

        onMain { model.initializeLocation(hasPermission = false) }

        val state = model.state.value
        assertEquals(0, location.calls.get())
        assertEquals(MapProvider.AMAP, state.provider)
        assertEquals(saved, (state.cameraCommand as DiscoveryCameraCommand.Restore).camera)
        assertFalse(state.locating)
        assertNull(state.location)

        onMain { store.clear() }
        application.getSharedPreferences("discovery_view", Context.MODE_PRIVATE).edit().clear().commit()
        val firstLaunch = model(location)
        onMain { firstLaunch.initializeLocation(hasPermission = false) }
        assertEquals(0, location.calls.get())
        assertEquals(MapProvider.GOOGLE, firstLaunch.state.value.provider)
        assertNull("No history leaves the initial overview to the map adapter", firstLaunch.state.value.cameraCommand)
    }

    @Test
    fun availableInitialLocationSupersedesSavedCameraAndRequestsOnlyOnce() {
        val saved = savedCamera()
        preferences.saveCamera(saved)
        val location = DeferredLocation()
        val model = model(location)
        val restored = requireNotNull(model.state.value.cameraCommand)

        onMain { model.initializeLocation(hasPermission = true) }
        awaitRequest(location)
        assertEquals(restored, model.state.value.cameraCommand)
        location.result.complete(points.last().coordinate)
        val state = awaitResolved(model)

        assertEquals(MapProvider.GOOGLE, state.provider)
        assertEquals(points.last().coordinate, state.location)
        val command = state.cameraCommand as DiscoveryCameraCommand.Locate
        assertEquals(points.last().coordinate, command.coordinate)
        assertTrue(command.sequence > restored.sequence)
        onMain { model.initializeLocation(hasPermission = true) }
        assertEquals(1, location.calls.get())
    }

    @Test
    fun unavailableInitialLocationRetainsSavedViewWithoutAnIntrusiveError() {
        val saved = savedCamera()
        preferences.saveCamera(saved)
        val location = DeferredLocation()
        val model = model(location)
        val restored = model.state.value.cameraCommand

        onMain { model.initializeLocation(hasPermission = true) }
        awaitRequest(location)
        location.result.completeExceptionally(IllegalStateException("Synthetic unavailable location"))
        val state = awaitResolved(model)

        assertEquals(1, location.calls.get())
        assertEquals(MapProvider.AMAP, state.provider)
        assertEquals(restored, state.cameraCommand)
        assertEquals(saved, preferences.lastCamera())
        assertNull(state.location)
        assertNull(state.message)
    }

    @Test
    fun lateInitialLocationAfterManualMoveCannotReclaimCameraOrProvider() {
        preferences.saveCamera(savedCamera())
        val location = DeferredLocation()
        val model = model(location)

        onMain { model.initializeLocation(hasPermission = true) }
        awaitRequest(location)
        onMain { model.manualMove() }
        assertNull(model.state.value.cameraCommand)
        location.result.complete(points.last().coordinate)
        val state = awaitResolved(model)

        assertEquals(1, location.calls.get())
        assertEquals(points.last().coordinate, state.location)
        assertEquals(MapProvider.AMAP, state.provider)
        assertNull(state.cameraCommand)
        assertFalse(state.locating)
    }

    @Test
    fun returningMapRestoresViewportButNewFocusRejectsDetachAndStaleAcknowledgement() {
        preferences.saveCamera(savedCamera().copy(provider = MapProvider.GOOGLE))
        val location = DeferredLocation()
        val model = model(location)
        val ready = runBlocking {
            withTimeout(5_000) { model.state.first { points.first().id in it.pointsById } }
        }
        val target = ready.pointsById.getValue(points.first().id)
        val moved = savedCamera().copy(
            center = points.last().coordinate, zoom = 14f, bearing = 73f, tilt = 19f,
            provider = MapProvider.GOOGLE,
        )
        onMain {
            model.cameraChanged(moved)
            model.manualMove()
            model.mapDetached()
        }
        val restore = model.state.value.cameraCommand as DiscoveryCameraCommand.Restore
        assertEquals(moved, restore.camera)
        assertEquals(moved.provider, model.state.value.provider)
        assertEquals(moved, preferences.lastCamera())

        onMain { model.updateQuery(target.displayName) }
        runBlocking {
            withTimeout(5_000) { model.searchResults.first { results -> results.points.any { it.id == target.id } } }
        }
        assertEquals(restore, model.state.value.cameraCommand)

        onMain { model.openPoint(target.id) }
        val focus = model.state.value.cameraCommand as DiscoveryCameraCommand.Focus
        assertEquals(target.id, focus.pointId)
        assertFalse(focus.minimallyPan)
        assertTrue(focus.sequence > restore.sequence)
        onMain {
            model.mapDetached()
            // Same provider, older sequence: rejection must not rely on provider mismatch alone.
            model.cameraCommandApplied(restore.sequence, moved.copy(zoom = 3f))
        }
        assertEquals(focus, model.state.value.cameraCommand)
        assertEquals(moved, preferences.lastCamera())

        val focused = moved.copy(center = target.coordinate, zoom = 16f)
        onMain { model.cameraCommandApplied(focus.sequence, focused) }
        assertNull(model.state.value.cameraCommand)
        assertEquals(focused, preferences.lastCamera())
        assertEquals(0, location.calls.get())
    }

    private fun model(location: CurrentLocationProvider): DiscoveryViewModel {
        lateinit var model: DiscoveryViewModel
        onMain {
            model = DiscoveryViewModel(
                repository = application.container.discoveryRepository,
                preferences = preferences,
                locationProvider = location,
                classifyTerritory = { TerritoryRegion.OTHER },
                savedState = SavedStateHandle(),
            )
            store.put("discovery-startup", model)
        }
        return model
    }

    private fun savedCamera() = DiscoveryCameraPosition(
        center = points.first().coordinate, zoom = 9f, bearing = 31f, tilt = 8f,
        provider = MapProvider.AMAP,
    )

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync { block() }

    private fun awaitRequest(location: DeferredLocation) = runBlocking {
        withTimeout(5_000) { location.requested.await() }
    }

    private fun awaitResolved(model: DiscoveryViewModel): DiscoveryUiState = runBlocking {
        withTimeout(5_000) { model.state.first { !it.locating } }
    }

    private class DeferredLocation : CurrentLocationProvider {
        val calls = AtomicInteger()
        val requested = CompletableDeferred<Unit>()
        val result = CompletableDeferred<GeoPoint>()
        override suspend fun currentLocation(): GeoPoint {
            calls.incrementAndGet()
            requested.complete(Unit)
            return result.await()
        }
    }
}
