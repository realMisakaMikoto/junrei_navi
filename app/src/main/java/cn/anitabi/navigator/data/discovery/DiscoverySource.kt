package cn.anitabi.navigator.data.discovery

import cn.anitabi.navigator.data.network.ApiHttpClient
import cn.anitabi.navigator.data.network.ApiHttpObserver
import cn.anitabi.navigator.data.network.ApiException
import cn.anitabi.navigator.data.network.UserAgentInterceptor
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request

interface DiscoverySource {
    suspend fun index(cacheToken: String): JsonElement
    suspend fun page(page: Int, cacheToken: String): JsonElement
    suspend fun subject(subjectId: Long): JsonElement
}

/** Dedicated public client: no auth, cookie jar, redirects, or provider credentials. */
class HttpDiscoverySource internal constructor(
    userAgent: UserAgentInterceptor,
    clientBuilder: OkHttpClient.Builder,
    private val trace: DiscoveryLoadTrace = DiscoveryLoadTrace(),
) : DiscoverySource {
    constructor(userAgent: UserAgentInterceptor, trace: DiscoveryLoadTrace = DiscoveryLoadTrace()) :
        this(userAgent, OkHttpClient.Builder(), trace)

    private val client = ApiHttpClient(
        userAgentInterceptor = userAgent,
        clientBuilder = clientBuilder
            .cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .followRedirects(false)
            .followSslRedirects(false),
    )

    override suspend fun index(cacheToken: String): JsonElement = staticFile("g", cacheToken, DiscoveryLoadEndpoint.STATIC_INDEX)

    override suspend fun page(page: Int, cacheToken: String): JsonElement {
        require(page >= 0)
        return staticFile("g$page", cacheToken, DiscoveryLoadEndpoint.STATIC_PAGE)
    }

    override suspend fun subject(subjectId: Long): JsonElement {
        require(subjectId > 0)
        return execute(
            Request.Builder().url("https://api.anitabi.cn/bangumi/$subjectId/points/detail").build(),
            DiscoveryLoadEndpoint.SUBJECT_DETAILS,
        )
    }

    private suspend fun staticFile(name: String, token: String, endpoint: DiscoveryLoadEndpoint): JsonElement {
        require(token.matches(Regex("[a-z0-9]+")))
        return execute(
            Request.Builder().url("https://www.anitabi.cn/d/$name.json?d=$token").build(),
            endpoint,
        )
    }

    private suspend fun execute(request: Request, endpoint: DiscoveryLoadEndpoint): JsonElement {
        if (!trace.enabled) return client.execute(request, JsonElement.serializer())
        val observation = DiscoveryRequestObservation(trace, endpoint)
        try {
            return client.execute(request, JsonElement.serializer(), observer = observation).also {
                observation.finish(DiscoveryLoadOutcome.OBSERVED)
            }
        } catch (cancelled: CancellationException) {
            observation.finish(DiscoveryLoadOutcome.CANCELLED)
            throw cancelled
        } catch (failure: Exception) {
            observation.finish(DiscoveryLoadOutcome.FAILED, failure.discoveryLoadError())
            throw failure
        }
    }
}

private class DiscoveryRequestObservation(
    private val trace: DiscoveryLoadTrace,
    private val endpoint: DiscoveryLoadEndpoint,
) : ApiHttpObserver {
    private val fetch = trace.begin(when (endpoint) {
        DiscoveryLoadEndpoint.STATIC_INDEX -> DiscoveryLoadPhase.INDEX_FETCH
        DiscoveryLoadEndpoint.STATIC_PAGE -> DiscoveryLoadPhase.PAGE_FETCH
        else -> DiscoveryLoadPhase.SUBJECT_FETCH
    })
    private var parsing: DiscoveryLoadTrace.Span? = null
    private var statusCode: Int? = null
    private var cache = DiscoveryLoadCache.UNKNOWN
    private var bytes: Long? = null
    private var active = true

    @Synchronized
    override fun onResponse(statusCode: Int, fromCache: Boolean) {
        if (!active) return
        this.statusCode = statusCode
        cache = if (fromCache) DiscoveryLoadCache.DISK else DiscoveryLoadCache.NETWORK
        bytes = 0
    }

    @Synchronized
    override fun onBodyBytes(count: Long) {
        if (active) bytes = (bytes ?: 0) + count
    }

    @Synchronized
    override fun onBodyCompleted() {
        if (!active) return
        val success = statusCode in 200..299
        trace.end(fetch, if (success) DiscoveryLoadOutcome.OBSERVED else DiscoveryLoadOutcome.FAILED,
            error = if (success) null else statusCode.httpLoadError())
    }

    @Synchronized
    override fun onParseStarted() {
        if (active) parsing = trace.begin(DiscoveryLoadPhase.JSON_PARSE)
    }

    @Synchronized
    override fun onParseCompleted(success: Boolean) {
        if (!active) return
        trace.end(parsing, if (success) DiscoveryLoadOutcome.OBSERVED else DiscoveryLoadOutcome.FAILED,
            error = if (success) null else DiscoveryLoadError.INVALID_DATA)
    }

    @Synchronized
    fun finish(outcome: DiscoveryLoadOutcome, error: DiscoveryLoadError? = null) {
        if (!active) return
        active = false
        trace.end(fetch, outcome, error)
        trace.end(parsing, outcome, error)
        trace.request(endpoint, outcome, receivedBytes = bytes, cache = cache, error = error, statusCode = statusCode)
    }
}

private fun Int?.httpLoadError(): DiscoveryLoadError = when (this) {
    401, 403 -> DiscoveryLoadError.DENIED
    404 -> DiscoveryLoadError.NOT_FOUND
    429 -> DiscoveryLoadError.RATE_LIMITED
    in 500..599 -> DiscoveryLoadError.SERVER
    null -> DiscoveryLoadError.UNKNOWN
    else -> DiscoveryLoadError.HTTP_OTHER
}

private fun Exception.discoveryLoadError(): DiscoveryLoadError = when (this) {
    is ApiException.InvalidResponse -> DiscoveryLoadError.INVALID_DATA
    is ApiException.InvalidCredentials, is ApiException.Forbidden -> DiscoveryLoadError.DENIED
    is ApiException.NotFound -> DiscoveryLoadError.NOT_FOUND
    is ApiException.RateLimited -> DiscoveryLoadError.RATE_LIMITED
    is ApiException.Server -> DiscoveryLoadError.SERVER
    is ApiException.Http -> DiscoveryLoadError.HTTP_OTHER
    else -> when (if (this is ApiException.Network) cause else this) {
        is UnknownHostException -> DiscoveryLoadError.DNS
        is SSLException -> DiscoveryLoadError.TLS
        is SocketTimeoutException -> DiscoveryLoadError.TIMEOUT
        is java.io.IOException -> DiscoveryLoadError.NETWORK
        else -> DiscoveryLoadError.UNKNOWN
    }
}
