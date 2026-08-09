package cn.anitabi.navigator.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class TravelMode {
    DRIVE,
    BIKE,
    WALK,
    TRANSIT,
}

@Serializable
enum class RouteObjective {
    FASTEST,
    SHORTEST,
}

@Serializable
enum class EndPolicy {
    OPEN,
    FIXED,
    RETURN_TO_START,
}

@Serializable
enum class TransitTimeMode {
    NOW,
    DEPART_AT,
    ARRIVE_BY,
}

@Serializable
enum class TransitRoutingPreference {
    RECOMMENDED,
    LESS_WALKING,
    FEWER_TRANSFERS,
}

@Serializable
enum class TransitTravelMode {
    BUS,
    SUBWAY,
    TRAIN,
    LIGHT_RAIL,
}

@Serializable
enum class TransitExecutionStrategy {
    IN_APP_GOOGLE_ROUTES,
    EXTERNAL_GOOGLE_MAPS_JAPAN,
    EXTERNAL_AMAP_MAINLAND,
}

@Serializable
enum class NavigationState {
    PLANNED,
    NAVIGATING,
    ARRIVING,
    DWELLING,
    NEXT_STOP,
    COMPLETED,
    ENDED;

    fun canTransitionTo(next: NavigationState): Boolean = next in allowedTransitions.getValue(this)

    companion object {
        private val allowedTransitions = mapOf(
            PLANNED to setOf(NAVIGATING, ENDED),
            NAVIGATING to setOf(ARRIVING, ENDED),
            ARRIVING to setOf(DWELLING, NEXT_STOP, COMPLETED, ENDED),
            DWELLING to setOf(NEXT_STOP, COMPLETED, ENDED),
            NEXT_STOP to setOf(NAVIGATING, COMPLETED, ENDED),
            COMPLETED to emptySet(),
            ENDED to emptySet(),
        )
    }
}

@Serializable
data class Anime(
    val subjectId: Long,
    val name: String,
    val nameCn: String? = null,
    val imageUrl: String? = null,
)

@Serializable
data class PilgrimagePoint(
    val id: String,
    val name: String,
    val coordinate: GeoPoint,
    val imageUrl: String? = null,
    val origin: String? = null,
    val originUrl: String? = null,
    val visited: Boolean = false,
)

@Serializable
data class RouteStep(
    val instruction: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val coordinate: GeoPoint? = null,
)

@Serializable
data class TourLeg(
    val from: GeoPoint,
    val to: GeoPoint,
    val mode: TravelMode,
    val geometry: List<GeoPoint>,
    val steps: List<RouteStep>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val source: String,
    val provider: MapProvider = MapProvider.GOOGLE,
    val coordinateSystem: CoordinateSystem = CoordinateSystem.WGS84,
    val transit: TransitLegDetails? = null,
    val destinationPointId: String? = null,
)

@Serializable
data class TransitLegDetails(
    val vehicleMode: String,
    val line: String? = null,
    val direction: String? = null,
    val departureStop: String? = null,
    val arrivalStop: String? = null,
    val stopCount: Int? = null,
    val departureTime: String? = null,
    val arrivalTime: String? = null,
    val departureTimeZone: String? = null,
    val arrivalTimeZone: String? = null,
    val departurePlatform: String? = null,
    val arrivalPlatform: String? = null,
    val intermediateStops: List<String> = emptyList(),
    val realtime: Boolean = false,
    val cancelled: Boolean = false,
)

@Serializable
data class TourPlan(
    val id: String,
    val anime: Anime,
    val selectedPoints: List<PilgrimagePoint>,
    val orderedPoints: List<PilgrimagePoint>,
    val legs: List<TourLeg>,
    val mode: TravelMode,
    val objective: RouteObjective,
    val endPolicy: EndPolicy,
    val estimatedDurationSeconds: Double,
    val attribution: List<String>,
    val departureTime: String? = null,
    val arrivalTime: String? = null,
    val transitTimeMode: TransitTimeMode = TransitTimeMode.DEPART_AT,
    val transitAnchorTime: String? = null,
    val transitRoutingPreference: TransitRoutingPreference = TransitRoutingPreference.RECOMMENDED,
    val transitTravelModes: Set<TransitTravelMode> = emptySet(),
    val dwellMinutes: Int = 15,
    val initialStart: GeoPoint? = null,
    val state: NavigationState = NavigationState.PLANNED,
    val executionStrategy: TransitExecutionStrategy = TransitExecutionStrategy.IN_APP_GOOGLE_ROUTES,
    val mapProvider: MapProvider = MapProvider.GOOGLE,
    val coordinateSystem: CoordinateSystem = CoordinateSystem.WGS84,
    val regionDataVersion: String? = null,
    val externalRouteFallback: Boolean = false,
)

@Serializable
data class NavigationProgress(
    val tourId: String,
    val legIndex: Int = 0,
    val stepIndex: Int = 0,
    val completedPointIds: Set<String> = emptySet(),
    val state: NavigationState = NavigationState.PLANNED,
    val dwellingUntilEpochMillis: Long? = null,
    val offRouteSinceEpochMillis: Long? = null,
    val lastRerouteEpochMillis: Long? = null,
    val isPaused: Boolean = false,
    val pausedAtEpochMillis: Long? = null,
)
