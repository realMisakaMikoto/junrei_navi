package cn.anitabi.navigator.ui.map

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.anitabi.navigator.TestAnitabiApplication
import com.google.android.libraries.navigation.NavigationApi
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Explicitly authorized setup for dedicated emulators only; never part of unattended consent.
 * Requires navigationTermsConsentApproved=true before accepting previously unaccepted terms.
 * Uses the real SDK notice and acceptance callback, without preferences, reset, Navigator or routes.
 * NavigationApi / OnTermsResponseListener docs and the reviewed English notice were read 2026-09-21.
 */
class GoogleNavigationConsentFixtureTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun explicitlyApprovedEmulatorMayAcceptReviewedNavigationNotice() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = ApplicationProvider.getApplicationContext<TestAnitabiApplication>()
        assertTrue("This consent fixture is restricted to Android emulators", Build.HARDWARE in setOf("ranchu", "goldfish"))
        var alreadyAccepted = false
        instrumentation.runOnMainSync { alreadyAccepted = NavigationApi.areTermsAccepted(application) }
        fun report(status: String) = instrumentation.sendStatus(0, Bundle().apply {
            putString("navigationTermsConsentStatus", status)
            putString("scope", "consent_fixture_only_not_navigator_or_night_mode_validation")
        })
        if (alreadyAccepted) {
            report("ALREADY_ACCEPTED_NO_ACTION")
            return
        }
        assertTrue("Explicit navigationTermsConsentApproved=true is required before accepting this notice",
            InstrumentationRegistry.getArguments().getString("navigationTermsConsentApproved") == "true")

        val host = AtomicReference<Activity?>()
        val response = AtomicReference<Boolean?>()
        composeRule.setContent {
            val context = LocalContext.current
            SideEffect { host.set(activity(context)) }
            Box(Modifier.fillMaxSize())
        }
        composeRule.runOnIdle {
            // Null companyName preserves the real application name and the SDK's default notice.
            NavigationApi.showTermsAndConditionsDialog(requireNotNull(host.get()), null,
                NavigationApi.OnTermsResponseListener { response.set(it) })
        }
        val automation = instrumentation.uiAutomation
        var acceptButton: AccessibilityNodeInfo? = null
        composeRule.waitUntil(20_000) {
            val root = automation.rootInActiveWindow ?: return@waitUntil false
            if (root.packageName?.toString() != application.packageName) return@waitUntil false
            val text = allText(root).joinToString(" ").replace(Regex("\\s+"), " ")
            if (NOTICE_TITLE !in text || DATA_HEADING !in text || TERMS_NOTICE !in text) return@waitUntil false
            val appName = application.applicationInfo.loadLabel(application.packageManager).toString()
            if (appName !in text) return@waitUntil false
            acceptButton = findAcceptButton(root)
            acceptButton != null
        }
        val button = requireNotNull(acceptButton)
        assertTrue("The real SDK acceptance button must still belong to this application",
            button.refresh() && button.packageName?.toString() == application.packageName)
        assertTrue("The reviewed GOT IT action must be visible and enabled",
            button.isVisibleToUser && button.isEnabled && button.isClickable && button.text?.toString() == ACCEPT)
        assertTrue("Android must execute the real SDK acceptance action",
            button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        composeRule.waitUntil(10_000) { response.get() != null }
        assertTrue("The SDK must report accepted terms through its callback", response.get() == true)
        composeRule.runOnIdle {
            assertTrue("The SDK must independently confirm accepted terms", NavigationApi.areTermsAccepted(application))
        }
        report("ACCEPTED_WITH_EXPLICIT_ARGUMENT")
    }

    private fun allText(node: AccessibilityNodeInfo): List<String> = buildList {
        node.text?.toString()?.let(::add)
        node.contentDescription?.toString()?.let(::add)
        repeat(node.childCount) { index -> node.getChild(index)?.let { addAll(allText(it)) } }
    }

    private fun findAcceptButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.text?.toString() == ACCEPT && node.isVisibleToUser && node.isEnabled && node.isClickable) return node
        repeat(node.childCount) { index ->
            node.getChild(index)?.let { child -> findAcceptButton(child)?.let { return it } }
        }
        return null
    }

    private fun activity(context: Context): Activity = when (context) {
        is Activity -> context
        is ContextWrapper -> activity(context.baseContext)
        else -> error("Expected the isolated test activity")
    }

    private companion object {
        const val NOTICE_TITLE = "Welcome to Google Maps navigation"
        const val DATA_HEADING = "How navigation data is used"
        const val TERMS_NOTICE = "Terms and Privacy Policy apply."
        const val ACCEPT = "GOT IT"
    }
}
