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
    ): T {
        val response = try {
            client.newCall(request).awaitBody()
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
        return try {
            json.decodeFromString(deserializer, response.body)
        } catch (exception: Exception) {
            throw ApiException.InvalidResponse(exception)
        }
    }

    private suspend fun Call.awaitBody(): HttpResponseBody = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        continuation.resume(
                            HttpResponseBody(
                                status = it.code,
                                retryAfter = it.header("Retry-After"),
                                body = it.body.string(),
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
