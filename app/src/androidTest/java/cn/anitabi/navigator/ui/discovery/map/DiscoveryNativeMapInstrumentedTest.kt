package cn.anitabi.navigator.ui.discovery.map

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.anitabi.navigator.TestAnitabiApplication
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.ui.map.OfficialAmapCoordinateConverter
import cn.anitabi.navigator.ui.theme.AnitabiTheme
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.annotation.DelicateCoilApi
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import com.amap.api.maps.MapView as AmapNativeView
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapColorScheme
import com.google.android.libraries.navigation.NavigationView
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Real SDK views/projection/marker pixels/input; all point metadata and image pixels are authored fixtures.
 * Marker rendering does not certify authenticated base tiles; that is a separate provider availability check.
 */
@OptIn(DelicateCoilApi::class)
class DiscoveryNativeMapInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()

    private val application = ApplicationProvider.getApplicationContext<TestAnitabiApplication>()
    private lateinit var previousLoader: ImageLoader
    private lateinit var fixtureLoader: ImageLoader
    private val imageRequests = AtomicInteger()
    private var privacyWasReady = false

    @Before
    fun prepareFixtureImages() {
        privacyWasReady = application.container.amapPrivacyGate.isReady
        previousLoader = SingletonImageLoader.get(application)
        fixtureLoader = ImageLoader.Builder(application).components {
            add(object : Interceptor {
                override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                    check(chain.request.data == SYNTHETIC_IMAGE_URL) { "Unexpected fixture image request" }
                    imageRequests.incrementAndGet()
                    val image = Bitmap.createBitmap(120, 90, Bitmap.Config.ARGB_8888).apply { eraseColor(IMAGE_COLOR) }
                    return SuccessResult(image = image.asImage(), request = chain.request, dataSource = DataSource.MEMORY)
                }
            })
        }.build()
        SingletonImageLoader.setUnsafe(fixtureLoader)
    }

    @After
    fun restoreImageLoaderAndPrivacy() {
        SingletonImageLoader.setUnsafe(previousLoader)
        fixtureLoader.shutdown()
        if (!privacyWasReady) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { application.container.amapPrivacyGate.revoke() }
        }
    }

    @Test
    fun googleProjectionAndBitmapAnchorSurvivePresentationChanges() = verifyProvider(MapProvider.GOOGLE)

    @Test
    fun amapProjectionAndBitmapAnchorSurvivePresentationChanges() = verifyProvider(MapProvider.AMAP)

    @Test
    fun amapOverlapMarkerExposesItsCountToNativeAccessibility() {
        prepareAmap()
        val harness = Harness(MapProvider.AMAP)
        harness.points = listOf(harness.points.first(), harness.points.first().copy(id = "synthetic::overlap"))
        show(harness)
        composeRule.waitUntil(30_000) {
            check(!harness.unavailable) { "Native map unavailable; this is a failed live SDK check" }
            harness.applied == listOf(1L) && harness.visible.size == 2
        }
        val view = nativeView(harness) as AmapNativeView
        composeRule.waitUntil(10_000) {
            composeRule.runOnIdle {
                view.map.mapScreenMarkers.any { it.title.startsWith("2 \u4e2a\u5730\u70b9") }
            }
        }
        composeRule.runOnIdle { assertEquals(1, view.map.mapScreenMarkers.size) }
    }

    @Test
    fun switchingProvidersAndLeavingMapRetainsOnlyOneAttachedSdkView() {
        prepareAmap()
        val harness = Harness(MapProvider.GOOGLE)
        show(harness)
        awaitPoint(harness)
        val first = nativeView(harness)
        composeRule.runOnIdle {
            harness.provider = MapProvider.AMAP
            harness.points = fixturePoints(MapProvider.AMAP)
            harness.visible = emptySet()
            harness.applied = emptyList()
        }
        awaitPoint(harness)
        composeRule.runOnIdle {
            val second = nativeViews(harness.root).single()
            assertTrue(second is AmapNativeView)
            assertFalse(first.isAttachedToWindow)
            harness.showMap = false
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertTrue(nativeViews(harness.root).isEmpty()) }
    }

    private fun verifyProvider(provider: MapProvider) {
        if (provider == MapProvider.AMAP) prepareAmap()
        val harness = Harness(provider)
        show(harness)
        awaitPoint(harness)
        val view = nativeView(harness)
        val projection = projection(harness, view)
        val originalSource = harness.points.first().coordinate
        val anchor = composeRule.runOnIdle { projection() }
        composeRule.runOnIdle {
            val expected = PADDING.content(view.width, view.height).center
            assertTrue("Native projection must place the point in the measured content centre", near(anchor, expected))
        }
        awaitTheme(harness, view, dark = false)
        awaitColor(harness, view, anchor, POINT_COLOR, "initial-dot")
        clickAnchor(harness, anchor)

        composeRule.runOnIdle {
            harness.points = harness.points.map { it.copy(title = "Synthetic image marker", imageUrl = SYNTHETIC_IMAGE_URL) }
            harness.presentationRevision++
        }
        awaitPresentation(harness)
        composeRule.waitUntil(10_000) { imageRequests.get() > 0 }
        val density = application.resources.displayMetrics.density
        awaitColor(harness, view, ScreenPoint(anchor.x, anchor.y - 35f * density), IMAGE_COLOR, "image-card")
        awaitColor(harness, view, anchor, POINT_COLOR, "image-dot")
        assertStable(harness, view, projection, anchor, originalSource)
        clickAnchor(harness, anchor)

        composeRule.runOnIdle {
            harness.dark = true
            harness.selected = setOf(POINT_ID)
            harness.focused = POINT_ID
            harness.points = harness.points.map { it.copy(title = "Synthetic updated metadata") }
            harness.presentationRevision++
        }
        awaitPresentation(harness)
        awaitTheme(harness, view, dark = true)
        awaitColor(harness, view, anchor, POINT_COLOR, "dark-selected-dot")
        assertStable(harness, view, projection, anchor, originalSource)
        clickAnchor(harness, anchor)

        composeRule.runOnIdle { harness.imagesEnabled = false; harness.presentationRevision++ }
        awaitPresentation(harness)
        awaitColor(harness, view, ScreenPoint(anchor.x, anchor.y - 35f * density), IMAGE_COLOR, "disabled-image", matches = false)
        awaitColor(harness, view, anchor, POINT_COLOR, "label-dot")
        assertStable(harness, view, projection, anchor, originalSource)
        clickAnchor(harness, anchor)
    }

    private fun prepareAmap() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue("AMap privacy/key precondition must succeed before SDK construction",
                application.container.amapPrivacyGate.prepareIfAllowed(true))
        }
    }

    private fun show(harness: Harness) {
        composeRule.setContent {
            val root = LocalView.current.rootView
            val presentationRevision = harness.presentationRevision
            SideEffect { harness.root = root }
            AnitabiTheme {
                if (harness.showMap) DiscoveryMap(
                    dataVersion = "synthetic-native-${harness.provider}", points = harness.points,
                    provider = harness.provider, privacyReady = true,
                    selectedIds = harness.selected, focusedPointId = harness.focused,
                    imagesEnabled = harness.imagesEnabled, darkTheme = harness.dark,
                    padding = PADDING, cameraCommand = DiscoveryCameraCommand.Focus(1L, POINT_ID),
                    onVisibleIdsChanged = { harness.visible = it; harness.visibleRevision = presentationRevision },
                    onPointClick = { harness.clicks += it },
                    onOverlapClick = { error("Unexpected synthetic overlap") }, onCameraChanged = {},
                    onManualMove = {}, onUnavailable = { harness.unavailable = true },
                    onCameraCommandApplied = { sequence, _ -> harness.applied += sequence },
                    modifier = Modifier.fillMaxSize().testTag(MAP_TAG),
                )
            }
        }
    }

    private fun awaitPoint(harness: Harness) {
        composeRule.waitUntil(30_000) {
            check(!harness.unavailable) { "Native map unavailable; this is a failed live SDK check" }
            harness.applied == listOf(1L) && harness.visible == setOf(POINT_ID)
        }
        composeRule.runOnIdle { assertEquals(1, nativeViews(harness.root).size) }
    }

    private fun nativeView(harness: Harness): View = composeRule.runOnIdle { nativeViews(harness.root).single() }

    private fun awaitPresentation(harness: Harness) {
        val revision = composeRule.runOnIdle { harness.presentationRevision }
        composeRule.waitUntil(10_000) {
            check(!harness.unavailable) { "Native map unavailable during presentation update" }
            harness.visibleRevision == revision && harness.visible == setOf(POINT_ID)
        }
    }

    private fun awaitTheme(harness: Harness, view: View, dark: Boolean) {
        composeRule.waitUntil(10_000) {
            composeRule.runOnIdle {
                when (view) {
                    is NavigationView -> requireNotNull(harness.googleMap).mapColorScheme ==
                        if (dark) MapColorScheme.DARK else MapColorScheme.LIGHT
                    is AmapNativeView -> view.map.mapType == if (dark) com.amap.api.maps.AMap.MAP_TYPE_NIGHT
                        else com.amap.api.maps.AMap.MAP_TYPE_NORMAL
                    else -> false
                }
            }
        }
    }

    private fun projection(harness: Harness, view: View): () -> ScreenPoint = when (view) {
        is NavigationView -> {
            var sdk: GoogleMap? = null
            composeRule.runOnIdle { view.getMapAsync { sdk = it } }
            composeRule.waitUntil(10_000) { sdk != null }
            harness.googleMap = sdk
            val source = harness.points.first().coordinate
            val project: () -> ScreenPoint = { requireNotNull(sdk).projection.toScreenLocation(LatLng(source.latitude, source.longitude))
                .let { ScreenPoint(it.x.toFloat(), it.y.toFloat()) } }
            project
        }
        is AmapNativeView -> {
            val source = harness.points.first().coordinate
            val display = composeRule.runOnIdle { OfficialAmapCoordinateConverter(application).convert(source).toLatLng() }
            val project: () -> ScreenPoint = { view.map.projection.toScreenLocation(display).let { ScreenPoint(it.x.toFloat(), it.y.toFloat()) } }
            project
        }
        else -> error("Expected an actual provider SDK view")
    }

    private fun assertStable(harness: Harness, view: View, project: () -> ScreenPoint, anchor: ScreenPoint, source: GeoPoint) {
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertSame(view, nativeViews(harness.root).single())
            assertTrue("Metadata/theme/selection must retain the native geographic anchor", near(anchor, project()))
            assertTrue("Source WGS84 fixture must remain unchanged", source == harness.points.first().coordinate)
            assertEquals(listOf(1L), harness.applied)
            assertEquals(setOf(POINT_ID), harness.visible)
        }
    }

    private fun clickAnchor(harness: Harness, anchor: ScreenPoint) {
        composeRule.runOnIdle { harness.clicks = emptyList() }
        // A real native touch must reach the SDK marker listener; no semantic click is substituted.
        composeRule.onNodeWithTag(MAP_TAG).performTouchInput { click(Offset(anchor.x, anchor.y)) }
        composeRule.waitUntil(5_000) { harness.clicks.isNotEmpty() }
        composeRule.runOnIdle { assertEquals(listOf(POINT_ID), harness.clicks) }
    }

    private fun awaitColor(harness: Harness, view: View, point: ScreenPoint, color: Int, stage: String, matches: Boolean = true) {
        val location = IntArray(2)
        var latest: Bitmap? = null
        var attempts = 0
        // UiAutomation includes SurfaceView pixels, unlike a Compose-only capture.
        try {
            composeRule.waitUntil(15_000) {
                composeRule.runOnIdle { view.getLocationOnScreen(location) }
                val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                    ?: return@waitUntil false
                latest?.recycle()
                latest = screenshot
                attempts++
                val x = location[0] + point.x.roundToInt()
                val y = location[1] + point.y.roundToInt()
                val present = (-2..2).any { dx -> (-2..2).any { dy ->
                    x + dx in 0 until screenshot.width && y + dy in 0 until screenshot.height &&
                        sameColor(screenshot.getPixel(x + dx, y + dy), color)
                } }
                present == matches
            }
        } catch (failure: Throwable) {
            latest?.let { screenshot ->
                runCatching { saveRenderFailure(harness, view, screenshot, location, point, color, stage, attempts) }
                    .onFailure { failure.addSuppressed(AssertionError("Render diagnostics failed: ${it.javaClass.simpleName}")) }
            }
            throw failure
        } finally {
            latest?.recycle()
        }
    }

    private fun saveRenderFailure(harness: Harness, view: View, screenshot: Bitmap, location: IntArray,
        point: ScreenPoint, color: Int, stage: String, attempts: Int) {
        val bounds = Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
        if (!bounds.intersect(0, 0, screenshot.width, screenshot.height)) return
        val crop = Bitmap.createBitmap(screenshot, bounds.left, bounds.top, bounds.width(), bounds.height())
        try {
            val directory = File(requireNotNull(application.getExternalFilesDir(null)), "frontend-review").apply { mkdirs() }
            val name = "native-${harness.provider.name.lowercase()}-$stage-failure"
            // Only this fixture-owned map viewport is retained; no device chrome or user UI.
            File(directory, "$name.png").outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val expectedX = location[0] + point.x.roundToInt() - bounds.left
            val expectedY = location[1] + point.y.roundToInt() - bounds.top
            var matchingPixels = 0
            var nearestDistanceSquared = Long.MAX_VALUE
            var nearestDx = 0
            var nearestDy = 0
            for (y in 0 until crop.height) for (x in 0 until crop.width) {
                if (!sameColor(crop.getPixel(x, y), color)) continue
                matchingPixels++
                val dx = x - expectedX
                val dy = y - expectedY
                val distanceSquared = dx.toLong() * dx + dy.toLong() * dy
                if (distanceSquared < nearestDistanceSquared) {
                    nearestDistanceSquared = distanceSquared; nearestDx = dx; nearestDy = dy
                }
            }
            val viewStats = composeRule.runOnIdle {
                "viewWidth=${view.width}\nviewHeight=${view.height}\n" +
                    "viewDensity=${view.resources.displayMetrics.density}\nhardwareAccelerated=${view.isHardwareAccelerated}\n" +
                    "attached=${view.isAttachedToWindow}\nshown=${view.isShown}\n" +
                    "visibleRevision=${harness.visibleRevision}\npresentationRevision=${harness.presentationRevision}\n"
            }
            File(directory, "$name.txt").writeText(viewStats +
                "screenWidth=${screenshot.width}\nscreenHeight=${screenshot.height}\nattempts=$attempts\n" +
                "imageRequests=${imageRequests.get()}\nmatchingPixels=$matchingPixels\n" +
                "nearestDx=$nearestDx\nnearestDy=$nearestDy\n")
        } finally { if (crop !== screenshot) crop.recycle() }
    }

    private class Harness(initialProvider: MapProvider) {
        lateinit var root: View
        var provider by mutableStateOf(initialProvider)
        var points by mutableStateOf(fixturePoints(initialProvider))
        var selected by mutableStateOf(emptySet<String>())
        var focused by mutableStateOf<String?>(null)
        var imagesEnabled by mutableStateOf(true)
        var dark by mutableStateOf(false)
        var showMap by mutableStateOf(true)
        var presentationRevision by mutableStateOf(0)
        var googleMap: GoogleMap? = null
        @Volatile var visible = emptySet<String>()
        @Volatile var clicks = emptyList<String>()
        @Volatile var applied = emptyList<Long>()
        @Volatile var unavailable = false
        @Volatile var visibleRevision = -1
    }

    private companion object {
        const val POINT_ID = "synthetic::native-anchor"
        const val MAP_TAG = "native-discovery-map"
        const val SYNTHETIC_IMAGE_URL = "https://image.anitabi.cn/synthetic-instrumentation-only.png"
        const val POINT_COLOR = 0xffd000d0.toInt()
        const val IMAGE_COLOR = 0xff00ccd0.toInt()
        val PADDING = DiscoveryMapPadding(left = 24, top = 48, right = 12, bottom = 120)

        fun fixturePoints(provider: MapProvider) = listOf(
            // Authored test grid, never downloaded source coordinates or a user's location.
            DiscoveryMapPoint(POINT_ID, 901L, if (provider == MapProvider.AMAP) GeoPoint(30.0, 110.0) else GeoPoint(0.0, 0.0),
                provider, "", POINT_COLOR),
            DiscoveryMapPoint("synthetic::excluded-provider", 902L, GeoPoint(0.0, 0.0),
                if (provider == MapProvider.GOOGLE) MapProvider.AMAP else MapProvider.GOOGLE, "", POINT_COLOR),
        )

        fun nativeViews(root: View): List<View> = buildList {
            if (root is NavigationView || root is AmapNativeView) add(root)
            else if (root is ViewGroup) repeat(root.childCount) { addAll(nativeViews(root.getChildAt(it))) }
        }

        fun near(first: ScreenPoint, second: ScreenPoint) = abs(first.x - second.x) <= 3f && abs(first.y - second.y) <= 3f
        fun sameColor(actual: Int, expected: Int) = abs(Color.red(actual) - Color.red(expected)) < 25 &&
            abs(Color.green(actual) - Color.green(expected)) < 25 && abs(Color.blue(actual) - Color.blue(expected)) < 25
    }
}
