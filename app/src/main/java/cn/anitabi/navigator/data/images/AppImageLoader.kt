package cn.anitabi.navigator.data.images

import android.content.Context
import cn.anitabi.navigator.AnitabiApplication
import cn.anitabi.navigator.createAppUserAgentInterceptor
import cn.anitabi.navigator.data.discovery.DiscoveryLoadCache
import cn.anitabi.navigator.data.discovery.DiscoveryLoadCounter
import cn.anitabi.navigator.data.discovery.DiscoveryLoadEndpoint
import cn.anitabi.navigator.data.discovery.DiscoveryLoadError
import cn.anitabi.navigator.data.discovery.DiscoveryLoadOutcome
import cn.anitabi.navigator.data.discovery.DiscoveryLoadPhase
import cn.anitabi.navigator.data.discovery.DiscoveryLoadTrace
import coil3.EventListener
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.network.HttpException
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

internal fun createAppImageHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .addInterceptor(createAppUserAgentInterceptor())
    .addInterceptor { chain ->
        val request = chain.request()
        if (request.url.host != "image.anitabi.cn") return@addInterceptor chain.proceed(request)
        val canonical = AnitabiImageReference.normalize(request.url.toString())
            ?: throw IOException("Image resource is unavailable")
        chain.proceed(request.newBuilder().url(canonical)
            .removeHeader("Authorization").removeHeader("Cookie")
            .removeHeader("X-Goog-Api-Key").build())
    }
    .addNetworkInterceptor { chain ->
        val response = chain.proceed(chain.request())
        // Inspect each redirect before OkHttp can follow it, including redirects after a redirect.
        if (chain.call().request().url.host == "image.anitabi.cn" && response.isRedirect) {
            val target = response.header("Location")?.let { response.request.url.resolve(it) }
            if (target == null || AnitabiImageReference.normalize(target.toString()) != target.toString()) {
                response.close()
                throw IOException("Image redirect is unavailable")
            }
        }
        response
    }
    .build()

/** Shared production network/decoder setup; tests may replace only the HTTP transport. */
internal fun createAppImageLoader(
    context: Context,
    httpClient: OkHttpClient = createAppImageHttpClient(),
    trace: DiscoveryLoadTrace = (context.applicationContext as? AnitabiApplication)?.discoveryDiagnostics?.trace
        ?: DiscoveryLoadTrace(),
): ImageLoader = ImageLoader.Builder(context)
    .components { add(OkHttpNetworkFetcherFactory(httpClient.withImageTrace(trace))) }
    .apply { if (trace.enabled) eventListenerFactory { CoilImageTraceListener(trace) } }
    .build()

/** Keeps the original client, listeners and interceptors untouched when profiling is disabled. */
internal fun OkHttpClient.withImageTrace(trace: DiscoveryLoadTrace): OkHttpClient =
    if (!trace.enabled) this else newBuilder().apply { interceptors().add(0, ImageTraceInterceptor(trace)) }.build()

/** Loader success means Coil delivered a result; it never proves that a visible pixel was drawn. */
private class CoilImageTraceListener(private val trace: DiscoveryLoadTrace) : EventListener() {
    private val load = trace.begin(DiscoveryLoadPhase.IMAGE_LOAD)
    private var decoding: DiscoveryLoadTrace.Span? = null
    private var finished = false

    init { trace.increment(DiscoveryLoadCounter.IMAGE_REQUEST_COUNT) }

    @Synchronized
    override fun decodeStart(request: ImageRequest, decoder: Decoder, options: Options) {
        if (finished) return
        decoding = trace.begin(DiscoveryLoadPhase.IMAGE_DECODE)
        trace.increment(DiscoveryLoadCounter.IMAGE_DECODE_COUNT)
    }

    @Synchronized
    override fun decodeEnd(request: ImageRequest, decoder: Decoder, options: Options, result: DecodeResult?) {
        if (finished) return
        trace.end(decoding, if (result == null) DiscoveryLoadOutcome.EMPTY else DiscoveryLoadOutcome.OBSERVED,
            itemCount = if (result == null) 0 else 1)
        decoding = null
    }

    @Synchronized
    override fun onSuccess(request: ImageRequest, result: SuccessResult) {
        if (finished) return
        finished = true
        trace.increment(DiscoveryLoadCounter.IMAGE_SUCCESS_COUNT)
        when {
            result.dataSource == DataSource.MEMORY_CACHE -> trace.increment(DiscoveryLoadCounter.IMAGE_MEMORY_HIT_COUNT)
            // DISK alone also includes resources/files, which are not image-cache hits.
            result.dataSource == DataSource.DISK && result.diskCacheKey != null ->
                trace.increment(DiscoveryLoadCounter.IMAGE_DISK_HIT_COUNT)
        }
        trace.end(load)
    }

    @Synchronized
    override fun onError(request: ImageRequest, result: ErrorResult) {
        if (finished) return
        finished = true
        val error = result.throwable.imageLoadError(decoding = decoding != null)
        trace.increment(DiscoveryLoadCounter.IMAGE_ERROR_COUNT)
        trace.end(decoding, DiscoveryLoadOutcome.FAILED, error)
        trace.end(load, DiscoveryLoadOutcome.FAILED, error)
    }

    @Synchronized
    override fun onCancel(request: ImageRequest) {
        if (finished) return
        finished = true
        trace.increment(DiscoveryLoadCounter.IMAGE_CANCEL_COUNT)
        trace.end(decoding, DiscoveryLoadOutcome.CANCELLED)
        trace.end(load, DiscoveryLoadOutcome.CANCELLED)
    }
}

/** One record per OkHttp call, after redirects, with bytes consumed after transparent decompression. */
private class ImageTraceInterceptor(private val trace: DiscoveryLoadTrace) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val observation = ImageHttpObservation(trace, chain.call())
        try {
            val response = chain.proceed(chain.request())
            observation.response(response)
            val original = response.body
            val observed = object : ResponseBody() {
                private val counted = object : ForwardingSource(original.source()) {
                    override fun read(sink: Buffer, byteCount: Long): Long = try {
                        super.read(sink, byteCount).also { count ->
                            if (count == -1L) observation.finish() else observation.received(count)
                        }
                    } catch (failure: IOException) {
                        observation.finish(failure)
                        throw failure
                    }

                    override fun close() {
                        try { super.close() } catch (failure: IOException) {
                            observation.finish(failure)
                            throw failure
                        } finally { observation.finish() }
                    }
                }.buffer()
                override fun contentType() = original.contentType()
                override fun contentLength() = original.contentLength()
                override fun source(): BufferedSource = counted
            }
            return response.newBuilder().body(observed).build()
        } catch (failure: IOException) {
            observation.finish(failure)
            throw failure
        }
    }
}

private class ImageHttpObservation(private val trace: DiscoveryLoadTrace, private val call: Call) {
    private val fetch = trace.begin(DiscoveryLoadPhase.IMAGE_FETCH)
    private var finished = false
    private var bytes: Long? = null
    private var status: Int? = null
    private var cache = DiscoveryLoadCache.UNKNOWN

    @Synchronized
    fun response(response: Response) {
        status = response.code
        bytes = 0
        cache = if (response.cacheResponse != null && response.networkResponse == null)
            DiscoveryLoadCache.DISK else DiscoveryLoadCache.NETWORK
    }

    @Synchronized
    fun received(count: Long) { if (!finished) bytes = (bytes ?: 0) + count }

    @Synchronized
    fun finish(failure: IOException? = null) {
        if (finished) return
        finished = true
        val error = when {
            failure != null -> failure.imageLoadError()
            status in 200..299 || status == 304 -> null
            else -> status.imageHttpError()
        }
        val outcome = when {
            call.isCanceled() -> DiscoveryLoadOutcome.CANCELLED
            error != null -> DiscoveryLoadOutcome.FAILED
            else -> DiscoveryLoadOutcome.OBSERVED
        }
        val recordedError = error.takeIf { outcome == DiscoveryLoadOutcome.FAILED }
        trace.end(fetch, outcome, recordedError)
        trace.request(DiscoveryLoadEndpoint.IMAGE, outcome, bytes, cache, recordedError, status)
    }
}

private fun Throwable.imageLoadError(decoding: Boolean = false): DiscoveryLoadError = when (this) {
    is HttpException -> response.code.imageHttpError()
    is UnknownHostException -> DiscoveryLoadError.DNS
    is SSLException -> DiscoveryLoadError.TLS
    is SocketTimeoutException -> DiscoveryLoadError.TIMEOUT
    else -> if (decoding) DiscoveryLoadError.DECODE else if (this is IOException) DiscoveryLoadError.NETWORK else DiscoveryLoadError.UNKNOWN
}

private fun Int?.imageHttpError(): DiscoveryLoadError = when (this) {
    401, 403 -> DiscoveryLoadError.DENIED
    404 -> DiscoveryLoadError.NOT_FOUND
    429 -> DiscoveryLoadError.RATE_LIMITED
    in 500..599 -> DiscoveryLoadError.SERVER
    null -> DiscoveryLoadError.UNKNOWN
    else -> DiscoveryLoadError.HTTP_OTHER
}
