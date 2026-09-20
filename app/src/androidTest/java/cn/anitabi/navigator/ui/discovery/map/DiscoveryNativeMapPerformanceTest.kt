package cn.anitabi.navigator.ui.discovery.map

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.ui.theme.AnitabiTheme
import com.google.android.gms.maps.GoogleMap
import com.google.android.libraries.navigation.NavigationView
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Real native projection and nine overlap clusters; this is a rebuild workload, not a tile/FPS benchmark. */
class DiscoveryNativeMapPerformanceTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun googleDenseMembershipAndNativePixelsReportWindowMetrics() {
        val harness = Harness()
        composeRule.setContent {
            val context = LocalContext.current
            val root = LocalView.current.rootView
            val version = harness.version
            SideEffect { harness.root = root; harness.window = activity(context).window }
            AnitabiTheme {
                DiscoveryMap(
                    dataVersion = "synthetic-dense-$version", points = harness.points,
                    provider = MapProvider.GOOGLE, privacyReady = true,
                    selectedIds = emptySet(), focusedPointId = null, imagesEnabled = false, darkTheme = false,
                    padding = PADDING,
                    cameraCommand = DiscoveryCameraCommand.Restore(1L, DiscoveryCameraPosition(GeoPoint(0.0, 0.0), 15f)),
                    onVisibleIdsChanged = {
                        harness.visible = it; harness.visibleAtNs = System.nanoTime(); harness.visibleVersion = version
                    },
                    onPointClick = {}, onOverlapClick = {}, onCameraChanged = {}, onManualMove = {},
                    onUnavailable = { harness.unavailable = true },
                    onCameraCommandApplied = { _, _ -> harness.cameraReady = true },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeRule.waitUntil(30_000) {
            check(!harness.unavailable) { "Google native map unavailable" }
            harness.cameraReady && harness.visibleVersion == 0
        }
        val view = composeRule.runOnIdle { nativeViews(harness.root).single() }
        var sdk: GoogleMap? = null
        composeRule.runOnIdle { view.getMapAsync { sdk = it } }
        composeRule.waitUntil(10_000) { sdk != null }
        val density = view.resources.displayMetrics.density
        // This screen-authored grid is converted only in memory; no source or device locations are used.
        val groups = composeRule.runOnIdle {
            val content = PADDING.content(view.width, view.height)
            val projection = requireNotNull(sdk).projection
            (0..2).flatMap { row -> (0..2).map { column ->
                val screen = Point((content.left + (content.right - content.left) * (.2f + .3f * column)).roundToInt(),
                    (content.top + (content.bottom - content.top) * (.2f + .3f * row)).roundToInt())
                val source = requireNotNull(projection.fromScreenLocation(screen)) { "Authored grid must intersect the map plane" }
                GeoPoint(source.latitude, source.longitude) to projection.toScreenLocation(source)
            } }
        }
        val anchors = groups.map { it.second }
        assertTrue("Fixture clusters must have independent native pixel samples", anchors.indices.all { a ->
            (a + 1 until anchors.size).all { b ->
                hypot((anchors[a].x - anchors[b].x).toDouble(), (anchors[a].y - anchors[b].y).toDouble()) > 64 * density
            }
        })
        val frameThread = HandlerThread("native-map-frame-metrics").apply { start() }
        val frames = WindowFrames()
        val directory = File(requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)),
            "frontend-review").apply { check(isDirectory || mkdirs()) }
        val report = JSONObject().put("api", Build.VERSION.SDK_INT)
            .put("workload", "full membership rebuild into nine authored overlap groups")
            .put("timeoutScope", "test watchdog only; no product performance threshold is asserted")
            .put("timingScope", "data publication through index/projection/clustering; pixel timing includes screenshot polling")
            .put("memoryScope", "sampled whole-process Java/native heaps including instrumentation and fixture data; excludes GPU memory")
            .put("frameScope", "application Window reports; not Navigation SDK surface FPS or base-tile authentication")
        val results = JSONArray()
        report.put("results", results)
        composeRule.runOnIdle { harness.window.addOnFrameMetricsAvailableListener(frames, Handler(frameThread.looper)) }
        try {
            for (size in listOf(1_000, 10_000, 100_000)) {
                composeRule.runOnIdle { harness.points = emptyList(); harness.version++ }
                composeRule.waitUntil(30_000) { harness.visibleVersion == harness.version && harness.visible.isEmpty() }
                awaitClusters(view, anchors, density, present = false, timeoutMs = 15_000).recycle()
                val points = List(size) { index ->
                    DiscoveryMapPoint("synthetic::dense-$size-$index", 901L, groups[index % groups.size].first,
                        MapProvider.GOOGLE, "", Color.MAGENTA)
                }
                val expectedIds = points.mapTo(HashSet(size)) { it.id }
                val heap = HeapSamples()
                val result = JSONObject().put("pointCount", size).put("expectedClusters", anchors.size)
                    .put("timeoutBudgetMs", LOAD_TIMEOUT_MS)
                var startedNs = System.nanoTime()
                var visibleMs = -1.0
                var pixelsMs = -1.0
                var complete = false
                var screenshot: Bitmap? = null
                try {
                    composeRule.runOnIdle {
                        startedNs = System.nanoTime()
                        frames.begin(startedNs)
                        harness.points = points; harness.version++
                    }
                    composeRule.waitUntil(remainingMs(startedNs)) {
                        heap.sample()
                        check(!harness.unavailable) { "Google native map unavailable during dense rebuild" }
                        harness.visibleVersion == harness.version && harness.visible == expectedIds
                    }
                    visibleMs = (harness.visibleAtNs - startedNs) / 1_000_000.0
                    screenshot = awaitClusters(view, anchors, density, true, remainingMs(startedNs), heap::sample)
                    pixelsMs = (System.nanoTime() - startedNs) / 1_000_000.0
                    assertTrue("Dense native fixture exceeded its test watchdog", pixelsMs <= LOAD_TIMEOUT_MS)
                    complete = true
                } finally {
                    heap.sample()
                    result.put("completeMembershipAndPixels", complete)
                        .put("visibleMemberCount", harness.visible.size)
                        .put("dataPublishToVisibleMs", visibleMs).put("dataPublishToPixelsMs", pixelsMs)
                        .put("elapsedMs", (System.nanoTime() - startedNs) / 1_000_000.0)
                        .put("heap", heap.summary()).put("windowFrames", frames.finish())
                    results.put(result)
                    File(directory, "native-google-dense-metrics-api${Build.VERSION.SDK_INT}.json").writeText(report.toString(2))
                    screenshot?.let {
                        try { saveMapCrop(it, view, File(directory, "native-google-dense-$size-api${Build.VERSION.SDK_INT}.png")) }
                        finally { it.recycle() }
                    }
                }
            }
        } finally {
            composeRule.runOnIdle { harness.window.removeOnFrameMetricsAvailableListener(frames) }
            frameThread.quitSafely()
        }
    }

    private fun awaitClusters(view: View, anchors: List<Point>, density: Float, present: Boolean, timeoutMs: Long,
        sample: () -> Unit = {}): Bitmap {
        var latest: Bitmap? = null
        val location = IntArray(2)
        try {
            composeRule.waitUntil(timeoutMs) {
                sample()
                composeRule.runOnIdle { view.getLocationOnScreen(location) }
                val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                    ?: return@waitUntil false
                latest?.recycle()
                latest = screenshot
                val observed = anchors.map { anchor ->
                    // Sample below the count glyphs and in the outer ring to identify an actual native circle.
                    val interior = listOf(-9f to 13f, 9f to 13f).map { (dx, dy) ->
                        pixelNear(screenshot, location[0] + anchor.x + (dx * density).roundToInt(),
                            location[1] + anchor.y + (dy * density).roundToInt(), Color.rgb(36, 36, 38))
                    }
                    val ring = pixelNear(screenshot, location[0] + anchor.x,
                        location[1] + anchor.y + (20f * density).roundToInt(), Color.WHITE)
                    (interior.all { it } && ring) to interior.any { it }
                }
                // A missing ring alone cannot certify removal of an old native marker.
                if (present) observed.all { it.first } else observed.none { it.second }
            }
            return requireNotNull(latest)
        } catch (error: Throwable) {
            latest?.recycle()
            throw error
        }
    }

    private fun saveMapCrop(screenshot: Bitmap, view: View, file: File) {
        val bounds = composeRule.runOnIdle {
            val location = IntArray(2).also(view::getLocationOnScreen)
            Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
        }
        check(bounds.intersect(0, 0, screenshot.width, screenshot.height))
        val crop = Bitmap.createBitmap(screenshot, bounds.left, bounds.top, bounds.width(), bounds.height())
        try { file.outputStream().use { check(crop.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { if (crop !== screenshot) crop.recycle() }
    }

    private class Harness {
        lateinit var root: View
        lateinit var window: Window
        var points by mutableStateOf(emptyList<DiscoveryMapPoint>())
        var version by mutableStateOf(0)
        @Volatile var visible = emptySet<String>()
        @Volatile var visibleVersion = -1
        @Volatile var visibleAtNs = 0L
        @Volatile var cameraReady = false
        @Volatile var unavailable = false
    }

    private class HeapSamples {
        private fun javaBytes() = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        private val javaStart = javaBytes()
        private val nativeStart = Debug.getNativeHeapAllocatedSize()
        private var javaLast = javaStart
        private var nativeLast = nativeStart
        private var javaMax = javaStart
        private var nativeMax = nativeStart
        private var samples = 1
        fun sample() {
            javaLast = javaBytes(); nativeLast = Debug.getNativeHeapAllocatedSize()
            javaMax = maxOf(javaMax, javaLast); nativeMax = maxOf(nativeMax, nativeLast); samples++
        }
        fun summary() = JSONObject().put("samples", samples)
            .put("javaStartBytes", javaStart).put("javaEndBytes", javaLast).put("javaSampledMaxBytes", javaMax)
            .put("nativeStartBytes", nativeStart).put("nativeEndBytes", nativeLast).put("nativeSampledMaxBytes", nativeMax)
    }

    private class WindowFrames : Window.OnFrameMetricsAvailableListener {
        private var startedNs = 0L
        private var active = false
        private val totals = ArrayList<Long>()
        private var droppedReports = 0
        private var firstDrawReports = 0
        private var deadlineReports = 0
        private var missedDeadlines = 0
        @Synchronized fun begin(start: Long) {
            totals.clear(); droppedReports = 0; firstDrawReports = 0; deadlineReports = 0; missedDeadlines = 0
            startedNs = start; active = true
        }
        @Synchronized override fun onFrameMetricsAvailable(window: Window, frame: FrameMetrics, dropped: Int) {
            val intendedVsyncNs = frame.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
            if (!active || (intendedVsyncNs >= 0 && intendedVsyncNs < startedNs)) return
            droppedReports += dropped
            // Read primitives immediately: the platform reuses the FrameMetrics object after this callback.
            if (frame.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) { firstDrawReports++; return }
            val total = frame.getMetric(FrameMetrics.TOTAL_DURATION)
            if (total < 0) return
            totals.add(total)
            if (Build.VERSION.SDK_INT >= 31) {
                val deadline = frame.getMetric(FrameMetrics.DEADLINE)
                if (deadline > 0) { deadlineReports++; if (total >= deadline) missedDeadlines++ }
            }
        }
        @Synchronized fun finish(): JSONObject {
            active = false
            val sorted = totals.sorted()
            fun percentile(fraction: Double): Any = if (sorted.isEmpty()) JSONObject.NULL
                else sorted[(ceil(sorted.size * fraction).toInt() - 1).coerceAtLeast(0)] / 1_000_000.0
            return JSONObject().put("available", sorted.isNotEmpty()).put("sampleCount", sorted.size)
                .put("p50TotalMs", percentile(.5)).put("p95TotalMs", percentile(.95)).put("maxTotalMs", percentile(1.0))
                .put("firstDrawReportsExcluded", firstDrawReports).put("droppedReports", droppedReports)
                .put("deadlineReports", deadlineReports)
                .put("missedDeadlines", if (deadlineReports > 0) missedDeadlines else JSONObject.NULL)
        }
    }

    private companion object {
        const val LOAD_TIMEOUT_MS = 120_000L
        val PADDING = DiscoveryMapPadding(left = 24, top = 48, right = 12, bottom = 120)
        fun remainingMs(startedNs: Long) = (LOAD_TIMEOUT_MS - (System.nanoTime() - startedNs) / 1_000_000).coerceAtLeast(1)
        fun activity(context: Context): Activity = when (context) {
            is Activity -> context
            is ContextWrapper -> activity(context.baseContext)
            else -> error("Expected an activity-backed map window")
        }
        fun nativeViews(root: View): List<NavigationView> = buildList {
            if (root is NavigationView) add(root)
            else if (root is ViewGroup) repeat(root.childCount) { addAll(nativeViews(root.getChildAt(it))) }
        }
        fun pixelNear(bitmap: Bitmap, x: Int, y: Int, expected: Int): Boolean = (-2..2).any { dx -> (-2..2).any { dy ->
            x + dx in 0 until bitmap.width && y + dy in 0 until bitmap.height && bitmap.getPixel(x + dx, y + dy).let { color ->
                abs(Color.red(color) - Color.red(expected)) < 25 && abs(Color.green(color) - Color.green(expected)) < 25 &&
                    abs(Color.blue(color) - Color.blue(expected)) < 25
            }
        } }
    }
}
