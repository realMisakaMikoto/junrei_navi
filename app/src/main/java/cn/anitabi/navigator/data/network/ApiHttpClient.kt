package cn.anitabi.navigator.data.network

import java.io.IOException
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

class UserAgentInterceptor(
    appName: String,
    appVersion: String,
    contact: String,
) : Interceptor {
    val value: String = buildUserAgent(appName, appVersion, contact)

    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(
        chain.request().newBuilder().header("User-Agent", value).build(),
    )

    companion object {
        fun buildUserAgent(appName: String, appVersion: String, contact: String): String {
            require(appName.isNotBlank()) { "App name is required for User-Agent" }
            require(appVersion.isNotBlank()) { "App version is required for User-Agent" }
            require(contact.startsWith("https://") || contact.startsWith("mailto:")) {
                "Contact must be an HTTPS URL or mailto address"
            }
            return "$appName/$appVersion ($contact)"
        }
    }
}

class ApiHttpClient(
    userAgentInterceptor: UserAgentInterceptor,
    private val json: Json = defaultJson,
    clientBuilder: OkHttpClient.Builder = OkHttpClient.Builder(),
) {
    private val client = clientBuilder
        .retryOnConnectionFailure(false)
        .addInterceptor(userAgentInterceptor)
        .build()

    suspend fun <T> execute(
        request: Request,
        deserializer: DeserializationStrategy<T>,
        errorMapper: (status: Int, body: String, retryAfter: String?) -> ApiException =
            { status, body, _ -> ApiException.fromStatus(status, body) },
        observer: ApiHttpObserver? = null,
    ): T {
        val response = try {
            client.newCall(request).awaitBody(observer)
        } catch (exception: IOException) {
            throw ApiException.Network(exception)
        }

        if (response.status !in 200..299) {
            throw errorMapper(
                response.status,
                response.body.take(MAX_ERROR_BODY_LENGTH),
                response.retryAfter,
            )
        }
        observer.notifySafely { onParseStarted() }
        return try {
            json.decodeFromString(deserializer, response.body).also {
                observer.notifySafely { onParseCompleted(success = true) }
            }
        } catch (exception: Exception) {
            observer.notifySafely { onParseCompleted(success = false) }
            throw ApiException.InvalidResponse(exception)
        }
    }

    private suspend fun Call.awaitBody(observer: ApiHttpObserver?): HttpResponseBody = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        observer.notifySafely {
                            onResponse(it.code, fromCache = it.cacheResponse != null && it.networkResponse == null)
                        }
                        val body = it.body.readObservedString(observer)
                        observer.notifySafely { onBodyCompleted() }
                        continuation.resume(
                            HttpResponseBody(
                                status = it.code,
                                retryAfter = it.header("Retry-After"),
                                body = body,
                            ),
                        )
                    }
                } catch (exception: IOException) {
                    continuation.resumeWith(Result.failure(exception))
                }
            }
        })
    }

    companion object {
        private const val MAX_ERROR_BODY_LENGTH = 500

        val defaultJson = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }
    }
}

/** Opt-in scalar observation only; never exposes requests, headers, URLs or response contents. */
interface ApiHttpObserver {
    fun onResponse(statusCode: Int, fromCache: Boolean)
    /** Bytes consumed from the response body after OkHttp's transparent decompression, when any. */
    fun onBodyBytes(count: Long)
    fun onBodyCompleted()
    fun onParseStarted()
    fun onParseCompleted(success: Boolean)
}

private fun ResponseBody.readObservedString(observer: ApiHttpObserver?): String {
    if (observer == null) return string()
    val original = this
    val observed = object : ResponseBody() {
        private val counted = object : ForwardingSource(original.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long = super.read(sink, byteCount).also { count ->
                if (count > 0) observer.notifySafely { onBodyBytes(count) }
            }
        }.buffer()
        override fun contentType() = original.contentType()
        override fun contentLength() = original.contentLength()
        override fun source(): BufferedSource = counted
    }
    return observed.string()
}

private inline fun ApiHttpObserver?.notifySafely(block: ApiHttpObserver.() -> Unit) {
    if (this != null) try { block() } catch (_: Exception) { /* Diagnostics never change request behavior. */ }
}

private data class HttpResponseBody(
    val status: Int,
    val retryAfter: String?,
    val body: String,
)

sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotFound : ApiException("Resource not found")
    class RateLimited(val retryAfterMillis: Long? = null) : ApiException("API rate limit reached")
    class Server(val status: Int) : ApiException("API server error $status")
    class Http(val status: Int) : ApiException("HTTP $status")
    class InvalidCredentials : ApiException("API credentials were rejected")
    class Forbidden : ApiException("API access was forbidden")
    class Network(cause: IOException) : ApiException("Network request failed", cause)
    class InvalidResponse(cause: Throwable) : ApiException("API response could not be parsed", cause)
    class Unauthenticated(cause: Throwable? = null) : ApiException("Authentication failed", cause)
    class InvalidArgument : ApiException("The request is invalid")
    class NoRoute : ApiException("No route is available")
    class QuotaExhausted : ApiException("The shared monthly routing quota is exhausted")
    class UpstreamUnavailable : ApiException("The routing provider is unavailable")
    class BackendUnavailable : ApiException("The routing backend is unavailable")
    class MixedMapProviders : ApiException("All journey locations must use the same map provider")
    class MixedTransitRegions : ApiException("Japanese and non-Japanese transit locations cannot be mixed")
    class RegionUnresolved : ApiException("The map region could not be resolved safely")
    class RegionDataOutdated : ApiException("The approved region data must be updated")
    class ClientUpgradeRequired : ApiException("A newer app version is required")

    companion object {
        @Suppress("UNUSED_PARAMETER")
        fun fromStatus(status: Int, body: String): ApiException = when {
            status == 401 -> InvalidCredentials()
            status == 403 -> Forbidden()
            status == 404 -> NotFound()
            status == 429 -> RateLimited()
            status >= 500 -> Server(status)
            else -> Http(status)
        }
    }
}
