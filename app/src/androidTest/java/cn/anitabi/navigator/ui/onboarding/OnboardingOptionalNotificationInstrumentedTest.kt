package cn.anitabi.navigator.ui.onboarding

import android.Manifest
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.anitabi.navigator.AnitabiApplication
import cn.anitabi.navigator.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Caller preconfigures approved emulator permissions; this test never grants permissions or completes onboarding. */
@RunWith(AndroidJUnit4::class)
class OnboardingOptionalNotificationInstrumentedTest {
    @get:Rule val composeRule = createEmptyComposeRule()

    @Test fun grantedLocationContinuesWithoutRequestingOptionalNotifications() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = ApplicationProvider.getApplicationContext<AnitabiApplication>()
        assertTrue("Only explicitly approved emulator setup may run this test",
            Build.HARDWARE in setOf("ranchu", "goldfish") &&
                InstrumentationRegistry.getArguments().getString("onboardingLocationPreconfiguredApproved") == "true")
        val settings = application.container.appSettingsStore
        assertFalse("The caller must preserve an incomplete onboarding state", settings.hasCompletedOnboarding())
        assertFalse("No AMap SDK consent is needed for this continuation", settings.hasCurrentAmapPrivacyConsent())
        val originalTelemetry = settings.telemetryConsent()
        fun assertPermissions() {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).forEach {
                assertEquals("Location must already be granted by the approved caller", PackageManager.PERMISSION_GRANTED,
                    application.checkSelfPermission(it))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                assertEquals("Optional notification permission must remain denied", PackageManager.PERMISSION_DENIED,
                    application.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
            }
        }
        assertPermissions()
        // Observe the platform permission-request activity without blocking it or supplying a result.
        val permissionRequests = instrumentation.addMonitor(
            IntentFilter("android.content.pm.action.REQUEST_PERMISSIONS"), null, false)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                composeRule.onNodeWithTag("onboarding-start").assertIsDisplayed().performClick()
                composeRule.onNodeWithTag("onboarding-permission-continue").assertIsDisplayed().performClick()
                val deadline = SystemClock.elapsedRealtime() + 15_000L
                fun remainingMillis(): Long = (deadline - SystemClock.elapsedRealtime()).also {
                    check(it > 0) { "The shared onboarding observation deadline expired" }
                }
                composeRule.waitUntil(timeoutMillis = remainingMillis()) {
                    composeRule.onAllNodesWithTag("onboarding-service-step").fetchSemanticsNodes().isNotEmpty()
                }
                composeRule.onNodeWithTag("onboarding-service-step").performScrollTo().assertIsDisplayed()
                assertEquals("Continuing with granted location must not launch another permission request",
                    0, permissionRequests.hits)
                val automation = instrumentation.uiAutomation
                var activePackage: String? = null
                composeRule.waitUntil(timeoutMillis = remainingMillis()) {
                    activePackage = automation.rootInActiveWindow?.packageName?.toString()
                    activePackage == application.packageName
                }
                assertEquals("The service page must retain focus without a system permission dialog",
                    application.packageName, activePackage)
                assertEquals("Waiting for the active window must not launch a permission request",
                    0, permissionRequests.hits)
                assertPermissions()
                assertFalse(settings.hasCompletedOnboarding())
                assertFalse(settings.hasCurrentAmapPrivacyConsent())
                // Stop before the service submit action, which would persist onboarding and SDK consent.
            }
        } finally {
            instrumentation.removeMonitor(permissionRequests)
            assertPermissions()
            assertFalse("The test must not complete onboarding", settings.hasCompletedOnboarding())
            assertFalse("The test must not persist AMap consent", settings.hasCurrentAmapPrivacyConsent())
            assertEquals("The test must not change telemetry choices", originalTelemetry, settings.telemetryConsent())
        }
    }
}
