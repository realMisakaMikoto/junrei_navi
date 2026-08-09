package cn.anitabi.navigator.core.navigation

import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.CoordinateSystem
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.core.model.NavigationProgress
import cn.anitabi.navigator.core.model.NavigationState
import cn.anitabi.navigator.core.model.TourPlan
import cn.anitabi.navigator.core.model.TransitExecutionStrategy
import cn.anitabi.navigator.core.model.TravelMode
import cn.anitabi.navigator.core.model.isExternalMapNavigation
import cn.anitabi.navigator.core.routing.TourOptimizer

class JapanExternalTransitEngine(
    private val plan: TourPlan,
    initialProgress: NavigationProgress = NavigationProgress(tourId = plan.id),
) {
    var progress: NavigationProgress = initialProgress
        private set

    var runtimeState: JapanExternalTransitRuntimeState = JapanExternalTransitRuntimeState()
        private set

    private var lastInsideSampleElapsedRealtimeMillis: Long? = null

    init {
        require(plan.executionStrategy.isExternalMapNavigation()) {
            "External map navigation requires an external execution strategy"
        }
        when (plan.executionStrategy) {
            TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN -> {
                require(plan.mode == TravelMode.TRANSIT) { "Google Japan external navigation requires transit mode" }
                require(plan.mapProvider == MapProvider.GOOGLE) { "Google external navigation requires Google" }
                require(plan.legs.all { it.provider == MapProvider.GOOGLE }) {
                    "Google external legs require Google"
                }
                require(plan.legs.all { it.coordinateSystem == CoordinateSystem.WGS84 }) {
                    "Google external legs must be WGS84"
                }
            }
            TransitExecutionStrategy.EXTERNAL_AMAP_MAINLAND -> {
                require(plan.mapProvider == MapProvider.AMAP) { "AMap external navigation requires AMap" }
                require(plan.legs.all { it.provider == MapProvider.AMAP }) {
                    "AMap external legs require AMap"
                }
                require(
                    plan.legs.none { it.geometry.isNotEmpty() } ||
                        plan.coordinateSystem == CoordinateSystem.GCJ02,
                ) { "AMap plan route geometry must already be GCJ02" }
                require(
                    plan.legs.all { leg ->
                        leg.geometry.isEmpty() || leg.coordinateSystem == CoordinateSystem.GCJ02
                    },
                ) { "AMap route geometry must already be GCJ02" }
            }
            TransitExecutionStrategy.IN_APP_GOOGLE_ROUTES -> error("In-app navigation is not external")
        }
        require(plan.dwellMinutes >= 0) { "Dwell time cannot be negative" }
        require(initialProgress.tourId == plan.id) { "Progress belongs to a different tour" }
    }

    fun start(): JapanExternalTransitUpdate {
        if (progress.state != NavigationState.PLANNED || progress.isPaused) return snapshot()
        progress = if (plan.legs.isEmpty()) {
            progress.copy(state = NavigationState.COMPLETED)
        } else {
            progress.copy(
                state = NavigationState.NAVIGATING,
                legIndex = progress.legIndex.coerceIn(plan.legs.indices),
                stepIndex = 0,
            )
        }
        return snapshot()
    }

    fun onLocation(sample: JapanTransitLocationSample): JapanExternalTransitUpdate {
        if (progress.isPaused || progress.state in TERMINAL_STATES) return snapshot()
        val target = currentTarget() ?: return snapshot()
        val distance = TourOptimizer.haversineMeters(sample.coordinate, target)
        runtimeState = runtimeState.copy(targetDistanceMeters = distance)
        if (progress.state != NavigationState.NAVIGATING) return snapshot()

        expireStaleCandidate(sample.elapsedRealtimeMillis)
        if (sample.accuracyMeters > MAX_ARRIVAL_ACCURACY_METERS) {
            lastInsideSampleElapsedRealtimeMillis = null
            return snapshot()
        }

        runtimeState = runtimeState.copy(
            lastQualifiedSampleElapsedRealtimeMillis = sample.elapsedRealtimeMillis,
        )
        when {
            distance <= ARRIVAL_ENTRY_RADIUS_METERS -> recordInsideSample(sample.elapsedRealtimeMillis)
            distance <= ARRIVAL_RESET_RADIUS_METERS -> lastInsideSampleElapsedRealtimeMillis = null
            else -> clearArrivalCandidate()
        }
        if (runtimeState.arrivalCandidateAccumulatedMillis >= ARRIVAL_CONFIRMATION_MILLIS) {
            progress = progress.copy(state = NavigationState.ARRIVING)
        }
        return snapshot()
    }

    fun onTick(
        nowEpochMillis: Long,
        nowElapsedRealtimeMillis: Long,
    ): JapanExternalTransitUpdate {
        if (progress.isPaused || progress.state in TERMINAL_STATES) return snapshot()
        when (progress.state) {
            NavigationState.NAVIGATING -> expireStaleCandidate(nowElapsedRealtimeMillis)
            NavigationState.DWELLING -> {
                val deadline = progress.dwellingUntilEpochMillis
                if (deadline != null && nowEpochMillis >= deadline) finishDwell()
            }
            else -> Unit
        }
        return snapshot()
    }

    fun confirmArrival(
        nowEpochMillis: Long,
        confirmEarly: Boolean = false,
    ): JapanExternalTransitUpdate {
        if (progress.isPaused || progress.state in TERMINAL_STATES) return snapshot()
        if (progress.state == NavigationState.NAVIGATING && !confirmEarly) {
            return snapshot(requiresEarlyArrivalConfirmation = true)
        }
        if (progress.state !in setOf(NavigationState.NAVIGATING, NavigationState.ARRIVING)) {
            return snapshot()
        }

        val leg = plan.legs.getOrNull(progress.legIndex) ?: return complete()
        val destinationPointId = leg.destinationPointId
        val completedPointIds = destinationPointId?.let { progress.completedPointIds + it }
            ?: progress.completedPointIds
        val dwellMillis = plan.dwellMinutes.toLong() * MILLIS_PER_MINUTE
        progress = when {
            destinationPointId != null -> progress.copy(
                completedPointIds = completedPointIds,
                state = NavigationState.DWELLING,
                dwellingUntilEpochMillis = nowEpochMillis + dwellMillis,
            )
            hasNextLeg() -> progress.copy(
                completedPointIds = completedPointIds,
                state = NavigationState.NEXT_STOP,
                dwellingUntilEpochMillis = null,
            )
            else -> progress.copy(
                completedPointIds = completedPointIds,
                state = NavigationState.COMPLETED,
                dwellingUntilEpochMillis = null,
            )
        }
        clearLegRuntime()
        return snapshot()
    }

    fun startNextLeg(): JapanExternalTransitUpdate {
        if (progress.isPaused || progress.state != NavigationState.NEXT_STOP) return snapshot()
        val nextLegIndex = progress.legIndex + 1
        progress = if (nextLegIndex in plan.legs.indices) {
            progress.copy(
                legIndex = nextLegIndex,
                stepIndex = 0,
                state = NavigationState.NAVIGATING,
                dwellingUntilEpochMillis = null,
            )
        } else {
            progress.copy(state = NavigationState.COMPLETED, dwellingUntilEpochMillis = null)
        }
        clearLegRuntime()
        return snapshot()
    }

    fun leaveDwellEarlyAndStartNextLeg(): JapanExternalTransitUpdate {
        if (progress.isPaused || progress.state != NavigationState.DWELLING) return snapshot()
        finishDwell()
        return if (progress.state == NavigationState.NEXT_STOP) startNextLeg() else snapshot()
    }

    fun pause(nowEpochMillis: Long): JapanExternalTransitUpdate {
        if (
            progress.isPaused ||
            progress.state !in setOf(
                NavigationState.NAVIGATING,
                NavigationState.ARRIVING,
                NavigationState.DWELLING,
                NavigationState.NEXT_STOP,
            )
        ) {
            return snapshot()
        }
        progress = progress.copy(isPaused = true, pausedAtEpochMillis = nowEpochMillis)
        clearArrivalCandidate()
        return snapshot()
    }

    fun resume(nowEpochMillis: Long): JapanExternalTransitUpdate {
        if (!progress.isPaused) return snapshot()
        val pausedAt = progress.pausedAtEpochMillis ?: nowEpochMillis
        val pauseDuration = (nowEpochMillis - pausedAt).coerceAtLeast(0L)
        val shiftedDwellDeadline = progress.dwellingUntilEpochMillis?.let { it + pauseDuration }
        progress = progress.copy(
            isPaused = false,
            pausedAtEpochMillis = null,
            dwellingUntilEpochMillis = shiftedDwellDeadline,
        )
        clearArrivalCandidate()
        return snapshot()
    }

    fun end(): JapanExternalTransitUpdate {
        if (progress.state in TERMINAL_STATES) return snapshot()
        progress = progress.copy(
            state = NavigationState.ENDED,
            isPaused = false,
            pausedAtEpochMillis = null,
            dwellingUntilEpochMillis = null,
        )
        clearLegRuntime()
        return snapshot()
    }

    private fun recordInsideSample(elapsedRealtimeMillis: Long) {
        val candidateSince = runtimeState.arrivalCandidateSinceElapsedRealtimeMillis
        if (candidateSince == null) {
            runtimeState = runtimeState.copy(
                arrivalCandidateSinceElapsedRealtimeMillis = elapsedRealtimeMillis,
                arrivalCandidateAccumulatedMillis = 0L,
            )
        } else {
            val additionalTime = lastInsideSampleElapsedRealtimeMillis
                ?.let { (elapsedRealtimeMillis - it).coerceAtLeast(0L) }
                ?: 0L
            runtimeState = runtimeState.copy(
                arrivalCandidateAccumulatedMillis =
                    runtimeState.arrivalCandidateAccumulatedMillis + additionalTime,
            )
        }
        lastInsideSampleElapsedRealtimeMillis = elapsedRealtimeMillis
    }

    private fun expireStaleCandidate(nowElapsedRealtimeMillis: Long) {
        val lastQualified = runtimeState.lastQualifiedSampleElapsedRealtimeMillis ?: return
        if (nowElapsedRealtimeMillis - lastQualified > MAX_QUALIFIED_SAMPLE_GAP_MILLIS) {
            clearArrivalCandidate()
        }
    }

    private fun finishDwell() {
        progress = if (hasNextLeg()) {
            progress.copy(state = NavigationState.NEXT_STOP, dwellingUntilEpochMillis = null)
        } else {
            progress.copy(state = NavigationState.COMPLETED, dwellingUntilEpochMillis = null)
        }
        clearLegRuntime()
    }

    private fun complete(): JapanExternalTransitUpdate {
        progress = progress.copy(state = NavigationState.COMPLETED, dwellingUntilEpochMillis = null)
        clearLegRuntime()
        return snapshot()
    }

    private fun hasNextLeg(): Boolean = progress.legIndex < plan.legs.lastIndex

    private fun currentTarget(): GeoPoint? = plan.legs.getOrNull(progress.legIndex)?.to

    private fun clearArrivalCandidate() {
        runtimeState = runtimeState.copy(
            arrivalCandidateSinceElapsedRealtimeMillis = null,
            arrivalCandidateAccumulatedMillis = 0L,
        )
        lastInsideSampleElapsedRealtimeMillis = null
    }

    private fun clearLegRuntime() {
        runtimeState = JapanExternalTransitRuntimeState()
        lastInsideSampleElapsedRealtimeMillis = null
    }

    private fun snapshot(requiresEarlyArrivalConfirmation: Boolean = false) = JapanExternalTransitUpdate(
        progress = progress,
        runtimeState = runtimeState,
        requiresEarlyArrivalConfirmation = requiresEarlyArrivalConfirmation,
    )

    companion object {
        const val MAX_ARRIVAL_ACCURACY_METERS = 50.0
        const val ARRIVAL_ENTRY_RADIUS_METERS = 80.0
        const val ARRIVAL_RESET_RADIUS_METERS = 120.0
        const val ARRIVAL_CONFIRMATION_MILLIS = 15_000L
        const val MAX_QUALIFIED_SAMPLE_GAP_MILLIS = 5_000L

        private const val MILLIS_PER_MINUTE = 60_000L
        private val TERMINAL_STATES = setOf(NavigationState.COMPLETED, NavigationState.ENDED)
    }
}

data class JapanTransitLocationSample(
    val coordinate: GeoPoint,
    val accuracyMeters: Double,
    val elapsedRealtimeMillis: Long,
) {
    init {
        require(accuracyMeters.isFinite() && accuracyMeters >= 0.0) {
            "Location accuracy must be a finite non-negative value"
        }
        require(elapsedRealtimeMillis >= 0L) { "Elapsed realtime cannot be negative" }
    }
}

data class JapanExternalTransitRuntimeState(
    val targetDistanceMeters: Double? = null,
    val arrivalCandidateSinceElapsedRealtimeMillis: Long? = null,
    val arrivalCandidateAccumulatedMillis: Long = 0L,
    val lastQualifiedSampleElapsedRealtimeMillis: Long? = null,
)

data class JapanExternalTransitUpdate(
    val progress: NavigationProgress,
    val runtimeState: JapanExternalTransitRuntimeState,
    val requiresEarlyArrivalConfirmation: Boolean = false,
)
