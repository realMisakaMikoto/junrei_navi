package cn.anitabi.navigator.data.images

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.anitabi.navigator.data.discovery.DiscoveryLoadCounter
import cn.anitabi.navigator.data.discovery.DiscoveryLoadEndpoint
import cn.anitabi.navigator.data.discovery.DiscoveryLoadError
import cn.anitabi.navigator.data.discovery.DiscoveryLoadOutcome
import cn.anitabi.navigator.data.discovery.DiscoveryLoadPhase
import cn.anitabi.navigator.data.discovery.DiscoveryLoadTrace
import cn.anitabi.navigator.data.discovery.DiscoveryLoadTraceConfig
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Path.Companion.toOkioPath
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Coil network fetcher and Android decoder; authored responses are injected, not public HTTPS. */
@RunWith(AndroidJUnit4::class)
class AppImageLoaderTraceInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun decodedNetworkMemoryAndDiskResultsKeepSeparateTraceCountsWithoutClaimingPixels() = runBlocking {
        val trace = enabled()
        val bytes = png()
        val calls = AtomicInteger()
        val client = createAppImageHttpClient().newBuilder().addInterceptor { chain ->
            calls.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("Synthetic image").body(bytes.toResponseBody("image/png".toMediaType())).build()
        }.build()
        val directory = File(context.cacheDir, "image-trace-${UUID.randomUUID()}")
        val diskCache = DiskCache.Builder().directory(directory.toOkioPath()).maxSizeBytes(1_048_576).build()
        val loader = createAppImageLoader(context, client, trace).newBuilder().diskCache(diskCache).build()
        try {
            val request = ImageRequest.Builder(context).data("https://image.anitabi.cn/points/synthetic/trace.png?plan=h160")
                .size(8, 6).allowHardware(false).build()
            val network = loader.execute(request) as SuccessResult
            assertEquals(DataSource.NETWORK, network.dataSource)
            assertEquals(0xff1f71b3.toInt(), network.image.toBitmap().getPixel(0, 0))
            assertEquals(DataSource.MEMORY_CACHE, (loader.execute(request) as SuccessResult).dataSource)
            val disk = loader.execute(request.newBuilder().memoryCachePolicy(CachePolicy.DISABLED).build()) as SuccessResult
            assertEquals(DataSource.DISK, disk.dataSource)
            assertEquals(0xff1f71b3.toInt(), disk.image.toBitmap().getPixel(0, 0))
            val state = trace.snapshot()
            assertEquals(1, calls.get())
            assertEquals(3L, state.counters[DiscoveryLoadCounter.IMAGE_REQUEST_COUNT])
            assertEquals(3L, state.counters[DiscoveryLoadCounter.IMAGE_SUCCESS_COUNT])
            assertEquals(1L, state.counters[DiscoveryLoadCounter.IMAGE_MEMORY_HIT_COUNT])
            assertEquals(1L, state.counters[DiscoveryLoadCounter.IMAGE_DISK_HIT_COUNT])
            assertEquals(2L, state.counters[DiscoveryLoadCounter.IMAGE_DECODE_COUNT])
            val http = state.requests.single { it.endpoint == DiscoveryLoadEndpoint.IMAGE }
            assertEquals(1L, http.completedCount)
            assertEquals(bytes.size.toLong(), http.receivedBytes)
            assertEquals(2, state.events.count { it.phase == DiscoveryLoadPhase.IMAGE_DECODE && it.outcome == DiscoveryLoadOutcome.OBSERVED })
            assertTrue(state.events.none { it.phase == DiscoveryLoadPhase.FIRST_VISIBLE_IMAGE })
            assertTrue(state.pendingSpans.isEmpty())
        } finally {
            loader.shutdown()
            diskCache.shutdown()
            directory.deleteRecursively()
        }
    }

    @Test fun successfulHttpBodyAndFailedActualDecoderAreDifferentResults() = runBlocking {
        val trace = enabled()
        val client = createAppImageHttpClient().newBuilder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("Synthetic corrupt image").body("not-image".toResponseBody("image/png".toMediaType())).build()
        }.build()
        val loader = createAppImageLoader(context, client, trace)
        try {
            val result = loader.execute(ImageRequest.Builder(context)
                .data("https://image.anitabi.cn/points/synthetic/corrupt.png?plan=h160")
                .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build())
            assertTrue(result is ErrorResult)
            val state = trace.snapshot()
            assertEquals(1L, state.counters[DiscoveryLoadCounter.IMAGE_ERROR_COUNT])
            val load = state.events.single { it.phase == DiscoveryLoadPhase.IMAGE_LOAD }
            assertEquals(DiscoveryLoadOutcome.FAILED, load.outcome)
            assertEquals(DiscoveryLoadError.DECODE, load.error)
            val http = state.requests.single { it.endpoint == DiscoveryLoadEndpoint.IMAGE }
            assertEquals(mapOf(DiscoveryLoadOutcome.OBSERVED to 1L), http.outcomes)
            assertEquals(9L, http.receivedBytes)
            assertTrue(state.pendingSpans.isEmpty())
        } finally { loader.shutdown() }
    }

    @Test fun cancellationClosesLogicalLoadAndLaterTransportWithoutFailureCounters() = runBlocking {
        val trace = enabled()
        val entered = CompletableDeferred<Unit>()
        val failed = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val client = createAppImageHttpClient().newBuilder()
            .eventListener(object : okhttp3.EventListener() {
                override fun callFailed(call: okhttp3.Call, ioe: IOException) { failed.complete(Unit) }
            })
            .addInterceptor {
                entered.complete(Unit)
                release.await()
                throw IOException("Synthetic cancelled image")
            }.build()
        val loader = createAppImageLoader(context, client, trace)
        try {
            val load = async(Dispatchers.Default) {
                loader.execute(ImageRequest.Builder(context).data("https://image.anitabi.cn/points/synthetic/cancel.png")
                    .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build())
            }
            withTimeout(10_000) { entered.await() }
            load.cancelAndJoin()
            release.countDown()
            withTimeout(10_000) { failed.await() }
            val state = trace.snapshot()
            assertEquals(1L, state.counters[DiscoveryLoadCounter.IMAGE_CANCEL_COUNT])
            assertEquals(0L, state.counters[DiscoveryLoadCounter.IMAGE_ERROR_COUNT] ?: 0)
            assertEquals(DiscoveryLoadOutcome.CANCELLED, state.events.single { it.phase == DiscoveryLoadPhase.IMAGE_LOAD }.outcome)
            val http = state.requests.single { it.endpoint == DiscoveryLoadEndpoint.IMAGE }
            assertEquals(mapOf(DiscoveryLoadOutcome.CANCELLED to 1L), http.outcomes)
            assertTrue(state.pendingSpans.isEmpty())
        } finally {
            release.countDown()
            loader.shutdown()
        }
    }

    private fun enabled() = DiscoveryLoadTrace(DiscoveryLoadTraceConfig(enabled = true))
    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(0xff1f71b3.toInt())
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
    }
}
