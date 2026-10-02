package cn.anitabi.navigator.ui.discovery.map

import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.anitabi.navigator.TestAnitabiApplication
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.navigation.PhoneHeadingSample
import cn.anitabi.navigator.navigation.freshPhoneHeadings
import cn.anitabi.navigator.navigation.phoneHeadingFromRotation
import cn.anitabi.navigator.security.AppAppearance
import cn.anitabi.navigator.ui.map.OfficialAmapCoordinateConverter
import cn.anitabi.navigator.ui.map.isAmapNativeMapLibraryAvailable
import cn.anitabi.navigator.ui.theme.AnitabiTheme
import com.amap.api.maps.MapView as AmapNativeView
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.navigation.NavigationView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Real SDK projection, marker pixels and native touches with authored positions/rotation vectors.
 * This does not certify physical sensors, GNSS accuracy, authenticated base tiles or route behavior.
 */
class DiscoveryUserLocationNativeInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()
    private val application = ApplicationProvider.getApplicationContext<TestAnitabiApplication>()
    private var privacyWasReady = false

    @Before fun rememberPrivacy() { privacyWasReady = application.container.amapPrivacyGate.isReady }

    @After fun restorePrivacy() {
        if (!privacyWasReady) InstrumentationRegistry.getInstrumentation().runOnMainSync {
            application.container.amapPrivacyGate.revoke()
        }
    }

    @Test fun googleLocateRendersFreshHeadingWithoutSelectingOrFollowingLocation() = verifyProvider(MapProvider.GOOGLE)

    @Test fun amapLocateRendersFreshHeadingWithoutSelectingOrFollowingLocation() = verifyProvider(MapProvider.AMAP)

    private fun verifyProvider(provider: MapProvider) {
        if (provider == MapProvider.AMAP) InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue("Native AMap fixture requires a supported installed ABI", isAmapNativeMapLibraryAvailable(application))
            assertTrue("AMap privacy/key gate must precede SDK construction", application.container.amapPrivacyGate.prepareIfAllowed(true))
        }
        val harness = Harness(provider)
        val sourcePoints = harness.points.map { it.coordinate }
        show(harness)
        awaitReady(harness, listOf(1L))
        val native = nativeMap(harness)
        val density = native.view.resources.displayMetrics.density
        val initialAnchor = composeRule.runOnIdle { native.project(harness.location!!) }
        val camera = composeRule.runOnIdle { native.camera() }
        awaitLocationPixels(harness, native, initialAnchor, density, null)
        composeRule.runOnIdle {
            assertTrue("Locate must center the qualified position in measured map content",
                near(initialAnchor, PADDING.content(native.view.width, native.view.height).center))
        }
        // The native dot is a separate overlay; it must not become a catalog tap or cluster member.
        tap(initialAnchor)
        composeRule.runOnIdle { harness.sensor = SyntheticHeading(0f) }
        awaitLocationPixels(harness, native, initialAnchor, density, 0)
        // Exercise screen remapping as well as the opposite SDK rotation conventions.
        composeRule.runOnIdle { harness.sensor = SyntheticHeading(0f, screenRotation = Surface.ROTATION_90) }
        awaitLocationPixels(harness, native, initialAnchor, density, 1)
        composeRule.runOnIdle { harness.sensor = SyntheticHeading(270f) }
        awaitLocationPixels(harness, native, initialAnchor, density, 3)

        composeRule.runOnIdle {
            harness.location = harness.location!!.copy(longitude = harness.location!!.longitude + .0004)
        }
        val movedAnchor = composeRule.runOnIdle { native.project(harness.location!!) }
        assertFalse("The passive position fixture must visibly move", near(initialAnchor, movedAnchor))
        awaitLocationPixels(harness, native, movedAnchor, density, 3)
        // A single sample must expire through the same production freshness flow used by sensors.
        composeRule.runOnIdle { harness.sensor = SyntheticHeading(270f, keepFresh = false) }
        composeRule.waitUntil(5_000) {
            composeRule.runOnIdle { harness.singleSampleNanos > 0L && harness.heading?.let { abs(it - 270f) < .01f } == true }
        }
        composeRule.waitUntil(5_000) { composeRule.runOnIdle { harness.heading == null } }
        assertTrue("A fresh heading must retain the two-second validity interval",
            SystemClock.elapsedRealtimeNanos() - harness.singleSampleNanos >= 1_900_000_000L)
        awaitLocationPixels(harness, native, movedAnchor, density, null)
        composeRule.runOnIdle {
            harness.dark = true
            harness.sensor = SyntheticHeading(102f, declination = -12f)
        }
        awaitLocationPixels(harness, native, movedAnchor, density, 1)
        composeRule.runOnIdle {
            assertRetainedCamera(native, camera)
            assertSame(native.view, nativeViews(harness.root).single())
            assertEquals("Location/heading must not enter visible catalog selection", IDS, harness.visible)
            assertTrue("A native location tap must not select a point", harness.clicks.isEmpty())
            assertTrue("A native location tap must not open overlap members", harness.overlaps.isEmpty())
            assertTrue("Location updates must preserve WGS84 catalog fixtures", sourcePoints == harness.points.map { it.coordinate })
            assertEquals("Passive updates must not replay Locate", listOf(1L), harness.applied)
            harness.locationProvider = if (provider == MapProvider.GOOGLE) MapProvider.AMAP else MapProvider.GOOGLE
        }
        awaitLocationAbsent(harness, native, movedAnchor, density)
        // Removing the location overlay must leave the catalog's exact overlap membership usable.
        composeRule.runOnIdle {
            harness.location = null
            harness.cameraCommand = DiscoveryCameraCommand.Restore(2L,
                DiscoveryCameraPosition(native.display(harness.points.first().coordinate), native.maxZoom(), provider = provider))
        }
        awaitReady(harness, listOf(1L, 2L))
        val clusterAnchor = composeRule.runOnIdle { native.project(harness.points.first().coordinate) }
        awaitColor(native.view, ScreenPoint(clusterAnchor.x, clusterAnchor.y + 13f * density), Color.rgb(243, 241, 238))
        tap(clusterAnchor)
        composeRule.waitUntil(5_000) { composeRule.runOnIdle { harness.overlaps.isNotEmpty() } }
        composeRule.runOnIdle {
            assertEquals("The native cluster must contain only its two catalog members", listOf(IDS.sorted()), harness.overlaps)
            assertTrue(harness.clicks.isEmpty())
            assertEquals(IDS, harness.visible)
            assertSame(native.view, nativeViews(harness.root).single())
            harness.showMap = false
        }
        composeRule.waitUntil(5_000) {
            composeRule.runOnIdle { nativeViews(harness.root).isEmpty() && !native.view.isAttachedToWindow }
        }
    }

    private fun show(harness: Harness) {
        composeRule.setContent {
            val root = LocalView.current.rootView
            SideEffect { harness.root = root }
            AnitabiTheme(if (harness.dark) AppAppearance.DARK else AppAppearance.LIGHT) {
                if (harness.showMap) {
                    val input = harness.sensor
                    LaunchedEffect(input) {
                        flow<PhoneHeadingSample?> {
                            if (input == null) emit(null) else do {
                                val halfRadians = Math.toRadians(-input.degrees.toDouble()) / 2
                                val matrix = FloatArray(9)
                                SensorManager.getRotationMatrixFromVector(matrix,
                                    floatArrayOf(0f, 0f, sin(halfRadians).toFloat(), cos(halfRadians).toFloat()))
                                val degrees = phoneHeadingFromRotation(matrix, input.screenRotation, input.declination)
                                val sampledAt = SystemClock.elapsedRealtimeNanos()
                                if (!input.keepFresh) harness.singleSampleNanos = sampledAt
                                emit(degrees?.let { PhoneHeadingSample(it, sampledAt) })
                                if (input.keepFresh) delay(250)
                            } while (input.keepFresh && currentCoroutineContext().isActive)
                        }.freshPhoneHeadings(SystemClock::elapsedRealtimeNanos).collect { harness.heading = it }
                    }
                    DiscoveryMap(
                        dataVersion = "synthetic-location-${harness.provider}", points = harness.points,
                        provider = harness.provider, privacyReady = true,
                        selectedIds = emptySet(), focusedPointId = null, imagesEnabled = false,
                        darkTheme = harness.dark, padding = PADDING, cameraCommand = harness.cameraCommand,
                        onVisibleIdsChanged = { harness.visible = it },
                        onPointClick = { harness.clicks += it }, onOverlapClick = { harness.overlaps = harness.overlaps + listOf(it.toList()) },
                        onCameraChanged = {}, onManualMove = {}, onUnavailable = { harness.unavailable = true },
                        onCameraCommandApplied = { sequence, _ -> harness.applied += sequence },
                        userLocation = harness.location, userLocationProvider = harness.locationProvider,
                        userHeading = harness.heading, modifier = Modifier.fillMaxSize().testTag(MAP_TAG),
                    )
                }
            }
        }
    }

    private fun awaitReady(harness: Harness, commands: List<Long>) {
        composeRule.waitUntil(30_000) {
            check(!harness.unavailable) { "Native SDK unavailable; this is a failed live map check" }
            harness.applied == commands && harness.visible == IDS
        }
        composeRule.runOnIdle { assertEquals(1, nativeViews(harness.root).size) }
    }

    private fun nativeMap(harness: Harness): NativeMap {
        val view = composeRule.runOnIdle { nativeViews(harness.root).single() }
        return when (view) {
            is NavigationView -> {
                composeRule.runOnIdle { view.getMapAsync { harness.googleMap = it } }
                composeRule.waitUntil(10_000) { harness.googleMap != null }
                val map = requireNotNull(harness.googleMap)
                NativeMap(view, { it }, { point -> map.projection.toScreenLocation(LatLng(point.latitude, point.longitude)).let {
                    ScreenPoint(it.x.toFloat(), it.y.toFloat())
                } }, { map.cameraPosition.let {
                    DiscoveryCameraPosition(GeoPoint(it.target.latitude, it.target.longitude), it.zoom, it.bearing, it.tilt)
                } }, { map.maxZoomLevel })
            }
            is AmapNativeView -> {
                val converter = composeRule.runOnIdle { OfficialAmapCoordinateConverter(application) }
                val display: (GeoPoint) -> GeoPoint = { converter.convert(it).let { GeoPoint(it.latitude, it.longitude) } }
                NativeMap(view, display, { point -> display(point).let {
                    view.map.projection.toScreenLocation(com.amap.api.maps.model.LatLng(it.latitude, it.longitude))
                }.let { ScreenPoint(it.x.toFloat(), it.y.toFloat()) } }, { view.map.cameraPosition.let {
                    DiscoveryCameraPosition(GeoPoint(it.target.latitude, it.target.longitude), it.zoom, it.bearing, it.tilt, MapProvider.AMAP)
                } }, { view.map.maxZoomLevel })
            }
            else -> error("Expected an actual provider SDK view")
        }
    }

    /** Main-thread camera/projection checks never print geographic values. */
    private fun assertRetainedCamera(native: NativeMap, expected: DiscoveryCameraPosition) {
        val actual = native.camera()
        assertTrue("Passive updates must retain the camera target", expected.center == actual.center)
        assertEquals(expected.zoom, actual.zoom, .001f)
        assertEquals(expected.bearing, actual.bearing, .001f)
        assertEquals(expected.tilt, actual.tilt, .001f)
    }

    private fun tap(anchor: ScreenPoint) = composeRule.onNodeWithTag(MAP_TAG)
        .performTouchInput { click(Offset(anchor.x, anchor.y)) }

    private fun awaitLocationPixels(harness: Harness, native: NativeMap, anchor: ScreenPoint, density: Float, direction: Int?) {
        val rays = listOf(ScreenPoint(0f, -12f), ScreenPoint(12f, 0f), ScreenPoint(0f, 12f), ScreenPoint(-12f, 0f))
        awaitPixels(native.view) { bitmap, x, y ->
            val blue = if (harness.dark) Color.rgb(138, 180, 248) else Color.rgb(23, 105, 224)
            val centerPresent = colorAt(bitmap, x + anchor.x, y + anchor.y, blue)
            val arrowMatches = rays.withIndex().all { (index, ray) ->
                colorAt(bitmap, x + anchor.x + ray.x * density, y + anchor.y + ray.y * density, blue) == (index == direction)
            }
            val ready = composeRule.runOnIdle {
                if (direction == null) harness.heading == null else harness.heading?.let { abs(it - direction * 90f) < .01f } == true
            }
            centerPresent && arrowMatches && ready
        }
    }

    private fun awaitLocationAbsent(harness: Harness, native: NativeMap, anchor: ScreenPoint, density: Float) {
        awaitPixels(native.view) { bitmap, x, y ->
            val blue = if (harness.dark) Color.rgb(138, 180, 248) else Color.rgb(23, 105, 224)
            listOf(ScreenPoint(0f, 0f), ScreenPoint(12f * density, 0f)).none {
                colorAt(bitmap, x + anchor.x + it.x, y + anchor.y + it.y, blue)
            }
        }
    }

    private fun awaitColor(view: View, anchor: ScreenPoint, color: Int) = awaitPixels(view) { bitmap, x, y ->
        colorAt(bitmap, x + anchor.x, y + anchor.y, color)
    }

    private fun awaitPixels(view: View, matches: (Bitmap, Int, Int) -> Boolean) {
        val location = IntArray(2)
        var consecutive = 0
        composeRule.waitUntil(15_000) {
            composeRule.runOnIdle { view.getLocationOnScreen(location) }
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return@waitUntil false
            try {
                consecutive = if (matches(bitmap, location[0], location[1])) consecutive + 1 else 0
                consecutive >= 3
            } finally { bitmap.recycle() }
        }
    }

    private data class NativeMap(val view: View, val display: (GeoPoint) -> GeoPoint,
        val project: (GeoPoint) -> ScreenPoint, val camera: () -> DiscoveryCameraPosition, val maxZoom: () -> Float)

    private data class SyntheticHeading(val degrees: Float, val screenRotation: Int = Surface.ROTATION_0,
        val declination: Float = 0f, val keepFresh: Boolean = true)

    private class Harness(val provider: MapProvider) {
        lateinit var root: View
        val originalLocation = if (provider == MapProvider.GOOGLE) GeoPoint(0.0, 0.0) else GeoPoint(30.0, 110.0)
        val points = IDS.sorted().map { DiscoveryMapPoint(it, 901L,
            originalLocation.copy(latitude = originalLocation.latitude + .0015), provider, "", Color.MAGENTA) }
        var location by mutableStateOf<GeoPoint?>(originalLocation)
        var locationProvider by mutableStateOf(provider)
        var sensor by mutableStateOf<SyntheticHeading?>(null)
        var heading by mutableStateOf<Float?>(null)
        var dark by mutableStateOf(false)
        var showMap by mutableStateOf(true)
        var cameraCommand by mutableStateOf<DiscoveryCameraCommand>(DiscoveryCameraCommand.Locate(1L, originalLocation))
        @Volatile var googleMap: GoogleMap? = null
        @Volatile var singleSampleNanos = 0L
        @Volatile var visible = emptySet<String>()
        @Volatile var applied = emptyList<Long>()
        @Volatile var clicks = emptyList<String>()
        @Volatile var overlaps = emptyList<List<String>>()
        @Volatile var unavailable = false
    }

    private companion object {
        const val MAP_TAG = "native-user-location-map"
        val IDS = setOf("synthetic::location-catalog-first", "synthetic::location-catalog-second")
        val PADDING = DiscoveryMapPadding(left = 24, top = 48, right = 12, bottom = 120)
        fun nativeViews(root: View): List<View> = buildList {
            if (root is NavigationView || root is AmapNativeView) add(root)
            else if (root is ViewGroup) repeat(root.childCount) { addAll(nativeViews(root.getChildAt(it))) }
        }
        fun near(first: ScreenPoint, second: ScreenPoint) = abs(first.x - second.x) <= 3f && abs(first.y - second.y) <= 3f
        fun colorAt(bitmap: Bitmap, x: Float, y: Float, expected: Int): Boolean = (-2..2).any { dx -> (-2..2).any { dy ->
            val pixelX = x.roundToInt() + dx
            val pixelY = y.roundToInt() + dy
            pixelX in 0 until bitmap.width && pixelY in 0 until bitmap.height && bitmap.getPixel(pixelX, pixelY).let {
                abs(Color.red(it) - Color.red(expected)) < 25 && abs(Color.green(it) - Color.green(expected)) < 25 &&
                    abs(Color.blue(it) - Color.blue(expected)) < 25
            }
        } }
    }
}
