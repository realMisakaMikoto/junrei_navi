package cn.anitabi.navigator.measurement

import android.app.Activity
import android.app.Application
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import cn.anitabi.navigator.AnitabiApplication
import cn.anitabi.navigator.AppContainer
import cn.anitabi.navigator.BuildConfig
import cn.anitabi.navigator.MainActivity
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.core.model.NavigationState
import cn.anitabi.navigator.core.model.RouteObjective
import cn.anitabi.navigator.core.model.TravelMode
import cn.anitabi.navigator.core.routing.RoadRoute
import cn.anitabi.navigator.core.routing.RoadRoutingProvider
import cn.anitabi.navigator.core.routing.TransitJourney
import cn.anitabi.navigator.core.routing.TransitJourneyProvider
import cn.anitabi.navigator.core.routing.TransitJourneyQuery
import cn.anitabi.navigator.core.routing.TravelMatrix
import cn.anitabi.navigator.createAppUserAgentInterceptor
import cn.anitabi.navigator.data.discovery.FileDiscoveryCache
import cn.anitabi.navigator.data.discovery.HttpDiscoverySource
import cn.anitabi.navigator.data.images.createAppImageHttpClient
import cn.anitabi.navigator.data.images.createAppImageLoader
import cn.anitabi.navigator.data.repository.FilePlannerDraftStorage
import cn.anitabi.navigator.data.repository.PlannerDraftRepository
import coil3.ImageLoader
import coil3.disk.DiskCache
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Path.Companion.toOkioPath

/** Included only in the explicitly signed, optimized measurement release source set. */
class DiscoveryMeasurementApplication : AnitabiApplication() {
    internal val fixtureFiles get() = File(filesDir, "discovery-measurement")
    internal val fixtureCaches get() = File(cacheDir, "discovery-measurement")
    internal val configFile get() = File(fixtureFiles, "config.json")
    internal val bootstrapFile get() = File(requireNotNull(getExternalFilesDir(null)), "discovery-measurement/bootstrap")
    internal val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    internal var config: MeasurementConfig? = null
        private set
    internal var activity = WeakReference<MainActivity>(null)
    internal var activityResumed = false
    internal var settingsRestored = false
    private var imageLoader: ImageLoader? = null
    private var lastRouteAttempts = 0
    internal val visualProbe by lazy { MeasurementVisualProbe(this) }

    internal fun checkFixture() {
        check(BuildConfig.DISCOVERY_MEASUREMENT && BuildConfig.DISCOVERY_PROFILING && !BuildConfig.DEBUG)
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Measurement requires an authorized emulator" }
    }

    override fun initializeContainerOnCreate(): Boolean {
        checkFixture()
        if (bootstrapFile.exists()) return false
        config = if (configFile.isFile) json.decodeFromString(MeasurementConfig.serializer(), configFile.readText()).also { it.validate() } else null
        return config != null
    }

    override fun onCreate() {
        checkFixture()
        discoveryDiagnostics.visualFrameObserver = { visualProbe.observe(it) }
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(value: Activity, savedInstanceState: Bundle?) {
                if (value is MainActivity) activity = WeakReference(value)
            }
            override fun onActivityResumed(value: Activity) { if (value is MainActivity) {
                activityResumed = true
                visualProbe.resume(value)
            } }
            override fun onActivityPaused(value: Activity) { if (value is MainActivity) {
                activityResumed = false
                visualProbe.pause(value)
            } }
            override fun onActivityDestroyed(value: Activity) { if (activity.get() === value) {
                if (value is MainActivity) visualProbe.pause(value)
                activity.clear()
            } }
            override fun onActivityStarted(value: Activity) = Unit
            override fun onActivityStopped(value: Activity) = Unit
            override fun onActivitySaveInstanceState(value: Activity, outState: Bundle) = Unit
        })
        super.onCreate()
    }

    override fun createContainer(): AppContainer {
        checkFixture()
        check(!bootstrapFile.exists()) { "Measurement bootstrap cannot initialize the application container" }
        val settings = requireNotNull(config) { "Prepare a measurement configuration before launching the activity" }
        checkNoActivePointerOrTelemetry()
        val transport = MeasurementTransport(this, settings.certificateSha256)
        val result = AppContainer(
            context = this,
            classifyTerritoryOverride = null,
            regionDataVersionOverride = null,
            discoverySourceOverride = HttpDiscoverySource(createAppUserAgentInterceptor(),
                transport.builder().addInterceptor(transport.dataMapping(settings)), discoveryDiagnostics.trace),
            discoveryCacheOverride = FileDiscoveryCache(cacheGroup(settings), discoveryDiagnostics.trace),
            plannerDraftRepositoryOverride = PlannerDraftRepository(FilePlannerDraftStorage(File(fixtureFiles, "draft"))),
            roadProviderOverride = object : RoadRoutingProvider {
                override suspend fun matrix(mode: TravelMode, points: List<GeoPoint>, objective: RouteObjective): TravelMatrix = unexpectedRouting()
                override suspend fun directions(mode: TravelMode, points: List<GeoPoint>): RoadRoute = unexpectedRouting()
            },
            transitProviderOverride = object : TransitJourneyProvider {
                override suspend fun journey(from: GeoPoint, to: GeoPoint, query: TransitJourneyQuery): TransitJourney = unexpectedRouting()
            },
        )
        runBlocking(Dispatchers.IO) {
            check(result.tourRepository.getSavedTours().none {
                it.progress?.state in setOf(NavigationState.NAVIGATING, NavigationState.ARRIVING, NavigationState.DWELLING, NavigationState.NEXT_STOP)
            }) { "Measurement refuses ongoing saved navigation" }
        }
        return result
    }

    override fun newImageLoader(context: Context): ImageLoader {
        checkFixture()
        val settings = requireNotNull(config)
        val transport = MeasurementTransport(this, settings.certificateSha256)
        val client = createAppImageHttpClient().newBuilder()
            .sslSocketFactory(transport.socketFactory, transport.trustManager)
            .addInterceptor(transport.imageMapping(settings)).build()
        val cache = DiskCache.Builder().directory(File(cacheGroup(settings), "images").toOkioPath()).build()
        return createAppImageLoader(context, client, discoveryDiagnostics.trace).newBuilder()
            .diskCache(cache).build().also { imageLoader = it }
    }

    internal fun cacheGroup(settings: MeasurementConfig): File = File(fixtureCaches, "${settings.provider.name}-${settings.size}")

    internal fun saveConfiguration(settings: MeasurementConfig) {
        settings.validate()
        writeAtomic(configFile, json.encodeToString(MeasurementConfig.serializer(), settings))
        config = settings
    }

    internal fun removeConfiguration() { check(!configFile.exists() || configFile.delete()); config = null }

    internal fun checkNoActivePointerOrTelemetry() {
        check(locationPermissionsDenied()) { "Measurement requires location permissions already denied" }
        check(getSharedPreferences("active_navigation", MODE_PRIVATE).getString("tour_id", null) == null) { "Measurement refuses an active journey pointer" }
        val settings = getSharedPreferences("anitabi_settings_v2", MODE_PRIVATE)
        check(!settings.getBoolean("analytics_consent", false) && !settings.getBoolean("crashlytics_consent", false)) { "Measurement requires telemetry disabled" }
    }

    internal fun fineLocationDenied(): Boolean = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
    internal fun coarseLocationDenied(): Boolean = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
    internal fun locationPermissionsDenied(): Boolean = fineLocationDenied() && coarseLocationDenied()

    internal fun deleteFixtureCache(directory: File) {
        check(activity.get() == null) { "Stop the measured activity before preparing caches" }
        imageLoader?.shutdown()
        imageLoader = null
        val root = fixtureCaches.canonicalFile
        val target = directory.canonicalFile
        check(target.toPath().startsWith(root.toPath()) && target != root)
        check(!target.exists() || target.deleteRecursively())
    }

    @Synchronized internal fun passiveRouteAttempts(): Int {
        val file = File(fixtureFiles, "routing-count")
        if (file.isFile) lastRouteAttempts = file.readText().toInt()
        return lastRouteAttempts
    }

    @Synchronized private fun unexpectedRouting(): Nothing {
        writeAtomic(File(fixtureFiles, "routing-count"), (passiveRouteAttempts() + 1).toString())
        error("Measurement blocked a routing attempt before network access")
    }
}

@Serializable internal enum class DataProfile { NORMAL, DELAYED, OFFLINE }
@Serializable internal enum class ImageProfile { NORMAL, THROTTLED, OFFLINE }
internal enum class FixtureCache { EMPTY, KEEP }

@Serializable
internal data class MeasurementLayout(
    val schema: Int,
    val provider: MapProvider,
    val size: Int,
    val centerLatitude: Double,
    val centerLongitude: Double,
    val zoom: Float,
    val subjectCount: Int,
    val pageSize: Int,
    val pageCount: Int,
    val uniqueImages: Int,
    val datasetSha256: String,
    val singletonPhotoPointIds: List<String> = emptyList(),
) {
    fun validate() {
        require(schema == 1 && size in setOf(1_000, 10_000, 100_000, 51_828))
        GeoPoint(centerLatitude, centerLongitude)
        require(zoom == 15f && subjectCount == (size + 127) / 128 && pageSize == 16 && pageCount == (subjectCount + 15) / 16)
        require(uniqueImages == 6 && datasetSha256.matches(Regex("[a-f0-9]{64}")))
        require(singletonPhotoPointIds.size == 6 && singletonPhotoPointIds.distinct().size == 6)
        require(singletonPhotoPointIds.all { it.length <= 100 && it.matches(Regex("[0-9]+::[A-Za-z0-9_-]+")) })
    }
}

@Serializable
internal data class MeasurementConfig(
    val schema: Int = 1,
    val provider: MapProvider,
    val size: Int,
    val data: DataProfile,
    val images: ImageProfile,
    val layout: MeasurementLayout,
    val certificateSha256: String,
    val cameraLatitude: Double,
    val cameraLongitude: Double,
) {
    fun validate() {
        require(schema == 1 && provider == layout.provider && size == layout.size)
        layout.validate()
        require(certificateSha256.matches(Regex("[a-f0-9]{64}")))
        GeoPoint(cameraLatitude, cameraLongitude)
    }
}

/** Trust is limited to the public localhost fixture certificate; hostname checks remain enabled. */
internal class MeasurementTransport(context: Context, expectedCertificate: String? = null) {
    private val certificateFile = File(requireNotNull(context.getExternalFilesDir(null)), "discovery-measurement/fixture-cert.pem")
    private val certificateBytes = certificateFile.inputStream().use { it.readBounded(16_384) }.also { require(it.isNotEmpty()) }
    val certificateSha256 = MessageDigest.getInstance("SHA-256").digest(certificateBytes).joinToString("") { "%02x".format(it) }
    val trustManager: X509TrustManager
    val socketFactory: javax.net.ssl.SSLSocketFactory

    init {
        check(expectedCertificate == null || expectedCertificate == certificateSha256) { "Fixture certificate changed without preparation" }
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(certificateBytes.inputStream()) as X509Certificate
        certificate.checkValidity()
        require(certificate.subjectAlternativeNames?.any { it[0] == 2 && it[1] == "localhost" } == true)
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("fixture", certificate) }
        trustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            .trustManagers.filterIsInstance<X509TrustManager>().single()
        socketFactory = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }.socketFactory
    }

    fun builder(): OkHttpClient.Builder = OkHttpClient.Builder().sslSocketFactory(socketFactory, trustManager)
        .followRedirects(false).followSslRedirects(false)

    fun layout(provider: MapProvider, size: Int, json: Json): MeasurementLayout {
        require(size in setOf(1_000, 10_000, 100_000, 51_828))
        val request = Request.Builder().url("https://localhost:18443/fixture/${provider.name}/$size/layout.json").build()
        return builder().callTimeout(java.time.Duration.ofSeconds(15)).build().newCall(request).execute().use { response ->
            check(response.code == 200) { "Fixture layout unavailable" }
            val body = response.body.byteStream().use { it.readBounded(32_768) }
            json.decodeFromString(MeasurementLayout.serializer(), body.toString(Charsets.UTF_8)).also {
                it.validate(); require(it.provider == provider && it.size == size)
            }
        }
    }

    fun dataMapping(settings: MeasurementConfig) = Interceptor { chain ->
        val request = chain.request()
        val url = request.url
        val allowed = url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() && when (url.host) {
            "www.anitabi.cn" -> url.encodedPath.matches(Regex("/d/g[0-9]*\\.json"))
            "api.anitabi.cn" -> url.encodedPath.matches(Regex("/bangumi/[0-9]+/points/detail"))
            else -> false
        }
        check(allowed && request.header("Authorization") == null && request.header("Cookie") == null)
        if (settings.data == DataProfile.OFFLINE) throw IOException("Controlled data offline")
        chain.proceed(request.newBuilder().url(url.newBuilder().host("localhost").port(18443)
            .encodedPath("/fixture/${settings.provider.name}/${settings.size}/${settings.data.name}${url.encodedPath}").build()).build())
    }

    fun imageMapping(settings: MeasurementConfig) = Interceptor { chain ->
        val request = chain.request()
        val url = request.url
        check(url.isHttps && url.host == "image.anitabi.cn" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty())
        check(url.encodedPath.matches(Regex("/(points|user|bangumi)/[A-Za-z0-9_/.-]+")))
        check(request.header("Authorization") == null && request.header("Cookie") == null && request.header("X-Goog-Api-Key") == null)
        if (settings.images == ImageProfile.OFFLINE) throw IOException("Controlled image offline")
        chain.proceed(request.newBuilder().url(url.newBuilder().host("localhost").port(18443)
            .encodedPath("/fixture/${settings.images.name}${url.encodedPath}").build()).build())
    }
}

internal fun writeAtomic(file: File, text: String) {
    check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
    val pending = File(file.parentFile, file.name + ".tmp")
    FileOutputStream(pending).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
    Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}

private fun InputStream.readBounded(limit: Int): ByteArray {
    val result = ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) {
        val count = read(buffer)
        if (count < 0) return result.toByteArray()
        require(result.size() + count <= limit) { "Fixture metadata exceeds its fixed size limit" }
        result.write(buffer, 0, count)
    }
}
