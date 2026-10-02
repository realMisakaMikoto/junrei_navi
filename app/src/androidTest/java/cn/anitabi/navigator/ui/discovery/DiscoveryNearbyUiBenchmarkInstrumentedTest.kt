package cn.anitabi.navigator.ui.discovery

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.anitabi.navigator.core.model.Anime
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.TerritoryRegion
import cn.anitabi.navigator.core.routing.TourOptimizer
import cn.anitabi.navigator.data.discovery.DISCOVERY_IMAGE_METADATA_VERSION
import cn.anitabi.navigator.data.discovery.DiscoveryCache
import cn.anitabi.navigator.data.discovery.DiscoveryLoadCounter
import cn.anitabi.navigator.data.discovery.DiscoveryLoadEvent
import cn.anitabi.navigator.data.discovery.DiscoveryLoadOutcome
import cn.anitabi.navigator.data.discovery.DiscoveryLoadPhase
import cn.anitabi.navigator.data.discovery.DiscoveryLoadTrace
import cn.anitabi.navigator.data.discovery.DiscoveryLoadTraceConfig
import cn.anitabi.navigator.data.discovery.DiscoveryPoint
import cn.anitabi.navigator.data.discovery.DiscoveryRepository
import cn.anitabi.navigator.data.discovery.DiscoverySnapshot
import cn.anitabi.navigator.data.discovery.DiscoverySource
import cn.anitabi.navigator.data.discovery.DiscoverySubject
import cn.anitabi.navigator.diagnostics.LocalDiscoveryTrace
import cn.anitabi.navigator.navigation.CurrentLocationProvider
import cn.anitabi.navigator.security.AppAppearance
import cn.anitabi.navigator.ui.discovery.map.DiscoveryCameraPosition
import cn.anitabi.navigator.ui.theme.AnitabiTheme
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Debug-to-Debug nearby UI probe only; never a Release loading or physical-location benchmark. */
class DiscoveryNearbyUiBenchmarkInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun twentyRelevantLocationChangesUseTheActualNearbyUiSort() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit nearby probe authorization required", args.getString("nearbyBenchmarkApproved") == "true")
        val checkpoint = requireNotNull(args.getString("nearbyBenchmarkCheckpoint"))
        require(checkpoint in setOf("B1", "B2"))
        val sourceSha = requireNotNull(args.getString("nearbyBenchmarkSourceSha"))
        require(sourceSha.matches(Regex("[0-9a-f]{40}")))
        val size = requireNotNull(args.getString("nearbyBenchmarkSize")).toInt()
        require(size in setOf(1_000, 10_000, 100_000))
        val context = ApplicationProvider.getApplicationContext<Context>()
        require(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Dedicated emulator required" }
        require(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) { "Debug target required" }
        val output = File(requireNotNull(context.getExternalFilesDir(null)), "frontend-fix-v2/nearby-ui-${checkpoint.lowercase()}-$size.json")
        require(!output.exists()) { "New nearby probe output required" }
        val fixture = Fixture(size)
        val owner = ViewModelStore()
        val displayed = mutableStateOf<DiscoveryViewModel?>(null)
        val attempts = mutableListOf<JsonObject>()
        var model: DiscoveryViewModel? = null
        var ownerJob: Job? = null
        var fullMembershipObserved = false
        var cleanupCompleted = false
        var failureCode: String? = null
        var windowWidth = 0
        var windowHeight = 0
        try {
            composeRule.runOnUiThread {
                model = DiscoveryViewModel(fixture.repository, object : DiscoveryCameraStore {
                    override fun lastCamera(): DiscoveryCameraPosition? = null
                    override fun saveCamera(camera: DiscoveryCameraPosition) = Unit
                }, object : CurrentLocationProvider {
                    override suspend fun currentLocation(): GeoPoint = fixture.location.get()
                }, { TerritoryRegion.OTHER }, SavedStateHandle(), trace = fixture.trace)
                owner.put("nearby-probe", requireNotNull(model))
                ownerJob = requireNotNull(model).viewModelScope.coroutineContext[Job]
                displayed.value = model
            }
            composeRule.setContent {
                CompositionLocalProvider(LocalDiscoveryTrace provides fixture.trace,
                    LocalUriHandler provides object : UriHandler {
                        override fun openUri(uri: String) { fixture.routeCalls.incrementAndGet(); error("External handoff forbidden") }
                    }) {
                    AnitabiTheme(appearance = AppAppearance.LIGHT) {
                        Box(Modifier.fillMaxSize().onSizeChanged { windowWidth = it.width; windowHeight = it.height }) {
                            displayed.value?.let { vm ->
                                val state by vm.state.collectAsState()
                                DiscoveryScreen(
                                    state = state.copy(listMode = true), selectedIds = emptySet(), privacyReady = true,
                                    imagesEnabled = true, darkTheme = false,
                                    onImagesEnabled = {}, onSearch = {}, onSettings = {}, onProvider = {}, onFilter = vm::toggleFilter,
                                    onLocate = { vm.locate() }, onResetBearing = {}, onListMode = {}, onBatchMode = vm::setBatchMode,
                                    onNearby = vm::setNearby, onRefresh = {}, onPoint = { _, _ -> }, onSubject = {}, onTogglePoint = {},
                                    onBackPanel = vm::backPanel, onPanelPresentation = vm::rememberPanel,
                                    onGroupByEpisode = vm::setGroupByEpisode, onFitAll = {}, onVisibleIds = {}, onOverlap = {},
                                    onCameraChanged = {}, onManualMove = {}, onUnavailable = {}, onSelectVisible = {},
                                    onClearSelection = {}, onPlan = { fixture.routeCalls.incrementAndGet(); error("Routing forbidden") },
                                    mapContent = { fixture.mapSlotCalls.incrementAndGet(); error("SDK map forbidden in list probe") },
                                )
                            }
                        }
                    }
                }
            }
            val vm = requireNotNull(model)
            composeRule.waitUntil(60_000) {
                vm.state.value.pointsById.size == size && !vm.state.value.dataPreparing && fixture.repository.isWorkQuiescent()
            }
            composeRule.runOnUiThread { vm.locate() }
            composeRule.waitUntil(60_000) { vm.state.value.location == fixture.location.get() && !vm.state.value.locating }
            composeRule.runOnUiThread { vm.setNearby(true) }
            awaitRows(vm, fixture, fixture.location.get(), fixture.firstName)
            // The final row exists beyond the first N lazy rows; this check happens before measured rounds.
            composeRule.onNodeWithTag("discovery-panel-list").performScrollToIndex(size)
            composeRule.waitUntil(60_000) { rowShown(fixture.lastName, distance(fixture.location.get(), fixture.lastCoordinate)) }
            composeRule.onNodeWithTag("discovery-panel-list").performScrollToIndex(0)
            awaitRows(vm, fixture, fixture.location.get(), fixture.firstName)
            fullMembershipObserved = true
            for (index in 1..20) {
                val nextLocation = GeoPoint(.9 - index * .002, 2.0)
                var started = 0L
                composeRule.runOnIdle {
                    require(fixture.trace.snapshot().pendingSpans.isEmpty()) { "Previous probe work pending" }
                    fixture.trace.reset()
                    synchronized(fixture.events) { fixture.events.clear() }
                    fixture.location.set(nextLocation)
                    started = System.nanoTime()
                    vm.locate()
                }
                var roundFailure: String? = null
                try { awaitRows(vm, fixture, nextLocation, fixture.firstName) }
                catch (_: Throwable) { roundFailure = "nearby_sort_or_ui_not_settled" }
                val uiUpperBound = System.nanoTime() - started
                val trace = fixture.trace.snapshot()
                val events = synchronized(fixture.events) { fixture.events.toList() }
                val observed = events.filter { it.first.outcome == DiscoveryLoadOutcome.OBSERVED && it.first.itemCount == size.toLong() }
                if (roundFailure == null && (events.size != 1 || observed.size != 1 || observed.single().first.durationNanos == null ||
                    trace.counters[DiscoveryLoadCounter.NEARBY_SORT_COUNT] != 1L ||
                    (trace.counters[DiscoveryLoadCounter.DISTANCE_COMPUTATION_COUNT] ?: -1) < size || trace.listenerFailureCount != 0L))
                    roundFailure = "paired_sort_timing_ambiguous_or_missing"
                if (roundFailure == null && (fixture.sourceCalls.get() != 0 || fixture.routeCalls.get() != 0 || fixture.mapSlotCalls.get() != 0))
                    roundFailure = "fixture_network_or_sdk_boundary_changed"
                attempts += buildJsonObject {
                    put("attemptedIndex", index); put("completed", roundFailure == null)
                    put("failureCode", roundFailure?.let(::JsonPrimitive) ?: JsonNull)
                    put("uiSettleUpperBoundNanos", uiUpperBound)
                    put("sortCount", trace.counters[DiscoveryLoadCounter.NEARBY_SORT_COUNT]?.let(::JsonPrimitive) ?: JsonNull)
                    put("distanceComputationCount", trace.counters[DiscoveryLoadCounter.DISTANCE_COMPUTATION_COUNT]?.let(::JsonPrimitive) ?: JsonNull)
                    put("currentFullMemberCount", vm.state.value.pointsById.size)
                    put("sortSpans", buildJsonArray { events.forEach { (event, main) -> add(buildJsonObject {
                        put("outcome", event.outcome.name)
                        put("durationNanos", event.durationNanos?.let(::JsonPrimitive) ?: JsonNull)
                        put("itemCount", event.itemCount?.let(::JsonPrimitive) ?: JsonNull)
                        put("listenerLane", if (main) "MAIN" else "BACKGROUND")
                    }) } })
                }
                if (roundFailure != null) { failureCode = roundFailure; break }
            }
        } catch (_: Throwable) { failureCode = "nearby_probe_precondition_or_execution_failed" }
        finally {
            try {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { displayed.value = null; owner.clear() }
            } catch (_: Throwable) { failureCode = "nearby_probe_cleanup_incomplete" }
            ownerJob?.cancel()
            fixture.job.cancel()
            try {
                runBlocking { withTimeout(10_000) { ownerJob?.join(); fixture.job.join() } }
                composeRule.waitForIdle()
                cleanupCompleted = true
            } catch (_: Throwable) { failureCode = "nearby_probe_cleanup_incomplete" }
            output.parentFile?.mkdirs()
            output.writeText(buildJsonObject {
                put("schemaVersion", 1); put("checkpoint", checkpoint); put("sourceSha", sourceSha)
                put("sourceBinding", "caller_verified_signed_debug_apk")
                put("buildMode", "DEBUG_INSTRUMENTATION"); put("releaseLoadingMatrix", false)
                put("evidenceScope", "isolated_repository_viewmodel_screen_synthetic_location")
                put("datasetSize", size); put("datasetSeed", 37); put("noImageReferenceCount", size)
                put("plannedAttempts", 20); put("attemptedCount", attempts.size)
                put("fullMembershipScrollObservedBeforeTiming", fullMembershipObserved)
                put("failureCode", failureCode?.let(::JsonPrimitive) ?: JsonNull)
                put("cleanupCompleted", cleanupCompleted)
                put("sourceCalls", fixture.sourceCalls.get()); put("routeAttemptCallbacks", fixture.routeCalls.get())
                put("mapSlotCalls", fixture.mapSlotCalls.get())
                put("api", Build.VERSION.SDK_INT); put("emulatorHardware", Build.HARDWARE)
                put("supportedAbis", buildJsonArray { Build.SUPPORTED_ABIS.forEach { add(JsonPrimitive(it)) } })
                put("windowWidth", windowWidth); put("windowHeight", windowHeight)
                put("densityDpi", context.resources.displayMetrics.densityDpi); put("fontScale", context.resources.configuration.fontScale)
                put("appearance", "LIGHT"); put("imagesEnabled", true)
                put("timingDefinition", "actual_nearby_sort_paired_span_and_instrumentation_ui_settle_upper_bound")
                put("attempts", buildJsonArray { attempts.forEach(::add) })
            }.toString())
        }
        assertTrue("Nearby UI probe incomplete; inspect scalar report", failureCode == null && cleanupCompleted && attempts.size == 20)
    }

    private fun awaitRows(vm: DiscoveryViewModel, fixture: Fixture, location: GeoPoint, name: String) {
        val expectedDistance = distance(location, fixture.firstCoordinate)
        composeRule.waitUntil(60_000) {
            vm.state.value.location == location && !vm.state.value.locating &&
                fixture.trace.snapshot().events.any { it.phase == DiscoveryLoadPhase.NEARBY_SORT && it.outcome == DiscoveryLoadOutcome.OBSERVED } &&
                rowShown(name, expectedDistance)
        }
        composeRule.waitForIdle()
    }

    private fun rowShown(name: String, distance: String): Boolean = composeRule.onAllNodes(
        SemanticsMatcher("current synthetic row and distance") { node ->
            val text = node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
            name in text && distance in text
        },
    ).fetchSemanticsNodes().any { it.boundsInRoot.width > 0 && it.boundsInRoot.height > 0 }

    private fun distance(location: GeoPoint, coordinate: GeoPoint) = straightLineDistance(TourOptimizer.haversineMeters(location, coordinate))

    private class Fixture(size: Int) {
        val job = SupervisorJob()
        val sourceCalls = AtomicInteger()
        val routeCalls = AtomicInteger()
        val mapSlotCalls = AtomicInteger()
        val location = AtomicReference(GeoPoint(.9, 2.0))
        val firstCoordinate = GeoPoint(1.0, 2.0)
        val lastCoordinate = GeoPoint(1.0 + (size - 1) * .000001, 2.0)
        val firstName = "SYNTHETIC_POINT_0"
        val lastName = "SYNTHETIC_POINT_${size - 1}"
        val events = mutableListOf<Pair<DiscoveryLoadEvent, Boolean>>()
        val trace = DiscoveryLoadTrace(DiscoveryLoadTraceConfig(enabled = true), listener = { event ->
            if (event.phase == DiscoveryLoadPhase.NEARBY_SORT) synchronized(events) {
                events += event to (Looper.myLooper() == Looper.getMainLooper())
            }
        })
        private val points = List(size) { index -> DiscoveryPoint(
            subjectId = 101L, rawId = "point-$index", coordinate = GeoPoint(1.0 + index * .000001, 2.0),
            name = "SYNTHETIC_POINT_$index", detailsVersion = "synthetic-nearby-v1", imageMetadataVersion = DISCOVERY_IMAGE_METADATA_VERSION,
        ) }.shuffled(Random(37))
        private val snapshot = DiscoverySnapshot(
            version = "synthetic-nearby-v1", modified = 100L, pageSize = 1,
            subjects = listOf(DiscoverySubject(Anime(101L, "SYNTHETIC_SUBJECT"), pointIds = points.map { it.id })),
            points = points, loadedPages = setOf(0), currentSubjectIds = setOf(101L),
            checkedAtMillis = System.currentTimeMillis(), endVersionVerified = true,
        )
        val repository = DiscoveryRepository(object : DiscoverySource {
            override suspend fun index(cacheToken: String): JsonElement = unexpectedSource()
            override suspend fun page(page: Int, cacheToken: String): JsonElement = unexpectedSource()
            override suspend fun subject(subjectId: Long): JsonElement = unexpectedSource()
            private fun unexpectedSource(): Nothing { sourceCalls.incrementAndGet(); error("Synthetic probe source request forbidden") }
        }, object : DiscoveryCache {
            override fun read() = snapshot
            override fun write(snapshot: DiscoverySnapshot) = Unit
        }, CoroutineScope(job + Dispatchers.Default), trace = trace)
    }
}
