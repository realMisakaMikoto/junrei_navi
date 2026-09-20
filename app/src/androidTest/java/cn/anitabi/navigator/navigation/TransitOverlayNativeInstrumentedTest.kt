package cn.anitabi.navigator.navigation

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.anitabi.navigator.R
import cn.anitabi.navigator.SyntheticDiscoveryFixture
import cn.anitabi.navigator.TestAnitabiApplication
import cn.anitabi.navigator.core.model.EndPolicy
import cn.anitabi.navigator.core.model.NavigationProgress
import cn.anitabi.navigator.core.model.NavigationState
import cn.anitabi.navigator.core.model.RouteObjective
import cn.anitabi.navigator.core.model.TourLeg
import cn.anitabi.navigator.core.model.TourPlan
import cn.anitabi.navigator.core.model.TransitExecutionStrategy
import cn.anitabi.navigator.core.model.TravelMode
import cn.anitabi.navigator.security.AppAppearance
import cn.anitabi.navigator.security.AppSettingsStore
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Dedicated emulator only, with SYSTEM_ALERT_WINDOW granted and restored by the test runner.
 * Real WindowManager overlay, native input and pixels at a local 1.6 font scale; synthetic route data.
 * External navigation, service actions, audible speech and physical-device behavior are not exercised.
 */
class TransitOverlayNativeInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun paletteRebuildRetainsDraggedResizedPanelAndCollapsedForm() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val application = ApplicationProvider.getApplicationContext<TestAnitabiApplication>()
        assertTrue("Grant overlay permission on the dedicated emulator before this test", Settings.canDrawOverlays(application))
        val originalServiceInfo = automation.serviceInfo
        val originalFlags = originalServiceInfo.flags
        val preferences = application.getSharedPreferences(OVERLAY_PREFERENCES, Context.MODE_PRIVATE)
        val originalLayout = preferences.all.toMap()
        val settingsPreferences = application.getSharedPreferences(AppSettingsStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
        val hadAppearance = settingsPreferences.contains(AppSettingsStore.PREFERENCE_APPEARANCE)
        val originalAppearance = settingsPreferences.getString(AppSettingsStore.PREFERENCE_APPEARANCE, null)
        val settings = application.container.appSettingsStore
        val configuration = Configuration(application.resources.configuration).apply { fontScale = 1.6f }
        val context = ContextThemeWrapper(application.createConfigurationContext(configuration), R.style.Theme_AnitabiNavigator)
        val density = context.resources.displayMetrics.density
        val plan = syntheticPlan()
        val progress = NavigationProgress(plan.id, state = NavigationState.NAVIGATING)
        var controller: TransitOverlayController? = null
        try {
            automation.serviceInfo = originalServiceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }
            composeRule.setContent {
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xffedf0f2)))
            }
            assertTrue(preferences.edit().clear().putBoolean("collapsed", false)
                .putFloat("horizontal_fraction", .2f).putFloat("vertical_fraction", .15f)
                .putInt("panel_width_dp", 232).putInt("panel_height_dp", 260).commit())
            composeRule.runOnIdle {
                settings.setAppearance(AppAppearance.LIGHT)
                controller = TransitOverlayController(context).also { it.render(plan, progress, 80.0) }
                assertTrue("The real overlay must attach", requireNotNull(controller).isShowing)
            }
            val originalBounds = capturePalette(automation, PANEL, Color.WHITE, "panel-light", density)
            assertPanelActions(automation, originalBounds)

            drag(automation, awaitNode(automation, DRAG).bounds(), 36f * density, 28f * density)
            val moved = awaitBounds(automation, PANEL) {
                abs(it.left - originalBounds.left - (36f * density).roundToInt()) <= 3 &&
                    abs(it.top - originalBounds.top - (28f * density).roundToInt()) <= 3
            }
            assertEquals(originalBounds.width(), moved.width())
            assertEquals(originalBounds.height(), moved.height())

            val resizeNode = awaitNode(automation, RESIZE)
            assertTrue("Refresh the native resize handle after the overlay window moves", resizeNode.refresh())
            val resizeHandle = resizeNode.bounds()
            val resizeEvidence = File(requireNotNull(application.getExternalFilesDir(null)), "frontend-review")
            check(resizeEvidence.isDirectory || resizeEvidence.mkdirs())
            File(resizeEvidence, "native-overlay-resize-input-api${Build.VERSION.SDK_INT}.txt").writeText(
                "movedFramePixels=$moved\nresizeHandlePixels=$resizeHandle\n" +
                    "deltaWidthPixels=${(32f * density).roundToInt()}\ndeltaHeightPixels=${(36f * density).roundToInt()}\n",
            )
            drag(automation, resizeHandle, 32f * density, 36f * density)
            val resized = awaitBounds(automation, PANEL) {
                abs(it.width() - moved.width() - (32f * density).roundToInt()) <= 3 &&
                    abs(it.height() - moved.height() - (36f * density).roundToInt()) <= 3
            }
            assertTrue("Native resize must preserve the panel origin", abs(resized.left - moved.left) <= 3 && abs(resized.top - moved.top) <= 3)
            assertPanelActions(automation, resized)
            composeRule.runOnIdle { settings.setAppearance(AppAppearance.DARK); requireNotNull(controller).render(plan, progress, 80.0) }
            assertBoundsRetained(resized, capturePalette(automation, PANEL, Color.rgb(32, 34, 38), "panel-dark", density))
            assertPanelActions(automation, resized)

            click(automation, COLLAPSE)
            val bubble = capturePalette(automation, BUBBLE, Color.rgb(255, 180, 166), "bubble-dark", density)
            assertTrue("Collapsed overlay must retain its bubble width", abs((60f * density).roundToInt() - bubble.width()) <= 3)
            assertTrue("Collapsed overlay must retain its bubble height", abs((60f * density).roundToInt() - bubble.height()) <= 3)
            assertTrue("Collapsed form must be persisted", preferences.getBoolean("collapsed", false))
            composeRule.runOnIdle { settings.setAppearance(AppAppearance.LIGHT); requireNotNull(controller).render(plan, progress, 80.0) }
            assertBoundsRetained(bubble, capturePalette(automation, BUBBLE, Color.rgb(201, 62, 79), "bubble-light", density))
            assertTrue("Palette rebuild must retain collapsed form", preferences.getBoolean("collapsed", false))

            click(automation, BUBBLE)
            val reopened = capturePalette(automation, PANEL, Color.WHITE, "panel-reopened", density, expectedFrame = resized)
            assertBoundsRetained(resized, reopened)
            assertPanelActions(automation, reopened)
            assertFalse("Reopening must persist panel form", preferences.getBoolean("collapsed", true))
        } finally {
            try {
                composeRule.runOnIdle { controller?.remove() }
            } finally {
                try {
                    restorePreferences(preferences, originalLayout)
                    val edit = settingsPreferences.edit()
                    if (hadAppearance) edit.putString(AppSettingsStore.PREFERENCE_APPEARANCE, originalAppearance)
                    else edit.remove(AppSettingsStore.PREFERENCE_APPEARANCE)
                    check(edit.commit())
                } finally {
                    originalServiceInfo.flags = originalFlags
                    automation.serviceInfo = originalServiceInfo
                }
            }
        }
    }

    private fun awaitNode(automation: UiAutomation, label: String): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = null
        composeRule.waitUntil(10_000) { result = findOverlayNode(automation, label); result != null }
        return requireNotNull(result)
    }

    private fun findOverlayNode(automation: UiAutomation, label: String): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && node.contentDescription?.toString()?.contains(label) == true) return node
            repeat(node.childCount) { index -> node.getChild(index)?.let { find(it)?.let { found -> return found } } }
            return null
        }
        return automation.windows.asSequence().mapNotNull { it.root }
            .filter { it.packageName?.toString() == "cn.anitabi.navigator" }
            .mapNotNull(::find).firstOrNull()
    }

    private fun awaitBounds(automation: UiAutomation, label: String, accept: (Rect) -> Boolean): Rect {
        var result: Rect? = null
        try {
            composeRule.waitUntil(10_000) {
                result = findOverlayNode(automation, label)?.bounds()
                result?.let(accept) == true
            }
        } catch (failure: Throwable) {
            runCatching {
                val directory = File(requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)), "frontend-review")
                check(directory.isDirectory || directory.mkdirs())
                val name = "native-overlay-await-bounds-failure-api${Build.VERSION.SDK_INT}"
                val screenshot = automation.takeScreenshot()
                try {
                    File(directory, "$name.txt").writeText("lastFramePixels=$result\n" +
                        "screenWidth=${screenshot?.width}\nscreenHeight=${screenshot?.height}\n")
                    screenshot?.let { bitmap -> File(directory, "$name.png").outputStream().use {
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    } }
                } finally { screenshot?.recycle() }
            }.onFailure { failure.addSuppressed(AssertionError("Overlay bounds diagnostics failed", it)) }
            throw failure
        }
        return requireNotNull(result)
    }

    private fun assertPanelActions(automation: UiAutomation, panel: Rect) {
        listOf(DRAG, COLLAPSE, RESIZE).forEach { label ->
            val node = awaitNode(automation, label)
            assertTrue("The overlay control must be enabled and inside the panel", node.isEnabled && panel.contains(node.bounds()))
        }
    }

    private fun click(automation: UiAutomation, label: String) {
        val node = awaitNode(automation, label)
        assertTrue("Only the panel's local form control may be activated", node.isClickable && node.isEnabled)
        assertTrue("Android must accept the overlay form action", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        composeRule.waitForIdle()
    }

    private fun drag(automation: UiAutomation, handle: Rect, dx: Float, dy: Float) {
        val started = SystemClock.uptimeMillis()
        fun send(action: Int, fraction: Float) {
            val event = MotionEvent.obtain(started, SystemClock.uptimeMillis(), action,
                handle.exactCenterX() + dx * fraction, handle.exactCenterY() + dy * fraction, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            try { assertTrue("Android must inject the real overlay touch", automation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, 0f)
        repeat(10) { step -> SystemClock.sleep(20); send(MotionEvent.ACTION_MOVE, (step + 1) / 10f) }
        send(MotionEvent.ACTION_UP, 1f)
    }

    private fun capturePalette(automation: UiAutomation, label: String, expected: Int, stage: String, density: Float,
        expectedFrame: Rect? = null): Rect {
        var screenshot: Bitmap? = null
        var bounds: Rect? = null
        var attempts = 0
        val observedFrames = linkedSetOf<String>()
        val directory = File(requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)), "frontend-review")
        check(directory.isDirectory || directory.mkdirs())
        val name = "native-overlay-$stage-large-font-api${Build.VERSION.SDK_INT}"
        fun diagnostics(bitmap: Bitmap) = "screenWidth=${bitmap.width}\nscreenHeight=${bitmap.height}\nattempts=$attempts\n" +
            "fontScale=1.6\ndensity=$density\nframePixels=${observedFrames.joinToString(";")}\n" +
            "lastFramePixels=$bounds\nexpectedFramePixels=$expectedFrame\n"
        try {
            composeRule.waitUntil(10_000) {
                bounds = findOverlayNode(automation, label)?.bounds() ?: return@waitUntil false
                screenshot?.recycle()
                screenshot = automation.takeScreenshot() ?: return@waitUntil false
                val frame = requireNotNull(bounds)
                val bitmap = requireNotNull(screenshot)
                attempts++
                if (observedFrames.size < 12) observedFrames.add("${frame.left},${frame.top},${frame.right},${frame.bottom}")
                // Rebuilding content and resizing/repositioning its Window arrive asynchronously.
                // Require the complete expected frame, then reject bounds that changed during capture.
                if (!Rect(0, 0, bitmap.width, bitmap.height).contains(frame) ||
                    expectedFrame?.let { !boundsRetained(it, frame) } == true ||
                    findOverlayNode(automation, label)?.bounds() != frame) return@waitUntil false
                val x = if (label == PANEL) frame.left + (4f * density).roundToInt() else frame.right - (8f * density).roundToInt()
                val y = if (label == PANEL) frame.top + (30f * density).roundToInt() else frame.centerY()
                x in 0 until bitmap.width && y in 0 until bitmap.height && sameColor(bitmap.getPixel(x, y), expected)
            }
            val bitmap = requireNotNull(screenshot)
            val frame = requireNotNull(bounds)
            check(Rect(0, 0, bitmap.width, bitmap.height).contains(frame))
            val crop = Bitmap.createBitmap(bitmap, frame.left, frame.top, frame.width(), frame.height())
            try {
                File(directory, "$name.png").outputStream().use {
                    check(crop.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                File(directory, "$name.txt").writeText(diagnostics(bitmap))
            } finally { if (crop !== bitmap) crop.recycle() }
            return frame
        } catch (failure: Throwable) {
            screenshot?.let { bitmap ->
                runCatching {
                    // The whole underlying activity is the authored blank test backdrop.
                    File(directory, "$name-failure.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    File(directory, "$name-failure.txt").writeText(diagnostics(bitmap))
                }.onFailure { failure.addSuppressed(AssertionError("Overlay capture diagnostics failed", it)) }
            }
            throw failure
        } finally { screenshot?.recycle() }
    }

    private fun syntheticPlan(): TourPlan {
        val snapshot = SyntheticDiscoveryFixture.snapshot()
        val points = snapshot.points.map { it.toPilgrimagePoint() }
        return TourPlan(
            id = "synthetic-overlay-only", anime = snapshot.subjects.single().anime,
            selectedPoints = points, orderedPoints = points,
            legs = listOf(TourLeg(points.first().coordinate, points.last().coordinate, TravelMode.TRANSIT,
                emptyList(), emptyList(), 0.0, 0.0, "Synthetic overlay fixture", destinationPointId = points.last().id)),
            mode = TravelMode.TRANSIT, objective = RouteObjective.FASTEST, endPolicy = EndPolicy.OPEN,
            estimatedDurationSeconds = 0.0, attribution = emptyList(),
            executionStrategy = TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN,
        )
    }

    private fun restorePreferences(preferences: SharedPreferences, values: Map<String, *>) {
        val editor = preferences.edit().clear()
        values.forEach { (key, value) -> when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is String -> editor.putString(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        check(editor.commit())
    }

    private fun AccessibilityNodeInfo.bounds() = Rect().also(::getBoundsInScreen)

    private fun assertBoundsRetained(expected: Rect, actual: Rect) {
        assertTrue("Overlay form rebuild must retain its native frame", boundsRetained(expected, actual))
    }

    private fun boundsRetained(expected: Rect, actual: Rect) = abs(expected.left - actual.left) <= 3 &&
        abs(expected.top - actual.top) <= 3 && abs(expected.right - actual.right) <= 3 && abs(expected.bottom - actual.bottom) <= 3

    private fun sameColor(actual: Int, expected: Int) = abs(Color.red(actual) - Color.red(expected)) <= 12 &&
        abs(Color.green(actual) - Color.green(expected)) <= 12 && abs(Color.blue(actual) - Color.blue(expected)) <= 12

    private companion object {
        const val OVERLAY_PREFERENCES = "transit_overlay_layout"
        const val PANEL = "\u63a7\u5236\u9762\u677f\u3002\u76ee\u6807 Synthetic Stop Beta"
        const val BUBBLE = "\u8f7b\u89e6\u5c55\u5f00\u63a7\u5236\uff0c\u62d6\u52a8\u53ef\u79fb\u52a8"
        const val DRAG = "\u62d6\u52a8\u79fb\u52a8\uff0c\u8f7b\u89e6\u5207\u6362\u505c\u9760\u4f4d\u7f6e"
        const val RESIZE = "\u62d6\u52a8\u8c03\u6574\u5927\u5c0f\uff0c\u8f7b\u89e6\u5207\u6362\u7d27\u51d1\u5c3a\u5bf8"
        const val COLLAPSE = "\u6536\u8d77\u4e3a\u60ac\u6d6e\u7403"
    }
}
