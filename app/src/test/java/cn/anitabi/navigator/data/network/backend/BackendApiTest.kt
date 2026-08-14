package cn.anitabi.navigator.data.network.backend

import cn.anitabi.navigator.BuildConfig
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.RouteObjective
import cn.anitabi.navigator.core.model.TransitRoutingPreference
import cn.anitabi.navigator.core.model.TransitTravelMode
import cn.anitabi.navigator.core.model.TravelMode
import cn.anitabi.navigator.data.auth.IdTokenProvider
import cn.anitabi.navigator.data.network.ApiException
import cn.anitabi.navigator.data.network.ApiHttpClient
import cn.anitabi.navigator.data.network.UserAgentInterceptor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BackendApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: BackendApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = BackendApi(
            httpClient = ApiHttpClient(UserAgentInterceptor("AnitabiNavigator", "0.2.1", "https://example.org")),
            tokenProvider = IdTokenProvider { "firebase-test-token" },
            baseUrl = server.url("/").toString(),
            contractVersion = BackendContractVersion.V1_COMPAT,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `matrix sends bearer JSON and normalized bounded contract`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"elements":[{"originIndex":0,"destinationIndex":1,"status":"OK","distanceMeters":1000,"durationSeconds":600}]}""",
            ),
        )

        val response = api.matrixV1(
            mode = TravelMode.BIKE,
            coordinates = listOf(GeoPoint(35.0, 139.0), GeoPoint(35.1, 139.1)),
            objective = RouteObjective.SHORTEST,
        )

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/matrix", request.requestUrl?.encodedPath)
        assertEquals("Bearer firebase-test-token", request.getHeader("Authorization"))
        assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"mode\":\"BICYCLE\""))
        assertTrue(body.contains("\"latitude\":35.0,\"longitude\":139.0"))
        assertTrue(body.contains("\"objective\":\"SHORTEST\""))
        assertEquals(600.0, response.elements.single().durationSeconds)
    }

    @Test
    fun `transit route sends exactly two locations and departure time`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"distanceMeters":1000,"durationSeconds":600,"legs":[{"distanceMeters":1000,"durationSeconds":600,"steps":[]}]}""",
            ),
        )

        api.routeV1(
            mode = TravelMode.TRANSIT,
            locations = listOf(GeoPoint(35.0, 139.0), GeoPoint(35.1, 139.1)),
            departureTime = "2026-07-29T09:00:00+09:00",
        )

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"mode\":\"TRANSIT\""))
        assertTrue(body.contains("\"departureTime\":\"2026-07-29T09:00:00+09:00\""))
        assertTrue(!body.contains("transitTravelModes"))
        assertEquals(2, Regex("\"latitude\"").findAll(body).count())
    }

    @Test
    fun `transit route sends arrival time and fewer transfers preference`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"distanceMeters":1000,"durationSeconds":600,"legs":[{"distanceMeters":1000,"durationSeconds":600,"steps":[]}]}""",
            ),
        )

        api.routeV1(
            mode = TravelMode.TRANSIT,
            locations = listOf(GeoPoint(35.0, 139.0), GeoPoint(35.1, 139.1)),
            arrivalTime = "2026-07-29T18:00:00+09:00",
            transitRoutingPreference = TransitRoutingPreference.FEWER_TRANSFERS,
            transitTravelModes = setOf(TransitTravelMode.TRAIN, TransitTravelMode.BUS),
        )

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"arrivalTime\":\"2026-07-29T18:00:00+09:00\""))
        assertTrue(body.contains("\"transitRoutingPreference\":\"FEWER_TRANSFERS\""))
        assertTrue(body.contains("\"transitTravelModes\":[\"BUS\",\"TRAIN\"]"))
        assertTrue(!body.contains("departureTime"))
    }

    @Test
    fun `bare HTTP 404 is a service failure rather than no transit route`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not a backend error envelope"))

        val exception = runCatching {
            runBlocking {
                api.routeV1(
                    mode = TravelMode.TRANSIT,
                    locations = listOf(GeoPoint(35.0, 139.0), GeoPoint(35.1, 139.1)),
                    departureTime = "2026-07-29T09:00:00+09:00",
                )
            }
        }.exceptionOrNull()

        assertTrue(exception is ApiException.UpstreamUnavailable)
    }

    @Test
    fun `stable NO_ROUTE envelope is the only 404 treated as no route`() {
        server.enqueue(
            MockResponse().setResponseCode(404).setBody(
                """{"error":{"code":"NO_ROUTE","message":"No route is available."}}""",
            ),
        )

        val exception = runCatching {
            runBlocking {
                api.routeV1(
                    mode = TravelMode.TRANSIT,
                    locations = listOf(GeoPoint(35.0, 139.0), GeoPoint(35.1, 139.1)),
                    departureTime = "2026-07-29T09:00:00+09:00",
                )
            }
        }.exceptionOrNull()

        assertTrue(exception is ApiException.NoRoute)
    }

    @Test
    fun `backend errors map by stable code without exposing message`() {
        server.enqueue(
            MockResponse().setResponseCode(429).setBody(
                """{"error":{"code":"QUOTA_EXHAUSTED","message":"private upstream detail"}}""",
            ),
        )

        val exception = runCatching {
            runBlocking {
                api.reserveNavigationV1(25)
            }
        }.exceptionOrNull()!!

        assertTrue(exception is ApiException.QuotaExhausted)
        assertTrue(!exception.message.orEmpty().contains("private upstream detail"))
    }

    @Test
    fun `trusted backend rate limit retries once then paces later posts until the bucket resets`() = runBlocking {
        val waits = mutableListOf<Long>()
        var now = 0L
        api = BackendApi(
            httpClient = ApiHttpClient(UserAgentInterceptor("AnitabiNavigator", "0.2.1", "https://example.org")),
            tokenProvider = IdTokenProvider { "firebase-test-token" },
            baseUrl = server.url("/").toString(),
            contractVersion = BackendContractVersion.V1_COMPAT,
            monotonicMillis = { now },
            sleeper = { millis ->
                waits += millis
                now += millis
            },
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "1")
                .setBody("""{"error":{"code":"RATE_LIMITED"}}"""),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"distanceMeters":1000,"durationSeconds":600,"legs":[]}""",
            ),
        )
        server.enqueue(MockResponse().setBody("""{"reservedDestinations":1}"""))
        server.enqueue(MockResponse().setBody("""{"reservedDestinations":1}"""))

        api.routeV1(
            mode = TravelMode.WALK,
            locations = listOf(GeoPoint(35.0, 139.0), GeoPoint(35.1, 139.1)),
        )
        api.reserveNavigationV1(1)
        now += 10_000L
        api.reserveNavigationV1(1)

        assertEquals(listOf(1_000L, 1_000L), waits)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `quota and untrusted bare rate limits are never retried`() {
        val cases = listOf(
            MockResponse().setResponseCode(429).setHeader("Retry-After", "1")
                .setBody("""{"error":{"code":"QUOTA_EXHAUSTED"}}"""),
            MockResponse().setResponseCode(429).setHeader("Retry-After", "1")
                .setBody("not a backend envelope"),
        )
        val exceptions = cases.map { response ->
            server.enqueue(response)
            runCatching {
                runBlocking {
                    api.routeV1(
                        mode = TravelMode.WALK,
                        locations = listOf(GeoPoint(35.0, 139.0), GeoPoint(35.1, 139.1)),
                    )
                }
            }.exceptionOrNull()
        }

        assertTrue(exceptions[0] is ApiException.QuotaExhausted)
        assertTrue(exceptions[1] is ApiException.RateLimited)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `second trusted rate limit stops after one retry`() {
        repeat(2) {
            server.enqueue(
                MockResponse().setResponseCode(429).setHeader("Retry-After", "1")
                    .setBody("""{"error":{"code":"RATE_LIMITED"}}"""),
            )
        }

        val exception = runCatching {
            runBlocking {
                api.routeV1(
                    mode = TravelMode.WALK,
                    locations = listOf(GeoPoint(35.0, 139.0), GeoPoint(35.1, 139.1)),
                )
            }
        }.exceptionOrNull()

        assertTrue(exception is ApiException.RateLimited)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `cancelling a trusted rate-limit wait sends no retry`() = runBlocking {
        val waiting = CompletableDeferred<Unit>()
        api = BackendApi(
            httpClient = ApiHttpClient(UserAgentInterceptor("AnitabiNavigator", "0.2.1", "https://example.org")),
            tokenProvider = IdTokenProvider { "firebase-test-token" },
            baseUrl = server.url("/").toString(),
            contractVersion = BackendContractVersion.V1_COMPAT,
            sleeper = {
                waiting.complete(Unit)
                CompletableDeferred<Unit>().await()
            },
        )
        server.enqueue(
            MockResponse().setResponseCode(429).setHeader("Retry-After", "1")
                .setBody("""{"error":{"code":"RATE_LIMITED"}}"""),
        )

        val requestJob = launch {
            api.routeV1(
                mode = TravelMode.WALK,
                locations = listOf(GeoPoint(35.0, 139.0), GeoPoint(35.1, 139.1)),
            )
        }
        waiting.await()
        requestJob.cancelAndJoin()

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `cancelling while a response body is streaming cancels the HTTP call`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody("""{"distanceMeters":1000,"durationSeconds":600,"legs":[]}""")
                .setBodyDelay(10, java.util.concurrent.TimeUnit.SECONDS),
        )

        val requestJob = launch {
            api.routeV1(
                mode = TravelMode.WALK,
                locations = listOf(GeoPoint(35.0, 139.0), GeoPoint(35.1, 139.1)),
            )
        }
        while (server.requestCount == 0) delay(10)
        withTimeout(2_000L) { requestJob.cancelAndJoin() }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `navigation reservation accepts the maximum batch`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"reservedDestinations":25}"""),
        )

        val reservation = api.reserveNavigationV1(25)

        assertEquals(25, reservation.reservedDestinations)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"destinationCount\":25"))
        assertEquals("https://api.anitabi.afunnypersonlol0.site", BackendApi.PRODUCTION_BASE_URL)
        assertEquals(BuildConfig.BACKEND_BASE_URL, BackendApi.BASE_URL)
    }

    @Test
    fun `navigation reservation ignores legacy daily quota metadata`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"reservedDestinations":1,"remainingToday":19}"""),
        )

        val reservation = api.reserveNavigationV1(1)

        assertEquals(1, reservation.reservedDestinations)
    }
}
