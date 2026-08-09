package cn.anitabi.navigator.core.model

import kotlinx.serialization.Serializable

@Serializable
data class StoredTourV2(
    val schemaVersion: Int = SCHEMA_VERSION,
    val id: String,
    val displayAnime: Anime,
    val selectedAnimes: List<Anime>,
    val selectedPoints: List<PilgrimagePoint>,
    val manualOrderPointIds: List<String>,
    val start: GeoPoint,
    val startPointId: String? = null,
    val mode: TravelMode,
    val objective: RouteObjective,
    val endPolicy: EndPolicy,
    val fixedEndPointId: String? = null,
    /** v0.2.0 compatibility only. New writes use transitAnchorTime. */
    val departureTime: String? = null,
    val transitTimeMode: TransitTimeMode? = null,
    val transitAnchorTime: String? = null,
    val transitRoutingPreference: TransitRoutingPreference = TransitRoutingPreference.RECOMMENDED,
    val transitTravelModes: Set<TransitTravelMode> = emptySet(),
    val dwellMinutes: Int = 15,
    val completedPointIds: Set<String> = emptySet(),
    val activePointId: String? = null,
    val activeLegIndex: Int? = null,
    val navigationState: NavigationState = NavigationState.PLANNED,
    val dwellingUntilEpochMillis: Long? = null,
    val offRouteSinceEpochMillis: Long? = null,
    val lastRerouteEpochMillis: Long? = null,
    val executionStrategy: TransitExecutionStrategy? = null,
    val mapProvider: MapProvider? = null,
    val regionDataVersion: String? = null,
    val externalRouteFallback: Boolean = false,
    val isPaused: Boolean = false,
    val pausedAtEpochMillis: Long? = null,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported stored tour schema" }
    }

    fun toUnresolvedPlan(
        resolvedExecutionStrategy: TransitExecutionStrategy,
        resolvedMapProvider: MapProvider = mapProvider ?: MapProvider.GOOGLE,
        resolvedRegionDataVersion: String? = regionDataVersion,
    ): TourPlan {
        val pointsById = selectedPoints.associateBy(PilgrimagePoint::id)
        val ordered = manualOrderPointIds.mapNotNull(pointsById::get) +
            selectedPoints.filterNot { it.id in manualOrderPointIds.toSet() }
        val legacyAnchorTime = transitAnchorTime ?: departureTime
        val restoredTimeMode = transitTimeMode ?: if (mode == TravelMode.TRANSIT && legacyAnchorTime != null) {
            TransitTimeMode.DEPART_AT
        } else {
            TransitTimeMode.NOW
        }
        val restoredAnchorTime = when (restoredTimeMode) {
            TransitTimeMode.NOW -> null
            TransitTimeMode.DEPART_AT -> legacyAnchorTime
            TransitTimeMode.ARRIVE_BY -> transitAnchorTime
        }
        return TourPlan(
            id = id,
            anime = displayAnime,
            selectedPoints = selectedPoints,
            orderedPoints = ordered,
            legs = emptyList(),
            mode = mode,
            objective = objective,
            endPolicy = endPolicy,
            estimatedDurationSeconds = 0.0,
            attribution = emptyList(),
            departureTime = null,
            arrivalTime = null,
            transitTimeMode = restoredTimeMode,
            transitAnchorTime = restoredAnchorTime,
            transitRoutingPreference = transitRoutingPreference,
            transitTravelModes = transitTravelModes,
            dwellMinutes = dwellMinutes,
            initialStart = start,
            state = navigationState,
            executionStrategy = resolvedExecutionStrategy,
            mapProvider = resolvedMapProvider,
            coordinateSystem = CoordinateSystem.WGS84,
            regionDataVersion = resolvedRegionDataVersion,
            externalRouteFallback = externalRouteFallback,
        )
    }

    fun toUnresolvedPlan(): TourPlan = toUnresolvedPlan(
        requireNotNull(executionStrategy) {
            "Legacy stored tours require coordinate-based execution strategy resolution"
        },
    )

    fun toNavigationProgress(
        resolvedExecutionStrategy: TransitExecutionStrategy,
    ): NavigationProgress {
        val pointsById = selectedPoints.associateBy(PilgrimagePoint::id)
        val ordered = manualOrderPointIds.mapNotNull(pointsById::get) +
            selectedPoints.filterNot { it.id in manualOrderPointIds.toSet() }
        val restoredLegIndex = activeLegIndex ?: if (
            resolvedExecutionStrategy.isExternalMapNavigation()
        ) {
            activePointId?.let { activeId ->
                ordered.indexOfFirst { it.id == activeId }.takeIf { it >= 0 }
            } ?: if (
                endPolicy == EndPolicy.RETURN_TO_START &&
                ordered.isNotEmpty() &&
                ordered.all { it.id in completedPointIds }
            ) {
                ordered.size
            } else {
                0
            }
        } else {
            val visits = ordered.filterNot { it.id == startPointId }
            activePointId?.let { activeId ->
                visits.indexOfFirst { it.id == activeId }.takeIf { it >= 0 }
            } ?: 0
        }
        return NavigationProgress(
            tourId = id,
            legIndex = restoredLegIndex,
            completedPointIds = completedPointIds,
            state = navigationState,
            dwellingUntilEpochMillis = dwellingUntilEpochMillis,
            offRouteSinceEpochMillis = offRouteSinceEpochMillis,
            lastRerouteEpochMillis = lastRerouteEpochMillis,
            isPaused = isPaused,
            pausedAtEpochMillis = pausedAtEpochMillis,
        )
    }

    fun toNavigationProgress(): NavigationProgress = toNavigationProgress(
        requireNotNull(executionStrategy) {
            "Legacy stored tours require coordinate-based execution strategy resolution"
        },
    )

    companion object {
        const val SCHEMA_VERSION = 2

        fun from(plan: TourPlan, progress: NavigationProgress?): StoredTourV2 = StoredTourV2(
            id = plan.id,
            displayAnime = plan.anime,
            selectedAnimes = inferSelectedAnimes(plan),
            selectedPoints = plan.selectedPoints,
            manualOrderPointIds = plan.orderedPoints.map(PilgrimagePoint::id),
            start = plan.initialStart ?: plan.orderedPoints.firstOrNull()?.coordinate
                ?: plan.selectedPoints.first().coordinate,
            startPointId = plan.orderedPoints.firstOrNull()
                ?.takeIf { it.coordinate == plan.initialStart }
                ?.id,
            mode = plan.mode,
            objective = plan.objective,
            endPolicy = plan.endPolicy,
            fixedEndPointId = plan.orderedPoints.lastOrNull()?.id
                ?.takeIf { plan.endPolicy == EndPolicy.FIXED },
            departureTime = null,
            transitTimeMode = plan.transitTimeMode,
            transitAnchorTime = when {
                plan.mode != TravelMode.TRANSIT -> null
                plan.transitTimeMode == TransitTimeMode.NOW -> null
                plan.transitTimeMode == TransitTimeMode.DEPART_AT ->
                    plan.transitAnchorTime ?: plan.departureTime
                else -> requireNotNull(plan.transitAnchorTime) {
                    "Arrive-by transit plans require the user-selected arrival time"
                }
            },
            transitRoutingPreference = plan.transitRoutingPreference,
            transitTravelModes = plan.transitTravelModes,
            dwellMinutes = plan.dwellMinutes,
            completedPointIds = progress?.completedPointIds.orEmpty(),
            activePointId = progress?.let { current ->
                plan.legs.getOrNull(current.legIndex)?.destinationPointId
                    ?: plan.orderedPoints.firstOrNull { it.id !in current.completedPointIds }?.id
            },
            activeLegIndex = progress?.legIndex,
            navigationState = progress?.state ?: plan.state,
            dwellingUntilEpochMillis = progress?.dwellingUntilEpochMillis,
            offRouteSinceEpochMillis = progress?.offRouteSinceEpochMillis,
            lastRerouteEpochMillis = progress?.lastRerouteEpochMillis,
            executionStrategy = plan.executionStrategy,
            mapProvider = plan.mapProvider,
            regionDataVersion = plan.regionDataVersion,
            externalRouteFallback = plan.externalRouteFallback,
            isPaused = progress?.isPaused ?: false,
            pausedAtEpochMillis = progress?.pausedAtEpochMillis,
        )

        private fun inferSelectedAnimes(plan: TourPlan): List<Anime> {
            if (plan.anime.subjectId != 0L) return listOf(plan.anime)
            return plan.selectedPoints.mapNotNull { point ->
                val subjectId = point.id.substringBefore("::", missingDelimiterValue = "").toLongOrNull()
                    ?: return@mapNotNull null
                val title = point.name.substringAfter('《', missingDelimiterValue = "")
                    .substringBefore("》·", missingDelimiterValue = "")
                    .takeIf(String::isNotBlank)
                    ?: "Bangumi #$subjectId"
                Anime(subjectId = subjectId, name = title)
            }.distinctBy(Anime::subjectId)
        }
    }
}
