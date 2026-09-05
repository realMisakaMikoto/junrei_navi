package cn.anitabi.navigator.data.network.backend

import cn.anitabi.navigator.core.model.CoordinateSystem
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.core.model.RouteObjective
import cn.anitabi.navigator.core.model.TravelMode
import cn.anitabi.navigator.data.auth.IdTokenProvider
import cn.anitabi.navigator.data.network.ApiException
import cn.anitabi.navigator.data.network.ApiHttpClient
import cn.anitabi.navigator.data.network.UserAgentInterceptor
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BackendApiV2Test {
    private lateinit var server: MockWebServer
    private lateinit var api: BackendApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = BackendApi(
            httpClient = ApiHttpClient(UserAgentInterceptor("AnitabiNavigator", "test", "https://example.org")),
            tokenProvider = IdTokenProvider { "test-token" },
            baseUrl = server.url("/").toString(),
            contractVersion = BackendContractVersion.V2,
            regionDataVersion = { REGION_VERSION },
            appVersion = "0.2.5",
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `v2 matrix sends policy headers and no client-selected provider fields`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"elements":[],"provider":"GOOGLE","coordinateSystem":"WGS84","regionDataVersion":"$REGION_VERSION"}""",
            ),
        )

        api.matrix(
            mode = TravelMode.WALK,
            coordinates = points(),
            objective = RouteObjective.FASTEST,
            expectedProvider = MapProvider.GOOGLE,
            expectedCoordinateSystem = CoordinateSystem.WGS84,
        )

        val request = server.takeRequest()
        assertEquals("/v2/matrix", request.requestUrl?.encodedPath)
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
        assertEquals(REGION_VERSION, request.getHeader(BackendApi.REGION_DATA_VERSION_HEADER))
        assertEquals("0.2.5", request.getHeader(BackendApi.APP_VERSION_HEADER))
        val body = request.body.readUtf8()
        assertFalse(body.contains("provider"))
        assertFalse(body.contains("coordinateSystem"))
    }

    @Test
    fun `v2 AMap route requires matching provider CRS and version metadata`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"distanceMeters":1000,"durationSeconds":600,"legs":[{"distanceMeters":1000,"durationSeconds":600,"encodedPolyline":"_p~iF~ps|U_ulLnnqC"}],"provider":"AMAP","coordinateSystem":"GCJ02","regionDataVersion":"$REGION_VERSION"}""",
            ),
        )

        val response = api.route(
            mode = TravelMode.DRIVE,
            locations = points(),
            expectedProvider = MapProvider.AMAP,
            expectedCoordinateSystem = CoordinateSystem.GCJ02,
        )

        assertEquals(MapProvider.AMAP, response.provider)
        assertEquals(CoordinateSystem.GCJ02, response.coordinateSystem)
        assertEquals(REGION_VERSION, response.regionDataVersion)
        assertEquals("/v2/route", server.takeRequest().requestUrl?.encodedPath)
    }

    @Test
    fun `v2 missing metadata fails without a v1 retry`() {
        server.enqueue(MockResponse().setBody("""{"distanceMeters":0,"durationSeconds":0,"legs":[]}"""))

        assertThrows(ApiException.InvalidResponse::class.java) {
            runBlocking {
                api.route(
                    mode = TravelMode.WALK,
                    locations = points(),
                    expectedProvider = MapProvider.GOOGLE,
                    expectedCoordinateSystem = CoordinateSystem.WGS84,
                )
            }
        }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `v2 navigation sends WGS84 endpoints and validates strategy`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"reservedDestinations":1,"executionStrategy":"EXTERNAL_AMAP_MAINLAND","provider":"AMAP","coordinateSystem":"WGS84","regionDataVersion":"$REGION_VERSION"}""",
            ),
        )

        val reservation = api.reserveNavigation(
            origin = GeoPoint(30.0, 120.0),
            destinations = listOf(GeoPoint(30.1, 120.1)),
            expectedProvider = MapProvider.AMAP,
        )

        assertEquals(BackendNavigationExecutionStrategy.EXTERNAL_AMAP_MAINLAND, reservation.executionStrategy)
        val request = server.takeRequest()
        assertEquals("/v2/navigation/reserve", request.requestUrl?.encodedPath)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"origin\""))
        assertTrue(body.contains("\"destinations\""))
        assertFalse(body.contains("provider"))
        assertFalse(body.contains("coordinateSystem"))
    }

    @Test
    fun `policy is an unauthenticated v2 bootstrap request`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"apiVersion":"2","regionDataVersion":"$REGION_VERSION","minimumAppVersion":"0.2.5","v1SunsetAt":"2026-12-01T00:00:00Z","providers":{"google":"enabled","amap":"enabled"}}""",
            ),
        )

        val policy = api.policy()

        assertEquals("2", policy.apiVersion)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v2/policy", request.requestUrl?.encodedPath)
        assertNull(request.getHeader("Authorization"))
        assertNull(request.getHeader(BackendApi.REGION_DATA_VERSION_HEADER))
        assertNull(request.getHeader(BackendApi.APP_VERSION_HEADER))
    }

    @Test
    fun `v2 stable upgrade error maps without a compatibility retry`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(426)
                .setBody("""{"error":{"code":"CLIENT_UPGRADE_REQUIRED"}}"""),
        )

        assertThrows(ApiException.ClientUpgradeRequired::class.java) {
            runBlocking {
                api.route(
                    mode = TravelMode.WALK,
                    locations = points(),
                    expectedProvider = MapProvider.GOOGLE,
                    expectedCoordinateSystem = CoordinateSystem.WGS84,
                )
            }
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `missing v2 endpoints are backend failures without compatibility retries`() {
        assertMissingEndpoint("/v2/policy") { api.policy() }
        assertMissingEndpoint("/v2/matrix") {
            api.matrix(
                mode = TravelMode.WALK,
                coordinates = points(),
                objective = RouteObjective.FASTEST,
                expectedProvider = MapProvider.GOOGLE,
                expectedCoordinateSystem = CoordinateSystem.WGS84,
            )
        }
        for (provider in MapProvider.entries) {
            assertMissingEndpoint("/v2/route") {
                api.route(
                    mode = TravelMode.WALK,
                    locations = points(),
                    expectedProvider = provider,
                    expectedCoordinateSystem = if (provider == MapProvider.AMAP) {
                        CoordinateSystem.GCJ02
                    } else {
                        CoordinateSystem.WGS84
                    },
                )
            }
        }
        assertMissingEndpoint("/v2/navigation/reserve") {
            api.reserveNavigation(
                origin = points().first(),
                destinations = points().drop(1),
                expectedProvider = MapProvider.GOOGLE,
            )
        }
    }

    @Test
    fun `HTML and unknown error 404s are backend failures and do not expose the response`() {
        val bodies = listOf(
            "<html>private deployment detail</html>",
            """{"error":{"code":"NOT_FOUND","message":"private deployment detail"}}""",
        )
        for (body in bodies) {
            server.enqueue(MockResponse().setResponseCode(404).setBody(body))

            val exception = assertThrows(ApiException.BackendUnavailable::class.java) {
                runBlocking { api.policy() }
            }

            assertFalse(exception.message.orEmpty().contains("private deployment detail"))
        }
        assertEquals(bodies.size, server.requestCount)
    }

    @Test
    fun `v2 documented no route and upstream failures retain their distinct meanings`() {
        val cases = listOf(
            Triple(404, "NO_ROUTE", ApiException.NoRoute::class.java),
            Triple(502, "UPSTREAM_UNAVAILABLE", ApiException.UpstreamUnavailable::class.java),
        )
        for ((status, code, exceptionType) in cases) {
            server.enqueue(
                MockResponse().setResponseCode(status)
                    .setBody("""{"error":{"code":"$code","message":"private provider detail"}}"""),
            )

            val exception = assertThrows(exceptionType) {
                runBlocking {
                    api.route(
                        mode = TravelMode.WALK,
                        locations = points(),
                        expectedProvider = MapProvider.GOOGLE,
                        expectedCoordinateSystem = CoordinateSystem.WGS84,
                    )
                }
            }

            assertFalse(exception.message.orEmpty().contains("private provider detail"))
        }
        assertEquals(cases.size, server.requestCount)
    }

    private fun assertMissingEndpoint(path: String, request: suspend () -> Unit) {
        val countBefore = server.requestCount
        server.enqueue(
            MockResponse().setResponseCode(404)
                .setBody("""{"message":"Not Found","error":"Not Found","statusCode":404}"""),
        )

        assertThrows(ApiException.BackendUnavailable::class.java) { runBlocking { request() } }

        assertEquals(countBefore + 1, server.requestCount)
        assertEquals(path, server.takeRequest().requestUrl?.encodedPath)
    }

    private fun points() = listOf(GeoPoint(35.0, 139.0), GeoPoint(35.1, 139.1))

    private companion object {
        const val REGION_VERSION = "TEST_ONLY-region-v1"
    }
}
