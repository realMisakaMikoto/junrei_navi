package cn.anitabi.navigator.data.repository

import cn.anitabi.navigator.core.model.NavigationProgress
import cn.anitabi.navigator.core.model.NavigationState
import cn.anitabi.navigator.core.model.StoredTourV2
import cn.anitabi.navigator.core.model.TourPlan
import cn.anitabi.navigator.core.model.TransitExecutionStrategy
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.CoordinateSystem
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.core.model.TerritoryRegion
import cn.anitabi.navigator.core.model.TravelMode
import cn.anitabi.navigator.core.region.JapanRegion
import cn.anitabi.navigator.core.region.JourneyProviderResolutionException
import cn.anitabi.navigator.core.region.resolveJourneyProvider
import cn.anitabi.navigator.core.routing.classifyTransitExecutionStrategy
import cn.anitabi.navigator.data.local.TourPlanDao
import cn.anitabi.navigator.data.local.TourPlanEntity
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.NonCancellable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class TourRepository(
    private val dao: TourPlanDao,
    private val json: Json,
    private val now: () -> Long = System::currentTimeMillis,
    private val classifyRegion: (GeoPoint) -> JapanRegion = { JapanRegion.NON_JAPAN },
    private val classifyTerritory: ((GeoPoint) -> TerritoryRegion?)? = null,
    private val regionDataVersion: () -> String? = { null },
) {
    private val resolvedRoutes = ConcurrentHashMap<String, TourPlan>()
    private val resolvedProgress = ConcurrentHashMap<String, NavigationProgress>()
    private val writeMutex = Mutex()
    private val runtimeProgressVersion = AtomicLong()
    private val runtimeProgress = AtomicReference<RuntimeProgressStamp?>()

    suspend fun save(plan: TourPlan, progress: NavigationProgress? = null) = writeAtomically {
        persist(plan, progress)
        publishResolved(plan, progress)
    }

    suspend fun saveUnresolved(plan: TourPlan, progress: NavigationProgress? = null) = writeAtomically {
        persist(plan, progress)
        resolvedRoutes.remove(plan.id)
        resolvedProgress.remove(plan.id)
    }

    suspend fun saveActiveEditIfCurrent(
        expectedPlan: TourPlan,
        expectedProgress: NavigationProgress,
        updatedPlan: TourPlan,
        updatedProgress: NavigationProgress,
    ): Boolean = writeAtomically {
        require(expectedPlan.id == updatedPlan.id && expectedProgress.tourId == updatedPlan.id)
        require(updatedProgress.tourId == updatedPlan.id)
        val progressStampBefore = runtimeProgress.get()
        if (
            progressStampBefore?.tourId == updatedPlan.id &&
            progressStampBefore.progress != expectedProgress &&
            progressStampBefore.progress != updatedProgress
        ) {
            return@writeAtomically false
        }
        val stored = dao.get(updatedPlan.id)?.toStoredTour() ?: return@writeAtomically false
        if (
            !stored.matches(expectedPlan, expectedProgress) ||
            resolvedRoutes[updatedPlan.id]?.let { it != expectedPlan } == true ||
            resolvedProgress[updatedPlan.id]?.let {
                !it.matchesAfterStartNormalization(expectedProgress, stored.startPointId)
            } == true
        ) {
            return@writeAtomically false
        }
        persist(updatedPlan, updatedProgress)
        val progressStampAfter = runtimeProgress.get()
        if (progressStampAfter != progressStampBefore) {
            val latestProgress = progressStampAfter
                ?.takeIf { it.tourId == updatedPlan.id }
                ?.progress
                ?: expectedProgress
            persist(expectedPlan, latestProgress)
            publishResolved(expectedPlan, latestProgress)
            return@writeAtomically false
        }
        publishResolved(updatedPlan, updatedProgress)
        true
    }

    suspend fun saveProgressOnLatestPlan(
        basePlan: TourPlan,
        expectedProgress: NavigationProgress,
        updatedProgress: NavigationProgress,
    ): TourPlan = saveProgressResultOnLatestPlan(
        basePlan = basePlan,
        expectedProgress = expectedProgress,
        updatedProgress = updatedProgress,
    ).plan

    internal suspend fun saveProgressResultOnLatestPlan(
        basePlan: TourPlan,
        expectedProgress: NavigationProgress,
        updatedProgress: NavigationProgress,
    ): ProgressCommitResult = writeAtomically {
        commitProgressOnLatestPlan(basePlan, expectedProgress, updatedProgress, keepResolved = true)
    }

    suspend fun saveUnresolvedProgressOnLatestPlan(
        basePlan: TourPlan,
        expectedProgress: NavigationProgress,
        updatedProgress: NavigationProgress,
    ): TourPlan = writeAtomically {
        commitProgressOnLatestPlan(basePlan, expectedProgress, updatedProgress, keepResolved = false).plan
    }

    fun noteRuntimeProgress(progress: NavigationProgress) {
        while (true) {
            val current = runtimeProgress.get()
            if (current?.tourId == progress.tourId && current.progress == progress) return
            val updated = RuntimeProgressStamp(
                tourId = progress.tourId,
                progress = progress,
                version = runtimeProgressVersion.incrementAndGet(),
            )
            if (runtimeProgress.compareAndSet(current, updated)) return
        }
    }

    fun clearRuntimeProgress(tourId: String, expectedProgress: NavigationProgress? = null) {
        while (true) {
            val current = runtimeProgress.get() ?: return
            if (current.tourId != tourId || expectedProgress?.let { it != current.progress } == true) return
            if (runtimeProgress.compareAndSet(current, null)) return
        }
    }

    private suspend fun commitProgressOnLatestPlan(
        basePlan: TourPlan,
        expectedProgress: NavigationProgress,
        updatedProgress: NavigationProgress,
        keepResolved: Boolean,
    ): ProgressCommitResult {
        require(expectedProgress.tourId == basePlan.id && updatedProgress.tourId == basePlan.id)
        val stored = dao.get(basePlan.id)?.toStoredTour() ?: throw ConcurrentTourUpdateException()
        val latestProgress = resolvedProgress[basePlan.id]
        val latestPlan = resolvedRoutes[basePlan.id]
        if (
            stored.matches(basePlan, updatedProgress) &&
            latestPlan?.let { it != basePlan } != true &&
            latestProgress?.let {
                !it.matchesAfterStartNormalization(updatedProgress, stored.startPointId)
            } != true
        ) {
            if (keepResolved) {
                publishResolved(basePlan, updatedProgress)
            } else {
                resolvedRoutes.remove(basePlan.id)
                resolvedProgress.remove(basePlan.id)
            }
            return ProgressCommitResult(basePlan, updatedProgress)
        }
        val runtimeStamp = runtimeProgress.get()
        val priorityProgress = runtimeStamp
            ?.takeIf { it.tourId == basePlan.id && it.progress != expectedProgress }
            ?.progress
        if (
            latestPlan != null && latestPlan != basePlan &&
            latestProgress?.matchesAfterStartNormalization(expectedProgress, stored.startPointId) == true &&
            stored.matches(latestPlan, expectedProgress) &&
            priorityProgress != null
        ) {
            persist(basePlan, priorityProgress)
            if (keepResolved) {
                publishResolved(basePlan, priorityProgress)
            } else {
                resolvedRoutes.remove(basePlan.id)
                resolvedProgress.remove(basePlan.id)
            }
            return ProgressCommitResult(basePlan, priorityProgress)
        }
        if (!stored.matches(basePlan, expectedProgress)) {
            throw ConcurrentTourUpdateException()
        }
        if (
            latestProgress != null &&
            !latestProgress.matchesAfterStartNormalization(expectedProgress, stored.startPointId)
        ) {
            throw ConcurrentTourUpdateException()
        }
        if (latestPlan != null && latestPlan != basePlan) {
            throw ConcurrentTourUpdateException()
        }
        val committedPlan = latestPlan ?: basePlan
        persist(committedPlan, updatedProgress)
        if (keepResolved) {
            publishResolved(committedPlan, updatedProgress)
        } else {
            resolvedRoutes.remove(committedPlan.id)
            resolvedProgress.remove(committedPlan.id)
        }
        return ProgressCommitResult(committedPlan, updatedProgress)
    }

    private suspend fun <T> writeAtomically(block: suspend () -> T): T = writeMutex.withLock {
        withContext(NonCancellable) { block() }
    }

    private fun publishResolved(plan: TourPlan, progress: NavigationProgress?) {
        if (progress == null) {
            resolvedProgress.remove(plan.id)
        } else {
            resolvedProgress[plan.id] = progress
        }
        resolvedRoutes[plan.id] = plan
    }

    private suspend fun persist(plan: TourPlan, progress: NavigationProgress?) {
        val stored = StoredTourV2.from(plan, progress)
        val entity = TourPlanEntity(
            id = plan.id,
            storedTourJson = json.encodeToString(StoredTourV2.serializer(), stored),
            legacyPlanJson = null,
            legacyProgressJson = null,
            migrationError = null,
            routeNeedsRefresh = true,
            updatedAtEpochMillis = now(),
        )
        try {
            dao.upsert(entity)
        } catch (failure: Throwable) {
            resolvedRoutes.remove(plan.id)
            resolvedProgress.remove(plan.id)
            throw failure
        }
    }

    suspend fun get(id: String): SavedTour? = readConsistently { dao.get(id)?.toSavedTour() }

    suspend fun getMostRecent(): SavedTour? = readConsistently { dao.getMostRecent()?.toSavedTour() }

    suspend fun getMostRecentInStates(states: Set<NavigationState>): SavedTour? = readConsistently {
        findMostRecentInStates(states)
    }

    suspend fun getMostRecentInStatesNewerThan(
        states: Set<NavigationState>,
        tourId: String,
    ): SavedTour? = readConsistently {
        findMostRecentInStates(states, stopAtTourId = tourId)
    }

    private suspend fun findMostRecentInStates(
        states: Set<NavigationState>,
        stopAtTourId: String? = null,
    ): SavedTour? {
        for (id in dao.getIdsMostRecentFirst()) {
            if (id == stopAtTourId) return null
            val entity = dao.get(id) ?: continue
            val stored = entity.toStoredTour() ?: continue
            if (stored.navigationState !in states) continue
            return entity.toSavedTour(stored)
        }
        return null
    }

    suspend fun getMostRecentRecoveryError(): String? = readConsistently { dao.getMostRecentMigrationError() }

    private suspend fun <T> readConsistently(block: suspend () -> T): T = writeMutex.withLock { block() }

    private suspend fun TourPlanEntity.toSavedTour(): SavedTour? =
        toStoredTour()?.let { toSavedTour(it) }

    private fun TourPlanEntity.toSavedTour(stored: StoredTourV2): SavedTour {
        val routing = try {
            resolveStoredRouting(stored)
        } catch (exception: JourneyProviderResolutionException) {
            return unresolvedSavedTour(stored, StoredRoutingError.fromCode(exception.code))
        } catch (exception: StoredRoutingResolutionException) {
            return unresolvedSavedTour(stored, exception.error)
        }
        val resolved = resolvedRoutes[id]
            ?.takeIf { plan ->
                plan.executionStrategy == routing.strategy &&
                    plan.mapProvider == routing.provider &&
                    plan.regionDataVersion == routing.regionDataVersion &&
                    plan.hasValidResolvedCoordinates(routing.provider)
            }
            ?.copy(executionStrategy = routing.strategy)
        return SavedTour(
            storedTour = stored,
            plan = resolved ?: stored.toUnresolvedPlan(
                resolvedExecutionStrategy = routing.strategy,
                resolvedMapProvider = routing.provider,
                resolvedRegionDataVersion = routing.regionDataVersion,
            ),
            progress = if (resolved == null) {
                stored.toNavigationProgress(routing.strategy)
            } else {
                resolvedProgress[id] ?: stored.toNavigationProgress(routing.strategy)
            },
            routeNeedsRefresh = resolved == null,
        )
    }

    private fun unresolvedSavedTour(
        stored: StoredTourV2,
        error: StoredRoutingError,
    ): SavedTour {
        val hint = stored.storedRoutingHint()
        return SavedTour(
            storedTour = stored,
            plan = stored.toUnresolvedPlan(
                resolvedExecutionStrategy = hint.strategy,
                resolvedMapProvider = hint.provider,
                resolvedRegionDataVersion = hint.regionDataVersion,
            ),
            progress = stored.toNavigationProgress(hint.strategy),
            routeNeedsRefresh = true,
            routingError = error,
        )
    }

    private suspend fun TourPlanEntity.toStoredTour(): StoredTourV2? =
        storedTourJson?.let { json.decodeFromString(StoredTourV2.serializer(), it) }
            ?: migrateLegacyTour()

    private fun StoredTourV2.matches(
        expectedPlan: TourPlan,
        expectedProgress: NavigationProgress,
    ): Boolean {
        val routing = runCatching { resolveStoredRouting(this) }.getOrElse { return false }
        if (
            routing.strategy != expectedPlan.executionStrategy ||
            routing.provider != expectedPlan.mapProvider
        ) return false
        val expected = StoredTourV2.from(expectedPlan, expectedProgress)
        val restoredPlan = toUnresolvedPlan(
            resolvedExecutionStrategy = routing.strategy,
            resolvedMapProvider = routing.provider,
            resolvedRegionDataVersion = routing.regionDataVersion,
        )
        val restoredProgress = toNavigationProgress(routing.strategy)
        val normalizedCompletedPointIds = startPointId
            ?.takeIf { it in expected.completedPointIds }
            ?.let { completedPointIds + it }
            ?: completedPointIds
        val normalized = copy(
            departureTime = null,
            transitTimeMode = restoredPlan.transitTimeMode,
            transitAnchorTime = restoredPlan.transitAnchorTime,
            completedPointIds = normalizedCompletedPointIds,
            activePointId = activePointId ?: expected.activePointId,
            activeLegIndex = restoredProgress.legIndex,
            executionStrategy = routing.strategy,
            mapProvider = routing.provider,
            regionDataVersion = routing.regionDataVersion,
        )
        return normalized == expected
    }

    private fun NavigationProgress.matchesAfterStartNormalization(
        expected: NavigationProgress,
        deterministicStartPointId: String?,
    ): Boolean {
        val normalized = deterministicStartPointId
            ?.takeIf { it in expected.completedPointIds }
            ?.let { copy(completedPointIds = completedPointIds + it) }
            ?: this
        return normalized == expected
    }

    private suspend fun TourPlanEntity.migrateLegacyTour(): StoredTourV2? {
        val legacyPlan = legacyPlanJson ?: return null
        return runCatching {
            val plan = json.decodeFromString(TourPlan.serializer(), legacyPlan)
            val progress = legacyProgressJson?.let {
                json.decodeFromString(NavigationProgress.serializer(), it)
            }
            StoredTourV2.from(plan, progress)
        }.onSuccess { stored ->
            dao.finishLegacyMigration(
                id = id,
                storedTourJson = json.encodeToString(StoredTourV2.serializer(), stored),
                updatedAtEpochMillis = now(),
            )
        }.onFailure {
            dao.recordMigrationError(id, RECOVERY_ERROR_MESSAGE)
        }.getOrNull()
    }

    private fun resolveStoredRouting(stored: StoredTourV2): StoredRoutingResolution {
        val territoryClassifier = classifyTerritory
        if (territoryClassifier == null) {
            val strategy = stored.executionStrategy ?: if (stored.mode == TravelMode.TRANSIT) {
                classifyTransitExecutionStrategy(stored.selectedPoints, classifyRegion)
            } else {
                TransitExecutionStrategy.IN_APP_GOOGLE_ROUTES
            }
            return StoredRoutingResolution(
                strategy = strategy,
                provider = stored.mapProvider ?: if (
                    strategy == TransitExecutionStrategy.EXTERNAL_AMAP_MAINLAND
                ) {
                    MapProvider.AMAP
                } else {
                    MapProvider.GOOGLE
                },
                regionDataVersion = stored.regionDataVersion,
            )
        }
        val resolved = resolveJourneyProvider(
            start = stored.start,
            destinations = stored.selectedPoints.map { it.coordinate },
            mode = stored.mode,
            classify = territoryClassifier,
        )
        val strategy = when {
            resolved.provider == MapProvider.AMAP -> TransitExecutionStrategy.EXTERNAL_AMAP_MAINLAND
            stored.mode == TravelMode.TRANSIT && resolved.regions == setOf(TerritoryRegion.JAPAN) ->
                TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN
            else -> TransitExecutionStrategy.IN_APP_GOOGLE_ROUTES
        }
        val currentRegionDataVersion = regionDataVersion()?.takeIf(String::isNotBlank)
            ?: throw StoredRoutingResolutionException(StoredRoutingError.REGION_DATA_OUTDATED)
        return StoredRoutingResolution(
            strategy = strategy,
            provider = resolved.provider,
            regionDataVersion = currentRegionDataVersion,
        )
    }

    companion object {
        const val RECOVERY_ERROR_MESSAGE = "无法恢复 v0.2.0 行程；原记录已保留，请勿清除应用数据"
    }
}

private fun TourPlan.hasValidResolvedCoordinates(provider: MapProvider): Boolean = when (provider) {
    MapProvider.GOOGLE ->
        !externalRouteFallback &&
            coordinateSystem == CoordinateSystem.WGS84 &&
            legs.all { leg ->
                leg.provider == MapProvider.GOOGLE && leg.coordinateSystem == CoordinateSystem.WGS84
            }
    MapProvider.AMAP -> if (externalRouteFallback) {
        executionStrategy == TransitExecutionStrategy.EXTERNAL_AMAP_MAINLAND &&
            coordinateSystem == CoordinateSystem.WGS84 &&
            estimatedDurationSeconds == 0.0 &&
            departureTime == null &&
            arrivalTime == null &&
            transitAnchorTime == null &&
            legs.all { leg ->
                leg.provider == MapProvider.AMAP &&
                    leg.coordinateSystem == CoordinateSystem.WGS84 &&
                    leg.geometry.isEmpty() &&
                    leg.steps.isEmpty() &&
                    leg.transit == null &&
                    leg.distanceMeters == 0.0 &&
                    leg.durationSeconds == 0.0
            }
    } else {
        coordinateSystem == CoordinateSystem.GCJ02 &&
            legs.all { leg ->
                leg.provider == MapProvider.AMAP && leg.coordinateSystem == CoordinateSystem.GCJ02
            }
    }
}

private data class StoredRoutingResolution(
    val strategy: TransitExecutionStrategy,
    val provider: MapProvider,
    val regionDataVersion: String?,
)

private fun StoredTourV2.storedRoutingHint(): StoredRoutingResolution {
    val strategy = executionStrategy ?: TransitExecutionStrategy.IN_APP_GOOGLE_ROUTES
    return StoredRoutingResolution(
        strategy = strategy,
        provider = mapProvider ?: if (strategy == TransitExecutionStrategy.EXTERNAL_AMAP_MAINLAND) {
            MapProvider.AMAP
        } else {
            MapProvider.GOOGLE
        },
        regionDataVersion = regionDataVersion,
    )
}

enum class StoredRoutingError {
    REGION_UNRESOLVED,
    MIXED_MAP_PROVIDERS,
    MIXED_TRANSIT_REGIONS,
    REGION_DATA_OUTDATED;

    companion object {
        fun fromCode(code: String): StoredRoutingError =
            entries.firstOrNull { it.name == code } ?: REGION_UNRESOLVED
    }
}

private class StoredRoutingResolutionException(
    val error: StoredRoutingError,
) : IllegalStateException(error.name)

private data class RuntimeProgressStamp(
    val tourId: String,
    val progress: NavigationProgress,
    val version: Long,
)

class ConcurrentTourUpdateException : IllegalStateException("行程状态已变化，请重试")

internal data class ProgressCommitResult(
    val plan: TourPlan,
    val progress: NavigationProgress,
)

data class SavedTour(
    val storedTour: StoredTourV2,
    val plan: TourPlan,
    val progress: NavigationProgress?,
    val routeNeedsRefresh: Boolean,
    val routingError: StoredRoutingError? = null,
)
