package cn.anitabi.navigator.recovery

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import cn.anitabi.navigator.AnitabiApplication
import cn.anitabi.navigator.AppContainer
import cn.anitabi.navigator.BuildConfig
import cn.anitabi.navigator.MainActivity
import cn.anitabi.navigator.core.model.Anime
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.core.model.NavigationState
import cn.anitabi.navigator.core.model.PilgrimagePoint
import cn.anitabi.navigator.core.model.RouteObjective
import cn.anitabi.navigator.core.model.TerritoryRegion
import cn.anitabi.navigator.core.model.TravelMode
import cn.anitabi.navigator.core.routing.RoadRoute
import cn.anitabi.navigator.core.routing.RoadRoutingProvider
import cn.anitabi.navigator.core.routing.TransitJourney
import cn.anitabi.navigator.core.routing.TransitJourneyProvider
import cn.anitabi.navigator.core.routing.TransitJourneyQuery
import cn.anitabi.navigator.core.routing.TravelMatrix
import cn.anitabi.navigator.data.discovery.DiscoveryCache
import cn.anitabi.navigator.data.discovery.DiscoveryParser
import cn.anitabi.navigator.data.discovery.DiscoverySnapshot
import cn.anitabi.navigator.data.discovery.DiscoverySource
import cn.anitabi.navigator.security.AppAppearance
import cn.anitabi.navigator.ui.discovery.map.DiscoveryCameraPosition
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Explicit fixture APK only. Both process lifetimes run the normal production MainActivity. */
class PlannerRecoveryApplication : AnitabiApplication() {
    internal val harnessDirectory get() = File(filesDir, "planner-recovery-harness")
    internal val fixtureEnabled get() = BuildConfig.DEBUG && BuildConfig.DRAFT_RECOVERY_FIXTURE &&
        Build.HARDWARE in setOf("ranchu", "goldfish") && File(harnessDirectory, "enabled").isFile
    internal var activity = WeakReference<MainActivity>(null)
    @Volatile internal var activityStopped = false
    @Volatile internal var activityRestored = false
    @Volatile internal var savedStateCallbacks = 0

    override fun onCreate() {
        check(fixtureEnabled) { "Recovery fixture requires its explicit build flag, emulator and prepared sentinel" }
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(value: Activity, state: Bundle?) {
                if (value is MainActivity) {
                    activity = WeakReference(value)
                    activityRestored = state != null
                    activityStopped = false
                }
            }
            override fun onActivityStopped(value: Activity) { if (value is MainActivity) activityStopped = true }
            override fun onActivityResumed(value: Activity) { if (value is MainActivity) activityStopped = false }
            override fun onActivitySaveInstanceState(value: Activity, state: Bundle) {
                if (value is MainActivity) savedStateCallbacks += 1
            }
            override fun onActivityDestroyed(value: Activity) { if (activity.get() === value) activity.clear() }
            override fun onActivityStarted(value: Activity) = Unit
            override fun onActivityPaused(value: Activity) = Unit
        })
        super.onCreate()
    }

    override fun createContainer(): AppContainer {
        check(fixtureEnabled) { "Recovery fixture refuses a production container" }
        val result = AppContainer(
            context = this,
            classifyTerritoryOverride = { TerritoryRegion.OTHER },
            regionDataVersionOverride = { "TEST_ONLY_DRAFT_RECOVERY" },
            discoverySourceOverride = RecoveryDiscoverySource,
            discoveryCacheOverride = object : DiscoveryCache {
                private var value = RecoveryDiscoverySource.snapshot()
                @Synchronized override fun read(): DiscoverySnapshot = value
                @Synchronized override fun write(snapshot: DiscoverySnapshot) { value = snapshot }
            },
            roadProviderOverride = object : RoadRoutingProvider {
                override suspend fun matrix(mode: TravelMode, points: List<GeoPoint>, objective: RouteObjective): TravelMatrix = unexpectedRouting()
                override suspend fun directions(mode: TravelMode, points: List<GeoPoint>): RoadRoute = unexpectedRouting()
            },
            transitProviderOverride = object : TransitJourneyProvider {
                override suspend fun journey(from: GeoPoint, to: GeoPoint, query: TransitJourneyQuery): TransitJourney = unexpectedRouting()
            },
        )
        // Never erase unrelated saved journeys to make the fixture pass.
        runBlocking(Dispatchers.IO) {
            check(result.tourRepository.getSavedTours().none {
                it.progress?.state in setOf(NavigationState.NAVIGATING, NavigationState.ARRIVING, NavigationState.DWELLING, NavigationState.NEXT_STOP)
            }) { "Recovery fixture requires no ongoing saved navigation" }
        }
        check(!result.appSettingsStore.telemetryConsent().analyticsEnabled && !result.appSettingsStore.telemetryConsent().crashlyticsEnabled) {
            "Recovery fixture requires telemetry disabled"
        }
        result.appSettingsStore.markOnboardingComplete()
        result.appSettingsStore.setAppearance(AppAppearance.LIGHT)
        result.appSettingsStore.setAmapPrivacyConsent(false)
        result.discoveryPreferences.saveCamera(DiscoveryCameraPosition(GeoPoint(0.0, 0.0), 5f, 0f, 0f, MapProvider.AMAP))
        return result
    }

    @Synchronized internal fun routingAttemptCount(): Int = File(harnessDirectory, "routing-count").let {
        if (it.isFile) it.readText().toInt() else 0
    }

    @Synchronized private fun unexpectedRouting(): Nothing {
        val count = routingAttemptCount() + 1
        FileOutputStream(File(harnessDirectory, "routing-count")).use {
            it.write(count.toString().toByteArray(Charsets.US_ASCII))
            it.fd.sync()
        }
        // Counts attempts in both processes and fails before any backend/network call.
        error("Passive draft restoration attempted routing")
    }
}

internal object RecoveryDiscoverySource : DiscoverySource {
    val anime = Anime(9901, "TEST_ONLY_RECOVERY")
    val points = (0..3).map { index ->
        PilgrimagePoint("recovery_$index", "TEST_ONLY_POINT_$index", GeoPoint(index / 100.0, index / 100.0))
    }
    private val index = Json.parseToJsonElement(
        """[[[9901,"TEST_ONLY_RECOVERY",null,null,null,null,null,0,"TV",0,0,4,["recovery_0",0.0,0.0,0,"recovery_1",0.01,0.01,1,"recovery_2",0.02,0.02,2,"recovery_3",0.03,0.03,3],0,[],0,0,0]],1,100]""",
    )
    private val page = Json.parseToJsonElement(
        """[[9901,[],[["recovery_0","TEST_ONLY_POINT_0",null,0,0,0,null,null,null,null,null,null,null,null,0],["recovery_1","TEST_ONLY_POINT_1",null,0,0,0,null,null,null,null,null,null,null,null,0],["recovery_2","TEST_ONLY_POINT_2",null,0,0,0,null,null,null,null,null,null,null,null,0],["recovery_3","TEST_ONLY_POINT_3",null,0,0,0,null,null,null,null,null,null,null,null,0]],100]]""",
    )
    override suspend fun index(cacheToken: String): JsonElement = index
    override suspend fun page(page: Int, cacheToken: String): JsonElement { require(page == 0); return this.page }
    override suspend fun subject(subjectId: Long): JsonElement { require(subjectId == 9901L); return Json.parseToJsonElement("[]") }
    fun snapshot(): DiscoverySnapshot = DiscoveryParser.merge(DiscoveryParser.index(index), DiscoveryParser.page(page))
        .copy(loadedPages = setOf(0), endVersionVerified = true, checkedAtMillis = System.currentTimeMillis())
}
