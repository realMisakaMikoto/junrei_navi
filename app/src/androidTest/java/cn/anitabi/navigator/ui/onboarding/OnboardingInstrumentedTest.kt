package cn.anitabi.navigator.ui.onboarding

import android.Manifest
import android.app.Instrumentation
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.anitabi.navigator.AnitabiApplication
import cn.anitabi.navigator.MainActivity
import java.io.FileInputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingInstrumentedTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val instrumentation: Instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val application: AnitabiApplication
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun completesPermissionsServiceAndRestartFlow() {
        assertFalse(application.container.appSettingsStore.hasCompletedOnboarding())
        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.onNodeWithText("初次使用设置").assertIsDisplayed()
            composeRule.onNodeWithTag("onboarding-start").performClick()
            composeRule.onNodeWithTag("onboarding-background-guidance")
                .performScrollTo()
                .assertIsDisplayed()
            composeRule.onNodeWithText("关闭电池优化").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("在后台锁定应用").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("允许悬浮窗").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("onboarding-battery-settings")
                .performScrollTo()
                .assertIsDisplayed()
            composeRule.onNodeWithTag("onboarding-overlay-settings")
                .performScrollTo()
                .assertIsDisplayed()
            reportEvidence("ONBOARDING_BACKGROUND_GUIDANCE_SHOWN")
            composeRule.onNodeWithTag("onboarding-permission-request").performClick()

            awaitPermissionDialog()
            reportEvidence("ONBOARDING_PERMISSION_DIALOG_SHOWN")
            grantRequiredPermissions()
            returnFromPermissionDialog()
            continueAfterPermissionGrantIfNeeded()

            awaitTag("onboarding-service-step")
            composeRule.onNodeWithTag("onboarding-service-step")
                .performScrollTo()
                .assertIsDisplayed()
            reportEvidence("ONBOARDING_SERVICE_GUIDE_SHOWN")

            composeRule.onNodeWithTag("onboarding-service-submit")
                .performScrollTo()
                .performClick()
            awaitTag("discovery-screen")
            composeRule.onNodeWithTag("discovery-screen").assertIsDisplayed()
            reportEvidence("ONBOARDING_COMPLETED_TO_DISCOVERY")
        }

        assertTrue(application.container.appSettingsStore.hasCompletedOnboarding())
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag("discovery-screen")
            composeRule.onNodeWithTag("discovery-screen").assertIsDisplayed()
            composeRule.onAllNodesWithTag("onboarding-start").fetchSemanticsNodes()
                .also { nodes -> assertTrue(nodes.isEmpty()) }
            reportEvidence("ONBOARDING_RESTARTED_IN_DISCOVERY")
        }
    }

    private fun awaitTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitPermissionDialog() {
        repeat(30) {
            val focus = focusedWindow().lowercase()
            if ("permissioncontroller" in focus || "packageinstaller" in focus) return
            Thread.sleep(250L)
        }
        throw AssertionError("The Android runtime-permission dialog did not appear")
    }

    private fun grantRequiredPermissions() {
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.O) {
            grantAndroid8PermissionDialog()
            return
        }
        grantRuntimePermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        grantRuntimePermission(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            grantRuntimePermission(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun grantAndroid8PermissionDialog() {
        repeat(30) {
            if (hasRequiredLocationPermissions()) return
            val focus = focusedWindow().lowercase()
            if ("permissioncontroller" !in focus && "packageinstaller" !in focus) return
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val systemUiCrashClose = root
                ?.findAccessibilityNodeInfosByViewId("android:id/aerr_close")
                ?.firstOrNull()
            if (systemUiCrashClose != null) {
                val bounds = Rect().also(systemUiCrashClose::getBoundsInScreen)
                val actionClicked = systemUiCrashClose.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                instrumentation.waitForIdleSync()
                if (!actionClicked && !bounds.isEmpty) {
                    shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
                }
                reportEvidence("ONBOARDING_SYSTEM_UI_CRASH_DISMISSED")
                Thread.sleep(750L)
                return@repeat
            }
            if ("application error: com.android.systemui" in focus) {
                tapAndroid8SystemUiCrashFallback()
                Thread.sleep(750L)
                return@repeat
            }
            val allowButton = listOf(
                "com.android.packageinstaller:id/permission_allow_button",
                "com.google.android.packageinstaller:id/permission_allow_button",
                "android:id/button1",
            ).firstNotNullOfOrNull { viewId ->
                root?.findAccessibilityNodeInfosByViewId(viewId)?.firstOrNull()
            } ?: listOf("ALLOW", "Allow", "允许").firstNotNullOfOrNull { label ->
                root?.findAccessibilityNodeInfosByText(label)?.firstOrNull { node -> node.isClickable }
            }
            if (allowButton != null) {
                val bounds = Rect().also(allowButton::getBoundsInScreen)
                reportEvidence("ONBOARDING_PERMISSION_ALLOW_NODE_FOUND")
                val actionClicked = allowButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                instrumentation.waitForIdleSync()
                Thread.sleep(500L)
                if (
                    !hasRequiredLocationPermissions() &&
                    !bounds.isEmpty &&
                    ("permissioncontroller" in focusedWindow().lowercase() ||
                        "packageinstaller" in focusedWindow().lowercase())
                ) {
                    shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
                    reportEvidence("ONBOARDING_PERMISSION_ALLOW_INPUT_TAP")
                } else if (actionClicked) {
                    reportEvidence("ONBOARDING_PERMISSION_ALLOW_ACTION_CLICK")
                }
            } else {
                tapAndroid8AllowFallback()
            }
            Thread.sleep(500L)
        }
        throw AssertionError("The Android 8 permission dialog did not return to the app")
    }

    private fun grantRuntimePermission(permission: String) {
        shell("pm grant ${application.packageName} $permission")
    }

    private fun returnFromPermissionDialog() {
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.O && hasRequiredLocationPermissions()) {
            instrumentation.waitForIdleSync()
            return
        }
        repeat(30) {
            if (application.packageName in focusedWindow().lowercase()) return
            Thread.sleep(500L)
        }
        shell("input keyevent 4")
        repeat(10) {
            if (application.packageName in focusedWindow().lowercase()) return
            Thread.sleep(500L)
        }
        throw AssertionError("The app did not regain focus after permissions were granted")
    }

    private fun hasRequiredLocationPermissions(): Boolean =
        application.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED &&
            application.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun tapAndroid8AllowFallback() {
        tapAtDisplayRatio(xPercent = 73, yPercent = 55)
        reportEvidence("ONBOARDING_PERMISSION_ALLOW_GEOMETRY_TAP")
    }

    private fun tapAndroid8SystemUiCrashFallback() {
        tapAtDisplayRatio(xPercent = 50, yPercent = 56)
        reportEvidence("ONBOARDING_SYSTEM_UI_CRASH_GEOMETRY_DISMISS")
    }

    private fun tapAtDisplayRatio(xPercent: Int, yPercent: Int) {
        val dimensions = Regex("(\\d+)x(\\d+)")
            .findAll(shell("wm size"))
            .lastOrNull()
            ?: return
        val width = dimensions.groupValues[1].toInt()
        val height = dimensions.groupValues[2].toInt()
        shell("input tap ${width * xPercent / 100} ${height * yPercent / 100}")
    }

    private fun continueAfterPermissionGrantIfNeeded() {
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule.onAllNodesWithTag("onboarding-service-step").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithTag("onboarding-permission-continue")
                    .fetchSemanticsNodes().isNotEmpty()
        }
        if (
            composeRule.onAllNodesWithTag("onboarding-permission-continue")
                .fetchSemanticsNodes().isNotEmpty()
        ) {
            composeRule.onNodeWithTag("onboarding-permission-continue").performClick()
        }
    }

    private fun focusedWindow(): String {
        val windowLines = shell("dumpsys window windows").lineSequence().toList()
        val activityLines = shell("dumpsys activity activities").lineSequence().toList()
        return buildList {
            addAll(
                windowLines.filter { line ->
                    "mCurrentFocus" in line || "mFocusedApp" in line
                },
            )
            addAll(
                activityLines.filter { line ->
                    "mResumedActivity" in line || "topResumedActivity" in line ||
                        "ResumedActivity" in line
                },
            )
        }.joinToString("\n")
    }

    private fun reportEvidence(message: String) {
        instrumentation.sendStatus(
            0,
            Bundle().apply { putString(Instrumentation.REPORT_KEY_STREAMRESULT, "$message\n") },
        )
    }

    private fun shell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return descriptor.use {
            FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText() }
        }
    }
}
