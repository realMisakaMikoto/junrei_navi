package cn.anitabi.navigator.ui.map

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.anitabi.navigator.TestAnitabiApplication
import cn.anitabi.navigator.navigation.ActiveNavigationStore
import cn.anitabi.navigator.navigation.NavigationRuntime
import cn.anitabi.navigator.security.AppAppearance
import cn.anitabi.navigator.ui.theme.AnitabiTheme
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.MapColorScheme
import com.google.android.libraries.navigation.ForceNightMode
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.NavigationView
import com.google.android.libraries.navigation.Navigator
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Run alone in a fresh instrumentation process on an approved dedicated emulator, after explicit
 * Navigation terms acceptance and location permission grants. This test never accepts/reset terms,
 * sets destinations, requests routes or starts guidance. It observes real Navigator-ready UI events.
 * Official NavigationApi, NavigationView, NightModeChangedEvent, ForceNightMode and Navigator
 * reference contracts were checked on 2026-09-21; the SDK force policy is global and cleanup is Main.
 */
class NativeNavigationThemeInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun navigatorReadyNavigationUiFollowsAppDayNightDayWithoutGuidance() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = ApplicationProvider.getApplicationContext<TestAnitabiApplication>()
        assertTrue("Run without a persisted active trip", ActiveNavigationStore.get(application) == null)
        assertTrue("Run in an isolated process without an active navigation session",
            NavigationRuntime.state.value.let { !it.isRunning && it.plan == null && it.progress == null })
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).forEach {
            assertTrue("Grant location permissions externally before Navigator initialization",
                application.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED)
        }
        instrumentation.runOnMainSync {
            assertTrue("Explicit Navigation terms acceptance must precede this test", NavigationApi.areTermsAccepted(application))
        }

        val ready = CountDownLatch(1)
        val navigator = AtomicReference<Navigator?>()
        val initializationError = AtomicInteger(-1)
        val closed = AtomicBoolean(false)
        val root = AtomicReference<View?>()
        val map = AtomicReference<GoogleMap?>()
        val unavailable = AtomicBoolean(false)
        val events = CopyOnWriteArrayList<Boolean>()
        val listener = NavigationView.OnNightModeChangedListener { events += it.inNightMode() }
        var navigationView: NavigationView? = null
        var appearance by mutableStateOf(AppAppearance.LIGHT)
        var showMap by mutableStateOf(true)
        try {
            instrumentation.runOnMainSync {
                NavigationApi.getNavigator(application, object : NavigationApi.NavigatorListener {
                    override fun onNavigatorReady(value: Navigator) {
                        // The SDK may complete after the watchdog/finally; it cannot be cancelled.
                        if (closed.get()) value.cleanup() else navigator.set(value)
                        ready.countDown()
                    }
                    override fun onError(errorCode: Int) {
                        initializationError.set(errorCode)
                        ready.countDown()
                    }
                })
            }
            assertTrue("Navigator initialization exceeded the 45-second test watchdog", ready.await(45, TimeUnit.SECONDS))
            check(initializationError.get() == -1) { "Navigator initialization failed with code ${initializationError.get()}" }
            instrumentation.runOnMainSync {
                assertIdleNavigator(requireNotNull(navigator.get()))
            }
            composeRule.setContent {
                val currentRoot = LocalView.current.rootView
                SideEffect { root.set(currentRoot) }
                AnitabiTheme(appearance = appearance) {
                    if (showMap) NavigationMapView(
                        navigationUiEnabled = true, onMapReady = { map.set(it) },
                        onUnavailable = { unavailable.set(true) }, modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            composeRule.waitUntil(30_000) {
                check(!unavailable.get()) { "Navigator-ready view unavailable" }
                map.get() != null
            }
            val view = composeRule.runOnIdle {
                navigationViews(requireNotNull(root.get())).single().also {
                    navigationView = it
                    it.addOnNightModeChangedListener(listener)
                }
            }
            fun awaitMode(night: Boolean, afterEvent: Int?) {
                try {
                    composeRule.waitUntil(15_000) {
                        check(!unavailable.get()) { "Navigation view unavailable during theme change" }
                        composeRule.runOnIdle {
                            view.isNavigationUiEnabled &&
                                (afterEvent == null || events.size > afterEvent && events.lastOrNull() == night) &&
                                requireNotNull(map.get()).mapColorScheme == if (night) MapColorScheme.DARK else MapColorScheme.LIGHT
                        }
                    }
                } catch (failure: Throwable) {
                    runCatching {
                        val diagnostic = composeRule.runOnIdle {
                            JSONObject().put("nightModeEvents", JSONArray(events.toList()))
                                .put("navigationUiEnabled", view.isNavigationUiEnabled)
                                .put("mapColorScheme", map.get()?.mapColorScheme ?: JSONObject.NULL)
                                .put("attachedToWindow", view.isAttachedToWindow)
                                .put("expectedNight", night).put("afterEventCount", afterEvent ?: JSONObject.NULL)
                        }
                        val directory = File(requireNotNull(application.getExternalFilesDir(null)), "frontend-review")
                        check(directory.isDirectory || directory.mkdirs())
                        File(directory, "native-navigation-theme-failure-api${Build.VERSION.SDK_INT}.json")
                            .writeText(diagnostic.toString(2))
                    }.onFailure { failure.addSuppressed(AssertionError("Navigation theme diagnostics failed", it)) }
                    throw failure
                }
                composeRule.runOnIdle {
                    assertSame("Theme changes must retain the actual NavigationView", view,
                        navigationViews(requireNotNull(root.get())).single())
                    assertTrue("Navigation UI must actually be enabled", view.isNavigationUiEnabled)
                    assertIdleNavigator(requireNotNull(navigator.get()))
                }
            }
            // SDK 7.8 was observed without its documented registration event. Do not synthesize it:
            // the baseline checks the actual enabled UI/light scheme; both explicit changes need new callbacks.
            awaitMode(night = false, afterEvent = null)
            val registrationEvents = events.toList()
            for (next in listOf(AppAppearance.DARK, AppAppearance.LIGHT)) {
                val eventCount = composeRule.runOnIdle { events.size.also { appearance = next } }
                awaitMode(night = next == AppAppearance.DARK, afterEvent = eventCount)
            }
            val evidence = JSONObject().put("api", Build.VERSION.SDK_INT)
                .put("navigatorReady", true).put("navigationUiEnabled", true)
                .put("sameViewAcrossThemes", true).put("nightModeEvents", JSONArray(events.toList()))
                .put("initialRegistrationEventObserved", registrationEvents.isNotEmpty())
                .put("initialRegistrationEvents", JSONArray(registrationEvents))
                .put("scope", "Navigator-ready native UI events; no destinations, routes or guidance")
            val directory = File(requireNotNull(application.getExternalFilesDir(null)), "frontend-review")
            check(directory.isDirectory || directory.mkdirs())
            File(directory, "native-navigation-theme-api${Build.VERSION.SDK_INT}.json").writeText(evidence.toString(2))
        } finally {
            try {
                instrumentation.runOnMainSync {
                    closed.set(true)
                    navigationView?.let {
                        it.removeOnNightModeChangedListener(listener)
                        // Known fresh-process SDK policy; no global getter exists to snapshot it.
                        it.setForceNightMode(ForceNightMode.AUTO)
                    }
                    showMap = false
                }
                composeRule.waitForIdle()
            } finally {
                instrumentation.runOnMainSync {
                    closed.set(true)
                    navigator.getAndSet(null)?.cleanup()
                }
            }
        }
    }

    private fun assertIdleNavigator(navigator: Navigator) {
        assertFalse("Theme validation must not start guidance", navigator.isGuidanceRunning)
        assertTrue("Theme validation requires no route destinations", navigator.routeSegments.isEmpty())
    }

    private fun navigationViews(view: View): List<NavigationView> = buildList {
        if (view is NavigationView) add(view)
        else if (view is ViewGroup) repeat(view.childCount) { addAll(navigationViews(view.getChildAt(it))) }
    }
}
