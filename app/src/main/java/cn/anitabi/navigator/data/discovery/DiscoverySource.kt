package cn.anitabi.navigator.data.discovery

import cn.anitabi.navigator.data.network.ApiHttpClient
import cn.anitabi.navigator.data.network.UserAgentInterceptor
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
) : DiscoverySource {
    constructor(userAgent: UserAgentInterceptor) : this(userAgent, OkHttpClient.Builder())

    private val client = ApiHttpClient(
        userAgentInterceptor = userAgent,
        clientBuilder = clientBuilder
            .cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .followRedirects(false)
            .followSslRedirects(false),
    )

    override suspend fun index(cacheToken: String): JsonElement = staticFile("g", cacheToken)

    override suspend fun page(page: Int, cacheToken: String): JsonElement {
        require(page >= 0)
        return staticFile("g$page", cacheToken)
    }

    override suspend fun subject(subjectId: Long): JsonElement {
        require(subjectId > 0)
        return client.execute(
            Request.Builder().url("https://api.anitabi.cn/bangumi/$subjectId/points/detail").build(),
            JsonElement.serializer(),
        )
    }

    private suspend fun staticFile(name: String, token: String): JsonElement {
        require(token.matches(Regex("[a-z0-9]+")))
        return client.execute(
            Request.Builder().url("https://www.anitabi.cn/d/$name.json?d=$token").build(),
            JsonElement.serializer(),
        )
    }
}
