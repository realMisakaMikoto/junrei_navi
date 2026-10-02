package cn.anitabi.navigator.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.anitabi.navigator.MainActivity
import cn.anitabi.navigator.SyntheticDiscoveryFixture
import cn.anitabi.navigator.TestAnitabiApplication
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.security.AppSettingsStore
import cn.anitabi.navigator.ui.discovery.map.DiscoveryCameraPosition
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Run separately with the real TalkBack service enabled on a dedicated emulator.
 * Verifies Android accessibility focus/click and app state, not audible speech or human gestures.
 * An absent or suppressed service fails the test; it is never reported as a skipped success.
 */
class DiscoveryTalkBackInstrumentedTest {
    @get:Rule val composeRule = createEmptyComposeRule()

    @Test
    fun enabledTalkBackCanFocusAndActivatePointSelectionAndDestinations() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val application = ApplicationProvider.getApplicationContext<TestAnitabiApplication>()
        val manager = application.getSystemService(AccessibilityManager::class.java)
        composeRule.waitUntil(10_000) {
            manager.isEnabled && manager.isTouchExplorationEnabled &&
                manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).any {
                    it.resolveInfo.serviceInfo.packageName == "com.google.android.marvin.talkback"
                }
        }
        val settings = application.container.appSettingsStore
        val originalOnboarding = settings.hasCompletedOnboarding()
        val originalConsent = settings.hasCurrentAmapPrivacyConsent()
        val originalCamera = application.container.discoveryPreferences.lastCamera()
        try {
            settings.markOnboardingComplete()
            settings.setAmapPrivacyConsent(false)
            // Explicit list fallback avoids network maps while exercising the real application shell.
            application.container.discoveryPreferences.saveCamera(
                DiscoveryCameraPosition(GeoPoint(0.0, 0.0), 5f, provider = MapProvider.AMAP),
            )
            ActivityScenario.launch(MainActivity::class.java).use {
                composeRule.waitUntil(15_000) {
                    composeRule.onAllNodesWithTag("discovery-panel-list").fetchSemanticsNodes().isNotEmpty()
                }
                focusAndClick(automation, SyntheticDiscoveryFixture.FIRST_POINT_NAME)
                focusAndClick(automation, ADD_TO_TRIP)
                composeRule.onNodeWithText(REMOVE_FROM_TRIP).assertIsDisplayed()

                focusAndClick(automation, TRIPS, exact = true)
                composeRule.onNodeWithText("1 \u90e8\u4f5c\u54c1 \u00b7 1 \u4e2a\u5df2\u9009\u5730\u70b9").assertIsDisplayed()
                focusAndClick(automation, MAP, exact = true)
                composeRule.onNodeWithText(REMOVE_FROM_TRIP).assertIsDisplayed()

                focusAndClick(automation, REMOVE_FROM_TRIP)
                composeRule.onNodeWithText(ADD_TO_TRIP).assertIsDisplayed()
                focusAndClick(automation, BACK)
                composeRule.onNodeWithText("\u5df2\u9009 0 \u4e2a\u5730\u70b9").assertIsDisplayed()
            }
            assertTrue("TalkBack must remain enabled throughout native accessibility actions", manager.isTouchExplorationEnabled)
        } finally {
            settings.setAmapPrivacyConsent(originalConsent)
            application.getSharedPreferences(AppSettingsStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(AppSettingsStore.PREFERENCE_ONBOARDING_COMPLETE, originalOnboarding).commit()
            if (originalCamera == null) {
                application.getSharedPreferences("discovery_view", Context.MODE_PRIVATE).edit().clear().commit()
            } else application.container.discoveryPreferences.saveCamera(originalCamera)
        }
    }

    private fun focusAndClick(automation: UiAutomation, label: String, exact: Boolean = false) {
        var candidate: AccessibilityNodeInfo? = null
        composeRule.waitUntil(10_000) {
            candidate = automation.rootInActiveWindow?.let { findClickable(it, label, exact) }
            candidate != null
        }
        val node = requireNotNull(candidate)
        assertTrue("Labeled app action must be visible and enabled", node.isVisibleToUser && node.isEnabled)
        assertTrue("Android must accept accessibility focus",
            node.isAccessibilityFocused || node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
        composeRule.waitUntil(5_000) { node.refresh() && node.isAccessibilityFocused }
        assertTrue("Android must execute the accessibility click", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        composeRule.waitForIdle()
    }

    private fun findClickable(node: AccessibilityNodeInfo, label: String, exact: Boolean): AccessibilityNodeInfo? {
        fun matches(value: CharSequence?) = value?.toString()?.let { if (exact) it == label else label in it } == true
        if (node.isVisibleToUser && (matches(node.text) || matches(node.contentDescription))) {
            var target: AccessibilityNodeInfo? = node
            while (target != null) {
                if (target.isClickable && target.isEnabled && target.isVisibleToUser) return target
                target = target.parent
            }
        }
        repeat(node.childCount) { index ->
            node.getChild(index)?.let { child -> findClickable(child, label, exact)?.let { return it } }
        }
        return null
    }

    private companion object {
        const val ADD_TO_TRIP = "\u52a0\u5165\u884c\u7a0b"
        const val REMOVE_FROM_TRIP = "\u79fb\u51fa\u884c\u7a0b"
        const val MAP = "\u5730\u56fe"
        const val TRIPS = "\u884c\u7a0b"
        const val BACK = "\u8fd4\u56de\u4e0a\u4e00\u5c42"
    }
}
