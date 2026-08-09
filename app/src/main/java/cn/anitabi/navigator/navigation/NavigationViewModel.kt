package cn.anitabi.navigator.navigation

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.anitabi.navigator.core.model.EndPolicy
import cn.anitabi.navigator.core.model.NavigationProgress
import cn.anitabi.navigator.core.model.NavigationState
import cn.anitabi.navigator.core.model.PilgrimagePoint
import cn.anitabi.navigator.core.model.TourPlan
import cn.anitabi.navigator.core.model.TransitExecutionStrategy
import cn.anitabi.navigator.core.model.TravelMode
import cn.anitabi.navigator.core.model.isExternalMapNavigation
import cn.anitabi.navigator.core.region.JapanRegionDataException
import cn.anitabi.navigator.core.region.TerritoryRegionDataException
import cn.anitabi.navigator.core.routing.ActiveTourEditException
import cn.anitabi.navigator.core.routing.MixedTransitRegionException
import cn.anitabi.navigator.core.routing.REGION_DATA_ERROR_MESSAGE
import cn.anitabi.navigator.core.routing.TourPlanner
import cn.anitabi.navigator.core.routing.editActiveTourFuture
import cn.anitabi.navigator.data.repository.SavedTour
import cn.anitabi.navigator.data.repository.StoredRoutingError
import cn.anitabi.navigator.data.repository.TourRepository
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

class NavigationViewModel(
    application: Application,
    private val repository: TourRepository,
    private val planner: TourPlanner,
) : AndroidViewModel(application) {
    val state: StateFlow<NavigationRuntimeState> = NavigationRuntime.state
    private val mutableEditState = MutableStateFlow(ActiveTourEditUiState())
    val editState: StateFlow<ActiveTourEditUiState> = mutableEditState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                val storedActiveTourId = ActiveNavigationStore.get(context)
                NavigationRuntime.state.value.takeIf { it.isRunning }?.let { running ->
                    running.plan?.id?.let { ActiveNavigationStore.set(context, it) }
                    return@launch
                }
                val saved = loadNavigationRecoveryCandidate(storedActiveTourId, repository)
                if (saved == null) {
                    if (storedActiveTourId != null) {
                        ActiveNavigationStore.replaceIfCurrent(context, storedActiveTourId, null)
                    }
                    return@launch
                }
                saved.routingError?.let { routingError ->
                    NavigationRuntime.update { current ->
                        navigationRuntimeAfterColdRecovery(
                            current = current,
                            recovered = NavigationRuntimeState(
                                plan = saved.plan,
                                progress = saved.progress,
                                instruction = "地区地图提供方尚未安全解析",
                                isRunning = false,
                                errorMessage = storedRoutingErrorMessage(routingError),
                            ),
                        )
                    }
                    return@launch
                }
                val progress = saved.progress ?: run {
                    ActiveNavigationStore.clear(context, saved.plan.id)
                    return@launch
                }
                if (progress.state in setOf(NavigationState.COMPLETED, NavigationState.ENDED)) {
                    ActiveNavigationStore.clear(context, saved.plan.id)
                    return@launch
                }
                if (!ActiveNavigationStore.replaceIfCurrent(context, storedActiveTourId, saved.plan.id)) {
                    return@launch
                }
                if (NavigationRuntime.state.value.isRunning) return@launch
                if (saved.plan.executionStrategy.isExternalMapNavigation()) {
                    val restoredPlan = restoreExternalJapanControlPlan(
                        plan = saved.plan,
                        routeNeedsRefresh = saved.routeNeedsRefresh,
                        planner = planner,
                    )
                    NavigationRuntime.update { current ->
                        navigationRuntimeAfterColdRecovery(
                            current = current,
                            recovered = NavigationRuntimeState(
                                plan = restoredPlan,
                                progress = progress,
                                instruction = externalRecoveryInstruction(restoredPlan, progress.isPaused),
                                isRunning = false,
                                errorMessage = "请手动继续当前分段",
                            ),
                        )
                    }
                } else if (progress.state !in setOf(
                        NavigationState.PLANNED,
                        NavigationState.COMPLETED,
                        NavigationState.ENDED,
                    ) &&
                    !NavigationRuntime.state.value.isRunning &&
                    AndroidLocationProvider.hasLocationPermission(getApplication())
                ) {
                    start(saved.plan)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                val message = if (
                    exception is JapanRegionDataException || exception is TerritoryRegionDataException
                ) {
                    REGION_DATA_ERROR_MESSAGE
                } else {
                    exception.message ?: "无法恢复导航行程，请稍后重试"
                }
                NavigationRuntime.update { it.copy(isRunning = false, errorMessage = message) }
            }
        }
    }

    fun start(plan: TourPlan) {
        externalPlanStartMismatch(plan)?.let { message ->
            NavigationRuntime.update { it.copy(plan = plan, isRunning = false, errorMessage = message) }
            return
        }
        if (
            navigationPlanRequiresFineLocationForeground(plan.mode, plan.executionStrategy) &&
            !AndroidLocationProvider.hasFineLocationPermission(getApplication())
        ) {
            NavigationRuntime.update {
                it.copy(errorMessage = externalFineLocationMessage(plan, resume = false))
            }
            return
        }
        val intent = Intent(getApplication(), NavigationService::class.java)
            .setAction(NavigationService.ACTION_START)
            .putExtra(NavigationService.EXTRA_TOUR_ID, plan.id)
            .putExtra(
                NavigationService.EXTRA_REQUIRE_FINE_LOCATION,
                navigationPlanRequiresFineLocationForeground(plan.mode, plan.executionStrategy),
            )
        try {
            ContextCompat.startForegroundService(getApplication(), intent)
        } catch (_: RuntimeException) {
            NavigationRuntime.update {
                it.copy(isRunning = false, errorMessage = "无法启动定位导航，请保持应用在前台并重试")
            }
            return
        }
        if (navigationPlanRequiresFineLocationForeground(plan.mode, plan.executionStrategy)) {
            getApplication<Application>().startActivity(
                TransitHandoffActivity.createIntent(
                    getApplication(),
                    TransitHandoffActivity.MODE_OPEN,
                    plan.id,
                    0,
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun stop() {
        val runtime = state.value
        val currentPlan = runtime.plan
        val progress = runtime.progress
        if (
            currentPlan?.executionStrategy?.isExternalMapNavigation() == true &&
            progress != null
        ) {
            openHandoff(TransitHandoffActivity.MODE_END, currentPlan, progress.legIndex)
            return
        }
        getApplication<Application>().startService(
            Intent(getApplication(), NavigationService::class.java).setAction(NavigationService.ACTION_STOP),
        )
    }

    fun markArrived() {
        val runtime = state.value
        val currentPlan = runtime.plan
        val progress = runtime.progress
        if (
            currentPlan?.executionStrategy?.isExternalMapNavigation() == true &&
            progress != null
        ) {
            openHandoff(TransitHandoffActivity.MODE_CONFIRM_ARRIVAL, currentPlan, progress.legIndex)
            return
        }
        getApplication<Application>().startService(
            Intent(getApplication(), NavigationService::class.java).setAction(NavigationService.ACTION_MANUAL_ARRIVAL),
        )
    }

    fun refreshTransit() {
        getApplication<Application>().startService(
            Intent(getApplication(), NavigationService::class.java).setAction(
                NavigationService.ACTION_REFRESH_TRANSIT,
            ),
        )
    }

    fun openCurrentExternalLeg() {
        val currentPlan = state.value.plan ?: return
        val progress = state.value.progress ?: return
        openHandoff(TransitHandoffActivity.MODE_OPEN, currentPlan, progress.legIndex)
    }

    fun startNextExternalLeg() {
        val currentPlan = state.value.plan ?: return
        val progress = state.value.progress ?: return
        openHandoff(TransitHandoffActivity.MODE_NEXT, currentPlan, progress.legIndex)
    }

    fun pauseExternal() {
        getApplication<Application>().startService(
            Intent(getApplication(), NavigationService::class.java)
                .setAction(NavigationService.ACTION_PAUSE_EXTERNAL),
        )
    }

    fun resumeExternal() {
        val currentPlan = state.value.plan ?: return
        if (!AndroidLocationProvider.hasFineLocationPermission(getApplication())) {
            NavigationRuntime.update {
                it.copy(errorMessage = externalFineLocationMessage(currentPlan, resume = true))
            }
            return
        }
        try {
            ContextCompat.startForegroundService(
                getApplication(),
                Intent(getApplication(), NavigationService::class.java)
                    .setAction(NavigationService.ACTION_RESUME_EXTERNAL)
                    .putExtra(NavigationService.EXTRA_TOUR_ID, currentPlan.id)
                    .putExtra(NavigationService.EXTRA_REQUIRE_FINE_LOCATION, true),
            )
        } catch (_: RuntimeException) {
            NavigationRuntime.update {
                it.copy(isRunning = false, errorMessage = "无法恢复定位导航，请保持应用在前台并重试")
            }
        }
    }

    fun openFutureEditor(availablePoints: List<PilgrimagePoint>) {
        val currentPlan = state.value.plan ?: return
        val progress = state.value.progress ?: return
        val recoveredBetweenLegs = progress.legIndex == -1 &&
            progress.state in setOf(NavigationState.DWELLING, NavigationState.NEXT_STOP)
        val currentPointIndex = if (recoveredBetweenLegs) {
            -1
        } else {
            val currentPointId = currentPlan.legs.indices
                .drop(progress.legIndex.coerceAtLeast(0))
                .firstNotNullOfOrNull { currentPlan.legs[it].destinationPointId }
                ?: return
            currentPlan.orderedPoints.indexOfFirst { it.id == currentPointId }
                .takeIf { it >= 0 }
                ?: return
        }
        val locked = currentPlan.orderedPoints.take(currentPointIndex + 1)
            .mapTo(mutableSetOf()) { it.id } + progress.completedPointIds
        val candidates = (availablePoints + currentPlan.selectedPoints)
            .distinctBy(PilgrimagePoint::id)
            .filterNot { it.id in locked }
        mutableEditState.value = ActiveTourEditUiState(
            isOpen = true,
            futurePoints = currentPlan.orderedPoints.drop(currentPointIndex + 1),
            availablePoints = candidates,
            lockedPointIds = locked,
            fixedEndPointId = currentPlan.orderedPoints.lastOrNull()?.id
                ?.takeIf { currentPlan.endPolicy == EndPolicy.FIXED },
        )
    }

    fun closeFutureEditor() {
        mutableEditState.value = ActiveTourEditUiState()
    }

    fun addFuturePoint(pointId: String) {
        mutableEditState.update { current ->
            val point = current.availablePoints.firstOrNull { it.id == pointId } ?: return@update current
            if (
                current.fixedEndPointId != null &&
                current.fixedEndPointId in current.lockedPointIds
            ) return@update current
            if (current.futurePoints.any { it.id == pointId }) current
            else {
                val updated = current.futurePoints.toMutableList()
                val fixedEndIndex = updated.indexOfFirst { it.id == current.fixedEndPointId }
                updated.add(if (fixedEndIndex >= 0) fixedEndIndex else updated.size, point)
                current.copy(futurePoints = updated, errorMessage = null)
            }
        }
    }

    fun removeFuturePoint(pointId: String) {
        mutableEditState.update { current ->
            if (pointId == current.fixedEndPointId) return@update current
            current.copy(
                futurePoints = current.futurePoints.filterNot { it.id == pointId },
                errorMessage = null,
            )
        }
    }

    fun moveFuturePoint(index: Int, delta: Int) {
        mutableEditState.update { current ->
            val target = index + delta
            if (index !in current.futurePoints.indices || target !in current.futurePoints.indices) return@update current
            if (
                current.futurePoints[index].id == current.fixedEndPointId ||
                current.futurePoints[target].id == current.fixedEndPointId
            ) return@update current
            val moved = current.futurePoints.toMutableList()
            val point = moved.removeAt(index)
            moved.add(target, point)
            current.copy(futurePoints = moved, errorMessage = null)
        }
    }

    fun saveFuturePoints() {
        val draft = editState.value
        if (!draft.isOpen || draft.isSaving) return
        val currentPlan = state.value.plan ?: return
        val currentProgress = state.value.progress ?: return
        viewModelScope.launch {
            mutableEditState.update { it.copy(isSaving = true, errorMessage = null) }
            try {
                val result = editActiveTourFuture(
                    currentPlan = currentPlan,
                    currentProgress = currentProgress,
                    orderedFuturePoints = draft.futurePoints,
                    planner = planner,
                )
                if (result.plan == currentPlan && result.progress == currentProgress) {
                    mutableEditState.value = ActiveTourEditUiState()
                    return@launch
                }
                val beforeSave = NavigationRuntime.state.value
                if (beforeSave.plan != currentPlan || beforeSave.progress != currentProgress) {
                    throw ActiveTourEditException("Navigation state changed while editing")
                }
                val saved = repository.saveActiveEditIfCurrent(
                    expectedPlan = currentPlan,
                    expectedProgress = currentProgress,
                    updatedPlan = result.plan,
                    updatedProgress = result.progress,
                )
                if (!saved) throw ActiveTourEditException("Navigation state changed while saving")
                if (!beforeSave.isRunning) {
                    NavigationRuntime.update {
                        it.copy(
                            plan = result.plan,
                            progress = result.progress,
                            isRunning = false,
                            isRerouting = false,
                            errorMessage = null,
                        )
                    }
                    mutableEditState.value = ActiveTourEditUiState()
                    return@launch
                }
                val reloadResult = requestTourReload(result.plan)
                if (reloadResult == TourReloadResult.SUPERSEDED) {
                    mutableEditState.update {
                        it.copy(
                            isSaving = false,
                            errorMessage = "新的导航操作已接管，保存结果尚未确认，请重试",
                        )
                    }
                    return@launch
                }
                if (reloadResult == TourReloadResult.TIMED_OUT) {
                    mutableEditState.update {
                        it.copy(
                            isSaving = false,
                            errorMessage = "保存结果尚未确认，导航仍在重新加载，请稍后重试",
                        )
                    }
                    return@launch
                }
                val reloaded = NavigationRuntime.state.value
                if (
                    reloadResult != TourReloadResult.RELOADED ||
                    reloaded.plan != result.plan ||
                    !reloaded.isRunning
                ) {
                    val latestPersisted = runCatching { repository.get(result.plan.id) }.getOrNull()
                    if (!activeEditPlanStillPersisted(result.plan, latestPersisted)) {
                        mutableEditState.update {
                            it.copy(
                                isSaving = false,
                                errorMessage = "导航进度已变化，后续点未保存，请重试",
                            )
                        }
                        return@launch
                    }
                    val activeTourId = ActiveNavigationStore.get(getApplication())
                    if (
                        shouldStopAfterReloadFailure(
                            requestedPlan = result.plan,
                            requestedProgress = result.progress,
                            expectedRuntimePlan = currentPlan,
                            expectedRuntimeProgress = currentProgress,
                            latestPersisted = latestPersisted,
                            runtime = reloaded,
                            activeTourId = activeTourId,
                        )
                    ) {
                        getApplication<Application>().stopService(
                            Intent(getApplication(), NavigationService::class.java),
                        )
                        val message = "后续点已保存，但导航已暂停，请手动恢复"
                        NavigationRuntime.set(
                            NavigationRuntimeState(
                                plan = result.plan,
                                progress = result.progress,
                                instruction = message,
                                isRunning = false,
                                errorMessage = message,
                            ),
                        )
                    }
                }
                mutableEditState.value = ActiveTourEditUiState()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                val message = when (exception) {
                    is MixedTransitRegionException -> exception.message
                    is JapanRegionDataException, is TerritoryRegionDataException -> REGION_DATA_ERROR_MESSAGE
                    is ActiveTourEditException -> "无法保存后续点，请保持已完成点和当前点不变"
                    else -> exception.message ?: "无法保存后续点，请稍后重试"
                }
                mutableEditState.update { it.copy(isSaving = false, errorMessage = message) }
            }
        }
    }

    private suspend fun requestTourReload(plan: TourPlan): TourReloadResult =
        withTimeoutOrNull(RELOAD_TIMEOUT_MILLIS) {
        suspendCancellableCoroutine { continuation ->
            val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    if (continuation.isActive) {
                        continuation.resume(
                            when (resultCode) {
                                NavigationService.RESULT_RELOADED -> TourReloadResult.RELOADED
                                NavigationService.RESULT_RELOAD_SUPERSEDED -> TourReloadResult.SUPERSEDED
                                else -> TourReloadResult.FAILED
                            },
                        )
                    }
                }
            }
            val intent = Intent(getApplication(), NavigationService::class.java)
                .setAction(NavigationService.ACTION_RELOAD_TOUR)
                .putExtra(NavigationService.EXTRA_TOUR_ID, plan.id)
                .putExtra(NavigationService.EXTRA_RESULT_RECEIVER, receiver)
                .putExtra(
                    NavigationService.EXTRA_REQUIRE_FINE_LOCATION,
                    navigationPlanRequiresFineLocationForeground(plan.mode, plan.executionStrategy),
                )
            try {
                ContextCompat.startForegroundService(getApplication(), intent)
            } catch (_: RuntimeException) {
                if (continuation.isActive) continuation.resume(TourReloadResult.FAILED)
            }
        }
    } ?: TourReloadResult.TIMED_OUT

    private fun openHandoff(mode: String, plan: TourPlan, legIndex: Int) {
        if (
            mode != TransitHandoffActivity.MODE_END &&
            !AndroidLocationProvider.hasFineLocationPermission(getApplication())
        ) {
            NavigationRuntime.update {
                it.copy(errorMessage = externalFineLocationMessage(plan, resume = false))
            }
            return
        }
        getApplication<Application>().startActivity(
            TransitHandoffActivity.createIntent(getApplication(), mode, plan.id, legIndex)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    class Factory(
        private val application: Application,
        private val repository: TourRepository,
        private val planner: TourPlanner,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            NavigationViewModel(application, repository, planner) as T
    }
}

internal suspend fun restoreExternalJapanControlPlan(
    plan: TourPlan,
    routeNeedsRefresh: Boolean,
    planner: TourPlanner,
): TourPlan {
    require(plan.executionStrategy.isExternalMapNavigation())
    return if (routeNeedsRefresh) planner.rebuild(plan, plan.orderedPoints) else plan
}

internal suspend fun loadNavigationRecoveryCandidate(
    storedActiveTourId: String?,
    repository: TourRepository,
): SavedTour? {
    if (storedActiveTourId != null) {
        repository.get(storedActiveTourId)?.let { stored ->
            if (stored.progress?.state in RECOVERABLE_LEGACY_STATES) return stored
            return repository.getMostRecentInStatesNewerThan(
                states = RECOVERABLE_LEGACY_STATES,
                tourId = storedActiveTourId,
            ) ?: stored
        }
    }
    return repository.getMostRecentInStates(RECOVERABLE_LEGACY_STATES)
}

internal enum class NavigationBootRestoreAction {
    CLEAR_STALE_POINTER,
    IGNORE_NON_EXTERNAL,
    SHOW_EXTERNAL_JAPAN_CONTROL,
}

internal fun navigationBootRestoreAction(saved: SavedTour?): NavigationBootRestoreAction {
    val candidate = saved ?: return NavigationBootRestoreAction.CLEAR_STALE_POINTER
    if (candidate.routingError != null) return NavigationBootRestoreAction.IGNORE_NON_EXTERNAL
    if (candidate.progress?.state !in RECOVERABLE_LEGACY_STATES) {
        return NavigationBootRestoreAction.CLEAR_STALE_POINTER
    }
    return if (candidate.plan.executionStrategy.isExternalMapNavigation()) {
        NavigationBootRestoreAction.SHOW_EXTERNAL_JAPAN_CONTROL
    } else {
        NavigationBootRestoreAction.IGNORE_NON_EXTERNAL
    }
}

internal fun storedRoutingErrorMessage(error: StoredRoutingError): String = when (error) {
    StoredRoutingError.REGION_UNRESOLVED -> "无法安全判定地图地区，未启动导航"
    StoredRoutingError.MIXED_MAP_PROVIDERS -> "一次行程的起点和所有目的地必须使用同一地图提供方"
    StoredRoutingError.MIXED_TRANSIT_REGIONS -> "公交行程不能跨越不同的地区提供方"
    StoredRoutingError.REGION_DATA_OUTDATED -> REGION_DATA_ERROR_MESSAGE
}

internal fun externalRecoveryInstruction(plan: TourPlan, paused: Boolean): String = when {
    plan.executionStrategy == TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN && paused ->
        "已恢复暂停的日本公交行程，请手动恢复"
    plan.executionStrategy == TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN ->
        "已恢复日本公交行程，不会自动打开 Google 地图"
    paused -> "已恢复暂停的高德地图外部分段导航，请手动恢复"
    else -> "已恢复高德地图外部分段导航，不会自动打开高德地图"
}

internal fun externalPlanStartMismatch(plan: TourPlan): String? {
    if (!plan.executionStrategy.isExternalMapNavigation()) return null
    if (plan.regionDataVersion.isNullOrBlank()) return REGION_DATA_ERROR_MESSAGE
    if (plan.legs.isEmpty()) return "当前外部分段路线尚未安全解析，未启动导航"
    return plan.legs.firstNotNullOfOrNull { leg -> externalHandoffMismatch(plan, leg) }
}

private fun externalFineLocationMessage(plan: TourPlan, resume: Boolean): String = when {
    plan.executionStrategy == TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN && resume ->
        "需要精确定位权限才能恢复日本公交行程"
    plan.executionStrategy == TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN ->
        "需要精确定位权限才能继续日本公交分段导航"
    resume -> "需要精确定位权限才能恢复高德地图外部分段导航"
    else -> "需要精确定位权限才能继续高德地图外部分段导航"
}

internal fun navigationRuntimeAfterColdRecovery(
    current: NavigationRuntimeState,
    recovered: NavigationRuntimeState,
): NavigationRuntimeState {
    if (current.isRunning) return current
    val recoveredTourId = recovered.plan?.id ?: recovered.progress?.tourId ?: return recovered
    val currentHasSameTour = current.plan?.id == recoveredTourId || current.progress?.tourId == recoveredTourId
    return if (
        currentHasSameTour &&
        (current.plan != recovered.plan || current.progress != recovered.progress)
    ) {
        current
    } else {
        recovered
    }
}

internal fun shouldStopAfterReloadFailure(
    requestedPlan: TourPlan,
    requestedProgress: NavigationProgress,
    expectedRuntimePlan: TourPlan,
    expectedRuntimeProgress: NavigationProgress,
    latestPersisted: SavedTour?,
    runtime: NavigationRuntimeState,
    activeTourId: String?,
): Boolean {
    return activeTourId == requestedPlan.id &&
        latestPersisted?.plan == requestedPlan &&
        latestPersisted.progress == requestedProgress &&
        runtime.plan == expectedRuntimePlan &&
        runtime.progress == expectedRuntimeProgress
}

internal fun activeEditPlanStillPersisted(
    requestedPlan: TourPlan,
    latestPersisted: SavedTour?,
): Boolean = latestPersisted?.plan == requestedPlan

internal enum class TourReloadResult {
    RELOADED,
    SUPERSEDED,
    TIMED_OUT,
    FAILED,
}

data class ActiveTourEditUiState(
    val isOpen: Boolean = false,
    val futurePoints: List<PilgrimagePoint> = emptyList(),
    val availablePoints: List<PilgrimagePoint> = emptyList(),
    val lockedPointIds: Set<String> = emptySet(),
    val fixedEndPointId: String? = null,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
) {
    val addablePoints: List<PilgrimagePoint>
        get() = if (fixedEndPointId != null && fixedEndPointId in lockedPointIds) {
            emptyList()
        } else {
            availablePoints.filter { candidate -> futurePoints.none { it.id == candidate.id } }
        }
}

private val RECOVERABLE_LEGACY_STATES = setOf(
    NavigationState.NAVIGATING,
    NavigationState.ARRIVING,
    NavigationState.DWELLING,
    NavigationState.NEXT_STOP,
)

private const val RELOAD_TIMEOUT_MILLIS = 10_000L
