package cn.anitabi.navigator.data.network.backend

import cn.anitabi.navigator.BuildConfig
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.CoordinateSystem
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.core.model.RouteObjective
import cn.anitabi.navigator.core.model.TransitRoutingPreference
import cn.anitabi.navigator.core.model.TransitTravelMode
import cn.anitabi.navigator.core.model.TravelMode
import cn.anitabi.navigator.data.auth.IdTokenProvider
import cn.anitabi.navigator.data.network.ApiException
import cn.anitabi.navigator.data.network.ApiHttpClient
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class BackendApi(
    private val httpClient: ApiHttpClient,
    private val tokenProvider: IdTokenProvider,
    private val json: Json = ApiHttpClient.defaultJson,
    private val baseUrl: String = BASE_URL,
    val contractVersion: BackendContractVersion = BackendContractVersion.V2,
    private val regionDataVersion: () -> String? = { null },
    private val appVersion: String = "0.2.5",
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {
    private val postMutex = Mutex()
    private var pacedAfterRateLimit = false
    private var nextPostAtMillis = 0L
    private var lastPostStartedAtMillis = 0L
    suspend fun matrix(
        mode: TravelMode,
        coordinates: List<GeoPoint>,
        objective: RouteObjective,
        departureTime: String? = null,
        expectedProvider: MapProvider,
        expectedCoordinateSystem: CoordinateSystem,
    ): BackendMatrixResponse {
        require(mode != TravelMode.TRANSIT) { "Transit has no matrix request" }
        require(coordinates.size in 2..10) { "Matrix requires 2 to 10 coordinates" }
        val response = post(
            path = contractPath("matrix"),
            body = BackendMatrixRequest(
                mode = mode.backendName(),
                coordinates = coordinates.map(GeoPoint::toBackendCoordinate),
                departureTime = departureTime,
                objective = objective.name,
            ),
            serializer = BackendMatrixRequest.serializer(),
            deserializer = BackendMatrixWireResponse.serializer(),
        )
        val metadata = resolveMetadata(
            provider = response.provider,
            coordinateSystem = response.coordinateSystem,
            responseRegionDataVersion = response.regionDataVersion,
            expectedProvider = expectedProvider,
            expectedCoordinateSystem = expectedCoordinateSystem,
        )
        return BackendMatrixResponse(
            elements = response.elements,
            provider = metadata.provider,
            coordinateSystem = metadata.coordinateSystem,
            regionDataVersion = metadata.regionDataVersion,
        )
    }

    suspend fun matrixV1(
        mode: TravelMode,
        coordinates: List<GeoPoint>,
        objective: RouteObjective,
        departureTime: String? = null,
    ): BackendMatrixResponse {
        require(contractVersion == BackendContractVersion.V1_COMPAT) {
            "v1 matrix requires explicit V1_COMPAT mode"
        }
        return matrix(
            mode = mode,
            coordinates = coordinates,
            objective = objective,
            departureTime = departureTime,
            expectedProvider = MapProvider.GOOGLE,
            expectedCoordinateSystem = CoordinateSystem.WGS84,
        )
    }

    suspend fun route(
        mode: TravelMode,
        locations: List<GeoPoint>,
        departureTime: String? = null,
        arrivalTime: String? = null,
        transitRoutingPreference: TransitRoutingPreference = TransitRoutingPreference.RECOMMENDED,
        transitTravelModes: Set<TransitTravelMode> = emptySet(),
        expectedProvider: MapProvider,
        expectedCoordinateSystem: CoordinateSystem,
    ): BackendRouteResponse {
        require(locations.size in 2..12) { "Route requires 2 to 12 locations" }
        require(mode != TravelMode.TRANSIT || locations.size == 2) {
            "Transit routes require exactly two locations"
        }
        require(departureTime == null || arrivalTime == null) {
            "A route cannot specify both departure and arrival time"
        }
        require(
            mode == TravelMode.TRANSIT ||
                (departureTime == null && arrivalTime == null &&
                    transitRoutingPreference == TransitRoutingPreference.RECOMMENDED &&
                    transitTravelModes.isEmpty()),
        ) {
            "Transit time and routing preferences require transit mode"
        }
        val response = post(
            path = contractPath("route"),
            body = BackendRouteRequest(
                mode = mode.backendName(),
                locations = locations.map(GeoPoint::toBackendCoordinate),
                departureTime = departureTime,
                arrivalTime = arrivalTime,
                transitRoutingPreference = transitRoutingPreference
                    .takeUnless { it == TransitRoutingPreference.RECOMMENDED }
                    ?.name,
                transitTravelModes = transitTravelModes
                    .sortedBy(TransitTravelMode::ordinal)
                    .map(TransitTravelMode::name)
                    .takeIf(List<String>::isNotEmpty),
            ),
            serializer = BackendRouteRequest.serializer(),
            deserializer = BackendRouteWireResponse.serializer(),
        )
        val metadata = resolveMetadata(
            provider = response.provider,
            coordinateSystem = response.coordinateSystem,
            responseRegionDataVersion = response.regionDataVersion,
            expectedProvider = expectedProvider,
            expectedCoordinateSystem = expectedCoordinateSystem,
        )
        return BackendRouteResponse(
            distanceMeters = response.distanceMeters,
            durationSeconds = response.durationSeconds,
            encodedPolyline = response.encodedPolyline,
            legs = response.legs,
            provider = metadata.provider,
            coordinateSystem = metadata.coordinateSystem,
            regionDataVersion = metadata.regionDataVersion,
        )
    }

    suspend fun routeV1(
        mode: TravelMode,
        locations: List<GeoPoint>,
        departureTime: String? = null,
        arrivalTime: String? = null,
        transitRoutingPreference: TransitRoutingPreference = TransitRoutingPreference.RECOMMENDED,
        transitTravelModes: Set<TransitTravelMode> = emptySet(),
    ): BackendRouteResponse {
        require(contractVersion == BackendContractVersion.V1_COMPAT) {
            "v1 route requires explicit V1_COMPAT mode"
        }
        return route(
            mode = mode,
            locations = locations,
            departureTime = departureTime,
            arrivalTime = arrivalTime,
            transitRoutingPreference = transitRoutingPreference,
            transitTravelModes = transitTravelModes,
            expectedProvider = MapProvider.GOOGLE,
            expectedCoordinateSystem = CoordinateSystem.WGS84,
        )
    }

    suspend fun policy(): BackendPolicyResponse {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/v2/policy")
            .get()
            .build()
        return httpClient.execute(
            request = request,
            deserializer = BackendPolicyResponse.serializer(),
            errorMapper = ::mapBackendError,
        ).also { policy ->
            if (
                policy.apiVersion != "2" ||
                policy.regionDataVersion.isBlank() ||
                policy.minimumAppVersion.isBlank() ||
                policy.v1SunsetAt.isBlank()
            ) {
                throw ApiException.InvalidResponse(IllegalStateException("v2 routing policy is invalid"))
            }
        }
    }

    suspend fun reserveNavigation(
        origin: GeoPoint,
        destinations: List<GeoPoint>,
        expectedProvider: MapProvider,
    ): BackendNavigationReservation {
        require(destinations.size in 1..25) { "Navigation reservation requires 1 to 25 destinations" }
        if (contractVersion == BackendContractVersion.V1_COMPAT) {
            return reserveNavigationV1(destinations.size)
        }
        val response = post(
            path = "v2/navigation/reserve",
            body = BackendV2NavigationReservationRequest(
                origin = origin.toBackendCoordinate(),
                destinations = destinations.map(GeoPoint::toBackendCoordinate),
            ),
            serializer = BackendV2NavigationReservationRequest.serializer(),
            deserializer = BackendNavigationReservationWire.serializer(),
        )
        val metadata = resolveMetadata(
            provider = response.provider,
            coordinateSystem = response.coordinateSystem,
            responseRegionDataVersion = response.regionDataVersion,
            expectedProvider = expectedProvider,
            expectedCoordinateSystem = CoordinateSystem.WGS84,
        )
        val executionStrategy = response.executionStrategy
            ?: throw ApiException.InvalidResponse(
                IllegalStateException("v2 navigation execution strategy is missing"),
            )
        val expectedStrategy = when (expectedProvider) {
            MapProvider.GOOGLE -> BackendNavigationExecutionStrategy.GOOGLE_NAVIGATION_SDK
            MapProvider.AMAP -> BackendNavigationExecutionStrategy.EXTERNAL_AMAP_MAINLAND
        }
        if (executionStrategy != expectedStrategy) {
            throw ApiException.InvalidResponse(
                IllegalStateException("Navigation execution strategy does not match the provider"),
            )
        }
        return BackendNavigationReservation(
            reservedDestinations = response.reservedDestinations,
            executionStrategy = executionStrategy,
            provider = metadata.provider,
            coordinateSystem = metadata.coordinateSystem,
            regionDataVersion = metadata.regionDataVersion,
        )
    }

    /** Explicit compatibility entry point. Production code must not silently downgrade to v1. */
    suspend fun reserveNavigationV1(destinationCount: Int): BackendNavigationReservation {
        require(contractVersion == BackendContractVersion.V1_COMPAT) {
            "v1 navigation reservation requires explicit V1_COMPAT mode"
        }
        require(destinationCount in 1..25) { "Navigation reservation requires 1 to 25 destinations" }
        val response = post(
            path = "v1/navigation/reserve",
            body = BackendNavigationReservationRequest(destinationCount),
            serializer = BackendNavigationReservationRequest.serializer(),
            deserializer = BackendNavigationReservationWire.serializer(),
        )
        return BackendNavigationReservation(
            reservedDestinations = response.reservedDestinations,
            executionStrategy = response.executionStrategy
                ?: BackendNavigationExecutionStrategy.GOOGLE_NAVIGATION_SDK,
            provider = response.provider ?: MapProvider.GOOGLE,
            coordinateSystem = response.coordinateSystem ?: CoordinateSystem.WGS84,
            regionDataVersion = response.regionDataVersion ?: V1_COMPAT_REGION_DATA_VERSION,
        )
    }

    @Deprecated("Use the coordinate-bearing v2 reservation or reserveNavigationV1 in explicit compatibility mode")
    suspend fun reserveNavigation(destinationCount: Int): BackendNavigationReservation =
        reserveNavigationV1(destinationCount)

    private suspend fun <RequestBody, ResponseBody> post(
        path: String,
        body: RequestBody,
        serializer: SerializationStrategy<RequestBody>,
        deserializer: DeserializationStrategy<ResponseBody>,
    ): ResponseBody {
        val token = tokenProvider.idToken()
        val requestBuilder = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/$path")
            .header("Authorization", "Bearer $token")
        if (contractVersion == BackendContractVersion.V2) {
            val version = regionDataVersion()?.takeIf(String::isNotBlank)
                ?: throw ApiException.RegionDataOutdated()
            requestBuilder
                .header(REGION_DATA_VERSION_HEADER, version)
                .header(APP_VERSION_HEADER, appVersion)
        }
        val request = requestBuilder
            .post(json.encodeToString(serializer, body).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return postMutex.withLock {
            waitForPostSlot()
            markPostStarted()
            try {
                executeOnce(request, deserializer).also { scheduleNextPostIfNeeded() }
            } catch (exception: ApiException.RateLimited) {
                val retryAfterMillis = exception.retryAfterMillis ?: throw exception
                pacedAfterRateLimit = true
                nextPostAtMillis = maxOf(nextPostAtMillis, monotonicMillis() + retryAfterMillis)
                waitForPostSlot()
                markPostStarted()
                try {
                    executeOnce(request, deserializer)
                } finally {
                    scheduleNextPostIfNeeded()
                }
            } catch (exception: Exception) {
                scheduleNextPostIfNeeded()
                throw exception
            }
        }
    }

    private suspend fun waitForPostSlot() {
        while (true) {
            val now = monotonicMillis()
            if (pacedAfterRateLimit && now - lastPostStartedAtMillis >= PACE_RESET_MILLIS) {
                pacedAfterRateLimit = false
                nextPostAtMillis = 0L
                return
            }
            val waitMillis = (nextPostAtMillis - now).coerceAtLeast(0L)
            if (waitMillis == 0L) return
            sleeper(waitMillis)
        }
    }

    private fun markPostStarted() {
        lastPostStartedAtMillis = monotonicMillis()
    }

    private fun scheduleNextPostIfNeeded() {
        if (pacedAfterRateLimit) nextPostAtMillis = lastPostStartedAtMillis + POST_PACE_MILLIS
    }

    private suspend fun <ResponseBody> executeOnce(
        request: Request,
        deserializer: DeserializationStrategy<ResponseBody>,
    ): ResponseBody = httpClient.execute(
        request = request,
        deserializer = deserializer,
        errorMapper = ::mapBackendError,
    )

    private fun mapBackendError(status: Int, body: String, retryAfter: String?): ApiException {
        val code = runCatching {
            json.decodeFromString(BackendErrorEnvelope.serializer(), body).error.code
        }.getOrNull()
        return when (code) {
            "UNAUTHENTICATED" -> ApiException.Unauthenticated()
            "INVALID_ARGUMENT" -> ApiException.InvalidArgument()
            "NO_ROUTE" -> ApiException.NoRoute()
            "QUOTA_EXHAUSTED" -> ApiException.QuotaExhausted()
            "RATE_LIMITED" -> if (status == 429) {
                ApiException.RateLimited(retryAfter.retryAfterMillis())
            } else {
                ApiException.InvalidResponse(IllegalStateException("RATE_LIMITED requires HTTP 429"))
            }
            "UPSTREAM_UNAVAILABLE" -> ApiException.UpstreamUnavailable()
            "BACKEND_UNAVAILABLE" -> ApiException.BackendUnavailable()
            "MIXED_MAP_PROVIDERS" -> ApiException.MixedMapProviders()
            "MIXED_TRANSIT_REGIONS" -> ApiException.MixedTransitRegions()
            "REGION_UNRESOLVED" -> ApiException.RegionUnresolved()
            "REGION_DATA_OUTDATED" -> ApiException.RegionDataOutdated()
            "CLIENT_UPGRADE_REQUIRED" -> ApiException.ClientUpgradeRequired()
            else -> when (status) {
                400 -> ApiException.InvalidArgument()
                401 -> ApiException.Unauthenticated()
                404 -> ApiException.UpstreamUnavailable()
                429 -> ApiException.RateLimited()
                426 -> ApiException.ClientUpgradeRequired()
                503 -> ApiException.BackendUnavailable()
                else -> ApiException.Http(status)
            }
        }
    }

    companion object {
        internal const val PRODUCTION_BASE_URL = "https://api.anitabi.afunnypersonlol0.site"
        internal val BASE_URL: String
            get() = BuildConfig.BACKEND_BASE_URL
        private const val POST_PACE_MILLIS = 1_000L
        private const val PACE_RESET_MILLIS = 10_000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        internal const val REGION_DATA_VERSION_HEADER = "X-Anitabi-Region-Data-Version"
        internal const val APP_VERSION_HEADER = "X-Anitabi-App-Version"
    }

    private fun contractPath(resource: String): String = when (contractVersion) {
        BackendContractVersion.V2 -> "v2/$resource"
        BackendContractVersion.V1_COMPAT -> "v1/$resource"
    }

    private fun resolveMetadata(
        provider: MapProvider?,
        coordinateSystem: CoordinateSystem?,
        responseRegionDataVersion: String?,
        expectedProvider: MapProvider,
        expectedCoordinateSystem: CoordinateSystem,
    ): ResolvedBackendMetadata {
        if (contractVersion == BackendContractVersion.V1_COMPAT) {
            return ResolvedBackendMetadata(
                provider = provider ?: expectedProvider,
                coordinateSystem = coordinateSystem ?: expectedCoordinateSystem,
                regionDataVersion = responseRegionDataVersion ?: V1_COMPAT_REGION_DATA_VERSION,
            )
        }
        val expectedVersion = regionDataVersion()?.takeIf(String::isNotBlank)
            ?: throw ApiException.RegionDataOutdated()
        if (
            provider != expectedProvider ||
            coordinateSystem != expectedCoordinateSystem ||
            responseRegionDataVersion != expectedVersion
        ) {
            throw ApiException.InvalidResponse(
                IllegalStateException("Routing provider metadata does not match the request policy"),
            )
        }
        return ResolvedBackendMetadata(
            provider = requireNotNull(provider),
            coordinateSystem = requireNotNull(coordinateSystem),
            regionDataVersion = requireNotNull(responseRegionDataVersion),
        )
    }
}

private const val V1_COMPAT_REGION_DATA_VERSION = "V1_COMPAT"

private data class ResolvedBackendMetadata(
    val provider: MapProvider,
    val coordinateSystem: CoordinateSystem,
    val regionDataVersion: String,
)

enum class BackendContractVersion {
    V2,
    V1_COMPAT,
}

private fun String?.retryAfterMillis(): Long? = this
    ?.toLongOrNull()
    ?.takeIf { it in 1..60 }
    ?.times(1_000L)

private fun TravelMode.backendName(): String = when (this) {
    TravelMode.DRIVE -> "DRIVE"
    TravelMode.BIKE -> "BICYCLE"
    TravelMode.WALK -> "WALK"
    TravelMode.TRANSIT -> "TRANSIT"
}

private fun GeoPoint.toBackendCoordinate() = BackendCoordinate(latitude, longitude)

@Serializable
data class BackendCoordinate(
    val latitude: Double,
    val longitude: Double,
)

@Serializable
data class BackendMatrixRequest(
    val mode: String,
    val coordinates: List<BackendCoordinate>,
    val departureTime: String? = null,
    val objective: String,
)

@Serializable
data class BackendRouteRequest(
    val mode: String,
    val locations: List<BackendCoordinate>,
    val departureTime: String? = null,
    val arrivalTime: String? = null,
    val transitRoutingPreference: String? = null,
    val transitTravelModes: List<String>? = null,
)

@Serializable
data class BackendNavigationReservationRequest(val destinationCount: Int)

@Serializable
data class BackendV2NavigationReservationRequest(
    val origin: BackendCoordinate,
    val destinations: List<BackendCoordinate>,
)

interface BackendProviderMetadata {
    val provider: MapProvider
    val coordinateSystem: CoordinateSystem
    val regionDataVersion: String
}

data class BackendMatrixResponse(
    val elements: List<BackendMatrixElement>,
    override val provider: MapProvider,
    override val coordinateSystem: CoordinateSystem,
    override val regionDataVersion: String,
) : BackendProviderMetadata

@Serializable
private data class BackendMatrixWireResponse(
    val elements: List<BackendMatrixElement> = emptyList(),
    val provider: MapProvider? = null,
    val coordinateSystem: CoordinateSystem? = null,
    val regionDataVersion: String? = null,
)

@Serializable
data class BackendMatrixElement(
    val originIndex: Int,
    val destinationIndex: Int,
    val status: String,
    val distanceMeters: Double? = null,
    val durationSeconds: Double? = null,
)

data class BackendRouteResponse(
    val distanceMeters: Double,
    val durationSeconds: Double,
    val encodedPolyline: String? = null,
    val legs: List<BackendRouteLeg> = emptyList(),
    override val provider: MapProvider,
    override val coordinateSystem: CoordinateSystem,
    override val regionDataVersion: String,
) : BackendProviderMetadata

@Serializable
private data class BackendRouteWireResponse(
    val distanceMeters: Double,
    val durationSeconds: Double,
    val encodedPolyline: String? = null,
    val legs: List<BackendRouteLeg> = emptyList(),
    val provider: MapProvider? = null,
    val coordinateSystem: CoordinateSystem? = null,
    val regionDataVersion: String? = null,
)

@Serializable
data class BackendRouteLeg(
    val distanceMeters: Double,
    val durationSeconds: Double,
    val encodedPolyline: String? = null,
    val steps: List<BackendRouteStep> = emptyList(),
)

@Serializable
data class BackendRouteStep(
    val travelMode: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val encodedPolyline: String? = null,
    val instruction: String? = null,
    val maneuver: String? = null,
    val transit: BackendTransitDetails? = null,
)

@Serializable
data class BackendTransitDetails(
    val departureStop: String? = null,
    val arrivalStop: String? = null,
    val departureTime: String? = null,
    val arrivalTime: String? = null,
    val departureTimeZone: String? = null,
    val arrivalTimeZone: String? = null,
    val lineName: String? = null,
    val lineShortName: String? = null,
    val headsign: String? = null,
    val vehicleName: String? = null,
    val vehicleType: String? = null,
    val stopCount: Int? = null,
)

data class BackendNavigationReservation(
    val reservedDestinations: Int,
    val executionStrategy: BackendNavigationExecutionStrategy,
    override val provider: MapProvider,
    override val coordinateSystem: CoordinateSystem,
    override val regionDataVersion: String,
) : BackendProviderMetadata

@Serializable
private data class BackendNavigationReservationWire(
    val reservedDestinations: Int,
    val executionStrategy: BackendNavigationExecutionStrategy? = null,
    val provider: MapProvider? = null,
    val coordinateSystem: CoordinateSystem? = null,
    val regionDataVersion: String? = null,
)

@Serializable
enum class BackendNavigationExecutionStrategy {
    GOOGLE_NAVIGATION_SDK,
    EXTERNAL_AMAP_MAINLAND,
}

@Serializable
data class BackendPolicyResponse(
    val apiVersion: String,
    val regionDataVersion: String,
    val minimumAppVersion: String,
    val v1SunsetAt: String,
    val providers: BackendPolicyProviders,
)

@Serializable
data class BackendPolicyProviders(
    val google: BackendProviderStatus,
    val amap: BackendProviderStatus,
)

@Serializable
enum class BackendProviderStatus {
    @SerialName("enabled")
    ENABLED,

    @SerialName("disabled")
    DISABLED,
}

@Serializable
private data class BackendErrorEnvelope(val error: BackendError)

@Serializable
private data class BackendError(val code: String)
