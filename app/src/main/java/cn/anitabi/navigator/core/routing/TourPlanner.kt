package cn.anitabi.navigator.core.routing

import cn.anitabi.navigator.core.model.Anime
import cn.anitabi.navigator.core.model.EndPolicy
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.CoordinateSystem
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.core.model.NavigationState
import cn.anitabi.navigator.core.model.PilgrimagePoint
import cn.anitabi.navigator.core.model.RouteObjective
import cn.anitabi.navigator.core.model.TourLeg
import cn.anitabi.navigator.core.model.TourPlan
import cn.anitabi.navigator.core.model.TransitExecutionStrategy
import cn.anitabi.navigator.core.model.TransitRoutingPreference
import cn.anitabi.navigator.core.model.TransitTimeMode
import cn.anitabi.navigator.core.model.TransitTravelMode
import cn.anitabi.navigator.core.model.TravelMode
import cn.anitabi.navigator.core.model.TerritoryRegion
import cn.anitabi.navigator.core.region.JapanRegion
import cn.anitabi.navigator.core.region.ResolvedJourneyProvider
import cn.anitabi.navigator.core.region.JourneyProviderResolutionException
import cn.anitabi.navigator.core.region.TerritoryRegionDataException
import cn.anitabi.navigator.core.region.resolveJourneyProvider
import cn.anitabi.navigator.data.network.ApiException
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToLong

class TourPlanner(
    private val roadProvider: RoadRoutingProvider,
    private val transitProvider: TransitJourneyProvider,
    private val optimizer: TourOptimizer = TourOptimizer(),
    private val classifyRegion: (GeoPoint) -> JapanRegion = { JapanRegion.NON_JAPAN },
    classifyTerritory: ((GeoPoint) -> TerritoryRegion?)? = null,
    private val regionDataVersion: () -> String? = { null },
    private val isProviderAvailable: (MapProvider) -> Boolean = { true },
) {
    private val explicitTerritoryClassifier = classifyTerritory
    private val classifyTerritoryPoint: (GeoPoint) -> TerritoryRegion? = classifyTerritory ?: { point ->
        if (classifyRegion(point) == JapanRegion.JAPAN) TerritoryRegion.JAPAN else TerritoryRegion.OTHER
    }

    fun transitExecutionStrategy(points: List<PilgrimagePoint>): TransitExecutionStrategy {
        require(points.isNotEmpty()) { "Select at least one pilgrimage point" }
        return if (explicitTerritoryClassifier == null) {
            classifyTransitExecutionStrategy(points, classifyRegion)
        } else {
            executionStrategy(TravelMode.TRANSIT, points.first().coordinate, points)
        }
    }

    fun executionStrategy(
        mode: TravelMode,
        start: GeoPoint,
        points: List<PilgrimagePoint>,
    ): TransitExecutionStrategy = resolveRouting(mode, start, points).executionStrategy(mode)

    suspend fun planRoad(request: RoadPlanRequest): TourPlan {
        require(request.mode != TravelMode.TRANSIT) { "Road planner requires drive, bike or walk mode" }
        require(request.selectedPoints.size >= 2) { "Select at least 2 pilgrimage points" }
        val startPoint = request.startPointId?.let { id ->
            request.selectedPoints.singleOrNull { it.id == id }
                ?: throw IllegalArgumentException("Start point must be selected")
        }
        val stops = request.selectedPoints.filterNot { it.id == request.startPointId }
        require(stops.isNotEmpty()) { "At least one stop must remain after the start" }
        val fixedEndPointId = if (request.endPolicy == EndPolicy.FIXED) {
            val fixedId = requireNotNull(request.fixedEndPointId) { "A fixed end point is required" }
            stops.singleOrNull { it.id == fixedId }?.id
                ?: throw IllegalArgumentException("Fixed end must be a selected point other than the start")
        } else {
            null
        }
        val regionalRouting = resolveRouting(request.mode, request.start, request.selectedPoints)
        val routingContext = regionalRouting.routingContext()
        val orderedStops = orderRoadStops(
            start = request.start,
            stops = stops,
            mode = request.mode,
            objective = request.objective,
            endPolicy = request.endPolicy,
            fixedEndPointId = fixedEndPointId,
            routingContext = routingContext,
        )
        val orderedPoints = listOfNotNull(startPoint) + orderedStops
        val routeCoordinates = buildRouteCoordinates(request.start, orderedStops, request.endPolicy)
        val route = roadDirections(request.mode, routeCoordinates, routingContext)
        return TourPlan(
            id = UUID.randomUUID().toString(),
            anime = request.anime,
            selectedPoints = request.selectedPoints,
            orderedPoints = orderedPoints,
            legs = route.toTourLegs(
                points = routeCoordinates,
                mode = request.mode,
                destinationPointIds = orderedStops.map { it.id } +
                    if (request.endPolicy == EndPolicy.RETURN_TO_START) listOf(null) else emptyList(),
            ),
            mode = request.mode,
            objective = request.objective,
            endPolicy = request.endPolicy,
            estimatedDurationSeconds = route.segments.sumOf { it.durationSeconds },
            attribution = routingContext.attribution(),
            initialStart = request.start,
            state = NavigationState.PLANNED,
            executionStrategy = regionalRouting.executionStrategy(request.mode),
            mapProvider = routingContext.provider,
            coordinateSystem = routingContext.responseCoordinateSystem,
            regionDataVersion = regionDataVersion(),
        )
    }

    suspend fun planTransit(
        request: TransitPlanRequest,
        onProgress: (completedSegments: Int, segmentCount: Int) -> Unit = { _, _ -> },
    ): TourPlan {
        require(request.selectedPoints.size >= 2) { "Select at least 2 pilgrimage points" }
        require(request.dwellMinutes >= 0) { "Dwell time cannot be negative" }
        val regionalRouting = resolveRouting(TravelMode.TRANSIT, request.start, request.selectedPoints)
        val executionStrategy = regionalRouting.executionStrategy(TravelMode.TRANSIT)
        if (executionStrategy == TransitExecutionStrategy.EXTERNAL_AMAP_MAINLAND) {
            require(request.timeMode != TransitTimeMode.ARRIVE_BY) {
                "AMap transit does not support arrive-by planning"
            }
            require(request.transitTravelModes.isEmpty()) { "AMap transit does not support transit-mode filters" }
        }
        if (executionStrategy == TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN) {
            return planExternalJapanTransit(request)
        }
        val routingContext = regionalRouting.routingContext()
        val startPoint = request.startPointId?.let { id ->
            request.selectedPoints.singleOrNull { it.id == id }
                ?: throw IllegalArgumentException("Start point must be selected")
        }
        val stops = request.selectedPoints.filterNot { it.id == request.startPointId }
        val orderedStops = optimizer.approximateGlobalOrder(
            start = request.start,
            points = stops,
            endPolicy = request.endPolicy,
            fixedEndPointId = request.fixedEndPointId,
        )
        val itinerary = buildTransitItinerary(
            start = request.start,
            orderedStops = orderedStops,
            timeMode = request.timeMode,
            anchorTime = requireNotNull(request.anchorTime) { "In-app transit requires an anchor time" },
            routingPreference = request.routingPreference,
            transitTravelModes = request.transitTravelModes,
            dwellMinutes = request.dwellMinutes,
            returnToStart = request.endPolicy == EndPolicy.RETURN_TO_START,
            routingContext = routingContext,
            onProgress = onProgress,
        )
        return TourPlan(
            id = UUID.randomUUID().toString(),
            anime = request.anime,
            selectedPoints = request.selectedPoints,
            orderedPoints = listOfNotNull(startPoint) + orderedStops,
            legs = itinerary.legs,
            mode = TravelMode.TRANSIT,
            objective = RouteObjective.FASTEST,
            endPolicy = request.endPolicy,
            estimatedDurationSeconds = itinerary.estimatedDurationSeconds(
                finalDwellMinutes = if (request.endPolicy == EndPolicy.RETURN_TO_START) 0 else request.dwellMinutes,
            ),
            attribution = routingContext.attribution(),
            departureTime = itinerary.departureTime,
            arrivalTime = itinerary.arrivalTime,
            transitTimeMode = request.timeMode,
            transitAnchorTime = if (request.timeMode == TransitTimeMode.NOW) {
                null
            } else {
                formatTransitDepartureTime(OffsetDateTime.parse(request.anchorTime))
            },
            transitRoutingPreference = request.routingPreference,
            transitTravelModes = request.transitTravelModes,
            dwellMinutes = request.dwellMinutes,
            initialStart = request.start,
            state = NavigationState.PLANNED,
            executionStrategy = executionStrategy,
            mapProvider = routingContext.provider,
            coordinateSystem = routingContext.responseCoordinateSystem,
            regionDataVersion = regionDataVersion(),
        )
    }

    fun planAmapExternalFallback(request: AmapExternalFallbackRequest): TourPlan {
        require(request.selectedPoints.size >= 2) { "Select at least 2 pilgrimage points" }
        require(
            request.orderedPoints.size == request.selectedPoints.size &&
                request.orderedPoints.map(PilgrimagePoint::id).toSet() ==
                request.selectedPoints.map(PilgrimagePoint::id).toSet(),
        ) {
            "Fallback order must contain every selected point exactly once"
        }
        val selectedStart = request.startPointId?.let { id ->
            request.selectedPoints.singleOrNull { it.id == id }
                ?: throw IllegalArgumentException("Start point must be selected")
        }
        if (selectedStart != null) {
            require(request.orderedPoints.firstOrNull()?.id == selectedStart.id) {
                "Selected start must remain first in fallback order"
            }
        }
        if (request.endPolicy == EndPolicy.FIXED) {
            val fixedEndPointId = requireNotNull(request.fixedEndPointId) {
                "A fixed end point is required"
            }
            require(fixedEndPointId != selectedStart?.id) {
                "Fixed end must differ from the selected start"
            }
            require(request.orderedPoints.lastOrNull()?.id == fixedEndPointId) {
                "Fixed end must remain last in fallback order"
            }
        }

        val resolved = resolveRouting(request.mode, request.start, request.selectedPoints)
        require(resolved.provider == MapProvider.AMAP) {
            "External AMap fallback is only available for AMap journeys"
        }
        val fallbackRegionDataVersion = regionDataVersion()?.takeIf(String::isNotBlank)
            ?: throw TerritoryRegionDataException("Approved region data version is unavailable")
        val visits = if (selectedStart == null) {
            request.orderedPoints
        } else {
            request.orderedPoints.drop(1)
        }
        return TourPlan(
            id = UUID.randomUUID().toString(),
            anime = request.anime,
            selectedPoints = request.selectedPoints,
            orderedPoints = request.orderedPoints,
            legs = externalMapLegs(
                start = request.start,
                orderedPoints = visits,
                endPolicy = request.endPolicy,
                mode = request.mode,
                strategy = TransitExecutionStrategy.EXTERNAL_AMAP_MAINLAND,
                includeApproximateDistance = false,
            ),
            mode = request.mode,
            objective = request.objective,
            endPolicy = request.endPolicy,
            estimatedDurationSeconds = 0.0,
            attribution = listOf(EXTERNAL_AMAP_SOURCE),
            transitTimeMode = TransitTimeMode.NOW,
            transitRoutingPreference = TransitRoutingPreference.RECOMMENDED,
            transitTravelModes = emptySet(),
            dwellMinutes = request.dwellMinutes,
            initialStart = request.start,
            state = NavigationState.PLANNED,
            executionStrategy = TransitExecutionStrategy.EXTERNAL_AMAP_MAINLAND,
            mapProvider = MapProvider.AMAP,
            coordinateSystem = CoordinateSystem.WGS84,
            regionDataVersion = fallbackRegionDataVersion,
            externalRouteFallback = true,
        )
    }

    suspend fun replanRemaining(
        plan: TourPlan,
        currentLocation: GeoPoint,
        completedPointIds: Set<String>,
        currentTime: String,
    ): TourPlan {
        val remaining = plan.orderedPoints.filterNot { it.id in completedPointIds }
        val originalStart = plan.initialStart ?: plan.legs.firstOrNull()?.from ?: currentLocation
        requirePlanRouting(plan, currentLocation, remaining, originalStart)
        if (
            plan.executionStrategy == TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN ||
            plan.isAmapExternalFallback()
        ) {
            return replanExternalJapanTransit(plan, currentLocation, completedPointIds)
        }
        val routingContext = plan.routingContext()
        if (remaining.isEmpty()) {
            val returnItinerary = if (plan.endPolicy != EndPolicy.RETURN_TO_START) {
                null
            } else if (plan.mode == TravelMode.TRANSIT) {
                buildTransitItinerary(
                    start = currentLocation,
                    orderedStops = emptyList(),
                    timeMode = TransitTimeMode.NOW,
                    anchorTime = currentTime,
                    routingPreference = plan.transitRoutingPreference,
                    transitTravelModes = plan.transitTravelModes,
                    dwellMinutes = plan.dwellMinutes,
                    returnToStart = true,
                    returnDestination = originalStart,
                    routingContext = routingContext,
                )
            } else {
                null
            }
            val returnLegs = if (plan.endPolicy != EndPolicy.RETURN_TO_START) {
                emptyList()
            } else if (plan.mode == TravelMode.TRANSIT) {
                requireNotNull(returnItinerary).legs
            } else {
                val coordinates = listOf(currentLocation, originalStart)
                roadDirections(plan.mode, coordinates, routingContext).toTourLegs(
                    points = coordinates,
                    mode = plan.mode,
                    destinationPointIds = listOf(null),
                )
            }
            return plan.copy(
                orderedPoints = emptyList(),
                legs = returnLegs,
                estimatedDurationSeconds = returnItinerary?.estimatedDurationSeconds() ?:
                    returnLegs.sumOf(TourLeg::durationSeconds),
                departureTime = returnItinerary?.departureTime ?: plan.departureTime,
                arrivalTime = returnItinerary?.arrivalTime ?: plan.arrivalTime,
                transitTimeMode = if (plan.mode == TravelMode.TRANSIT) TransitTimeMode.NOW else plan.transitTimeMode,
                transitAnchorTime = if (plan.mode == TravelMode.TRANSIT) null else plan.transitAnchorTime,
                initialStart = originalStart,
                mapProvider = routingContext.provider,
                coordinateSystem = routingContext.responseCoordinateSystem,
            )
        }

        val legs: List<TourLeg>
        var transitItinerary: BuiltTransitItinerary? = null
        val orderedRemaining: List<PilgrimagePoint>
        if (plan.mode == TravelMode.TRANSIT) {
            orderedRemaining = when (plan.endPolicy) {
                EndPolicy.FIXED -> {
                    val fixedEnd = remaining.lastOrNull()
                    if (fixedEnd == null) emptyList()
                    else optimizer.recommendTransitOrder(currentLocation, remaining.dropLast(1)) + fixedEnd
                }
                else -> optimizer.recommendTransitOrder(currentLocation, remaining)
            }
            transitItinerary = buildTransitItinerary(
                start = currentLocation,
                orderedStops = orderedRemaining,
                timeMode = TransitTimeMode.NOW,
                anchorTime = currentTime,
                routingPreference = plan.transitRoutingPreference,
                transitTravelModes = plan.transitTravelModes,
                dwellMinutes = plan.dwellMinutes,
                returnToStart = plan.endPolicy == EndPolicy.RETURN_TO_START,
                returnDestination = originalStart,
                routingContext = routingContext,
            )
            legs = transitItinerary.legs
        } else {
            orderedRemaining = orderRoadStops(
                start = currentLocation,
                stops = remaining,
                mode = plan.mode,
                objective = plan.objective,
                endPolicy = plan.endPolicy,
                fixedEndPointId = if (plan.endPolicy == EndPolicy.FIXED) remaining.last().id else null,
                routingContext = routingContext,
            )
            val coordinates = listOf(currentLocation) + orderedRemaining.map { it.coordinate } +
                if (plan.endPolicy == EndPolicy.RETURN_TO_START) listOf(originalStart) else emptyList()
            legs = roadDirections(plan.mode, coordinates, routingContext).toTourLegs(
                points = coordinates,
                mode = plan.mode,
                destinationPointIds = orderedRemaining.map { it.id } +
                    if (plan.endPolicy == EndPolicy.RETURN_TO_START) listOf(null) else emptyList(),
            )
        }
        return plan.copy(
            orderedPoints = orderedRemaining,
            legs = legs,
            estimatedDurationSeconds = transitItinerary?.estimatedDurationSeconds(
                finalDwellMinutes = if (plan.endPolicy == EndPolicy.RETURN_TO_START) 0 else plan.dwellMinutes,
            ) ?: legs.sumOf(TourLeg::durationSeconds),
            departureTime = transitItinerary?.departureTime ?: plan.departureTime,
            arrivalTime = transitItinerary?.arrivalTime ?: plan.arrivalTime,
            transitTimeMode = if (plan.mode == TravelMode.TRANSIT) TransitTimeMode.NOW else plan.transitTimeMode,
            transitAnchorTime = if (plan.mode == TravelMode.TRANSIT) null else plan.transitAnchorTime,
            initialStart = originalStart,
            mapProvider = routingContext.provider,
            coordinateSystem = routingContext.responseCoordinateSystem,
        )
    }

    suspend fun rebuild(plan: TourPlan, orderedPoints: List<PilgrimagePoint>): TourPlan {
        require(
            orderedPoints.size == plan.selectedPoints.size &&
                orderedPoints.map { it.id }.toSet() == plan.selectedPoints.map { it.id }.toSet(),
        ) {
            "Manual order must contain every selected point exactly once"
        }
        val start = plan.initialStart ?: plan.legs.firstOrNull()?.from ?: orderedPoints.first().coordinate
        requirePlanRouting(plan, start, orderedPoints, plan.initialStart ?: start)
        if (
            plan.executionStrategy == TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN ||
            plan.isAmapExternalFallback()
        ) {
            val externalPoints = if (
                plan.isAmapExternalFallback() && orderedPoints.firstOrNull()?.coordinate == start
            ) {
                orderedPoints.drop(1)
            } else {
                orderedPoints
            }
            return plan.copy(
                orderedPoints = orderedPoints,
                legs = externalMapLegs(
                    start = start,
                    orderedPoints = externalPoints,
                    endPolicy = plan.endPolicy,
                    mode = plan.mode,
                    strategy = plan.executionStrategy,
                    includeApproximateDistance = !plan.isAmapExternalFallback(),
                ),
                estimatedDurationSeconds = 0.0,
                departureTime = null,
                arrivalTime = null,
                transitTimeMode = TransitTimeMode.NOW,
                transitAnchorTime = null,
                transitRoutingPreference = TransitRoutingPreference.RECOMMENDED,
                transitTravelModes = emptySet(),
            )
        }
        val routingContext = plan.routingContext()
        val visits = if (orderedPoints.firstOrNull()?.coordinate == start) orderedPoints.drop(1) else orderedPoints
        var transitItinerary: BuiltTransitItinerary? = null
        val legs = if (plan.mode == TravelMode.TRANSIT) {
            val timeMode = plan.transitTimeMode
            val anchorTime = if (timeMode == TransitTimeMode.NOW) {
                requireNotNull(plan.departureTime)
            } else {
                requireNotNull(plan.transitAnchorTime)
            }
            transitItinerary = buildTransitItinerary(
                start = start,
                orderedStops = visits,
                timeMode = timeMode,
                anchorTime = anchorTime,
                routingPreference = plan.transitRoutingPreference,
                transitTravelModes = plan.transitTravelModes,
                dwellMinutes = plan.dwellMinutes,
                returnToStart = plan.endPolicy == EndPolicy.RETURN_TO_START,
                routingContext = routingContext,
            )
            transitItinerary.legs
        } else {
            val coordinates = buildRouteCoordinates(start, visits, plan.endPolicy)
            refreshChangedRoadLegs(
                plan = plan,
                coordinates = coordinates,
                destinationPointIds = visits.map { it.id } +
                    if (plan.endPolicy == EndPolicy.RETURN_TO_START) listOf(null) else emptyList(),
                routingContext = routingContext,
            )
        }
        return plan.copy(
            orderedPoints = orderedPoints,
            legs = legs,
            estimatedDurationSeconds = transitItinerary?.estimatedDurationSeconds(
                finalDwellMinutes = if (plan.endPolicy == EndPolicy.RETURN_TO_START) 0 else plan.dwellMinutes,
            ) ?: legs.sumOf(TourLeg::durationSeconds),
            departureTime = transitItinerary?.departureTime ?: plan.departureTime,
            arrivalTime = transitItinerary?.arrivalTime ?: plan.arrivalTime,
            mapProvider = routingContext.provider,
            coordinateSystem = routingContext.responseCoordinateSystem,
        )
    }

    internal suspend fun rebuildActiveFutureSuffix(
        plan: TourPlan,
        start: GeoPoint,
        orderedFuturePoints: List<PilgrimagePoint>,
        transitAnchorTime: String,
        reusableRoadLegs: List<TourLeg>,
    ): ActiveFutureSuffix {
        val originalStart = plan.initialStart ?: start
        requirePlanRouting(plan, start, orderedFuturePoints, originalStart)
        if (
            plan.executionStrategy == TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN ||
            plan.isAmapExternalFallback()
        ) {
            val legs = externalMapLegs(
                start = start,
                orderedPoints = orderedFuturePoints,
                endPolicy = plan.endPolicy,
                returnDestination = originalStart,
                mode = plan.mode,
                strategy = plan.executionStrategy,
                includeApproximateDistance = !plan.isAmapExternalFallback(),
            )
            return ActiveFutureSuffix(legs = legs, estimatedDurationSeconds = 0.0)
        }
        val routingContext = plan.routingContext()
        if (plan.mode == TravelMode.TRANSIT) {
            val itinerary = buildTransitItinerary(
                start = start,
                orderedStops = orderedFuturePoints,
                timeMode = TransitTimeMode.NOW,
                anchorTime = transitAnchorTime,
                routingPreference = plan.transitRoutingPreference,
                transitTravelModes = plan.transitTravelModes,
                dwellMinutes = plan.dwellMinutes,
                returnToStart = plan.endPolicy == EndPolicy.RETURN_TO_START,
                returnDestination = originalStart,
                routingContext = routingContext,
            )
            return ActiveFutureSuffix(
                legs = itinerary.legs,
                estimatedDurationSeconds = itinerary.estimatedDurationSeconds(
                    finalDwellMinutes = if (plan.endPolicy == EndPolicy.RETURN_TO_START) {
                        0
                    } else {
                        plan.dwellMinutes
                    },
                ),
                arrivalTime = itinerary.arrivalTime,
            )
        }

        val coordinates = listOf(start) + orderedFuturePoints.map(PilgrimagePoint::coordinate) +
            if (plan.endPolicy == EndPolicy.RETURN_TO_START) listOf(originalStart) else emptyList()
        val destinationPointIds = orderedFuturePoints.map(PilgrimagePoint::id) +
            if (plan.endPolicy == EndPolicy.RETURN_TO_START) listOf(null) else emptyList()
        val legs = if (coordinates.size < 2) {
            emptyList()
        } else {
            refreshChangedRoadLegs(
                plan = plan.copy(legs = reusableRoadLegs),
                coordinates = coordinates,
                destinationPointIds = destinationPointIds,
                routingContext = routingContext,
            )
        }
        return ActiveFutureSuffix(
            legs = legs,
            estimatedDurationSeconds = legs.sumOf(TourLeg::durationSeconds),
        )
    }

    private suspend fun buildTransitItinerary(
        start: GeoPoint,
        orderedStops: List<PilgrimagePoint>,
        timeMode: TransitTimeMode,
        anchorTime: String,
        routingPreference: TransitRoutingPreference,
        transitTravelModes: Set<TransitTravelMode>,
        dwellMinutes: Int,
        returnToStart: Boolean,
        returnDestination: GeoPoint = start,
        routingContext: RoutingProviderContext,
        onProgress: (completedSegments: Int, segmentCount: Int) -> Unit = { _, _ -> },
    ): BuiltTransitItinerary {
        val destinations = orderedStops.map { it.coordinate } +
            if (returnToStart) listOf(returnDestination) else emptyList()
        val normalizedAnchor = formatTransitDepartureTime(OffsetDateTime.parse(anchorTime))
        if (destinations.isEmpty()) {
            return BuiltTransitItinerary(emptyList(), normalizedAnchor, normalizedAnchor)
        }
        val itinerary = if (timeMode == TransitTimeMode.ARRIVE_BY) {
            buildArriveByTransitItinerary(
                start = start,
                destinations = destinations,
                orderedStops = orderedStops,
                arrivalTime = normalizedAnchor,
                routingPreference = routingPreference,
                transitTravelModes = transitTravelModes,
                dwellMinutes = dwellMinutes,
                routingContext = routingContext,
                onProgress = onProgress,
            )
        } else {
            buildDepartAtTransitItinerary(
                start = start,
                destinations = destinations,
                orderedStops = orderedStops,
                departureTime = normalizedAnchor,
                routingPreference = routingPreference,
                transitTravelModes = transitTravelModes,
                dwellMinutes = dwellMinutes,
                routingContext = routingContext,
                onProgress = onProgress,
            )
        }
        if (
            itinerary.legs.any {
                it.from != it.to || it.distanceMeters > 0.0 || it.durationSeconds > 0.0
            } &&
            itinerary.legs.none { it.mode == TravelMode.TRANSIT }
        ) {
            throw TransitRideUnavailableException()
        }
        return itinerary
    }

    private suspend fun buildDepartAtTransitItinerary(
        start: GeoPoint,
        destinations: List<GeoPoint>,
        orderedStops: List<PilgrimagePoint>,
        departureTime: String,
        routingPreference: TransitRoutingPreference,
        transitTravelModes: Set<TransitTravelMode>,
        dwellMinutes: Int,
        routingContext: RoutingProviderContext,
        onProgress: (completedSegments: Int, segmentCount: Int) -> Unit,
    ): BuiltTransitItinerary {
        val legs = mutableListOf<TourLeg>()
        var from = start
        var nextDeparture = departureTime
        var actualDeparture = departureTime
        var actualArrival = departureTime
        destinations.forEachIndexed { index, destination ->
            val journey = transitJourneyOrWalk(
                from = from,
                to = destination,
                query = TransitJourneyQuery(
                    departureTime = nextDeparture,
                    routingPreference = routingPreference,
                    transitTravelModes = transitTravelModes,
                ),
                segmentIndex = index,
                segmentCount = destinations.size,
                routingContext = routingContext,
            )
            if (index == 0) {
                actualDeparture = formatTransitDepartureTime(OffsetDateTime.parse(journey.departureTime))
            }
            actualArrival = formatTransitDepartureTime(OffsetDateTime.parse(journey.arrivalTime))
            legs += journey.withDestinationPointId(orderedStops.getOrNull(index)?.id)
            onProgress(index + 1, destinations.size)
            if (index < destinations.lastIndex) {
                nextDeparture = formatTransitDepartureTime(
                    OffsetDateTime.parse(journey.arrivalTime).plusMinutes(dwellMinutes.toLong()),
                )
            }
            from = destination
        }
        return BuiltTransitItinerary(legs, actualDeparture, actualArrival)
    }

    private suspend fun buildArriveByTransitItinerary(
        start: GeoPoint,
        destinations: List<GeoPoint>,
        orderedStops: List<PilgrimagePoint>,
        arrivalTime: String,
        routingPreference: TransitRoutingPreference,
        transitTravelModes: Set<TransitTravelMode>,
        dwellMinutes: Int,
        routingContext: RoutingProviderContext,
        onProgress: (completedSegments: Int, segmentCount: Int) -> Unit,
    ): BuiltTransitItinerary {
        val origins = listOf(start) + destinations.dropLast(1)
        val segmentLegs = MutableList<List<TourLeg>>(destinations.size) { emptyList() }
        var previousArrivalDeadline = arrivalTime
        var actualDeparture = arrivalTime
        var actualArrival = arrivalTime
        var completedSegments = 0
        for (index in destinations.indices.reversed()) {
            val journey = transitJourneyOrWalk(
                from = origins[index],
                to = destinations[index],
                query = TransitJourneyQuery(
                    arrivalTime = previousArrivalDeadline,
                    routingPreference = routingPreference,
                    transitTravelModes = transitTravelModes,
                ),
                segmentIndex = index,
                segmentCount = destinations.size,
                routingContext = routingContext,
            )
            if (index == destinations.lastIndex) {
                actualArrival = formatTransitDepartureTime(OffsetDateTime.parse(journey.arrivalTime))
            }
            actualDeparture = formatTransitDepartureTime(OffsetDateTime.parse(journey.departureTime))
            segmentLegs[index] = journey.withDestinationPointId(orderedStops.getOrNull(index)?.id)
            completedSegments += 1
            onProgress(completedSegments, destinations.size)
            if (index > 0) {
                previousArrivalDeadline = formatTransitDepartureTime(
                    OffsetDateTime.parse(journey.departureTime).minusMinutes(dwellMinutes.toLong()),
                )
            }
        }
        return BuiltTransitItinerary(segmentLegs.flatten(), actualDeparture, actualArrival)
    }

    private suspend fun transitJourneyOrWalk(
        from: GeoPoint,
        to: GeoPoint,
        query: TransitJourneyQuery,
        segmentIndex: Int,
        segmentCount: Int,
        routingContext: RoutingProviderContext,
    ): TransitJourney = if (from == to) {
        walkingConnector(from, to, query, routingContext)
    } else try {
        transitJourney(from, to, query, routingContext)
    } catch (transitFailure: ApiException.NoRoute) {
        try {
            walkingConnector(from, to, query, routingContext)
        } catch (walkingFailure: ApiException.NoRoute) {
            throw TransitSegmentUnavailableException(
                segmentNumber = segmentIndex + 1,
                segmentCount = segmentCount,
                from = from,
                to = to,
                cause = walkingFailure,
            )
        }
    }

    private suspend fun walkingConnector(
        from: GeoPoint,
        to: GeoPoint,
        query: TransitJourneyQuery,
        routingContext: RoutingProviderContext,
    ): TransitJourney {
        val route = if (from == to) {
            RoadRoute(
                listOf(
                    RoadRouteSegment(
                        geometry = if (routingContext.responseCoordinateSystem == CoordinateSystem.WGS84) {
                            listOf(from)
                        } else {
                            emptyList()
                        },
                        steps = emptyList(),
                        distanceMeters = 0.0,
                        durationSeconds = 0.0,
                        provider = routingContext.provider,
                        coordinateSystem = routingContext.responseCoordinateSystem,
                    ),
                ),
            )
        } else {
            roadDirections(TravelMode.WALK, listOf(from, to), routingContext)
        }
        if (route.segments.size != 1) {
            throw ApiException.InvalidResponse(IllegalStateException("Walking connector must contain one segment"))
        }
        val durationSeconds = route.segments.single().durationSeconds.roundToLong()
        val requestedDeparture = query.departureTime?.let(OffsetDateTime::parse)
        val requestedArrival = query.arrivalTime?.let(OffsetDateTime::parse)
        val departure = requestedDeparture ?: requireNotNull(requestedArrival).minusSeconds(durationSeconds)
        val arrival = requestedArrival ?: departure.plusSeconds(durationSeconds)
        return TransitJourney(
            legs = route.toTourLegs(
                points = listOf(from, to),
                mode = TravelMode.WALK,
                destinationPointIds = listOf(null),
            ),
            departureTime = formatTransitDepartureTime(departure),
            arrivalTime = formatTransitDepartureTime(arrival),
        )
    }

    private fun TransitJourney.withDestinationPointId(pointId: String?): List<TourLeg> =
        legs.mapIndexed { index, leg ->
            if (index == legs.lastIndex) leg.copy(destinationPointId = pointId) else leg
        }

    private fun buildRouteCoordinates(
        start: GeoPoint,
        orderedStops: List<PilgrimagePoint>,
        endPolicy: EndPolicy,
    ): List<GeoPoint> = listOf(start) + orderedStops.map { it.coordinate } +
        if (endPolicy == EndPolicy.RETURN_TO_START) listOf(start) else emptyList()

    private suspend fun orderRoadStops(
        start: GeoPoint,
        stops: List<PilgrimagePoint>,
        mode: TravelMode,
        objective: RouteObjective,
        endPolicy: EndPolicy,
        fixedEndPointId: String?,
        routingContext: RoutingProviderContext,
    ): List<PilgrimagePoint> {
        val approximate = optimizer.approximateGlobalOrder(
            start = start,
            points = stops,
            endPolicy = endPolicy,
            fixedEndPointId = fixedEndPointId,
        )
        val refined = ArrayList<PilgrimagePoint>(approximate.size)
        var anchor = start
        approximate.chunked(TourRequestBatcher.MAX_MATRIX_LOCATIONS - 1).forEachIndexed { index, window ->
            val matrix = roadMatrixProvider(
                mode = mode,
                points = listOf(anchor) + window.map(PilgrimagePoint::coordinate),
                objective = objective,
                routingContext = routingContext,
            )
            val costs = when (objective) {
                RouteObjective.FASTEST -> matrix.durations
                RouteObjective.SHORTEST -> matrix.distances
            }
            val isLastWindow = index == (approximate.size - 1) / (TourRequestBatcher.MAX_MATRIX_LOCATIONS - 1)
            val localEndPolicy = if (isLastWindow && endPolicy == EndPolicy.FIXED) {
                EndPolicy.FIXED
            } else {
                EndPolicy.OPEN
            }
            val localFixedEndIndex = if (localEndPolicy == EndPolicy.FIXED) window.lastIndex else null
            val localOrder = optimizer.optimizeRoad(costs, localEndPolicy, localFixedEndIndex)
            val orderedWindow = localOrder.map(window::get)
            refined += orderedWindow
            anchor = orderedWindow.last().coordinate
        }
        return refined
    }

    private suspend fun roadDirections(
        mode: TravelMode,
        locations: List<GeoPoint>,
        routingContext: RoutingProviderContext,
    ): RoadRoute {
        val segments = TourRequestBatcher.routeBatches(locations).flatMap { batch ->
            roadDirectionsProvider(mode, batch, routingContext).segments
        }
        if (
            segments.any {
                it.provider != routingContext.provider ||
                    it.coordinateSystem != routingContext.responseCoordinateSystem
            }
        ) {
            throw ApiException.InvalidResponse(
                IllegalStateException("Route segments mix providers or coordinate systems"),
            )
        }
        return RoadRoute(segments)
    }

    private suspend fun refreshChangedRoadLegs(
        plan: TourPlan,
        coordinates: List<GeoPoint>,
        destinationPointIds: List<String?>,
        routingContext: RoutingProviderContext,
    ): List<TourLeg> {
        val legCount = coordinates.size - 1
        val usedLegIndexes = mutableSetOf<Int>()
        val refreshed = MutableList<TourLeg?>(legCount) { index ->
            val reusableIndex = plan.legs.indices.firstOrNull { candidateIndex ->
                candidateIndex !in usedLegIndexes && plan.legs[candidateIndex].let { candidate ->
                    candidate.mode == plan.mode &&
                        candidate.provider == routingContext.provider &&
                        candidate.coordinateSystem == routingContext.responseCoordinateSystem &&
                        candidate.from == coordinates[index] &&
                        candidate.to == coordinates[index + 1] &&
                        candidate.destinationPointId == destinationPointIds.getOrNull(index)
                }
            }
            if (reusableIndex == null) {
                null
            } else {
                usedLegIndexes += reusableIndex
                plan.legs[reusableIndex]
            }
        }
        val changedIndexes = refreshed.indices.filter { refreshed[it] == null }
        if (changedIndexes.isEmpty()) return refreshed.map { requireNotNull(it) }

        changedLegRanges(changedIndexes).forEach { range ->
            val requestPoints = coordinates.slice(range.first..range.last + 1)
            val segments = roadDirectionsProvider(plan.mode, requestPoints, routingContext).segments
            check(segments.size == range.count()) { "Route leg count does not match changed locations" }
            segments.forEachIndexed { offset, segment ->
                val index = range.first + offset
                refreshed[index] = TourLeg(
                    from = coordinates[index],
                    to = coordinates[index + 1],
                    mode = plan.mode,
                    geometry = segment.geometry,
                    steps = segment.steps,
                    distanceMeters = segment.distanceMeters,
                    durationSeconds = segment.durationSeconds,
                    source = segment.provider.routeSource(),
                    provider = segment.provider,
                    coordinateSystem = segment.coordinateSystem,
                    destinationPointId = destinationPointIds.getOrNull(index),
                )
            }
        }
        return refreshed.map { requireNotNull(it) }
    }

    private fun changedLegRanges(changedIndexes: List<Int>): List<IntRange> {
        val maxLegs = TourRequestBatcher.MAX_ROUTE_LOCATIONS - 1
        val ranges = mutableListOf<IntRange>()
        var start = changedIndexes.first()
        var end = start
        changedIndexes.drop(1).forEach { index ->
            if (index == end + 1 && index - start < maxLegs) {
                end = index
            } else {
                ranges += start..end
                start = index
                end = index
            }
        }
        ranges += start..end
        return ranges
    }

    private fun RoadRoute.toTourLegs(
        points: List<GeoPoint>,
        mode: TravelMode,
        destinationPointIds: List<String?>,
    ): List<TourLeg> =
        segments.mapIndexed { index, segment ->
            TourLeg(
                from = points[index],
                to = points[index + 1],
                mode = mode,
                geometry = segment.geometry,
                steps = segment.steps,
                distanceMeters = segment.distanceMeters,
                durationSeconds = segment.durationSeconds,
                source = segment.provider.routeSource(),
                provider = segment.provider,
                coordinateSystem = segment.coordinateSystem,
                destinationPointId = destinationPointIds.getOrNull(index),
            )
        }

    private fun planExternalJapanTransit(request: TransitPlanRequest): TourPlan {
        val selectedStart = request.startPointId?.let { id ->
            request.selectedPoints.singleOrNull { it.id == id }
                ?: throw IllegalArgumentException("Start point must be selected")
        }
        val orderedPoints = if (selectedStart == null) {
            optimizer.approximateGlobalOrder(
                start = request.start,
                points = request.selectedPoints,
                endPolicy = request.endPolicy,
                fixedEndPointId = request.fixedEndPointId,
            )
        } else {
            val remaining = request.selectedPoints.filterNot { it.id == selectedStart.id }
            listOf(selectedStart) + optimizer.approximateGlobalOrder(
                start = selectedStart.coordinate,
                points = remaining,
                endPolicy = request.endPolicy,
                fixedEndPointId = request.fixedEndPointId,
            )
        }
        return TourPlan(
            id = UUID.randomUUID().toString(),
            anime = request.anime,
            selectedPoints = request.selectedPoints,
            orderedPoints = orderedPoints,
            legs = externalMapLegs(
                start = request.start,
                orderedPoints = orderedPoints,
                endPolicy = request.endPolicy,
                mode = TravelMode.TRANSIT,
                strategy = TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN,
            ),
            mode = TravelMode.TRANSIT,
            objective = RouteObjective.FASTEST,
            endPolicy = request.endPolicy,
            estimatedDurationSeconds = 0.0,
            attribution = listOf(EXTERNAL_GOOGLE_MAPS_SOURCE),
            transitTimeMode = TransitTimeMode.NOW,
            transitRoutingPreference = TransitRoutingPreference.RECOMMENDED,
            transitTravelModes = emptySet(),
            dwellMinutes = request.dwellMinutes,
            initialStart = request.start,
            state = NavigationState.PLANNED,
            executionStrategy = TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN,
            mapProvider = MapProvider.GOOGLE,
            coordinateSystem = CoordinateSystem.WGS84,
            regionDataVersion = regionDataVersion(),
        )
    }

    private suspend fun transitJourney(
        from: GeoPoint,
        to: GeoPoint,
        query: TransitJourneyQuery,
        routingContext: RoutingProviderContext,
    ): TransitJourney {
        val provider = transitProvider as? ProviderAwareTransitJourneyProvider
            ?: requireGoogleOnlyProvider(routingContext, "transit") { transitProvider }
        val journey = if (provider is ProviderAwareTransitJourneyProvider) {
            provider.journey(from, to, query, routingContext)
        } else {
            provider.journey(from, to, query)
        }
        if (
            journey.legs.any {
                it.provider != routingContext.provider ||
                    it.coordinateSystem != routingContext.responseCoordinateSystem
            }
        ) {
            throw ApiException.InvalidResponse(
                IllegalStateException("Transit legs mix providers or coordinate systems"),
            )
        }
        return journey
    }

    private suspend fun roadMatrixProvider(
        mode: TravelMode,
        points: List<GeoPoint>,
        objective: RouteObjective,
        routingContext: RoutingProviderContext,
    ): TravelMatrix {
        val provider = roadProvider as? ProviderAwareRoadRoutingProvider
            ?: requireGoogleOnlyProvider(routingContext, "road matrix") { roadProvider }
        return if (provider is ProviderAwareRoadRoutingProvider) {
            provider.matrix(mode, points, objective, routingContext)
        } else {
            provider.matrix(mode, points, objective)
        }
    }

    private suspend fun roadDirectionsProvider(
        mode: TravelMode,
        points: List<GeoPoint>,
        routingContext: RoutingProviderContext,
    ): RoadRoute {
        val provider = roadProvider as? ProviderAwareRoadRoutingProvider
            ?: requireGoogleOnlyProvider(routingContext, "road route") { roadProvider }
        return if (provider is ProviderAwareRoadRoutingProvider) {
            provider.directions(mode, points, routingContext)
        } else {
            provider.directions(mode, points)
        }
    }

    private fun <T> requireGoogleOnlyProvider(
        routingContext: RoutingProviderContext,
        operation: String,
        provider: () -> T,
    ): T {
        require(routingContext == RoutingProviderContext.GOOGLE) {
            "$operation provider does not support explicit AMap dispatch"
        }
        return provider()
    }

    private fun replanExternalJapanTransit(
        plan: TourPlan,
        currentLocation: GeoPoint,
        completedPointIds: Set<String>,
    ): TourPlan {
        val remaining = plan.orderedPoints.filterNot { it.id in completedPointIds }
        val originalStart = plan.initialStart ?: currentLocation
        val legs = externalMapLegs(
            start = currentLocation,
            orderedPoints = remaining,
            endPolicy = plan.endPolicy,
            returnDestination = originalStart,
            mode = plan.mode,
            strategy = plan.executionStrategy,
            includeApproximateDistance = !plan.isAmapExternalFallback(),
        )
        return plan.copy(
            orderedPoints = remaining,
            legs = legs,
            estimatedDurationSeconds = 0.0,
            departureTime = null,
            arrivalTime = null,
            transitTimeMode = TransitTimeMode.NOW,
            transitAnchorTime = null,
            transitRoutingPreference = TransitRoutingPreference.RECOMMENDED,
            transitTravelModes = emptySet(),
            initialStart = originalStart,
        )
    }

    private fun externalMapLegs(
        start: GeoPoint,
        orderedPoints: List<PilgrimagePoint>,
        endPolicy: EndPolicy,
        mode: TravelMode,
        strategy: TransitExecutionStrategy,
        returnDestination: GeoPoint = start,
        includeApproximateDistance: Boolean = true,
    ): List<TourLeg> {
        val destinations = orderedPoints.map(PilgrimagePoint::coordinate) +
            if (endPolicy == EndPolicy.RETURN_TO_START) listOf(returnDestination) else emptyList()
        val destinationIds = orderedPoints.map(PilgrimagePoint::id) +
            if (endPolicy == EndPolicy.RETURN_TO_START) listOf(null) else emptyList()
        var origin = start
        return destinations.mapIndexed { index, destination ->
            TourLeg(
                from = origin,
                to = destination,
                mode = mode,
                geometry = emptyList(),
                steps = emptyList(),
                distanceMeters = if (includeApproximateDistance) {
                    TourOptimizer.haversineMeters(origin, destination)
                } else {
                    0.0
                },
                durationSeconds = 0.0,
                source = if (strategy == TransitExecutionStrategy.EXTERNAL_AMAP_MAINLAND) {
                    EXTERNAL_AMAP_SOURCE
                } else {
                    EXTERNAL_GOOGLE_MAPS_SOURCE
                },
                provider = if (strategy == TransitExecutionStrategy.EXTERNAL_AMAP_MAINLAND) {
                    MapProvider.AMAP
                } else {
                    MapProvider.GOOGLE
                },
                coordinateSystem = CoordinateSystem.WGS84,
                destinationPointId = destinationIds[index],
            ).also { origin = destination }
        }
    }

    private fun resolveRouting(
        mode: TravelMode,
        start: GeoPoint,
        points: List<PilgrimagePoint>,
    ): ResolvedJourneyProvider = resolveRoutingCoordinates(
        mode = mode,
        start = start,
        destinations = points.map(PilgrimagePoint::coordinate),
    )

    private fun resolveRoutingCoordinates(
        mode: TravelMode,
        start: GeoPoint,
        destinations: List<GeoPoint>,
    ): ResolvedJourneyProvider = try {
        resolveJourneyProvider(
            start = start,
            destinations = destinations,
            mode = mode,
            classify = classifyTerritoryPoint,
        ).also { resolved ->
            if (!isProviderAvailable(resolved.provider)) {
                throw MapProviderUnavailableException(resolved.provider)
            }
        }
    } catch (exception: JourneyProviderResolutionException.MixedTransitRegions) {
        if (explicitTerritoryClassifier == null) throw MixedTransitRegionException()
        throw exception
    }

    private fun requirePlanRouting(
        plan: TourPlan,
        start: GeoPoint,
        orderedPoints: List<PilgrimagePoint>,
        returnDestination: GeoPoint,
    ) {
        val destinations = orderedPoints.map(PilgrimagePoint::coordinate) +
            if (plan.endPolicy == EndPolicy.RETURN_TO_START) listOf(returnDestination) else emptyList()
        val resolved = resolveRoutingCoordinates(plan.mode, start, destinations)
        require(
            resolved.provider == plan.mapProvider &&
                resolved.executionStrategy(plan.mode) == plan.executionStrategy,
        ) {
            "Map provider no longer matches the current journey"
        }
    }

}

private fun ResolvedJourneyProvider.executionStrategy(mode: TravelMode): TransitExecutionStrategy = when {
    provider == MapProvider.AMAP -> TransitExecutionStrategy.EXTERNAL_AMAP_MAINLAND
    mode == TravelMode.TRANSIT && regions == setOf(TerritoryRegion.JAPAN) ->
        TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN
    else -> TransitExecutionStrategy.IN_APP_GOOGLE_ROUTES
}

private fun ResolvedJourneyProvider.routingContext(): RoutingProviderContext = when (provider) {
    MapProvider.GOOGLE -> RoutingProviderContext.GOOGLE
    MapProvider.AMAP -> RoutingProviderContext.AMAP
}

private fun TourPlan.routingContext(): RoutingProviderContext = when {
    mapProvider == MapProvider.GOOGLE && coordinateSystem == CoordinateSystem.WGS84 ->
        RoutingProviderContext.GOOGLE
    mapProvider == MapProvider.AMAP && coordinateSystem == CoordinateSystem.GCJ02 ->
        RoutingProviderContext.AMAP
    mapProvider == MapProvider.AMAP &&
        coordinateSystem == CoordinateSystem.WGS84 &&
        legs.all { it.geometry.isEmpty() } -> RoutingProviderContext.AMAP
    else -> throw IllegalArgumentException("Tour provider and route coordinate system do not match")
}

internal fun TourPlan.isAmapExternalFallback(): Boolean =
    externalRouteFallback &&
    executionStrategy == TransitExecutionStrategy.EXTERNAL_AMAP_MAINLAND &&
        mapProvider == MapProvider.AMAP &&
        coordinateSystem == CoordinateSystem.WGS84

private fun RoutingProviderContext.attribution(): List<String> = when (provider) {
    MapProvider.GOOGLE -> listOf(GOOGLE_ROUTES_SOURCE, "Google")
    MapProvider.AMAP -> listOf(AMAP_WEB_SERVICE_SOURCE, "AMap")
}

private fun MapProvider.routeSource(): String = when (this) {
    MapProvider.GOOGLE -> GOOGLE_ROUTES_SOURCE
    MapProvider.AMAP -> AMAP_WEB_SERVICE_SOURCE
}

private val transitDepartureTimeFormatter = DateTimeFormatterBuilder()
    .append(DateTimeFormatter.ISO_LOCAL_DATE)
    .appendLiteral('T')
    .appendPattern("HH:mm:ss")
    .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
    .appendOffsetId()
    .toFormatter(Locale.ROOT)

internal fun formatTransitDepartureTime(value: OffsetDateTime): String =
    transitDepartureTimeFormatter.format(value)

data class RoadPlanRequest(
    val anime: Anime,
    val selectedPoints: List<PilgrimagePoint>,
    val start: GeoPoint,
    val startPointId: String? = null,
    val mode: TravelMode,
    val objective: RouteObjective,
    val endPolicy: EndPolicy,
    val fixedEndPointId: String? = null,
)

data class TransitPlanRequest(
    val anime: Anime,
    val selectedPoints: List<PilgrimagePoint>,
    val start: GeoPoint,
    val startPointId: String? = null,
    val endPolicy: EndPolicy,
    val fixedEndPointId: String? = null,
    val timeMode: TransitTimeMode,
    val anchorTime: String? = null,
    val routingPreference: TransitRoutingPreference = TransitRoutingPreference.RECOMMENDED,
    val transitTravelModes: Set<TransitTravelMode> = emptySet(),
    val dwellMinutes: Int = 15,
)

data class AmapExternalFallbackRequest(
    val anime: Anime,
    val selectedPoints: List<PilgrimagePoint>,
    val orderedPoints: List<PilgrimagePoint>,
    val start: GeoPoint,
    val startPointId: String? = null,
    val mode: TravelMode,
    val objective: RouteObjective,
    val endPolicy: EndPolicy,
    val fixedEndPointId: String? = null,
    val dwellMinutes: Int = 15,
)

private data class BuiltTransitItinerary(
    val legs: List<TourLeg>,
    val departureTime: String,
    val arrivalTime: String,
) {
    fun estimatedDurationSeconds(finalDwellMinutes: Int = 0): Double =
        Duration.between(
            OffsetDateTime.parse(departureTime),
            OffsetDateTime.parse(arrivalTime),
        ).seconds.coerceAtLeast(0).toDouble() + finalDwellMinutes * 60.0
}

internal data class ActiveFutureSuffix(
    val legs: List<TourLeg>,
    val estimatedDurationSeconds: Double,
    val arrivalTime: String? = null,
)

class TransitSegmentUnavailableException(
    val segmentNumber: Int,
    val segmentCount: Int,
    val from: GeoPoint,
    val to: GeoPoint,
    cause: Throwable? = null,
) : Exception("Transit segment $segmentNumber of $segmentCount has no transit or walking route", cause)

class TransitRideUnavailableException : Exception("Transit itinerary contains no transit ride")

class MapProviderUnavailableException(
    val provider: MapProvider,
) : IllegalStateException("${provider.name} map provider is not available")

const val MIXED_TRANSIT_REGION_MESSAGE = "不支持此操作，请去除日本或日本以外的点。"
const val REGION_DATA_ERROR_MESSAGE = "地区数据无法读取，请重新安装应用后重试"
const val EXTERNAL_GOOGLE_MAPS_SOURCE = "Google Maps"
const val EXTERNAL_AMAP_SOURCE = "AMap"

class MixedTransitRegionException : IllegalArgumentException(MIXED_TRANSIT_REGION_MESSAGE)

fun classifyTransitExecutionStrategy(
    points: List<PilgrimagePoint>,
    classifyRegion: (GeoPoint) -> JapanRegion,
): TransitExecutionStrategy {
    require(points.isNotEmpty()) { "Select at least one pilgrimage point" }
    val regions = points.mapTo(mutableSetOf()) { classifyRegion(it.coordinate) }
    if (regions.size > 1) throw MixedTransitRegionException()
    return if (regions.single() == JapanRegion.JAPAN) {
        TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN
    } else {
        TransitExecutionStrategy.IN_APP_GOOGLE_ROUTES
    }
}
