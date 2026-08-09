package cn.anitabi.navigator.core.routing

import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.CoordinateSystem
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.core.model.RouteObjective
import cn.anitabi.navigator.core.model.RouteStep
import cn.anitabi.navigator.core.model.TourLeg
import cn.anitabi.navigator.core.model.TransitLegDetails
import cn.anitabi.navigator.core.model.TransitRoutingPreference
import cn.anitabi.navigator.core.model.TransitTravelMode
import cn.anitabi.navigator.core.model.TravelMode
import cn.anitabi.navigator.data.network.ApiException
import cn.anitabi.navigator.data.network.backend.BackendApi
import cn.anitabi.navigator.data.network.backend.BackendRouteLeg
import cn.anitabi.navigator.data.network.backend.BackendRouteStep
import java.time.OffsetDateTime
import kotlin.math.roundToLong

interface RoadRoutingProvider {
    suspend fun matrix(
        mode: TravelMode,
        points: List<GeoPoint>,
        objective: RouteObjective,
    ): TravelMatrix

    suspend fun directions(mode: TravelMode, points: List<GeoPoint>): RoadRoute
}

data class RoutingProviderContext(
    val provider: MapProvider,
    val responseCoordinateSystem: CoordinateSystem,
) {
    init {
        require(
            (provider == MapProvider.GOOGLE && responseCoordinateSystem == CoordinateSystem.WGS84) ||
                (provider == MapProvider.AMAP && responseCoordinateSystem == CoordinateSystem.GCJ02),
        ) { "Route provider and response coordinate system do not match" }
    }

    companion object {
        val GOOGLE = RoutingProviderContext(MapProvider.GOOGLE, CoordinateSystem.WGS84)
        val AMAP = RoutingProviderContext(MapProvider.AMAP, CoordinateSystem.GCJ02)
    }
}

interface ProviderAwareRoadRoutingProvider : RoadRoutingProvider {
    suspend fun matrix(
        mode: TravelMode,
        points: List<GeoPoint>,
        objective: RouteObjective,
        context: RoutingProviderContext,
    ): TravelMatrix

    suspend fun directions(
        mode: TravelMode,
        points: List<GeoPoint>,
        context: RoutingProviderContext,
    ): RoadRoute
}

data class TravelMatrix(
    val durations: List<List<Double?>>,
    val distances: List<List<Double?>>,
)

data class RoadRoute(val segments: List<RoadRouteSegment>)

data class RoadRouteSegment(
    val geometry: List<GeoPoint>,
    val steps: List<RouteStep>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val provider: MapProvider = MapProvider.GOOGLE,
    val coordinateSystem: CoordinateSystem = CoordinateSystem.WGS84,
)

class BackendRoadRoutingProvider(private val api: BackendApi) : ProviderAwareRoadRoutingProvider {
    override suspend fun matrix(
        mode: TravelMode,
        points: List<GeoPoint>,
        objective: RouteObjective,
    ): TravelMatrix = matrix(mode, points, objective, RoutingProviderContext.GOOGLE)

    override suspend fun matrix(
        mode: TravelMode,
        points: List<GeoPoint>,
        objective: RouteObjective,
        context: RoutingProviderContext,
    ): TravelMatrix {
        val response = api.matrix(
            mode = mode,
            coordinates = points,
            objective = objective,
            expectedProvider = context.provider,
            expectedCoordinateSystem = context.responseCoordinateSystem,
        )
        val durations = List(points.size) { MutableList<Double?>(points.size) { null } }
        val distances = List(points.size) { MutableList<Double?>(points.size) { null } }
        response.elements.forEach { element ->
            if (element.originIndex !in points.indices || element.destinationIndex !in points.indices) {
                throw ApiException.InvalidResponse(IllegalStateException("Matrix index is outside the request"))
            }
            if (element.status == "OK") {
                durations[element.originIndex][element.destinationIndex] = element.durationSeconds
                distances[element.originIndex][element.destinationIndex] = element.distanceMeters
            } else if (element.status != "UNREACHABLE") {
                throw ApiException.InvalidResponse(IllegalStateException("Unknown matrix element status"))
            }
        }
        points.indices.forEach { index ->
            durations[index][index] = 0.0
            distances[index][index] = 0.0
        }
        return TravelMatrix(durations, distances)
    }

    override suspend fun directions(mode: TravelMode, points: List<GeoPoint>): RoadRoute =
        directions(mode, points, RoutingProviderContext.GOOGLE)

    override suspend fun directions(
        mode: TravelMode,
        points: List<GeoPoint>,
        context: RoutingProviderContext,
    ): RoadRoute {
        val response = api.route(
            mode = mode,
            locations = points,
            expectedProvider = context.provider,
            expectedCoordinateSystem = context.responseCoordinateSystem,
        )
        if (response.legs.size != points.size - 1) {
            throw ApiException.InvalidResponse(IllegalStateException("Route leg count does not match locations"))
        }
        return RoadRoute(
            response.legs.mapIndexed { index, leg ->
                leg.toRoadSegment(
                    from = points[index],
                    to = points[index + 1],
                    provider = response.provider,
                    coordinateSystem = response.coordinateSystem,
                )
            },
        )
    }
}

private fun BackendRouteLeg.toRoadSegment(
    from: GeoPoint,
    to: GeoPoint,
    provider: MapProvider,
    coordinateSystem: CoordinateSystem,
): RoadRouteSegment {
    val decodedGeometry = decodeGooglePolyline(encodedPolyline)
        .ifEmpty { steps.flatMap { decodeGooglePolyline(it.encodedPolyline) }.withoutConsecutiveDuplicates() }
    if (
        coordinateSystem == CoordinateSystem.GCJ02 &&
        decodedGeometry.isEmpty() &&
        (from != to || distanceMeters > 0.0 || durationSeconds > 0.0)
    ) {
        throw ApiException.InvalidResponse(
            IllegalStateException("AMap route geometry is required for a nontrivial route"),
        )
    }
    val geometry = decodedGeometry.ifEmpty {
        if (coordinateSystem == CoordinateSystem.WGS84) listOf(from, to) else emptyList()
    }
    return RoadRouteSegment(
        geometry = geometry,
        steps = steps.map { step ->
            val stepGeometry = decodeGooglePolyline(step.encodedPolyline)
            RouteStep(
                instruction = step.instruction?.takeIf(String::isNotBlank) ?: roadInstruction(step.travelMode),
                distanceMeters = step.distanceMeters,
                durationSeconds = step.durationSeconds,
                coordinate = stepGeometry.firstOrNull(),
            )
        },
        distanceMeters = distanceMeters,
        durationSeconds = durationSeconds,
        provider = provider,
        coordinateSystem = coordinateSystem,
    )
}

private fun roadInstruction(travelMode: String): String = when (travelMode) {
    "DRIVE" -> "沿路线继续行驶"
    "BICYCLE" -> "沿路线继续骑行"
    "TRANSIT" -> "乘坐公共交通"
    else -> "沿路线继续步行"
}

interface TransitJourneyProvider {
    suspend fun journey(from: GeoPoint, to: GeoPoint, query: TransitJourneyQuery): TransitJourney
}

interface ProviderAwareTransitJourneyProvider : TransitJourneyProvider {
    suspend fun journey(
        from: GeoPoint,
        to: GeoPoint,
        query: TransitJourneyQuery,
        context: RoutingProviderContext,
    ): TransitJourney
}

data class TransitJourneyQuery(
    val departureTime: String? = null,
    val arrivalTime: String? = null,
    val routingPreference: TransitRoutingPreference = TransitRoutingPreference.RECOMMENDED,
    val transitTravelModes: Set<TransitTravelMode> = emptySet(),
) {
    init {
        require((departureTime == null) xor (arrivalTime == null)) {
            "Transit journey requires exactly one time anchor"
        }
    }
}

data class TransitJourney(
    val legs: List<TourLeg>,
    val departureTime: String,
    val arrivalTime: String,
)

class BackendTransitJourneyProvider(private val api: BackendApi) : ProviderAwareTransitJourneyProvider {
    override suspend fun journey(
        from: GeoPoint,
        to: GeoPoint,
        query: TransitJourneyQuery,
    ): TransitJourney = journey(from, to, query, RoutingProviderContext.GOOGLE)

    override suspend fun journey(
        from: GeoPoint,
        to: GeoPoint,
        query: TransitJourneyQuery,
        context: RoutingProviderContext,
    ): TransitJourney {
        val response = api.route(
            mode = TravelMode.TRANSIT,
            locations = listOf(from, to),
            departureTime = query.departureTime,
            arrivalTime = query.arrivalTime,
            transitRoutingPreference = query.routingPreference,
            transitTravelModes = query.transitTravelModes,
            expectedProvider = context.provider,
            expectedCoordinateSystem = context.responseCoordinateSystem,
        )
        val routeSteps = response.legs.flatMap(BackendRouteLeg::steps)
        val provider = response.provider
        val coordinateSystem = response.coordinateSystem
        val legs = if (provider == MapProvider.AMAP) {
            listOf(
                response.toAmapTransitLeg(
                    from = from,
                    to = to,
                    coordinateSystem = coordinateSystem,
                ),
            )
        } else if (routeSteps.isEmpty()) {
            if (
                from != to ||
                response.distanceMeters > 0.0 ||
                response.durationSeconds > 0.0 ||
                response.legs.any { it.distanceMeters > 0.0 || it.durationSeconds > 0.0 }
            ) {
                throw ApiException.InvalidResponse(
                    IllegalStateException("Nontrivial transit route contains no steps"),
                )
            }
            listOf(
                TourLeg(
                    from = from,
                    to = to,
                    mode = TravelMode.WALK,
                    geometry = decodeGooglePolyline(response.encodedPolyline).ifEmpty { listOf(from, to) },
                    steps = emptyList(),
                    distanceMeters = response.distanceMeters,
                    durationSeconds = response.durationSeconds,
                    source = GOOGLE_ROUTES_SOURCE,
                    provider = provider,
                    coordinateSystem = coordinateSystem,
                ),
            )
        } else {
            routeSteps.toTransitLegs(
                journeyStart = from,
                journeyEnd = to,
                provider = provider,
                coordinateSystem = coordinateSystem,
            )
        }
        val (departureTime, arrivalTime) = resolveJourneyTimes(
            steps = routeSteps,
            query = query,
            durationSeconds = response.durationSeconds.roundToLong(),
        )
        return TransitJourney(
            legs = legs,
            departureTime = formatTransitDepartureTime(departureTime),
            arrivalTime = formatTransitDepartureTime(arrivalTime),
        )
    }
}

private fun cn.anitabi.navigator.data.network.backend.BackendRouteResponse.toAmapTransitLeg(
    from: GeoPoint,
    to: GeoPoint,
    coordinateSystem: CoordinateSystem,
): TourLeg {
    val routeSteps = legs.flatMap(BackendRouteLeg::steps)
    val geometry = decodeGooglePolyline(encodedPolyline)
        .ifEmpty { legs.flatMap { decodeGooglePolyline(it.encodedPolyline) }.withoutConsecutiveDuplicates() }
        .ifEmpty { routeSteps.flatMap { decodeGooglePolyline(it.encodedPolyline) }.withoutConsecutiveDuplicates() }
    if (
        geometry.isEmpty() &&
        (from != to || distanceMeters > 0.0 || durationSeconds > 0.0)
    ) {
        throw ApiException.InvalidResponse(
            IllegalStateException("AMap transit geometry is required for a nontrivial route"),
        )
    }
    val primaryTransit = routeSteps.firstNotNullOfOrNull(BackendRouteStep::transit)
    val line = primaryTransit?.lineShortName?.takeIf(String::isNotBlank)
        ?: primaryTransit?.lineName?.takeIf(String::isNotBlank)
    return TourLeg(
        from = from,
        to = to,
        mode = TravelMode.TRANSIT,
        geometry = geometry,
        steps = routeSteps.map { step ->
            val stepGeometry = decodeGooglePolyline(step.encodedPolyline)
            RouteStep(
                instruction = step.instruction?.takeIf(String::isNotBlank)
                    ?: roadInstruction(step.travelMode),
                distanceMeters = step.distanceMeters,
                durationSeconds = step.durationSeconds,
                coordinate = stepGeometry.firstOrNull(),
            )
        },
        distanceMeters = distanceMeters,
        durationSeconds = durationSeconds,
        source = AMAP_WEB_SERVICE_SOURCE,
        provider = MapProvider.AMAP,
        coordinateSystem = coordinateSystem,
        transit = primaryTransit?.let {
            TransitLegDetails(
                vehicleMode = it.vehicleType ?: "TRANSIT",
                line = line,
                direction = it.headsign,
                departureStop = it.departureStop,
                arrivalStop = it.arrivalStop,
                stopCount = it.stopCount,
                departureTime = it.departureTime,
                arrivalTime = it.arrivalTime,
                departureTimeZone = it.departureTimeZone,
                arrivalTimeZone = it.arrivalTimeZone,
            )
        },
    )
}

private fun resolveJourneyTimes(
    steps: List<BackendRouteStep>,
    query: TransitJourneyQuery,
    durationSeconds: Long,
): Pair<OffsetDateTime, OffsetDateTime> {
    val firstTransitIndex = steps.indexOfFirst { it.transit?.departureTime != null }
    val lastTransitIndex = steps.indexOfLast { it.transit?.arrivalTime != null }
    val derivedDeparture = firstTransitIndex.takeIf { it >= 0 }?.let { index ->
        parseOffsetDateTime(steps[index].transit?.departureTime)?.minusSeconds(
            steps.take(index).sumOf { it.durationSeconds }.roundToLong(),
        )
    }
    val derivedArrival = lastTransitIndex.takeIf { it >= 0 }?.let { index ->
        parseOffsetDateTime(steps[index].transit?.arrivalTime)?.plusSeconds(
            steps.drop(index + 1).sumOf { it.durationSeconds }.roundToLong(),
        )
    }
    val requestedDeparture = parseOffsetDateTime(query.departureTime)
    val requestedArrival = parseOffsetDateTime(query.arrivalTime)
    val departure = derivedDeparture
        ?: requestedDeparture
        ?: requireNotNull(requestedArrival).minusSeconds(durationSeconds)
    val arrival = derivedArrival
        ?.takeUnless { it.isBefore(departure) }
        ?: requestedArrival
        ?: departure.plusSeconds(durationSeconds)
    return departure to arrival
}

private fun parseOffsetDateTime(value: String?): OffsetDateTime? = value?.let { raw ->
    runCatching { OffsetDateTime.parse(raw) }.getOrNull()
}

private fun List<BackendRouteStep>.toTransitLegs(
    journeyStart: GeoPoint,
    journeyEnd: GeoPoint,
    provider: MapProvider,
    coordinateSystem: CoordinateSystem,
): List<TourLeg> {
    var cursor = journeyStart
    return mapIndexed { index, step ->
        val decoded = decodeGooglePolyline(step.encodedPolyline)
        val from = decoded.firstOrNull() ?: cursor
        val to = decoded.lastOrNull() ?: if (index == lastIndex) journeyEnd else from
        cursor = to
        val transit = step.transit
        val line = transit?.lineShortName?.takeIf(String::isNotBlank)
            ?: transit?.lineName?.takeIf(String::isNotBlank)
        val instruction = step.instruction?.takeIf(String::isNotBlank) ?: when {
            transit == null -> "步行前往下一站"
            line != null && !transit.headsign.isNullOrBlank() -> "$line · 开往 ${transit.headsign}"
            line != null -> "乘坐 $line"
            else -> "乘坐公共交通"
        }
        TourLeg(
            from = from,
            to = to,
            mode = step.travelMode.toTravelMode(),
            geometry = decoded.ifEmpty { listOf(from, to).withoutConsecutiveDuplicates() },
            steps = listOf(RouteStep(instruction, step.distanceMeters, step.durationSeconds, from)),
            distanceMeters = step.distanceMeters,
            durationSeconds = step.durationSeconds,
            source = GOOGLE_ROUTES_SOURCE,
            provider = provider,
            coordinateSystem = coordinateSystem,
            transit = transit?.let {
                TransitLegDetails(
                    vehicleMode = it.vehicleType ?: step.travelMode,
                    line = line,
                    direction = it.headsign,
                    departureStop = it.departureStop,
                    arrivalStop = it.arrivalStop,
                    stopCount = it.stopCount,
                    departureTime = it.departureTime,
                    arrivalTime = it.arrivalTime,
                    departureTimeZone = it.departureTimeZone,
                    arrivalTimeZone = it.arrivalTimeZone,
                )
            },
        )
    }
}

private fun String.toTravelMode(): TravelMode = when (this) {
    "DRIVE" -> TravelMode.DRIVE
    "BICYCLE" -> TravelMode.BIKE
    "WALK" -> TravelMode.WALK
    "TRANSIT" -> TravelMode.TRANSIT
    else -> throw ApiException.InvalidResponse(IllegalStateException("Unknown travel mode"))
}

private fun decodeGooglePolyline(encoded: String?): List<GeoPoint> = encoded
    ?.takeIf(String::isNotBlank)
    ?.let { value ->
        runCatching { PolylineDecoder.decode(value, precision = 5) }
            .getOrElse { throw ApiException.InvalidResponse(it) }
    }
    .orEmpty()

private fun List<GeoPoint>.withoutConsecutiveDuplicates(): List<GeoPoint> = buildList {
    this@withoutConsecutiveDuplicates.forEach { point ->
        if (lastOrNull() != point) add(point)
    }
}

const val GOOGLE_ROUTES_SOURCE = "Google Routes API"
const val AMAP_WEB_SERVICE_SOURCE = "AMap Web Service"
