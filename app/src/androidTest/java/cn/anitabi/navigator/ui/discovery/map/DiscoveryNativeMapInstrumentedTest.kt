package cn.anitabi.navigator.ui.discovery.map

import android.graphics.Bitmap
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.anitabi.navigator.TestAnitabiApplication
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.ui.map.OfficialAmapCoordinateConverter
import cn.anitabi.navigator.ui.map.AmapMapView
import cn.anitabi.navigator.ui.map.isAmapNativeMapLibraryAvailable
import cn.anitabi.navigator.ui.map.NavigationMapView
import cn.anitabi.navigator.ui.map.animateCameraRespectingMotion
import cn.anitabi.navigator.security.AppAppearance
import cn.anitabi.navigator.ui.theme.AnitabiTheme
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.annotation.DelicateCoilApi
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.request.ErrorResult
import com.amap.api.maps.MapView as AmapNativeView
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapColorScheme
import com.google.android.libraries.navigation.NavigationView
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
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
    @Volatile private var imageGate: CompletableDeferred<Unit>? = null
    @Volatile private var failImage = false
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
                    imageGate?.await()
                    if (failImage) return ErrorResult(image = null, request = chain.request, throwable = IllegalStateException("Synthetic image failure"))
                    val image = Bitmap.createBitmap(120, 90, Bitmap.Config.ARGB_8888).apply { eraseColor(IMAGE_COLOR) }
                    return SuccessResult(image = image.asImage(), request = chain.request, dataSource = DataSource.MEMORY)
                }
            })
        }.build()
        SingletonImageLoader.setUnsafe(fixtureLoader)
    }

    @After
    fun restoreImageLoaderAndPrivacy() {
        imageGate?.cancel()
        SingletonImageLoader.setUnsafe(previousLoader)
        fixtureLoader.shutdown()
        if (!privacyWasReady) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { application.container.amapPrivacyGate.revoke() }
        }
    }

    @Test
    fun googleProjectionAndBitmapAnchorSurvivePresentationChanges() = verifyProvider(MapProvider.GOOGLE)

    @Test
    fun googleNativeClusterTapZoomsThenReturnsAllOverlapMembersAtSdkMaximum() {
        val harness = Harness(MapProvider.GOOGLE)
        val source = harness.points.first()
        val overlaps = mutableListOf<List<String>>()
        harness.points = listOf(source, source.copy(id = "synthetic::overlap-second"), source.copy(id = "synthetic::overlap-third"))
        harness.imagesEnabled = false
        harness.cameraCommand = DiscoveryCameraCommand.Restore(1L, DiscoveryCameraPosition(source.coordinate, 12f))
        harness.overlapClick = { overlaps += it.toList() }
        val expected = harness.points.mapTo(hashSetOf()) { it.id }
        show(harness)
        composeRule.waitUntil(30_000) {
            check(!harness.unavailable) { "Native map unavailable for cluster interaction" }
            harness.applied == listOf(1L) && harness.visible == expected
        }
        val view = nativeView(harness)
        val project = projection(harness, view)
        val sdk = requireNotNull(harness.googleMap)
        val density = view.resources.displayMetrics.density
        val anchor = composeRule.runOnIdle { project() }
        awaitColor(harness, view, ScreenPoint(anchor.x, anchor.y + 13f * density), Color.rgb(36, 36, 38), "cluster-before-tap")
        val zoomBeforeTap = composeRule.runOnIdle {
            assertTrue("The cluster fixture must start below the SDK maximum", sdk.cameraPosition.zoom < sdk.maxZoomLevel - .1f)
            sdk.cameraPosition.zoom
        }
        val idleBeforeTap = harness.cameraIdleCount
        composeRule.onNodeWithTag(MAP_TAG).performTouchInput { click(Offset(anchor.x, anchor.y)) }
        composeRule.waitUntil(15_000) {
            check(!harness.unavailable) { "Native cluster zoom failed" }
            harness.cameraIdleCount > idleBeforeTap && harness.visibleIdleCount == harness.cameraIdleCount &&
                harness.visible == expected && composeRule.runOnIdle { sdk.cameraPosition.zoom > zoomBeforeTap }
        }
        composeRule.runOnIdle {
            assertTrue("A lower-zoom cluster tap must zoom before opening overlap members", overlaps.isEmpty())
            assertTrue("A cluster must not masquerade as a single point click", harness.clicks.isEmpty())
            harness.cameraCommand = DiscoveryCameraCommand.Restore(2L, DiscoveryCameraPosition(source.coordinate, sdk.maxZoomLevel))
            harness.presentationRevision++
        }
        composeRule.waitUntil(15_000) {
            check(!harness.unavailable) { "Native maximum-zoom fixture unavailable" }
            harness.applied == listOf(1L, 2L) && harness.visible == expected &&
                harness.visibleRevision == harness.presentationRevision && harness.visibleIdleCount == harness.cameraIdleCount &&
                composeRule.runOnIdle { sdk.cameraPosition.zoom >= sdk.maxZoomLevel - .1f }
        }
        val maxAnchor = composeRule.runOnIdle { project() }
        val maxCamera = composeRule.runOnIdle { sdk.cameraPosition }
        awaitColor(harness, view, ScreenPoint(maxAnchor.x, maxAnchor.y + 13f * density), Color.rgb(36, 36, 38), "cluster-at-maximum")
        composeRule.onNodeWithTag(MAP_TAG).performTouchInput { click(Offset(maxAnchor.x, maxAnchor.y)) }
        composeRule.waitUntil(5_000) { composeRule.runOnIdle { overlaps.isNotEmpty() } }
        composeRule.runOnIdle {
            assertEquals("One native tap must deliver every overlap member exactly once", listOf(expected.sorted()), overlaps)
            assertGoogleCameraRetained(sdk, maxCamera)
            assertEquals(expected, harness.visible)
            assertTrue("Cluster interaction must retain source coordinates", harness.points.all { it.coordinate == source.coordinate })
            assertSame(view, nativeViews(harness.root).single())
        }
    }

    @Test
    fun googleMinimalFocusKeepsVisibleTapStillAndOnlyUncoversHiddenPoint() {
        val harness = Harness(MapProvider.GOOGLE)
        harness.imagesEnabled = false
        val sourceBits = harness.points.map { it.coordinate.latitude.toRawBits() to it.coordinate.longitude.toRawBits() }
        show(harness)
        awaitPoint(harness)
        val view = nativeView(harness)
        val project = projection(harness, view)
        val sdk = requireNotNull(harness.googleMap)
        val density = view.resources.displayMetrics.density
        val idleBeforeOffset = harness.cameraIdleCount
        composeRule.runOnIdle { sdk.moveCamera(CameraUpdateFactory.scrollBy(-40f * density, 0f)) }
        composeRule.waitUntil(15_000) {
            harness.cameraIdleCount > idleBeforeOffset && harness.visibleIdleCount == harness.cameraIdleCount &&
                harness.visible == setOf(POINT_ID)
        }
        val visibleAnchor = composeRule.runOnIdle { project() }
        val visibleCamera = composeRule.runOnIdle { sdk.cameraPosition }
        composeRule.runOnIdle {
            val content = harness.padding.content(view.width, view.height)
            assertTrue("The off-centre fixture must already be inside the safe visible area", content.inset(28f * density).contains(visibleAnchor))
            assertFalse("An already-centred fixture would not detect an unnecessary refocus", near(content.center, visibleAnchor))
            harness.pointClick = { id ->
                harness.cameraCommand = DiscoveryCameraCommand.Focus(2L, id, minimallyPan = true)
                harness.presentationRevision++
            }
        }
        awaitColor(harness, view, visibleAnchor, POINT_COLOR, "minimal-visible-before-tap")
        clickAnchor(harness, visibleAnchor)
        awaitPresentation(harness)
        composeRule.runOnIdle {
            assertEquals(listOf(1L, 2L), harness.applied)
            assertGoogleCameraRetained(sdk, visibleCamera)
            assertTrue("Tapping an unobscured point must not recenter it", near(visibleAnchor, project()))
            harness.pointClick = null
            harness.padding = PADDING.copy(bottom = (view.height * .4f).roundToInt())
            harness.presentationRevision++
        }
        composeRule.waitUntil(10_000) { harness.visibleRevision == harness.presentationRevision }
        val idleBeforeCover = harness.cameraIdleCount
        composeRule.runOnIdle {
            val content = harness.padding.content(view.width, view.height)
            val coveredY = content.bottom + 20f * density
            sdk.moveCamera(CameraUpdateFactory.scrollBy(0f, project().y - coveredY))
        }
        composeRule.waitUntil(15_000) {
            check(!harness.unavailable) { "Native covered-point fixture unavailable" }
            harness.cameraIdleCount > idleBeforeCover && harness.visibleIdleCount == harness.cameraIdleCount && harness.visible.isEmpty()
        }
        val coveredAnchor = composeRule.runOnIdle { project() }
        val coveredZoom = composeRule.runOnIdle { sdk.cameraPosition.zoom }
        val expectedAnchor = composeRule.runOnIdle {
            val content = harness.padding.content(view.width, view.height)
            assertFalse("The fixture point must actually be covered by measured padding", content.contains(coveredAnchor))
            val pan = focusPan(coveredAnchor, content.inset(28f * density), minimallyPan = true)
            assertTrue("Only the covered vertical axis should need movement", abs(pan.x) <= 3f && pan.y > 0f)
            harness.cameraCommand = DiscoveryCameraCommand.Focus(3L, POINT_ID, minimallyPan = true)
            harness.presentationRevision++
            ScreenPoint(coveredAnchor.x - pan.x, coveredAnchor.y - pan.y)
        }
        awaitPresentation(harness)
        val uncoveredAnchor = composeRule.runOnIdle {
            val actual = project()
            assertEquals(listOf(1L, 2L, 3L), harness.applied)
            assertTrue("Native minimum pan must match the safe-edge pixel reference", near(expectedAnchor, actual))
            assertFalse("Uncovering a point must not reframe it at the content centre", near(actual, harness.padding.content(view.width, view.height).center))
            assertEquals("Minimum pan must retain zoom", coveredZoom, sdk.cameraPosition.zoom, .001f)
            assertTrue("Minimum pan must preserve every source Double bit",
                sourceBits == harness.points.map { it.coordinate.latitude.toRawBits() to it.coordinate.longitude.toRawBits() })
            assertSame(view, nativeViews(harness.root).single())
            actual
        }
        awaitColor(harness, view, uncoveredAnchor, POINT_COLOR, "minimal-uncovered-dot")
        clickAnchor(harness, uncoveredAnchor)
    }

    @Test
    fun googleFitAllIncludesDateLineMembersAndDistantOutlierInsidePadding() {
        // Authored geometry only: three neighbours straddle the date line and one point is far away.
        val points = listOf(
            DiscoveryMapPoint("synthetic::date-east", 901L, GeoPoint(3.125000000000004, 179.25000000000003),
                MapProvider.GOOGLE, "", POINT_COLOR),
            DiscoveryMapPoint("synthetic::date-west", 901L, GeoPoint(4.250000000000008, -179.12500000000003),
                MapProvider.GOOGLE, "", POINT_COLOR),
            DiscoveryMapPoint("synthetic::date-near", 901L, GeoPoint(5.375000000000012, 178.75000000000006),
                MapProvider.GOOGLE, "", POINT_COLOR),
            DiscoveryMapPoint(POINT_ID, 901L, GeoPoint(-28.625000000000004, 145.37500000000003),
                MapProvider.GOOGLE, "", POINT_COLOR),
        )
        val sourceBits = points.map { it.coordinate.latitude.toRawBits() to it.coordinate.longitude.toRawBits() }
        val dateLineIds = points.dropLast(1).mapTo(hashSetOf()) { it.id }
        val allIds = points.mapTo(hashSetOf()) { it.id }
        val harness = Harness(MapProvider.GOOGLE).apply {
            this.points = points
            imagesEnabled = false
            cameraCommand = DiscoveryCameraCommand.FitAll(1L, dateLineIds)
        }
        show(harness)
        composeRule.waitUntil(30_000) {
            check(!harness.unavailable) { "Native map unavailable during date-line fit" }
            harness.applied == listOf(1L) && harness.visible == dateLineIds
        }
        val view = nativeView(harness) as NavigationView
        var readyMap: GoogleMap? = null
        composeRule.runOnIdle { view.getMapAsync { readyMap = it } }
        composeRule.waitUntil(10_000) { readyMap != null }
        val sdk = requireNotNull(readyMap)
        val outlier = points.last().coordinate.let { LatLng(it.latitude, it.longitude) }
        val nearZoom = composeRule.runOnIdle {
            val bounds = sdk.projection.visibleRegion.latLngBounds
            assertTrue("The tight date-line viewport must cross the antimeridian",
                bounds.southwest.longitude > bounds.northeast.longitude)
            assertFalse("The distant outlier must start outside the tight viewport", bounds.contains(outlier))
            sdk.cameraPosition.zoom
        }
        composeRule.runOnIdle {
            harness.padding = DiscoveryMapPadding(left = (view.width * .18f).roundToInt(),
                top = (view.height * .12f).roundToInt(), right = (view.width * .08f).roundToInt(),
                bottom = (view.height * .30f).roundToInt())
            harness.cameraCommand = DiscoveryCameraCommand.FitAll(2L)
        }
        composeRule.waitUntil(30_000) {
            check(!harness.unavailable) { "Native map unavailable during full fit" }
            harness.applied == listOf(1L, 2L) && harness.visible == allIds &&
                harness.visibleIdleCount == harness.cameraIdleCount
        }
        val outlierAnchor = composeRule.runOnIdle {
            val content = harness.padding.content(view.width, view.height)
            val projection = sdk.projection
            val bounds = projection.visibleRegion.latLngBounds
            assertTrue("FitAll must zoom out to include the distant outlier", sdk.cameraPosition.zoom < nearZoom)
            assertTrue("Full framing must retain the date-line crossing", bounds.southwest.longitude > bounds.northeast.longitude)
            points.forEach { point ->
                val position = LatLng(point.coordinate.latitude, point.coordinate.longitude)
                val screen = projection.toScreenLocation(position)
                assertTrue("Every original member must be inside the SDK visible bounds", bounds.contains(position))
                assertTrue("Every original member must fit inside measured panel padding",
                    content.contains(ScreenPoint(screen.x.toFloat(), screen.y.toFloat())))
            }
            assertTrue("FitAll must preserve every source Double bit",
                sourceBits == harness.points.map { it.coordinate.latitude.toRawBits() to it.coordinate.longitude.toRawBits() })
            assertSame(view, nativeViews(harness.root).single())
            projection.toScreenLocation(outlier).let { ScreenPoint(it.x.toFloat(), it.y.toFloat()) }
        }
        awaitColor(harness, view, outlierAnchor, POINT_COLOR, "fit-all-outlier-dot")
        clickAnchor(harness, outlierAnchor)
    }

    @Test
    fun googleFailedImageRetainsSelectableMarkerWithoutRepeatedRequests() {
        failImage = true
        val harness = Harness(MapProvider.GOOGLE)
        harness.points = harness.points.map { it.copy(imageUrl = SYNTHETIC_IMAGE_URL) }
        show(harness)
        awaitPoint(harness)
        composeRule.waitUntil(10_000) { imageRequests.get() == 1 }
        val view = nativeView(harness)
        val project = projection(harness, view)
        val anchor = composeRule.runOnIdle { project() }
        awaitColor(harness, view, anchor, POINT_COLOR, "failed-image-dot")
        clickAnchor(harness, anchor)
        composeRule.runOnIdle { harness.selected = setOf(POINT_ID); harness.dark = true; harness.presentationRevision++ }
        awaitPresentation(harness)
        awaitTheme(harness, view, true)
        awaitColor(harness, view, anchor, POINT_COLOR, "failed-image-selected-dot")
        composeRule.runOnIdle {
            assertEquals("A failed image must retain its indexed member", setOf(POINT_ID), harness.visible)
            assertEquals("Presentation changes must respect the failure cache", 1, imageRequests.get())
            assertSame(view, nativeViews(harness.root).single())
        }
        clickAnchor(harness, anchor)
    }

    @Test
    fun googleBrowsingThemeChangesInPlaceAndDisabledMotionMovesImmediately() {
        var dark by mutableStateOf(false)
        var sdk: GoogleMap? = null
        var root: View? = null
        var unavailable = false
        composeRule.setContent {
            val currentRoot = LocalView.current.rootView
            SideEffect { root = currentRoot }
            AnitabiTheme(if (dark) AppAppearance.DARK else AppAppearance.LIGHT) {
                NavigationMapView(
                    onMapReady = { sdk = it },
                    onUnavailable = { unavailable = true }, modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeRule.waitUntil(30_000) {
            check(!unavailable) { "Native browsing map unavailable" }
            sdk != null
        }
        val view = composeRule.runOnIdle { nativeViews(requireNotNull(root)).single() as NavigationView }
        composeRule.runOnIdle { dark = true }
        composeRule.waitUntil(10_000) {
            composeRule.runOnIdle { sdk!!.mapColorScheme == MapColorScheme.DARK }
        }
        composeRule.runOnIdle { dark = false }
        composeRule.waitUntil(10_000) {
            composeRule.runOnIdle { sdk!!.mapColorScheme == MapColorScheme.LIGHT }
        }
        composeRule.runOnIdle {
            assertSame("Appearance changes must retain the active browsing view", view, nativeViews(requireNotNull(root)).single())
            assertFalse("Run the reduced-motion case with emulator animations disabled", ValueAnimator.areAnimatorsEnabled())
            val map = requireNotNull(sdk)
            val zoom = (map.cameraPosition.zoom + 1f).coerceAtMost(map.maxZoomLevel)
            map.animateCameraRespectingMotion(CameraUpdateFactory.zoomTo(zoom))
            assertEquals("Disabled motion must update synchronously", zoom, map.cameraPosition.zoom, .001f)
        }
    }

    @Test
    fun googleGestureSurvivesLateImageAndMeasuredPanelPadding() {
        val harness = Harness(MapProvider.GOOGLE)
        show(harness)
        awaitPoint(harness)
        val view = nativeView(harness)
        val project = projection(harness, view)
        val sdk = requireNotNull(harness.googleMap)
        val sourceCoordinates = harness.points.associate { it.id to it.coordinate }
        val initialAnchor = composeRule.runOnIdle { project() }
        val initialCamera = composeRule.runOnIdle { sdk.cameraPosition }
        val density = view.resources.displayMetrics.density
        awaitColor(harness, view, initialAnchor, POINT_COLOR, "gesture-initial-dot")
        val idleBeforeDrag = harness.cameraIdleCount
        val movesBeforeDrag = harness.manualMoves
        composeRule.onNodeWithTag(MAP_TAG).performTouchInput {
            val start = Offset(width * .25f, height * .7f)
            val end = Offset(width * .39f, start.y)
            down(start)
            repeat(12) { step -> moveTo(start + (end - start) * ((step + 1) / 12f), 40L) }
            // End with a stationary event, so the regression observes a completed drag instead of a fling.
            moveTo(end, 300L)
            up()
        }
        composeRule.waitUntil(15_000) {
            check(!harness.unavailable) { "Native map unavailable after drag" }
            harness.manualMoves > movesBeforeDrag && harness.cameraIdleCount > idleBeforeDrag &&
                harness.cameraIdleCount > harness.idleAtLastManualMove &&
                harness.visibleIdleCount == harness.cameraIdleCount && harness.visible == setOf(POINT_ID)
        }
        val draggedCamera = composeRule.runOnIdle { sdk.cameraPosition }
        val draggedAnchor = composeRule.runOnIdle { project() }
        val draggedMoves = harness.manualMoves
        composeRule.runOnIdle {
            assertTrue("The injected native drag must visibly change map framing",
                abs(draggedAnchor.x - initialAnchor.x) > 24f * density)
            assertEquals("A one-finger drag must retain zoom", initialCamera.zoom, draggedCamera.zoom, .001f)
        }
        awaitColor(harness, view, draggedAnchor, POINT_COLOR, "gesture-dragged-dot")

        val gate = CompletableDeferred<Unit>()
        imageGate = gate
        val requestsBeforeMetadata = imageRequests.get()
        composeRule.runOnIdle {
            harness.points = harness.points.map { it.copy(title = "Synthetic delayed metadata", imageUrl = SYNTHETIC_IMAGE_URL) }
            harness.presentationRevision++
        }
        awaitPresentation(harness)
        composeRule.waitUntil(10_000) { imageRequests.get() > requestsBeforeMetadata }
        assertFalse("The fixture image must still be pending while the panel changes", gate.isCompleted)
        composeRule.runOnIdle {
            assertGoogleCameraRetained(sdk, draggedCamera)
            assertTrue("Metadata must retain the dragged geographic anchor", near(draggedAnchor, project()))
            harness.padding = PADDING.copy(bottom = (view.height * .3f).roundToInt())
            harness.presentationRevision++
        }
        awaitPresentation(harness)
        val paddedAnchor = composeRule.runOnIdle { project() }
        val paddedCamera = composeRule.runOnIdle { sdk.cameraPosition }
        composeRule.runOnIdle {
            val originalCentre = PADDING.content(view.width, view.height).center
            val paddedCentre = harness.padding.content(view.width, view.height).center
            assertEquals("Measured panel padding must not refit or reset zoom", draggedCamera.zoom, paddedCamera.zoom, .001f)
            assertEquals(draggedCamera.bearing, paddedCamera.bearing, .001f)
            assertEquals(draggedCamera.tilt, paddedCamera.tilt, .001f)
            // Only bottom padding changes. Permit its vertical translation without losing the horizontal drag.
            assertEquals("Panel padding must preserve the user's horizontal framing",
                draggedAnchor.x - originalCentre.x, paddedAnchor.x - paddedCentre.x, 3f)
            assertTrue("Panel changes must not return the marker to its initial framing",
                abs(paddedAnchor.x - paddedCentre.x) > 24f * density)
        }
        gate.complete(Unit)
        awaitColor(harness, view, ScreenPoint(paddedAnchor.x, paddedAnchor.y - 35f * density), IMAGE_COLOR, "gesture-late-image")
        awaitColor(harness, view, paddedAnchor, POINT_COLOR, "gesture-padded-dot")
        composeRule.runOnIdle {
            assertGoogleCameraRetained(sdk, paddedCamera)
            assertTrue("The completed image must retain the padded geographic anchor", near(paddedAnchor, project()))
            assertSame(view, nativeViews(harness.root).single())
            assertEquals("Automatic focus must not be applied again", listOf(1L), harness.applied)
            assertEquals("Presentation updates must not synthesize gestures", draggedMoves, harness.manualMoves)
            assertTrue("Source coordinates must survive gesture and presentation changes unchanged",
                sourceCoordinates == harness.points.associate { it.id to it.coordinate })
        }
        clickAnchor(harness, paddedAnchor)
    }

    @Test
    fun amapProjectionAndBitmapAnchorSurvivePresentationChanges() = verifyProvider(MapProvider.AMAP)

    @Test
    fun unsupportedAmapAbiReportsUnavailableWithoutConstructingSdkView() {
        assertFalse("Run this fixture only on an ABI without the AMap native library",
            isAmapNativeMapLibraryAvailable(application))
        prepareAmap()
        val root = AtomicReference<View?>()
        val unavailable = AtomicBoolean(false)
        val ready = AtomicBoolean(false)
        composeRule.setContent {
            val currentRoot = LocalView.current.rootView
            SideEffect { root.set(currentRoot) }
            AnitabiTheme {
                AmapMapView(privacyReady = true, onMapReady = { ready.set(true) },
                    onUnavailable = { unavailable.set(true) }, modifier = Modifier.fillMaxSize())
            }
        }
        composeRule.waitUntil(10_000) { unavailable.get() }
        composeRule.runOnIdle {
            assertFalse("An unsupported native library must never deliver a map", ready.get())
            assertTrue("No native SDK View may be attached on the unsupported ABI",
                nativeViews(requireNotNull(root.get())).isEmpty())
        }
    }

    @Test
    fun amapOverlapMarkerExposesItsCountToNativeAccessibility() {
        prepareAmap()
        val harness = Harness(MapProvider.AMAP)
        harness.points = listOf(harness.points.first(), harness.points.first().copy(id = "synthetic::overlap"))
        show(harness)
        withAmapAwaitDiagnostics(harness, "await-overlap") {
            composeRule.waitUntil(30_000) {
                check(!harness.unavailable) { "Native map unavailable; this is a failed live SDK check" }
                harness.applied == listOf(1L) && harness.visible.size == 2
            }
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

        // The SDK must receive real touches across the label's 48dp-high target, not just its dot.
        clickAnchor(harness, ScreenPoint(anchor.x + 20f * density, anchor.y - 22f * density))
        composeRule.runOnIdle {
            harness.selected = emptySet(); harness.focused = null; harness.presentationRevision++
        }
        awaitPresentation(harness)
        val enlargedLayout = discoveryMarkerLayout(
            DiscoveryCluster("fixture-label", listOf(POINT_ID), POINT_ID, anchor, false, DiscoveryMarkerDecoration.LABEL),
            Density(density, 2f), imageAvailable = false,
        )
        val enlargedBorder = ScreenPoint(anchor.x + enlargedLayout.width / 2f - 2f * density, anchor.y - 22f * density)
        awaitColor(harness, view, enlargedBorder, POINT_COLOR, "before-enlarged-label", matches = false)
        composeRule.runOnIdle { harness.fontScale = 2f; harness.presentationRevision++ }
        awaitPresentation(harness)
        awaitColor(harness, view, enlargedBorder, POINT_COLOR, "enlarged-label-border")
        awaitColor(harness, view, anchor, POINT_COLOR, "enlarged-label-dot")
        assertStable(harness, view, projection, anchor, originalSource)
        clickAnchor(harness, ScreenPoint(anchor.x - 20f * density, anchor.y - 22f * density))
        composeRule.runOnIdle {
            assertEquals("Font changes must reuse the already loaded image", 1, imageRequests.get())
        }
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
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, harness.fontScale)) {
              AnitabiTheme(if (harness.dark) AppAppearance.DARK else AppAppearance.LIGHT) {
                if (harness.showMap) DiscoveryMap(
                    dataVersion = "synthetic-native-${harness.provider}", points = harness.points,
                    provider = harness.provider, privacyReady = true,
                    selectedIds = harness.selected, focusedPointId = harness.focused,
                    imagesEnabled = harness.imagesEnabled, darkTheme = harness.dark,
                    padding = harness.padding, cameraCommand = harness.cameraCommand,
                    onVisibleIdsChanged = {
                        harness.visible = it; harness.visibleRevision = presentationRevision
                        harness.visibleIdleCount = harness.cameraIdleCount
                    },
                    onPointClick = { harness.clicks += it; harness.pointClick?.invoke(it) },
                    onOverlapClick = { harness.overlapClick(it) }, onCameraChanged = { harness.cameraIdleCount++ },
                    onManualMove = { harness.idleAtLastManualMove = harness.cameraIdleCount; harness.manualMoves++ },
                    onUnavailable = { harness.unavailable = true },
                    onCameraCommandApplied = { sequence, _ -> harness.applied += sequence },
                    modifier = Modifier.fillMaxSize().testTag(MAP_TAG),
                )
              }
            }
        }
    }

    private fun awaitPoint(harness: Harness) {
        withAmapAwaitDiagnostics(harness, "await-point") {
            composeRule.waitUntil(30_000) {
                check(!harness.unavailable) { "Native map unavailable; this is a failed live SDK check" }
                harness.applied == listOf(1L) && harness.visible == setOf(POINT_ID)
            }
        }
        composeRule.runOnIdle { assertEquals(1, nativeViews(harness.root).size) }
    }

    private fun withAmapAwaitDiagnostics(harness: Harness, stage: String, await: () -> Unit) {
        try {
            await()
        } catch (failure: Throwable) {
            if (harness.provider == MapProvider.AMAP) {
                runCatching { saveAmapAwaitFailure(harness, stage) }
                    .onFailure { failure.addSuppressed(AssertionError("AMap wait diagnostics failed: ${it.javaClass.simpleName}")) }
            }
            throw failure
        }
    }

    private fun saveAmapAwaitFailure(harness: Harness, stage: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val report = JSONObject().put("api", Build.VERSION.SDK_INT)
        instrumentation.runOnMainSync {
            val root = runCatching { harness.root }.getOrNull()
            val views = root?.let(::nativeViews)
            val view = views?.filterIsInstance<AmapNativeView>()?.singleOrNull()
            val sdk = runCatching { view?.map }.getOrNull()
            // AMap documents a null projection before initialization. Only scalar results leave Main.
            val projection = runCatching { sdk?.projection }.getOrNull()
            val bounds = runCatching { projection?.visibleRegion?.latLngBounds }.getOrNull()
            val source = harness.points.firstOrNull { it.id == POINT_ID && it.provider == MapProvider.AMAP }
            val display = if (sdk != null && application.container.amapPrivacyGate.isReady) {
                runCatching { source?.let { OfficialAmapCoordinateConverter(application).convert(it.coordinate).toLatLng() } }.getOrNull()
            } else null
            val screen = runCatching { display?.let { projection?.toScreenLocation(it) } }
                .getOrNull()?.let { ScreenPoint(it.x.toFloat(), it.y.toFloat()) }
            val content = view?.takeIf { it.width > 0 && it.height > 0 }?.let { harness.padding.content(it.width, it.height) }
            report.put("appliedCommandCount", harness.applied.size)
                .put("visibleMemberCount", harness.visible.size)
                .put("cameraIdleCount", harness.cameraIdleCount)
                .put("visibleIdleCount", harness.visibleIdleCount)
                .put("visibleRevision", harness.visibleRevision)
                .put("presentationRevision", harness.presentationRevision)
                .put("unavailable", harness.unavailable)
                .put("rootAvailable", root != null)
                .put("nativeViewCount", views?.size ?: JSONObject.NULL)
                .put("nativeMapAvailable", sdk != null)
                .put("nativeProjectionAvailable", projection != null)
                .put("nativeBoundsAvailable", bounds != null)
                .put("nativeScreenMarkerCount", runCatching { sdk?.mapScreenMarkers?.size }.getOrNull() ?: JSONObject.NULL)
                .put("convertedFixtureAvailable", display != null)
                .put("fixtureInNativeBounds", runCatching { display?.let { bounds?.contains(it) } }.getOrNull() ?: JSONObject.NULL)
                .put("fixtureProjected", screen != null)
                .put("fixtureInViewport", if (screen != null && view != null) {
                    screen.x >= 0f && screen.x < view.width && screen.y >= 0f && screen.y < view.height
                } else JSONObject.NULL)
                .put("fixtureInContent", if (screen != null && content != null) content.contains(screen) else JSONObject.NULL)
                .put("fixtureNearContentCenter", if (screen != null && content != null) near(screen, content.center) else JSONObject.NULL)
        }
        val directory = File(requireNotNull(application.getExternalFilesDir(null)), "frontend-review").apply { mkdirs() }
        File(directory, "native-amap-$stage-failure.json").writeText(report.toString())
        instrumentation.sendStatus(0, Bundle().apply { putString("amapAwaitDiagnostic", report.toString()) })
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

    /** Called on Main; compare target displacement in SDK pixels without logging geographic values. */
    private fun assertGoogleCameraRetained(sdk: GoogleMap, expected: CameraPosition) {
        val actual = sdk.cameraPosition
        val before = sdk.projection.toScreenLocation(expected.target)
        val after = sdk.projection.toScreenLocation(actual.target)
        assertTrue("Metadata/image completion must retain the camera target",
            abs(before.x - after.x) <= 3 && abs(before.y - after.y) <= 3)
        assertEquals("Metadata/image completion must retain zoom", expected.zoom, actual.zoom, .001f)
        assertEquals(expected.bearing, actual.bearing, .001f)
        assertEquals(expected.tilt, actual.tilt, .001f)
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
        var fontScale by mutableStateOf(1f)
        var showMap by mutableStateOf(true)
        var padding by mutableStateOf(PADDING)
        var cameraCommand by mutableStateOf<DiscoveryCameraCommand>(DiscoveryCameraCommand.Focus(1L, POINT_ID))
        var presentationRevision by mutableStateOf(0)
        var googleMap: GoogleMap? = null
        var pointClick: ((String) -> Unit)? = null
        var overlapClick: (List<String>) -> Unit = { error("Unexpected synthetic overlap") }
        @Volatile var visible = emptySet<String>()
        @Volatile var clicks = emptyList<String>()
        @Volatile var applied = emptyList<Long>()
        @Volatile var unavailable = false
        @Volatile var visibleRevision = -1
        @Volatile var manualMoves = 0
        @Volatile var idleAtLastManualMove = -1
        @Volatile var cameraIdleCount = 0
        @Volatile var visibleIdleCount = -1
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
