package cn.anitabi.navigator.data.discovery

import cn.anitabi.navigator.data.network.ApiException
import cn.anitabi.navigator.data.network.UserAgentInterceptor
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class DiscoverySourceTest {
    @Test fun publicRequestsUseOnlyAllowedPathsWithoutCredentials() = runTest {
        val requests = mutableListOf<Request>()
        val source = source(requests)
        source.index("abc123")
        source.page(8, "abc123")
        source.subject(123)
        assertEquals(
            listOf("/d/g.json", "/d/g8.json", "/bangumi/123/points/detail"),
            requests.map { it.url.encodedPath },
        )
        assertEquals(listOf("www.anitabi.cn", "www.anitabi.cn", "api.anitabi.cn"), requests.map { it.url.host })
        requests.forEach {
            assertEquals("https", it.url.scheme)
            assertNull(it.header("Authorization"))
            assertNull(it.header("Cookie"))
            assertNull(it.header("X-Goog-Api-Key"))
        }
        assertEquals("abc123", requests.first().url.queryParameter("d"))
        assertNull(requests.last().url.query)
    }

    @Test fun redirectsAreRejectedRatherThanFollowingAnUnapprovedHost() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/redirected")))
            server.enqueue(MockResponse().setBody("[]"))
            val source = HttpDiscoverySource(
                UserAgentInterceptor("SyntheticApp", "1", "https://example.com"),
                OkHttpClient.Builder().addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().url(server.url("/index")).build())
                },
            )
            val result = runCatching { source.index("abc123") }
            assertTrue(result.exceptionOrNull() is ApiException.Http)
            assertEquals(1, server.requestCount)
            assertEquals("SyntheticApp/1 (https://example.com)", server.takeRequest().getHeader("User-Agent"))
        }
    }

    @Test fun invalidPathInputsFailBeforeSendingARequest() = runTest {
        val requests = mutableListOf<Request>()
        val source = source(requests)
        assertTrue(runCatching { source.index("abc&key=value") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { source.page(-1, "abc") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { source.subject(0) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(requests.isEmpty())
    }

    private fun source(requests: MutableList<Request>) = HttpDiscoverySource(
        UserAgentInterceptor("SyntheticApp", "1", "https://example.com"),
        OkHttpClient.Builder().addInterceptor { chain ->
            requests.add(chain.request())
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("Synthetic response")
                .header("Location", "https://unapproved.invalid/private")
                .body("[]".toResponseBody())
                .build()
        },
    )
}
