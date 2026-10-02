package cn.anitabi.navigator.data.images

import android.content.Context
import android.graphics.Color
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import cn.anitabi.navigator.ui.discovery.DiscoveryThumbnail
import cn.anitabi.navigator.ui.theme.AnitabiTheme
import coil3.EventListener
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import java.io.File
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName

/** Controlled HTTPS and real Coil/Android decoding. Never sends requests to a public service. */
@OptIn(coil3.annotation.DelicateCoilApi::class)
class DiscoveryImageResilienceInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val caseName = TestName()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var transport: ControlledImageTransport
    private val loaders = mutableListOf<ImageLoader>()
    private val caches = mutableListOf<DiskCache>()
    private val directories = mutableListOf<File>()
    private val checks = linkedMapOf<String, Boolean>()
    private var previous: ImageLoader? = null

    @Before fun setup() { transport = ControlledImageTransport(context) }

    @After fun cleanup() {
        previous?.let(SingletonImageLoader::setUnsafe)
        loaders.forEach { runCatching(it::shutdown) }
        caches.forEach { runCatching(it::shutdown) }
        directories.forEach { directory ->
            check(directory.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
            directory.deleteRecursively()
        }
        val directory = File(context.getExternalFilesDir(null), "frontend-fix-v2").apply { mkdirs() }
        File(directory, "image-resilience-${caseName.methodName}.json").writeText(buildJsonObject {
            put("controlledHttps", true); put("productionLoaderAndDecoder", true); put("fakeDecodedImages", false)
            put("checks", buildJsonObject { checks.forEach { (key, value) -> put(key, value) } })
            if (::transport.isInitialized) {
                put("unexpectedDnsLookups", transport.dnsHosts.count { it !in setOf("image.anitabi.cn", "lain.bgm.tv") })
                put("responses", buildJsonArray { transport.responses.forEach { row -> add(buildJsonObject {
                    put("status", row.status); put("anitabi", row.anitabi); put("fixtureControl", row.control)
                    put("legacyPrefixSent", row.legacyPrefix); put("plan", row.plan ?: "none")
                    put("sensitiveHeadersAbsent", row.sensitiveHeadersAbsent)
                }) } })
            }
        }.toString())
    }

    @Test fun offlineDiskCachePreservesDecodedBytesAndVariantIdentity() = runBlocking {
        val directory = File(context.cacheDir, "controlled-image-cache-${UUID.randomUUID()}").also { directories += it }
        fun disk() = DiskCache.Builder().directory(directory.absolutePath.toPath()).maxSizeBytes(4L * 1024 * 1024)
            .build().also { caches += it }
        val firstCache = disk()
        val online = loader(disk = firstCache)
        val reference = image("cache", "cache-fixture")
        val primed = success(online.execute(request(reference)))
        assertEquals(DataSource.NETWORK, primed.dataSource)
        assertNotNull(primed.diskCacheKey)
        online.shutdown()
        firstCache.shutdown()
        val callsBefore = transport.responses.size
        val offlineAttempts = AtomicInteger()
        val cacheOnlyAttempts = AtomicInteger()
        val offline = loader(transport.client.newBuilder().addInterceptor {
            offlineAttempts.incrementAndGet()
            if (it.request().cacheControl.onlyIfCached) cacheOnlyAttempts.incrementAndGet()
            throw UnknownHostException("Synthetic offline transport")
        }.build(), disk = disk())
        val cached = success(offline.execute(request(reference, offline = true)))
        assertEquals(DataSource.DISK, cached.dataSource)
        assertNotNull(cached.diskCacheKey)
        assertEquals(0, offlineAttempts.get())
        assertEquals(callsBefore, transport.responses.size)
        assertTrue(offline.execute(request(reference, offline = true, variant = AnitabiImageVariant.DISPLAY)) is ErrorResult)
        assertTrue(offline.execute(request(image("cache", "new-version"), offline = true)) is ErrorResult)
        // A missing Coil entry reaches OkHttp with only-if-cached before its cache lookup.
        assertEquals(2, offlineAttempts.get())
        assertEquals(2, cacheOnlyAttempts.get())
        assertEquals(callsBefore, transport.responses.size)
        checks["offlineDiskDecoded"] = true
        checks["otherVariantAndVersionDoNotAlias"] = true
        checks["deviceNetworkWasUnchanged"] = true
    }

    @Test fun cancelledLoadRetriesWithRealDecodeAndNoErrorCallback() = runBlocking {
        val cancelled = AtomicInteger()
        val errors = AtomicInteger()
        val loader = loader(listener = object : EventListener() {
            override fun onCancel(request: ImageRequest) { cancelled.incrementAndGet() }
            override fun onError(request: ImageRequest, result: ErrorResult) { errors.incrementAndGet() }
        })
        val pending = async(Dispatchers.Default) { loader.execute(request(image("cancel"))) }
        try {
            withContext(Dispatchers.IO) { transport.control("await-cancel") }
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
            assertEquals(1, cancelled.get())
            assertEquals(0, errors.get())
        } finally {
            withContext(Dispatchers.IO) { transport.control("release-cancel") }
            pending.cancelAndJoin()
        }
        success(loader.execute(request(image("cancel"))))
        assertEquals(0, errors.get())
        checks["cancelCallbackWithoutErrorResult"] = true
        checks["sameReferenceDecodedAfterCancellation"] = true
        // Map-specific failure-state integration still requires the native map selector.
        checks["mapBackoffStateInspected"] = false
    }

    @Test fun approvedSameHostRedirectReachesRealDecoder() = runBlocking {
        success(loader().execute(request(image("redirect-allowed"))))
        val responses = transport.responses.filterNot { it.control }
        assertEquals(listOf(302, 200), responses.map { it.status })
        assertTrue(responses.all { it.anitabi && it.sensitiveHeadersAbsent && !it.legacyPrefix && it.plan == "h160" })
        assertTrue(transport.dnsHosts.all { it == "image.anitabi.cn" })
        checks["approvedRedirectDecoded"] = true
    }

    @Test fun unapprovedSecondRedirectHopIsRejectedBeforeDnsOrConnection() = runBlocking {
        val result = loader().execute(request(image("redirect-chain")))
        assertTrue(result is ErrorResult)
        assertTrue(generateSequence((result as ErrorResult).throwable) { it.cause }.take(8)
            .any { it.message == "Image redirect is unavailable" })
        assertEquals(listOf(302, 302), transport.responses.filterNot { it.control }.map { it.status })
        assertTrue(transport.dnsHosts.all { it == "image.anitabi.cn" })
        checks["secondHopBlockedByProductionGuard"] = true
    }

    @Test fun downgradedSchemeAndCredentialRedirectsAreRejectedBeforeFollowing() = runBlocking {
        val loader = loader()
        for (name in listOf("redirect-http", "redirect-credentials")) {
            val count = transport.responses.size
            val result = loader.execute(request(image(name)))
            assertTrue(result is ErrorResult)
            assertTrue(generateSequence((result as ErrorResult).throwable) { it.cause }.take(8)
                .any { it.message == "Image redirect is unavailable" })
            assertEquals(listOf(302), transport.responses.drop(count).map { it.status })
        }
        assertTrue(transport.dnsHosts.all { it == "image.anitabi.cn" })
        checks["schemeAndCredentialsBlocked"] = true
    }

    @Test fun independentBangumiCoverStillUsesItsExistingHostAndRealDecoder() = runBlocking {
        val source = "https://lain.bgm.tv/pic/cover/l/synthetic.png"
        val model = AnitabiImageReference.displayModel(source, AnitabiImageVariant.THUMBNAIL)
        assertEquals(source, model)
        success(loader().execute(ImageRequest.Builder(context).data(model).size(8, 6).allowHardware(false).build()))
        assertEquals(1, transport.responses.size)
        assertFalse(transport.responses.single().anitabi)
        assertNull(transport.responses.single().plan)
        assertEquals(listOf("lain.bgm.tv"), transport.dnsHosts.toList())
        checks["bangumiHostAndQueryPreserved"] = true
    }

    @Test fun lateOldFailureCannotCoverTheNewResourcesDecodedPixels() {
        val lateTerminal = CountDownLatch(1)
        val lateCancelled = AtomicInteger()
        val loader = loader(listener = object : EventListener() {
            override fun onCancel(request: ImageRequest) {
                if (request.data.toString().contains("late-a.png")) {
                    lateCancelled.incrementAndGet()
                    lateTerminal.countDown()
                }
            }
            override fun onError(request: ImageRequest, result: ErrorResult) {
                if (request.data.toString().contains("late-a.png")) lateTerminal.countDown()
            }
        })
        previous = SingletonImageLoader.get(context)
        SingletonImageLoader.setUnsafe(loader)
        val url = mutableStateOf(image("late-a"))
        compose.setContent { AnitabiTheme {
            DiscoveryThumbnail(url.value, Modifier.size(56.dp).testTag("late-image"))
        } }
        try {
            transport.control("await-late-a")
            compose.runOnIdle { url.value = image("upload", "new-resource") }
            awaitNewPixels()
            transport.control("release-late-a")
            transport.control("await-late-a-released")
            assertTrue(lateTerminal.await(15, TimeUnit.SECONDS))
            compose.waitForIdle()
            awaitNewPixels()
            compose.onNodeWithContentDescription(COMPACT_RETRY).assertDoesNotExist()
            assertEquals(1, lateCancelled.get())
            checks["oldRequestCancelledBeforeLateServerFailure"] = true
            checks["newDecodedPixelsSurvivedLateFailure"] = true
        } finally {
            transport.control("release-late-a")
        }
    }

    private fun awaitNewPixels() {
        compose.waitUntil(15_000) {
            val image = compose.onNodeWithTag("late-image").captureToImage()
            val pixel = image.toPixelMap()[image.width / 2, image.height / 2]
            kotlin.math.abs(pixel.red * 255 - 31) < 1 && kotlin.math.abs(pixel.green * 255 - 113) < 1 &&
                kotlin.math.abs(pixel.blue * 255 - 179) < 1
        }
    }

    private fun loader(client: OkHttpClient = transport.client, disk: DiskCache? = null, listener: EventListener? = null): ImageLoader =
        createAppImageLoader(context, client).newBuilder().memoryCachePolicy(CachePolicy.DISABLED)
            .diskCache(disk).diskCachePolicy(if (disk == null) CachePolicy.DISABLED else CachePolicy.ENABLED)
            .apply { if (listener != null) eventListener(listener) }.build().also { loaders += it }

    private fun image(name: String, version: String = "resilience-fixture") =
        "https://image.anitabi.cn/images/points/synthetic-owner/$name.png?v=$version"

    private fun request(source: String, offline: Boolean = false, variant: AnitabiImageVariant = AnitabiImageVariant.THUMBNAIL): ImageRequest =
        ImageRequest.Builder(context).data(AnitabiImageReference.request(source, variant)).size(8, 6).allowHardware(false)
            .networkCachePolicy(if (offline) CachePolicy.DISABLED else CachePolicy.ENABLED).build()

    private fun success(result: ImageResult): SuccessResult {
        assertTrue("The real Coil request must decode the HTTPS fixture", result is SuccessResult)
        val success = result as SuccessResult
        val bitmap = success.image.toBitmap()
        assertEquals(8, bitmap.width); assertEquals(6, bitmap.height)
        assertEquals(Color.rgb(31, 113, 179), bitmap.getPixel(3, 2))
        return success
    }

    companion object {
        private const val COMPACT_RETRY = "\u56fe\u7247\u52a0\u8f7d\u5931\u8d25\uff0c\u91cd\u8bd5"
    }
}
