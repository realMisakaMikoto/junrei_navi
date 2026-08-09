package cn.anitabi.navigator.ui.planner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.anitabi.navigator.core.model.Anime
import cn.anitabi.navigator.core.model.EndPolicy
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.core.model.PilgrimagePoint
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
import cn.anitabi.navigator.navigation.CurrentLocationProvider
import cn.anitabi.navigator.navigation.LocationUnavailableException
import cn.anitabi.navigator.navigation.MissingLocationPermissionException
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
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
) : ViewModel() {
    private val mutableState = MutableStateFlow(PlannerUiState())
    val state: StateFlow<PlannerUiState> = mutableState.asStateFlow()
    private var planningJob: Job? = null
    private var planningGeneration = 0L
    private var pendingAmapExternalFallback: AmapExternalFallbackRequest? = null

    fun configure(anime: Anime, points: List<PilgrimagePoint>) {
        require(points.size >= 2)
        cancelPlanning()
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
    }

    fun setMode(mode: TravelMode) {
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
    }

    fun setObjective(objective: RouteObjective) {
        pendingAmapExternalFallback = null
        mutableState.update {
            if (it.isLoading) it
            else it.copy(objective = objective, plan = null, amapExternalFallbackAvailable = false)
        }
    }

    fun setEndPolicy(policy: EndPolicy) {
        pendingAmapExternalFallback = null
        mutableState.update {
            if (it.isLoading) it
            else it.copy(endPolicy = policy, plan = null, amapExternalFallbackAvailable = false)
        }
    }

    fun setStartPoint(pointId: String) {
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
    }

    fun setUseCurrentLocation() {
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
        pendingAmapExternalFallback = null
        mutableState.update {
            if (it.isLoading) it
            else it.copy(fixedEndPointId = pointId, plan = null, amapExternalFallbackAvailable = false)
        }
    }

    fun setTransitSchedule(mode: TransitTimeMode, date: LocalDate, time: LocalTime) {
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
    }

    fun setTransitRoutingPreference(preference: TransitRoutingPreference) {
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
    }

    fun toggleTransitTravelMode(mode: TransitTravelMode) {
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
    }

    fun setDwellMinutes(value: String) {
        pendingAmapExternalFallback = null
        mutableState.update {
            if (it.isLoading) it
            else it.copy(
                dwellMinutesInput = value.filter(Char::isDigit).take(3),
                plan = null,
                amapExternalFallbackAvailable = false,
            )
        }
    }

    fun generate() {
        val current = state.value
        if (current.isLoading) return
        val anime = current.anime ?: return
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
                        val now = currentTransitPlanningTime(clock)
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
        mutableState.update { current ->
            if (current.isLoading) return@update current
            val order = current.draftOrder.toMutableList()
            if (!current.canMove(fromIndex, toIndex)) return@update current
            order.add(toIndex, order.removeAt(fromIndex))
            pendingAmapExternalFallback = null
            current.copy(
                draftOrder = order,
                orderChanged = true,
                amapExternalFallbackAvailable = false,
            )
        }
    }

    fun applyManualOrder() {
        val current = state.value
        if (current.isLoading) return
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
                draftOrder = emptyList(),
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
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlannerViewModel(planner, repository, locationProvider) as T
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
    val draftOrder: List<PilgrimagePoint> = emptyList(),
    val orderChanged: Boolean = false,
    val isLoading: Boolean = false,
    val plannedTransitSegments: Int = 0,
    val totalTransitSegments: Int = 0,
    val errorMessage: String? = null,
    val unavailableRouteSegment: UnavailableRouteSegment? = null,
    val amapExternalFallbackAvailable: Boolean = false,
) {
    fun transitSegmentCount(): Int =
        selectedPoints.size -
            (if (
                startPointId != null &&
                transitExecutionStrategy != TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN
            ) 1 else 0) +
            (if (endPolicy == EndPolicy.RETURN_TO_START) 1 else 0)
}

private const val MIXED_TRANSIT_REGION_FALLBACK = "不支持此操作，请去除日本或日本以外的点。"

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
