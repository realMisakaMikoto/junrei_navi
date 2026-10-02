package cn.anitabi.navigator

import android.content.Context
import cn.anitabi.navigator.data.local.AnitabiDatabase
import cn.anitabi.navigator.data.auth.FirebaseAnonymousTokenProvider
import cn.anitabi.navigator.data.network.ApiHttpClient
import cn.anitabi.navigator.data.network.UserAgentInterceptor
import cn.anitabi.navigator.data.network.anitabi.AnitabiApi
import cn.anitabi.navigator.data.network.backend.BackendApi
import cn.anitabi.navigator.data.network.bangumi.BangumiApi
import cn.anitabi.navigator.data.repository.PilgrimageRepository
import cn.anitabi.navigator.data.repository.TourRepository
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.TerritoryRegion
import cn.anitabi.navigator.core.routing.BackendRoadRoutingProvider
import cn.anitabi.navigator.core.routing.BackendTransitJourneyProvider
import cn.anitabi.navigator.core.routing.TourPlanner
import cn.anitabi.navigator.core.region.FailClosedTerritoryClassifier
import cn.anitabi.navigator.security.AppSettingsStore
import cn.anitabi.navigator.telemetry.FirebaseTelemetryRuntime
import cn.anitabi.navigator.telemetry.TelemetryConsentController
import cn.anitabi.navigator.navigation.AndroidLocationProvider
import cn.anitabi.navigator.ui.map.AmapPrivacyGate
import cn.anitabi.navigator.data.discovery.DiscoveryRepository
import cn.anitabi.navigator.data.discovery.DiscoveryCache
import cn.anitabi.navigator.data.discovery.DiscoverySource
import cn.anitabi.navigator.data.discovery.FileDiscoveryCache
import cn.anitabi.navigator.data.discovery.HttpDiscoverySource
import cn.anitabi.navigator.ui.discovery.DiscoveryPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class AppContainer internal constructor(
    context: Context,
    classifyTerritoryOverride: ((GeoPoint) -> TerritoryRegion?)?,
    regionDataVersionOverride: (() -> String?)?,
    discoverySourceOverride: DiscoverySource? = null,
    discoveryCacheOverride: DiscoveryCache? = null,
    roadProviderOverride: cn.anitabi.navigator.core.routing.RoadRoutingProvider? = null,
    transitProviderOverride: cn.anitabi.navigator.core.routing.TransitJourneyProvider? = null,
    plannerDraftRepositoryOverride: cn.anitabi.navigator.data.repository.PlannerDraftRepository? = null,
) {
    constructor(context: Context) : this(
        context = context,
        classifyTerritoryOverride = null,
        regionDataVersionOverride = null,
    )

    private val appContext = context.applicationContext
    val discoveryTrace = (appContext as? AnitabiApplication)?.discoveryDiagnostics?.trace
        ?: cn.anitabi.navigator.data.discovery.DiscoveryLoadTrace()
    private val containerSpan = discoveryTrace.begin(cn.anitabi.navigator.data.discovery.DiscoveryLoadPhase.APP_CONTAINER_INIT)
    private val json = ApiHttpClient.defaultJson
    private val database = AnitabiDatabase.create(context)
    val appSettingsStore = AppSettingsStore(context)
    val telemetryConsentController = TelemetryConsentController(
        store = appSettingsStore,
        runtime = FirebaseTelemetryRuntime(context),
    )
    val locationProvider = AndroidLocationProvider(context)
    val amapPrivacyGate = AmapPrivacyGate(
        context = context,
        apiKeyConfigured = BuildConfig.AMAP_API_KEY_CONFIGURED,
    )
    val territoryClassifier = run {
        val span = discoveryTrace.begin(cn.anitabi.navigator.data.discovery.DiscoveryLoadPhase.REGION_ASSET_READ)
        try {
            FailClosedTerritoryClassifier.load { assetPath -> appContext.assets.open(assetPath) }.also { classifier ->
                val unavailable = classifier.metadata == null
                discoveryTrace.end(span,
                    if (unavailable) cn.anitabi.navigator.data.discovery.DiscoveryLoadOutcome.FAILED else cn.anitabi.navigator.data.discovery.DiscoveryLoadOutcome.OBSERVED,
                    if (unavailable) cn.anitabi.navigator.data.discovery.DiscoveryLoadError.REGION_UNAVAILABLE else null)
            }
        } catch (failure: Throwable) {
            discoveryTrace.end(span, cn.anitabi.navigator.data.discovery.DiscoveryLoadOutcome.FAILED,
                cn.anitabi.navigator.data.discovery.DiscoveryLoadError.REGION_UNAVAILABLE)
            throw failure
        }
    }
    private val classifyTerritory = classifyTerritoryOverride ?: territoryClassifier::classify
    private val regionDataVersion = regionDataVersionOverride ?: { territoryClassifier.metadata?.version }
    private val httpClient = ApiHttpClient(
        userAgentInterceptor = createAppUserAgentInterceptor(),
        json = json,
    )

    val bangumiApi = BangumiApi(httpClient, json)
    val discoveryPreferences = DiscoveryPreferences(appContext)
    val discoveryRepository = DiscoveryRepository(
        source = discoverySourceOverride ?: HttpDiscoverySource(createAppUserAgentInterceptor(), trace = discoveryTrace),
        cache = discoveryCacheOverride ?: FileDiscoveryCache(appContext.cacheDir, trace = discoveryTrace),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        trace = discoveryTrace,
    )
    val pilgrimageRepository = PilgrimageRepository(
        api = AnitabiApi(httpClient),
        cacheDao = database.pilgrimageCacheDao(),
        json = json,
    )
    val tourRepository = TourRepository(
        dao = database.tourPlanDao(),
        json = json,
        classifyTerritory = classifyTerritory,
        regionDataVersion = regionDataVersion,
    )
    val plannerDraftRepository = plannerDraftRepositoryOverride ?: cn.anitabi.navigator.data.repository.PlannerDraftRepository(
        cn.anitabi.navigator.data.repository.FilePlannerDraftStorage(java.io.File(appContext.filesDir, "planner-draft")),
        isTourComplete = { id ->
            when (tourRepository.get(id)?.storedTour?.navigationState) {
                cn.anitabi.navigator.core.model.NavigationState.COMPLETED,
                cn.anitabi.navigator.core.model.NavigationState.ENDED -> true
                else -> false
            }
        },
    )
    val backendApi = BackendApi(
        httpClient = httpClient,
        tokenProvider = FirebaseAnonymousTokenProvider(),
        json = json,
        regionDataVersion = regionDataVersion,
        appVersion = BuildConfig.VERSION_NAME,
    )
    val tourPlanner = TourPlanner(
        roadProvider = roadProviderOverride ?: BackendRoadRoutingProvider(backendApi),
        transitProvider = transitProviderOverride ?: BackendTransitJourneyProvider(backendApi),
        classifyTerritory = classifyTerritory,
        regionDataVersion = regionDataVersion,
        isProviderAvailable = { provider ->
            provider != cn.anitabi.navigator.core.model.MapProvider.AMAP || amapPrivacyGate.isReady
        },
    )

    init {
        val localScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        cn.anitabi.navigator.data.repository.observePlannerDraftImages(plannerDraftRepository, discoveryRepository, localScope)
        localScope.launch {
            combine(
                plannerDraftRepository.state.map { it.draft?.generatedTourId }.distinctUntilChanged(),
                tourRepository.persistedChanges,
            ) { id, _ -> id }.collect { id ->
                if (id != null) {
                    try {
                        tourRepository.get(id)?.storedTour?.navigationState?.let { state ->
                            // Serial collection lets the durable tombstone finish after it clears the link.
                            plannerDraftRepository.completeGeneratedTour(id, state)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // A failed local read preserves the draft; startup also reconciles persisted status.
                    }
                }
            }
        }
        discoveryTrace.end(containerSpan)
    }

    companion object {
        const val PROJECT_CONTACT = "https://github.com/realMisakaMikoto"
    }
}

internal fun createAppUserAgentInterceptor(): UserAgentInterceptor = UserAgentInterceptor(
    appName = "AnitabiNavigator",
    appVersion = BuildConfig.VERSION_NAME,
    contact = AppContainer.PROJECT_CONTACT,
)
