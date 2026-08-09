package cn.anitabi.navigator.navigation

import android.Manifest
import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.anitabi.navigator.AnitabiApplication
import cn.anitabi.navigator.MainActivity
import cn.anitabi.navigator.TEST_REGION_DATA_VERSION
import cn.anitabi.navigator.TestAnitabiApplication
import cn.anitabi.navigator.core.model.Anime
import cn.anitabi.navigator.core.model.EndPolicy
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.NavigationProgress
import cn.anitabi.navigator.core.model.NavigationState
import cn.anitabi.navigator.core.model.PilgrimagePoint
import cn.anitabi.navigator.core.model.RouteObjective
import cn.anitabi.navigator.core.model.RouteStep
import cn.anitabi.navigator.core.model.TourLeg
import cn.anitabi.navigator.core.model.TourPlan
import cn.anitabi.navigator.core.model.TravelMode
import java.io.FileInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationRuntimeInstrumentedTest {
    private val application: AnitabiApplication
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun seedProcessRecoveryFixture() = runBlocking {
        assertTrue(application is TestAnitabiApplication)
        prepareDevice()
        application.stopService(Intent(application, NavigationService::class.java))
        NavigationRuntime.set(NavigationRuntimeState())
        val plan = fixturePlan(RECOVERY_TOUR_ID)
        application.container.tourRepository.save(
            plan,
            NavigationProgress(tourId = plan.id, state = NavigationState.NAVIGATING),
        )
        val saved = application.container.tourRepository.get(plan.id)
        assertEquals(NavigationState.NAVIGATING, saved?.progress?.state)
        assertEquals(2, saved?.plan?.legs?.size)
    }

    @Test
    fun verifyFailedProcessRecoveryState() = runBlocking {
        val saved = requireNotNull(application.container.tourRepository.get(RECOVERY_TOUR_ID))

        assertTrue(saved.routeNeedsRefresh)
        assertTrue(saved.plan.legs.isEmpty())
        assertEquals(NavigationState.PLANNED, saved.progress?.state)
        assertTrue(START_ID in saved.progress?.completedPointIds.orEmpty())
        assertNull(ActiveNavigationStore.get(application))
        reportEvidence("RECOVERY_FAILED_ROUTE_REMAINS_REFRESHABLE")
    }

    @Test
    fun foregroundServiceCompletesOfflineRouteAndPersistsProgress() = runBlocking {
        prepareDevice()
        application.stopService(Intent(application, NavigationService::class.java))
        NavigationRuntime.set(NavigationRuntimeState())
        val plan = fixturePlan(SERVICE_TOUR_ID)
        application.container.tourRepository.save(
            plan,
            NavigationProgress(tourId = plan.id, state = NavigationState.NAVIGATING),
        )
        val managesAirplaneMode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        if (managesAirplaneMode) {
            setAirplaneMode(true)
        } else {
            assertEquals("1", shell("settings get global airplane_mode_on").trim())
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            awaitCondition("foreground service did not resume the saved route") {
                NavigationRuntime.state.value.let { state ->
                    state.plan?.id == plan.id && state.isRunning &&
                        state.progress?.state == NavigationState.NAVIGATING
                }
            }
            assertEquals("1", shell("settings get global airplane_mode_on").trim())
            assertNotNull(activeNavigationNotification())
            assertEquals(plan.id, ActiveNavigationStore.get(application))

            sendServiceAction(NavigationService.ACTION_MANUAL_ARRIVAL)
            awaitCondition("navigation did not advance to the second leg") {
                NavigationRuntime.state.value.progress?.let { progress ->
                    progress.legIndex == 1 && progress.state == NavigationState.NAVIGATING &&
                        FIRST_STOP_ID in progress.completedPointIds
                } == true
            }

            sendServiceAction(NavigationService.ACTION_MANUAL_ARRIVAL)
            awaitCondition("navigation did not complete the second leg") {
                NavigationRuntime.state.value.progress?.state == NavigationState.COMPLETED
            }
            awaitCondition("completed progress was not persisted") {
                application.container.tourRepository.get(plan.id)?.progress?.state == NavigationState.COMPLETED
            }
            awaitCondition("completed navigation pointer was not cleared") {
                ActiveNavigationStore.get(application) == null
            }
            awaitCondition("completed foreground notification was not removed") {
                activeNavigationNotification() == null
            }
            val completed = application.container.tourRepository.get(plan.id)?.progress
            assertEquals(setOf(START_ID, FIRST_STOP_ID, SECOND_STOP_ID), completed?.completedPointIds)
            assertTrue(NavigationRuntime.state.value.errorMessage == null)
        } finally {
            if (managesAirplaneMode) setAirplaneMode(false)
            application.stopService(Intent(application, NavigationService::class.java))
            scenario.close()
        }
    }

    @Test
    fun foregroundServiceAutoArrivesFromMockGpsWhileScreenOff() = runBlocking {
        prepareDevice()
        application.stopService(Intent(application, NavigationService::class.java))
        NavigationRuntime.set(NavigationRuntimeState())
        val locationManager = installMockGpsProvider()
        val powerManager = application.getSystemService(Context.POWER_SERVICE) as PowerManager
        val stalePlan = fixturePlan(STALE_TERMINAL_TOUR_ID)
        application.container.tourRepository.save(
            stalePlan,
            NavigationProgress(tourId = stalePlan.id, state = NavigationState.COMPLETED),
        )
        ActiveNavigationStore.set(application, stalePlan.id)
        val plan = fixturePlan(AUTOMATIC_TOUR_ID)
        application.container.tourRepository.save(
            plan,
            NavigationProgress(tourId = plan.id, state = NavigationState.NAVIGATING),
        )
        val managesAirplaneMode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        if (managesAirplaneMode) {
            setAirplaneMode(true)
        } else {
            assertEquals("1", shell("settings get global airplane_mode_on").trim())
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            awaitCondition("foreground service did not start for automatic arrival") {
                NavigationRuntime.state.value.let { state ->
                    state.plan?.id == plan.id && state.isRunning &&
                        state.progress?.state == NavigationState.NAVIGATING
                }
            }
            shell("input keyevent 223")
            awaitCondition("emulator screen did not turn off") { !powerManager.isInteractive }

            injectMockGps(locationManager, plan.legs[0].to)
            awaitCondition("mock GPS did not advance to the second leg while the screen was off") {
                NavigationRuntime.state.value.progress?.let { progress ->
                    progress.legIndex == 1 && progress.state == NavigationState.NAVIGATING &&
                        FIRST_STOP_ID in progress.completedPointIds
                } == true
            }
            assertFalse(powerManager.isInteractive)
            assertNotNull(activeNavigationNotification())
            reportEvidence("GPS_FIX_1_ADVANCED_WHILE_SCREEN_OFF")

            wakeDevice()
            injectMockGps(locationManager, plan.legs[1].to)
            awaitCondition("mock GPS did not complete the second leg") {
                NavigationRuntime.state.value.progress?.state == NavigationState.COMPLETED
            }
            awaitCondition("automatic completion was not persisted") {
                application.container.tourRepository.get(plan.id)?.progress?.state == NavigationState.COMPLETED
            }
            val completed = application.container.tourRepository.get(plan.id)?.progress
            assertEquals(setOf(START_ID, FIRST_STOP_ID, SECOND_STOP_ID), completed?.completedPointIds)
            assertTrue(NavigationRuntime.state.value.errorMessage == null)
            reportEvidence("GPS_FIX_2_COMPLETED_AND_PERSISTED")
        } finally {
            if (!powerManager.isInteractive) wakeDevice()
            runCatching { locationManager.removeTestProvider(LocationManager.GPS_PROVIDER) }
            if (managesAirplaneMode) setAirplaneMode(false)
            application.stopService(Intent(application, NavigationService::class.java))
            scenario.close()
        }
    }

    private fun prepareDevice() {
        val packageName = application.packageName
        shell("settings put secure location_mode 3")
        shell("pm grant $packageName ${Manifest.permission.ACCESS_COARSE_LOCATION}")
        shell("pm grant $packageName ${Manifest.permission.ACCESS_FINE_LOCATION}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            shell("pm grant $packageName ${Manifest.permission.POST_NOTIFICATIONS}")
        }
        application.container.appSettingsStore.markOnboardingComplete()
        check(application.container.appSettingsStore.hasCompletedOnboarding())
    }

    private fun sendServiceAction(action: String) {
        application.startService(Intent(application, NavigationService::class.java).setAction(action))
    }

    @Suppress("DEPRECATION")
    private fun installMockGpsProvider(): LocationManager {
        val manager = application.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        runCatching { manager.removeTestProvider(LocationManager.GPS_PROVIDER) }
        manager.addTestProvider(
            LocationManager.GPS_PROVIDER,
            false,
            true,
            false,
            false,
            true,
            true,
            true,
            Criteria.POWER_LOW,
            Criteria.ACCURACY_FINE,
        )
        manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
        return manager
    }

    private fun injectMockGps(manager: LocationManager, point: GeoPoint) {
        manager.setTestProviderLocation(
            LocationManager.GPS_PROVIDER,
            Location(LocationManager.GPS_PROVIDER).apply {
                latitude = point.latitude
                longitude = point.longitude
                accuracy = 3f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            },
        )
    }

    private fun wakeDevice() {
        shell("input keyevent 224")
        shell("wm dismiss-keyguard")
    }

    private fun reportEvidence(message: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply { putString(Instrumentation.REPORT_KEY_STREAMRESULT, "$message\n") },
        )
    }

    private fun activeNavigationNotification(): Notification? {
        val manager = application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.activeNotifications
            .map { it.notification }
            .firstOrNull { notification ->
                notification.extras.getString(Notification.EXTRA_TITLE) == "巡礼手帳 · 连续导航" &&
                    notification.flags and Notification.FLAG_ONGOING_EVENT != 0
            }
    }

    private suspend fun awaitCondition(message: String, block: suspend () -> Boolean) {
        repeat(150) {
            if (block()) return
            delay(100L)
        }
        throw AssertionError(message)
    }

    private fun setAirplaneMode(enabled: Boolean) {
        shell("cmd connectivity airplane-mode ${if (enabled) "enable" else "disable"}")
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return descriptor.use {
            FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText() }
        }
    }

    private fun fixturePlan(id: String): TourPlan {
        val start = PilgrimagePoint(
            id = START_ID,
            name = "Runtime Start",
            coordinate = GeoPoint(35.681236, 139.767125),
        )
        val first = PilgrimagePoint(
            id = FIRST_STOP_ID,
            name = "Runtime Stop A",
            coordinate = GeoPoint(35.681900, 139.768000),
        )
        val second = PilgrimagePoint(
            id = SECOND_STOP_ID,
            name = "Runtime Stop B",
            coordinate = GeoPoint(35.682500, 139.769000),
        )
        val points = listOf(start, first, second)
        val legs = points.zipWithNext().mapIndexed { index, (from, to) ->
            TourLeg(
                from = from.coordinate,
                to = to.coordinate,
                mode = TravelMode.TRANSIT,
                geometry = listOf(from.coordinate, to.coordinate),
                steps = listOf(
                    RouteStep(
                        instruction = "Continue to ${to.name}",
                        distanceMeters = 120.0,
                        durationSeconds = 90.0,
                        coordinate = to.coordinate,
                    ),
                ),
                distanceMeters = 120.0,
                durationSeconds = 90.0,
                source = "Runtime fixture",
                destinationPointId = if (index == 0) FIRST_STOP_ID else SECOND_STOP_ID,
            )
        }
        return TourPlan(
            id = id,
            anime = Anime(subjectId = 1L, name = "Runtime Smoke Tour"),
            selectedPoints = points,
            orderedPoints = points,
            legs = legs,
            mode = TravelMode.TRANSIT,
            objective = RouteObjective.FASTEST,
            endPolicy = EndPolicy.OPEN,
            estimatedDurationSeconds = 180.0,
            attribution = listOf("Runtime fixture"),
            dwellMinutes = 0,
            initialStart = start.coordinate,
            regionDataVersion = TEST_REGION_DATA_VERSION,
        )
    }

    companion object {
        private const val RECOVERY_TOUR_ID = "runtime-recovery-fixture"
        private const val SERVICE_TOUR_ID = "runtime-service-fixture"
        private const val STALE_TERMINAL_TOUR_ID = "runtime-000-stale-terminal-fixture"
        private const val AUTOMATIC_TOUR_ID = "runtime-automatic-fixture"
        private const val START_ID = "runtime-start"
        private const val FIRST_STOP_ID = "runtime-stop-a"
        private const val SECOND_STOP_ID = "runtime-stop-b"
    }
}
