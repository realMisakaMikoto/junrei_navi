package cn.anitabi.navigator.ui.planner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.anitabi.navigator.core.model.Anime
import cn.anitabi.navigator.core.model.EndPolicy
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.core.model.NavigationState
import cn.anitabi.navigator.core.model.PilgrimagePoint
import cn.anitabi.navigator.core.model.PlannerDraft
import cn.anitabi.navigator.core.model.RouteObjective
import cn.anitabi.navigator.core.model.TourPlan
import cn.anitabi.navigator.core.model.TransitExecutionStrategy
import cn.anitabi.navigator.core.model.TransitRoutingPreference
import cn.anitabi.navigator.core.model.TransitTimeMode
import cn.anitabi.navigator.core.model.TransitTravelMode
import cn.anitabi.navigator.core.model.TravelMode
import cn.anitabi.navigator.core.routing.AmapExternalFallbackRequest
import cn.anitabi.navigator.core.routing.NoRouteException
import cn.anitabi.navigator.core.routing.MixedTransitRegionException
import cn.anitabi.navigator.core.routing.MapProviderUnavailableException
import cn.anitabi.navigator.core.routing.REGION_DATA_ERROR_MESSAGE
import cn.anitabi.navigator.core.routing.RoadPlanRequest
import cn.anitabi.navigator.core.routing.TourPlanner
import cn.anitabi.navigator.core.routing.TransitPlanRequest
import cn.anitabi.navigator.core.routing.TransitRideUnavailableException
import cn.anitabi.navigator.core.routing.TransitSegmentUnavailableException
import cn.anitabi.navigator.core.routing.formatTransitDepartureTime
import cn.anitabi.navigator.core.region.JapanRegionDataException
import cn.anitabi.navigator.core.region.JourneyProviderResolutionException
import cn.anitabi.navigator.core.region.TerritoryRegionDataException
import cn.anitabi.navigator.data.network.ApiException
import cn.anitabi.navigator.data.repository.TourRepository
import cn.anitabi.navigator.data.repository.ConcurrentTourUpdateException
import cn.anitabi.navigator.data.repository.SavedTour
import cn.anitabi.navigator.data.repository.PlannerDraftRepository
import cn.anitabi.navigator.data.repository.PlannerDraftProblem
import cn.anitabi.navigator.navigation.CurrentLocationProvider
import cn.anitabi.navigator.navigation.LocationUnavailableException
import cn.anitabi.navigator.navigation.MissingLocationPermissionException
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.ZoneId
import java.util.UUID
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PlannerViewModel(
    private val planner: TourPlanner,
    private val repository: TourRepository,
    private val locationProvider: CurrentLocationProvider,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val draftRepository: PlannerDraftRepository? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(PlannerUiState())
    val state: StateFlow<PlannerUiState> = mutableState.asStateFlow()
    private var planningJob: Job? = null
    private var planningGeneration = 0L
    private var pendingAmapExternalFallback: AmapExternalFallbackRequest? = null
    private var restoredTour: SavedTour? = null
    private var restoreGeneration = 0L
    private var restoringDraftId: String? = null
    private var appliedDraftRevision = -1L

    fun restoreDraft(draftId: String) {
        if (restoringDraftId == draftId) return
        if (state.value.draftId == draftId && state.value.draftRecoveryError == null &&
            appliedDraftRevision == draftRepository?.state?.value?.revision) return
        val drafts = draftRepository ?: run { showDraftProblem(PlannerDraftProblem.MISSING); return }
        cancelPlanning()
        val generation = ++restoreGeneration
        restoringDraftId = draftId
        mutableState.value = PlannerUiState(isRestoringDraft = true)
        viewModelScope.launch {
            try {
                val loaded = drafts.awaitLoaded()
                if (generation != restoreGeneration) return@launch
                val draft = loaded.draft?.takeIf { it.draftId == draftId }
                if (draft == null || draft.selectedPoints.size < 2) {
                    showDraftProblem(loaded.problem ?: PlannerDraftProblem.MISSING)
                    return@launch
                }
                val saved = draft.sourceTourId?.let { repository.get(it) }
                if (generation != restoreGeneration) return@launch
                if (drafts.state.value.draft != draft) {
                    showDraftProblem(PlannerDraftProblem.MISSING)
                    return@launch
                }
                appliedDraftRevision = drafts.state.value.revision
                if (draft.sourceTourId != null && saved == null) {
                    showDraftProblem(PlannerDraftProblem.MISSING)
                    return@launch
                }
                if (saved != null && !draft.matchesSavedInputs(saved)) {
                    showDraftProblem(PlannerDraftProblem.MISSING)
                    return@launch
                }
                restoredTour = saved
                val pointsById = draft.selectedPoints.associateBy(PilgrimagePoint::id)
                val progress = saved?.let { it.progress ?: it.storedTour.toNavigationProgress(it.plan.executionStrategy) }
                val blocked = when {
                    saved?.routingError != null -> "当前地区资料无法安全恢复这份行程，请更新应用后重试"
                    progress != null && progress.state !in setOf(NavigationState.PLANNED, NavigationState.COMPLETED, NavigationState.ENDED) ->
                        "行程已有导航进度，请返回行程页继续导航；恢复时会安全刷新剩余路线"
                    else -> null
                }
                mutableState.value = PlannerUiState(
                    draftId = draft.draftId,
                    anime = draft.displayAnime,
                    selectedPoints = draft.selectedPoints,
                    mode = draft.mode,
                    objective = draft.objective,
                    endPolicy = draft.endPolicy,
                    startPointId = draft.startPointId,
                    useCurrentLocation = draft.useCurrentLocation,
                    fixedEndPointId = draft.fixedEndPointId,
                    dwellMinutesInput = draft.dwellMinutesInput,
                    transitTimeMode = draft.transitTimeMode,
                    transitDate = LocalDate.parse(draft.transitDate),
                    transitTime = LocalTime.parse(draft.transitTime),
                    transitZoneId = draft.transitZoneId,
                    transitRoutingPreference = draft.transitRoutingPreference,
                    transitTravelModes = draft.transitTravelModes,
                    draftOrder = draft.manualOrderPointIds.map(pointsById::getValue),
                    manualOrderRequested = draft.manualOrderRequested,
                    restoredTourId = draft.sourceTourId,
                    restoredNavigationState = progress?.state,
                    errorMessage = blocked,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == restoreGeneration) showDraftProblem(PlannerDraftProblem.CORRUPT)
            } finally {
                if (generation == restoreGeneration) restoringDraftId = null
            }
        }
    }

    suspend fun prepareSavedDraft(saved: SavedTour): String? {
        configureSaved(saved)
        return flushDraft()
    }

    suspend fun flushDraft(): String? {
        val id = state.value.draftId ?: return null
        if (draftRepository?.flush(id) != true) {
            mutableState.update { it.copy(errorMessage = "草稿未能保存，请检查设备存储后重试") }
            return null
        }
        return id
    }

    suspend fun finishDraft(): Boolean {
        val drafts = draftRepository ?: return true
        val id = state.value.draftId ?: return true
        drafts.clear(id)
        return drafts.flush()
    }

    private fun showDraftProblem(problem: PlannerDraftProblem) {
        restoredTour = null
        val message = when (problem) {
            PlannerDraftProblem.CORRUPT -> "草稿数据损坏，请返回选点重新规划；已保存行程不受影响"
            PlannerDraftProblem.UNSUPPORTED -> "此草稿来自不支持的版本，请返回选点重新规划"
            PlannerDraftProblem.WRITE_FAILED -> "草稿无法读取，请返回选点并检查设备存储"
            PlannerDraftProblem.MISSING -> DRAFT_MISSING_MESSAGE
        }
        mutableState.value = PlannerUiState(draftRecoveryError = message, errorMessage = message)
    }

    private fun persistNewDraft(selectedAnimes: List<Anime>) {
        val drafts = draftRepository ?: return
        val current = state.value
        val anime = current.anime ?: return
        val draft = current.toDraft(
            PlannerDraft(
                draftId = UUID.randomUUID().toString(),
                selectedAnimes = selectedAnimes,
                displayAnime = anime,
                selectedPoints = current.selectedPoints,
                transitDate = current.transitDate.toString(),
                transitTime = current.transitTime.toString(),
                transitZoneId = current.transitZoneId,
                sourceTourId = current.restoredTourId,
                savedStart = restoredTour?.storedTour?.start,
            ),
        )
        drafts.replace(draft)
        appliedDraftRevision = drafts.state.value.revision
        mutableState.update { it.copy(draftId = draft.draftId) }
    }

    private fun persistDraftInputs() {
        restoreGeneration += 1
        val id = state.value.draftId ?: return
        draftRepository?.update(id) { state.value.toDraft(it).withValidEndpointOrder() }
        draftRepository?.state?.value?.draft?.takeIf { it.draftId == id }?.let { draft ->
            if (state.value.manualOrderRequested) {
                val points = state.value.selectedPoints.associateBy(PilgrimagePoint::id)
                mutableState.update { it.copy(draftOrder = draft.manualOrderPointIds.map(points::getValue)) }
            }
        }
        appliedDraftRevision = draftRepository?.state?.value?.revision ?: -1
    }

    fun configure(anime: Anime, points: List<PilgrimagePoint>) {
        require(points.size >= 2)
        restoreGeneration += 1
        restoringDraftId = null
        cancelPlanning()
        restoredTour = null
        pendingAmapExternalFallback = null
        val now = ZonedDateTime.now(clock).withSecond(0).withNano(0)
        mutableState.value = PlannerUiState(
            anime = anime,
            selectedPoints = points,
            startPointId = points.first().id,
            fixedEndPointId = points.last().id,
            transitDate = now.toLocalDate(),
            transitTime = now.toLocalTime(),
            transitZoneId = clock.zone.id,
        )
        persistNewDraft(listOf(anime))
    }

    fun configureSaved(saved: SavedTour) {
        restoreGeneration += 1
        restoringDraftId = null
        cancelPlanning()
        restoredTour = saved
        val stored = saved.storedTour
        val unresolved = stored.toUnresolvedPlan(
            resolvedExecutionStrategy = saved.plan.executionStrategy,
            resolvedMapProvider = saved.plan.mapProvider,
            resolvedRegionDataVersion = saved.plan.regionDataVersion,
        )
        val anchor = unresolved.transitAnchorTime?.let {
            runCatching { OffsetDateTime.parse(it).atZoneSameInstant(clock.zone) }.getOrNull()
        } ?: ZonedDateTime.now(clock).withSecond(0).withNano(0)
        val progress = saved.progress ?: stored.toNavigationProgress(saved.plan.executionStrategy)
        mutableState.value = PlannerUiState(
            anime = stored.displayAnime,
            selectedPoints = stored.selectedPoints,
            mode = stored.mode,
            objective = stored.objective,
            endPolicy = stored.endPolicy,
            startPointId = stored.startPointId,
            fixedEndPointId = stored.fixedEndPointId,
            transitTimeMode = unresolved.transitTimeMode,
            transitDate = anchor.toLocalDate(),
            transitTime = anchor.toLocalTime(),
            transitZoneId = clock.zone.id,
            transitRoutingPreference = stored.transitRoutingPreference,
            transitTravelModes = stored.transitTravelModes,
            transitExecutionStrategy = unresolved.executionStrategy,
            dwellMinutesInput = stored.dwellMinutes.toString(),
            draftOrder = unresolved.orderedPoints,
            restoredTourId = stored.id,
            restoredNavigationState = progress.state,
        )
        persistNewDraft(stored.selectedAnimes)
        if (saved.routingError != null) {
            mutableState.update {
                it.copy(errorMessage = "当前地区资料无法安全恢复这份行程，请更新应用后重试")
            }
            return
        }
        if (progress.state !in setOf(NavigationState.PLANNED, NavigationState.COMPLETED, NavigationState.ENDED)) {
            mutableState.update {
                it.copy(errorMessage = "行程已有导航进度，请返回行程页继续导航；恢复时会安全刷新剩余路线")
            }
            return
        }
    }

    private fun refreshSaved(saved: SavedTour) {
        val stored = saved.storedTour
        val unresolved = stored.toUnresolvedPlan(
            resolvedExecutionStrategy = saved.plan.executionStrategy,
            resolvedMapProvider = saved.plan.mapProvider,
            resolvedRegionDataVersion = saved.plan.regionDataVersion,
        )
        val progress = saved.progress ?: stored.toNavigationProgress(saved.plan.executionStrategy)
        if (saved.routingError != null) {
            mutableState.update {
                it.copy(errorMessage = "当前地区资料无法安全恢复这份行程，请更新应用后重试")
            }
            return
        }
        if (progress.state !in setOf(NavigationState.PLANNED, NavigationState.COMPLETED, NavigationState.ENDED)) {
            mutableState.update {
                it.copy(errorMessage = "行程已有导航进度，请返回行程页继续导航；恢复时会安全刷新剩余路线")
            }
            return
        }
        mutableState.update { it.copy(isLoading = true) }
        val generation = ++planningGeneration
        planningJob = viewModelScope.launch {
            try {
                val planForRebuild = if (
                    unresolved.mode == TravelMode.TRANSIT && unresolved.transitTimeMode == TransitTimeMode.NOW
                ) {
                    unresolved.copy(
                        departureTime = formatTransitDepartureTime(currentTransitPlanningTime(clock).toOffsetDateTime()),
                    )
                } else {
                    unresolved
                }
                val refreshed = planner.rebuild(planForRebuild, unresolved.orderedPoints)
                if (generation != planningGeneration) return@launch
                val savedCurrent = repository.publishRefreshedRouteIfCurrent(
                    expected = saved,
                    refreshedPlan = refreshed,
                )
                if (generation != planningGeneration) return@launch
                if (!savedCurrent) throw ConcurrentTourUpdateException()
                restoredTour = saved.copy(plan = refreshed, progress = progress, routeNeedsRefresh = false)
                mutableState.update {
                    it.copy(plan = refreshed, draftOrder = refreshed.orderedPoints, isLoading = false)
                }
            } catch (exception: Exception) {
                if (generation == planningGeneration) handleFailure(exception)
                else if (exception is CancellationException) throw exception
            } finally {
                if (generation == planningGeneration) planningJob = null
            }
        }
    }

    fun setMode(mode: TravelMode) {
        if (state.value.isRestoringDraft) return
        pendingAmapExternalFallback = null
        mutableState.update { current ->
            if (current.isLoading) current
            else {
                val classification = if (
                    mode == TravelMode.TRANSIT &&
                    current.transitExecutionStrategy == null &&
                    current.transitRegionError == null
                ) {
                    runCatching { planner.transitExecutionStrategy(current.selectedPoints) }
                } else {
                    null
                }
                val transitRegionError = classification?.exceptionOrNull()?.let(::plannerFailureMessage)
                    ?: current.transitRegionError
                current.copy(
                    mode = mode,
                    transitExecutionStrategy = classification?.getOrNull() ?: current.transitExecutionStrategy,
                    transitRegionError = transitRegionError,
                    errorMessage = if (mode == TravelMode.TRANSIT) transitRegionError else null,
                    unavailableRouteSegment = null,
                    amapExternalFallbackAvailable = false,
                    plan = null,
                )
            }
        }
        persistDraftInputs()
    }

    fun setObjective(objective: RouteObjective) {
        if (state.value.isRestoringDraft) return
        pendingAmapExternalFallback = null
        mutableState.update {
            if (it.isLoading) it
            else it.copy(objective = objective, plan = null, amapExternalFallbackAvailable = false)
        }
        persistDraftInputs()
    }

    fun setEndPolicy(policy: EndPolicy) {
        if (state.value.isRestoringDraft) return
        pendingAmapExternalFallback = null
        mutableState.update {
            if (it.isLoading) it
            else it.copy(endPolicy = policy, plan = null, amapExternalFallbackAvailable = false)
        }
        persistDraftInputs()
    }

    fun setStartPoint(pointId: String) {
        if (state.value.isRestoringDraft) return
        pendingAmapExternalFallback = null
        mutableState.update { current ->
            if (current.isLoading) return@update current
            val fixedEnd = if (current.fixedEndPointId == pointId) {
                current.selectedPoints.lastOrNull { it.id != pointId }?.id
            } else {
                current.fixedEndPointId
            }
            current.copy(
                startPointId = pointId,
                fixedEndPointId = fixedEnd,
                useCurrentLocation = false,
                plan = null,
                amapExternalFallbackAvailable = false,
            )
        }
        persistDraftInputs()
    }

    fun setUseCurrentLocation() {
        if (state.value.isRestoringDraft) return
        pendingAmapExternalFallback = null
        mutableState.update {
            if (it.isLoading) it
            else it.copy(
                useCurrentLocation = true,
                startPointId = null,
                plan = null,
                errorMessage = null,
                unavailableRouteSegment = null,
                amapExternalFallbackAvailable = false,
            )
        }
        persistDraftInputs()
    }

    fun locationPermissionDenied() {
        pendingAmapExternalFallback = null
        mutableState.update {
            it.copy(
                errorMessage = "需要定位权限才能从当前位置出发",
                unavailableRouteSegment = null,
                amapExternalFallbackAvailable = false,
            )
        }
    }

    fun navigationPermissionDenied(message: String) {
        mutableState.update { it.copy(errorMessage = message, unavailableRouteSegment = null) }
    }

    fun setFixedEndPoint(pointId: String) {
        if (state.value.isRestoringDraft) return
        pendingAmapExternalFallback = null
        mutableState.update {
            if (it.isLoading) it
            else it.copy(fixedEndPointId = pointId, plan = null, amapExternalFallbackAvailable = false)
        }
        persistDraftInputs()
    }

    fun setTransitSchedule(mode: TransitTimeMode, date: LocalDate, time: LocalTime) {
        if (state.value.isRestoringDraft) return
        pendingAmapExternalFallback = null
        mutableState.update {
            if (it.isLoading) it else it.copy(
                transitTimeMode = mode,
                transitDate = date,
                transitTime = time,
                plan = null,
                errorMessage = null,
                unavailableRouteSegment = null,
                amapExternalFallbackAvailable = false,
            )
        }
        persistDraftInputs()
    }

    fun setTransitRoutingPreference(preference: TransitRoutingPreference) {
        if (state.value.isRestoringDraft) return
        pendingAmapExternalFallback = null
        mutableState.update {
            if (it.isLoading) it
            else it.copy(
                transitRoutingPreference = preference,
                plan = null,
                errorMessage = null,
                unavailableRouteSegment = null,
                amapExternalFallbackAvailable = false,
            )
        }
        persistDraftInputs()
    }

    fun toggleTransitTravelMode(mode: TransitTravelMode) {
        if (state.value.isRestoringDraft) return
        pendingAmapExternalFallback = null
        mutableState.update {
            if (it.isLoading) it
            else it.copy(
                transitTravelModes = toggledTransitTravelModes(it.transitTravelModes, mode),
                plan = null,
                errorMessage = null,
                unavailableRouteSegment = null,
                amapExternalFallbackAvailable = false,
            )
        }
        persistDraftInputs()
    }

    fun setDwellMinutes(value: String) {
        if (state.value.isRestoringDraft) return
        pendingAmapExternalFallback = null
        mutableState.update {
            if (it.isLoading) it
            else it.copy(
                dwellMinutesInput = value.filter(Char::isDigit).take(3),
                plan = null,
                amapExternalFallbackAvailable = false,
            )
        }
        persistDraftInputs()
    }

    fun generate() {
        val current = state.value
        if (current.isLoading || current.isRestoringDraft) return
        restoredTour?.let {
            refreshSaved(it)
            return
        }
        val anime = current.anime
        if (anime == null || current.selectedPoints.size < 2 || current.draftRecoveryError != null) {
            mutableState.update { it.copy(errorMessage = it.draftRecoveryError ?: DRAFT_MISSING_MESSAGE) }
            return
        }
        if (current.mode == TravelMode.TRANSIT && current.transitRegionError != null) {
            mutableState.update { it.copy(errorMessage = current.transitRegionError) }
            return
        }
        pendingAmapExternalFallback = null
        mutableState.update {
            it.copy(
                isLoading = true,
                errorMessage = null,
                unavailableRouteSegment = null,
                plannedTransitSegments = 0,
                totalTransitSegments = if (current.mode == TravelMode.TRANSIT) current.transitSegmentCount() else 0,
                amapExternalFallbackAvailable = false,
            )
        }
        val generation = ++planningGeneration
        planningJob?.cancel()
        planningJob = viewModelScope.launch {
            var fallbackRequest: AmapExternalFallbackRequest? = null
            try {
                val startPoint = current.startPointId?.let { id -> current.selectedPoints.single { it.id == id } }
                val startCoordinate = if (current.useCurrentLocation) {
                    locationProvider.currentLocation()
                } else {
                    requireNotNull(startPoint).coordinate
                }
                val executionStrategy = planner.executionStrategy(
                    mode = current.mode,
                    start = startCoordinate,
                    points = current.selectedPoints,
                )
                if (generation == planningGeneration && current.mode == TravelMode.TRANSIT) {
                    mutableState.update { it.copy(transitExecutionStrategy = executionStrategy) }
                }
                if (executionStrategy == TransitExecutionStrategy.EXTERNAL_AMAP_MAINLAND) {
                    fallbackRequest = current.toAmapExternalFallbackRequest(
                        anime = anime,
                        start = startCoordinate,
                        startPointId = startPoint?.id,
                    )
                }
                val plan = if (current.mode == TravelMode.TRANSIT) {
                    val anchorTime = if (
                        executionStrategy != TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN
                    ) {
                        val now = currentTransitPlanningTime(clock.withZone(ZoneId.of(current.transitZoneId)))
                        formatTransitDepartureTime(
                            resolveTransitAnchor(
                                mode = current.transitTimeMode,
                                date = current.transitDate,
                                time = current.transitTime,
                                now = now,
                            ).toOffsetDateTime(),
                        )
                    } else {
                        null
                    }
                    planner.planTransit(
                        TransitPlanRequest(
                            anime = anime,
                            selectedPoints = current.selectedPoints,
                            start = startCoordinate,
                            startPointId = startPoint?.id,
                            endPolicy = current.endPolicy,
                            fixedEndPointId = current.fixedEndPointId,
                            manualOrderPointIds = current.draftOrder.map(PilgrimagePoint::id).takeIf { current.manualOrderRequested },
                            timeMode = if (
                                executionStrategy == TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN
                            ) {
                                TransitTimeMode.NOW
                            } else {
                                current.transitTimeMode
                            },
                            anchorTime = anchorTime,
                            routingPreference = current.transitRoutingPreference,
                            transitTravelModes = current.transitTravelModes,
                            dwellMinutes = current.dwellMinutesInput.toIntOrNull() ?: 15,
                        ),
                    ) { completed, total ->
                        if (generation == planningGeneration) {
                            mutableState.update {
                                it.copy(plannedTransitSegments = completed, totalTransitSegments = total)
                            }
                        }
                    }
                } else {
                    planner.planRoad(
                        RoadPlanRequest(
                            anime = anime,
                            selectedPoints = current.selectedPoints,
                            start = startCoordinate,
                            startPointId = startPoint?.id,
                            mode = current.mode,
                            objective = current.objective,
                            endPolicy = current.endPolicy,
                            fixedEndPointId = current.fixedEndPointId,
                            manualOrderPointIds = current.draftOrder.map(PilgrimagePoint::id).takeIf { current.manualOrderRequested },
                        ),
                    )
                }
                if (generation != planningGeneration) return@launch
                repository.save(plan)
                if (generation != planningGeneration) return@launch
                pendingAmapExternalFallback = null
                mutableState.update {
                    it.copy(
                        plan = plan,
                        draftOrder = plan.orderedPoints,
                        isLoading = false,
                        plannedTransitSegments = 0,
                        totalTransitSegments = 0,
                        amapExternalFallbackAvailable = false,
                    )
                }
            } catch (exception: Exception) {
                if (generation == planningGeneration) handleFailure(exception, fallbackRequest)
                else if (exception is CancellationException) throw exception
            } finally {
                if (generation == planningGeneration) planningJob = null
            }
        }
    }

    fun cancelPlanning() {
        planningGeneration += 1
        planningJob?.cancel()
        planningJob = null
        pendingAmapExternalFallback = null
        mutableState.update {
            if (!it.isLoading) {
                it.copy(amapExternalFallbackAvailable = false)
            } else {
                it.copy(
                    isLoading = false,
                    plannedTransitSegments = 0,
                    totalTransitSegments = 0,
                    amapExternalFallbackAvailable = false,
                )
            }
        }
    }

    fun moveDraft(fromIndex: Int, toIndex: Int) {
        if (state.value.isRestoringDraft) return
        mutableState.update { current ->
            if (current.isLoading || current.restoredTourId != null) return@update current
            val order = current.draftOrder.toMutableList()
            if (!current.canMove(fromIndex, toIndex)) return@update current
            order.add(toIndex, order.removeAt(fromIndex))
            pendingAmapExternalFallback = null
            current.copy(
                draftOrder = order,
                orderChanged = true,
                manualOrderRequested = true,
                amapExternalFallbackAvailable = false,
            )
        }
        persistDraftInputs()
    }

    fun applyManualOrder() {
        val current = state.value
        if (current.isLoading || current.restoredTourId != null) return
        val plan = current.plan ?: return
        pendingAmapExternalFallback = null
        mutableState.update {
            it.copy(
                isLoading = true,
                errorMessage = null,
                unavailableRouteSegment = null,
                amapExternalFallbackAvailable = false,
            )
        }
        val generation = ++planningGeneration
        planningJob?.cancel()
        planningJob = viewModelScope.launch {
            val fallbackRequest = if (plan.mapProvider == MapProvider.AMAP) {
                AmapExternalFallbackRequest(
                    anime = plan.anime,
                    selectedPoints = plan.selectedPoints,
                    orderedPoints = current.draftOrder,
                    start = plan.initialStart ?: plan.legs.firstOrNull()?.from
                        ?: current.draftOrder.first().coordinate,
                    startPointId = current.startPointId.takeUnless { current.useCurrentLocation },
                    mode = plan.mode,
                    objective = plan.objective,
                    endPolicy = plan.endPolicy,
                    fixedEndPointId = current.fixedEndPointId,
                    dwellMinutes = plan.dwellMinutes,
                )
            } else {
                null
            }
            try {
                val planForRebuild = if (
                    plan.mode == TravelMode.TRANSIT && plan.transitTimeMode == TransitTimeMode.NOW
                ) {
                    plan.copy(
                        departureTime = formatTransitDepartureTime(
                            currentTransitPlanningTime(clock).toOffsetDateTime(),
                        ),
                    )
                } else {
                    plan
                }
                val updated = planner.rebuild(planForRebuild, current.draftOrder)
                if (generation != planningGeneration) return@launch
                repository.save(updated)
                if (generation == planningGeneration) {
                    pendingAmapExternalFallback = null
                    mutableState.update {
                        it.copy(
                            plan = updated,
                            draftOrder = updated.orderedPoints,
                            orderChanged = false,
                            isLoading = false,
                            amapExternalFallbackAvailable = false,
                        )
                    }
                }
            } catch (exception: Exception) {
                if (generation == planningGeneration) handleFailure(exception, fallbackRequest)
                else if (exception is CancellationException) throw exception
            } finally {
                if (generation == planningGeneration) planningJob = null
            }
        }
    }

    fun useAmapExternalFallback() {
        val request = pendingAmapExternalFallback ?: return
        if (state.value.isLoading || !state.value.amapExternalFallbackAvailable) return
        pendingAmapExternalFallback = null
        mutableState.update {
            it.copy(
                isLoading = true,
                errorMessage = null,
                unavailableRouteSegment = null,
                amapExternalFallbackAvailable = false,
            )
        }
        val generation = ++planningGeneration
        planningJob?.cancel()
        planningJob = viewModelScope.launch {
            try {
                val fallback = planner.planAmapExternalFallback(request)
                if (generation != planningGeneration) return@launch
                repository.save(fallback)
                if (generation != planningGeneration) return@launch
                mutableState.update {
                    it.copy(
                        plan = fallback,
                        draftOrder = fallback.orderedPoints,
                        orderChanged = false,
                        isLoading = false,
                        plannedTransitSegments = 0,
                        totalTransitSegments = 0,
                    )
                }
            } catch (exception: Exception) {
                if (generation == planningGeneration) handleFailure(exception)
                else if (exception is CancellationException) throw exception
            } finally {
                if (generation == planningGeneration) planningJob = null
            }
        }
    }

    fun clearPlan() {
        pendingAmapExternalFallback = null
        mutableState.update {
            if (it.isLoading) it
            else it.copy(
                plan = null,
                draftOrder = if (it.manualOrderRequested) it.draftOrder else emptyList(),
                orderChanged = false,
                errorMessage = null,
                unavailableRouteSegment = null,
                amapExternalFallbackAvailable = false,
            )
        }
    }

    private fun PlannerUiState.canMove(fromIndex: Int, toIndex: Int): Boolean {
        if (fromIndex !in draftOrder.indices || toIndex !in draftOrder.indices) return false
        val startLocked = draftOrder.firstOrNull()?.id == startPointId
        if (startLocked && (fromIndex == 0 || toIndex == 0)) return false
        if (endPolicy == EndPolicy.FIXED && (fromIndex == draftOrder.lastIndex || toIndex == draftOrder.lastIndex)) {
            return false
        }
        return true
    }

    private fun handleFailure(
        throwable: Throwable,
        fallbackRequest: AmapExternalFallbackRequest? = null,
    ) {
        if (throwable is CancellationException) throw throwable
        val offer = fallbackRequest?.takeIf { isAmapExternalFallbackFailure(throwable) }
        pendingAmapExternalFallback = offer
        mutableState.update {
            it.copy(
                isLoading = false,
                errorMessage = plannerFailureMessage(throwable),
                unavailableRouteSegment = unavailableRouteSegmentDetails(throwable, it),
                plannedTransitSegments = 0,
                totalTransitSegments = 0,
                amapExternalFallbackAvailable = offer != null,
            )
        }
    }

    class Factory(
        private val planner: TourPlanner,
        private val repository: TourRepository,
        private val locationProvider: CurrentLocationProvider,
        private val draftRepository: PlannerDraftRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlannerViewModel(planner, repository, locationProvider, draftRepository = draftRepository) as T
    }
}

private fun PlannerUiState.toAmapExternalFallbackRequest(
    anime: Anime,
    start: GeoPoint,
    startPointId: String?,
): AmapExternalFallbackRequest {
    val selectedStart = startPointId?.let { id -> selectedPoints.singleOrNull { it.id == id } }
    val destinations = selectedPoints.filterNot { it.id == selectedStart?.id }
    val fixedEnd = if (endPolicy == EndPolicy.FIXED) {
        destinations.singleOrNull { it.id == fixedEndPointId }
    } else {
        null
    }
    val orderedPoints = listOfNotNull(selectedStart) +
        destinations.filterNot { it.id == fixedEnd?.id } +
        listOfNotNull(fixedEnd)
    return AmapExternalFallbackRequest(
        anime = anime,
        selectedPoints = selectedPoints,
        orderedPoints = orderedPoints,
        start = start,
        startPointId = startPointId,
        mode = mode,
        objective = objective,
        endPolicy = endPolicy,
        fixedEndPointId = fixedEndPointId,
        dwellMinutes = dwellMinutesInput.toIntOrNull() ?: 15,
    )
}

internal fun isAmapExternalFallbackFailure(throwable: Throwable): Boolean = when (throwable) {
    is ApiException.BackendUnavailable,
    is ApiException.Network,
    is ApiException.Server,
    is ApiException.UpstreamUnavailable,
    is ApiException.QuotaExhausted,
    is ApiException.RateLimited,
    is ApiException.NoRoute,
    is NoRouteException,
    is TransitSegmentUnavailableException,
    is TransitRideUnavailableException -> true
    else -> false
}

internal fun plannerFailureMessage(throwable: Throwable): String = when (throwable) {
    is ApiException.Unauthenticated -> "匿名连接失败，请检查网络后重试"
    is ApiException.InvalidArgument -> "路线请求参数无效，请检查时间和地点"
    is ApiException.NoRoute, is ApiException.NotFound -> "所选地点之间暂无可用路线"
    is ApiException.QuotaExhausted -> "本月共享路线额度已用尽，暂时无法查询；不会继续产生费用"
    is ApiException.RateLimited -> "请求过于频繁，请稍后再试"
    is ApiException.UpstreamUnavailable -> "路线提供方暂时不可用，请稍后再试"
    is ApiException.MixedMapProviders,
    is JourneyProviderResolutionException.MixedMapProviders -> "一次行程的起点和所有目的地必须使用同一地图提供方"
    is ApiException.MixedTransitRegions,
    is JourneyProviderResolutionException.MixedTransitRegions -> MIXED_TRANSIT_REGION_FALLBACK
    is ApiException.RegionUnresolved,
    is JourneyProviderResolutionException.RegionUnresolved -> "无法安全判定地图地区，未发送路线请求"
    is ApiException.RegionDataOutdated -> "地区数据版本与服务端不一致，请更新后重试"
    is MapProviderUnavailableException -> when (throwable.provider) {
        cn.anitabi.navigator.core.model.MapProvider.AMAP -> "请先在“关于”中允许高德地图 SDK，再规划该地区的路线"
        cn.anitabi.navigator.core.model.MapProvider.GOOGLE -> "Google 地图提供方当前不可用"
    }
    is ApiException.ClientUpgradeRequired -> "当前版本已不再受路线服务支持，请更新应用"
    is ApiException.BackendUnavailable, is ApiException.Server ->
        "路线服务暂时不可用；行程和导航进度仍保留在本机"
    is ApiException.InvalidResponse -> "路线服务返回了无法识别的数据"
    is ApiException.InvalidCredentials, is ApiException.Forbidden, is ApiException.Http ->
        "路线请求失败，请稍后再试"
    is ApiException.Network -> "无法连接路线服务；当前网络出口可能被拦截，请切换网络后重试"
    is TransitSegmentUnavailableException ->
        "第 ${throwable.segmentNumber}/${throwable.segmentCount} 段在所选时间未找到公交或步行路线，请调整时间、顺序或出行方式"
    is TransitRideUnavailableException ->
        "公交路线没有返回任何乘车线路，未将全步行路线作为公交方案；日本路线还可能受 Google Routes API 官方覆盖限制"
    is MixedTransitRegionException -> throwable.message ?: MIXED_TRANSIT_REGION_FALLBACK
    is JapanRegionDataException, is TerritoryRegionDataException -> REGION_DATA_ERROR_MESSAGE
    is InvalidTransitScheduleException -> throwable.message ?: "请选择当前或未来 100 天内的时间"
    is NoRouteException -> "所选地点之间存在不可达路段"
    is MissingLocationPermissionException -> "需要定位权限才能从当前位置出发"
    is LocationUnavailableException -> "暂时无法取得当前位置，请检查系统定位开关"
    else -> throwable.message ?: "路线生成失败"
}

internal fun resolveTransitAnchor(
    mode: TransitTimeMode,
    date: LocalDate,
    time: LocalTime,
    now: ZonedDateTime,
): ZonedDateTime {
    if (mode == TransitTimeMode.NOW) return now
    val selected = LocalDateTime.of(date, time).atZone(now.zone)
    if (selected.toInstant().isBefore(now.toInstant().minus(7, ChronoUnit.DAYS))) {
        throw InvalidTransitScheduleException("公交路线最多可查询过去 7 天")
    }
    if (selected.toInstant().isAfter(now.toInstant().plus(100, ChronoUnit.DAYS))) {
        throw InvalidTransitScheduleException("公交路线最多可查询未来 100 天")
    }
    return selected
}

class InvalidTransitScheduleException(message: String) : IllegalArgumentException(message)

internal fun currentTransitPlanningTime(clock: Clock): ZonedDateTime = ZonedDateTime.now(clock)

internal val allTransitTravelModes = listOf(
    TransitTravelMode.BUS,
    TransitTravelMode.SUBWAY,
    TransitTravelMode.TRAIN,
    TransitTravelMode.LIGHT_RAIL,
)

internal fun selectedTransitTravelModes(storedModes: Set<TransitTravelMode>): Set<TransitTravelMode> =
    if (storedModes.isEmpty()) allTransitTravelModes.toSet() else storedModes

internal fun toggledTransitTravelModes(
    storedModes: Set<TransitTravelMode>,
    toggledMode: TransitTravelMode,
): Set<TransitTravelMode> {
    val selected = selectedTransitTravelModes(storedModes).toMutableSet()
    if (toggledMode in selected) {
        if (selected.size == 1) return storedModes
        selected -= toggledMode
    } else {
        selected += toggledMode
    }
    return if (selected.size == allTransitTravelModes.size && selected.containsAll(allTransitTravelModes)) {
        emptySet()
    } else {
        selected
    }
}

data class PlannerUiState(
    val draftId: String? = null,
    val draftRecoveryError: String? = null,
    val isRestoringDraft: Boolean = false,
    val anime: Anime? = null,
    val selectedPoints: List<PilgrimagePoint> = emptyList(),
    val mode: TravelMode = TravelMode.WALK,
    val objective: RouteObjective = RouteObjective.FASTEST,
    val endPolicy: EndPolicy = EndPolicy.OPEN,
    val startPointId: String? = null,
    val useCurrentLocation: Boolean = false,
    val fixedEndPointId: String? = null,
    val transitTimeMode: TransitTimeMode = TransitTimeMode.NOW,
    val transitDate: LocalDate = LocalDate.of(1970, 1, 1),
    val transitTime: LocalTime = LocalTime.MIDNIGHT,
    val transitZoneId: String = "UTC",
    val transitRoutingPreference: TransitRoutingPreference = TransitRoutingPreference.RECOMMENDED,
    val transitTravelModes: Set<TransitTravelMode> = emptySet(),
    val transitExecutionStrategy: TransitExecutionStrategy? = null,
    val transitRegionError: String? = null,
    val dwellMinutesInput: String = "15",
    val plan: TourPlan? = null,
    val restoredTourId: String? = null,
    val restoredNavigationState: NavigationState? = null,
    val draftOrder: List<PilgrimagePoint> = emptyList(),
    val orderChanged: Boolean = false,
    val manualOrderRequested: Boolean = false,
    val isLoading: Boolean = false,
    val plannedTransitSegments: Int = 0,
    val totalTransitSegments: Int = 0,
    val errorMessage: String? = null,
    val unavailableRouteSegment: UnavailableRouteSegment? = null,
    val amapExternalFallbackAvailable: Boolean = false,
) {
    val canGenerate: Boolean
        get() = !isLoading && !isRestoringDraft && draftRecoveryError == null && anime != null && selectedPoints.size >= 2 &&
            (restoredNavigationState == null || restoredNavigationState in setOf(NavigationState.PLANNED, NavigationState.COMPLETED, NavigationState.ENDED))

    fun transitSegmentCount(): Int =
        selectedPoints.size -
            (if (
                startPointId != null &&
                transitExecutionStrategy != TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN
            ) 1 else 0) +
            (if (endPolicy == EndPolicy.RETURN_TO_START) 1 else 0)
}

private const val MIXED_TRANSIT_REGION_FALLBACK = "不支持此操作，请去除日本或日本以外的点。"
private const val DRAFT_MISSING_MESSAGE = "未找到可恢复的规划草稿，请返回选点后重新规划"

private fun PlannerUiState.toDraft(previous: PlannerDraft): PlannerDraft = previous.copy(
    mode = mode,
    objective = objective,
    endPolicy = endPolicy,
    startPointId = startPointId,
    useCurrentLocation = useCurrentLocation,
    fixedEndPointId = fixedEndPointId,
    manualOrderPointIds = (draftOrder.ifEmpty { selectedPoints }).map(PilgrimagePoint::id),
    manualOrderRequested = manualOrderRequested,
    dwellMinutesInput = dwellMinutesInput,
    transitTimeMode = transitTimeMode,
    transitDate = transitDate.toString(),
    transitTime = transitTime.toString(),
    transitZoneId = transitZoneId,
    transitRoutingPreference = transitRoutingPreference,
    transitTravelModes = transitTravelModes,
)

private fun PlannerDraft.matchesSavedInputs(saved: SavedTour): Boolean {
    val stored = saved.storedTour
    val anchor = saved.plan.transitAnchorTime?.takeIf { mode == TravelMode.TRANSIT }?.let {
        OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.of(transitZoneId))
    }
    // Road plans retain the model's legacy DEPART_AT default without having a transit anchor.
    val scheduleMatches = mode != TravelMode.TRANSIT || transitTimeMode == TransitTimeMode.NOW ||
        anchor != null && anchor.toLocalDate() == LocalDate.parse(transitDate) && anchor.toLocalTime() == LocalTime.parse(transitTime)
    return sourceTourId == stored.id && selectedAnimes == stored.selectedAnimes &&
        selectedPoints == stored.selectedPoints && manualOrderPointIds == saved.plan.orderedPoints.map(PilgrimagePoint::id) &&
        savedStart == stored.start && startPointId == stored.startPointId && mode == stored.mode &&
        objective == stored.objective && endPolicy == stored.endPolicy && fixedEndPointId == stored.fixedEndPointId &&
        dwellMinutesInput == stored.dwellMinutes.toString() && transitTimeMode == saved.plan.transitTimeMode && scheduleMatches &&
        transitRoutingPreference == stored.transitRoutingPreference && transitTravelModes == stored.transitTravelModes
}

data class UnavailableRouteSegment(
    val segmentNumber: Int,
    val segmentCount: Int,
    val origin: UnavailableRouteEndpoint,
    val destination: UnavailableRouteEndpoint,
)

data class UnavailableRouteEndpoint(
    val name: String,
    val coordinate: GeoPoint,
)

internal fun unavailableRouteSegmentDetails(
    throwable: Throwable,
    state: PlannerUiState,
): UnavailableRouteSegment? {
    val failure = throwable as? TransitSegmentUnavailableException ?: return null

    fun endpoint(coordinate: GeoPoint): UnavailableRouteEndpoint {
        val point = state.selectedPoints.firstOrNull { it.coordinate == coordinate }
        val name = point?.name ?: if (state.useCurrentLocation) "当前位置" else "行程起点"
        return UnavailableRouteEndpoint(name = name, coordinate = coordinate)
    }

    return UnavailableRouteSegment(
        segmentNumber = failure.segmentNumber,
        segmentCount = failure.segmentCount,
        origin = endpoint(failure.from),
        destination = endpoint(failure.to),
    )
}
