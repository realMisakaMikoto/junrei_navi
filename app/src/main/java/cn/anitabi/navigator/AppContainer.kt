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
import cn.anitabi.navigator.core.routing.BackendRoadRoutingProvider
import cn.anitabi.navigator.core.routing.BackendTransitJourneyProvider
import cn.anitabi.navigator.core.routing.TourPlanner
import cn.anitabi.navigator.core.region.FailClosedTerritoryClassifier
import cn.anitabi.navigator.security.AppSettingsStore
import cn.anitabi.navigator.telemetry.FirebaseTelemetryRuntime
import cn.anitabi.navigator.telemetry.TelemetryConsentController
import cn.anitabi.navigator.navigation.AndroidLocationProvider
import cn.anitabi.navigator.ui.map.AmapPrivacyGate

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
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
    val territoryClassifier = FailClosedTerritoryClassifier.load { assetPath ->
        appContext.assets.open(assetPath)
    }
    private val classifyTerritory = { point: cn.anitabi.navigator.core.model.GeoPoint ->
        territoryClassifier.classify(point)
    }
    private val regionDataVersion = { territoryClassifier.metadata?.version }
    private val httpClient = ApiHttpClient(
        userAgentInterceptor = createAppUserAgentInterceptor(),
        json = json,
    )

    val bangumiApi = BangumiApi(httpClient, json)
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
    val backendApi = BackendApi(
        httpClient = httpClient,
        tokenProvider = FirebaseAnonymousTokenProvider(),
        json = json,
        regionDataVersion = regionDataVersion,
        appVersion = BuildConfig.VERSION_NAME,
    )
    val tourPlanner = TourPlanner(
        roadProvider = BackendRoadRoutingProvider(backendApi),
        transitProvider = BackendTransitJourneyProvider(backendApi),
        classifyTerritory = classifyTerritory,
        regionDataVersion = regionDataVersion,
        isProviderAvailable = { provider ->
            provider != cn.anitabi.navigator.core.model.MapProvider.AMAP || amapPrivacyGate.isReady
        },
    )

    companion object {
        const val PROJECT_CONTACT = "https://github.com/realMisakaMikoto"
    }
}

internal fun createAppUserAgentInterceptor(): UserAgentInterceptor = UserAgentInterceptor(
    appName = "AnitabiNavigator",
    appVersion = BuildConfig.VERSION_NAME,
    contact = AppContainer.PROJECT_CONTACT,
)
