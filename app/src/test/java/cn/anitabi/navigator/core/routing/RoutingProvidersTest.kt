package cn.anitabi.navigator.core.routing

import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.CoordinateSystem
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.core.model.RouteObjective
import cn.anitabi.navigator.core.model.TransitRoutingPreference
import cn.anitabi.navigator.core.model.TransitTravelMode
import cn.anitabi.navigator.core.model.TravelMode
import cn.anitabi.navigator.data.auth.IdTokenProvider
import cn.anitabi.navigator.data.network.ApiException
import cn.anitabi.navigator.data.network.ApiHttpClient
import cn.anitabi.navigator.data.network.UserAgentInterceptor
import cn.anitabi.navigator.data.network.backend.BackendApi
import cn.anitabi.navigator.data.network.backend.BackendContractVersion
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RoutingProvidersTest {
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
            contractVersion = BackendContractVersion.V1_COMPAT,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `road provider maps matrix unreachable elements and Google polyline`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"elements":[{"originIndex":0,"destinationIndex":0,"status":"OK","distanceMeters":0,"durationSeconds":0},{"originIndex":0,"destinationIndex":1,"status":"UNREACHABLE"},{"originIndex":1,"destinationIndex":0,"status":"OK","distanceMeters":1000,"durationSeconds":600},{"originIndex":1,"destinationIndex":1,"status":"OK","distanceMeters":0,"durationSeconds":0}]}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"distanceMeters":1000,"durationSeconds":600,"legs":[{"distanceMeters":1000,"durationSeconds":600,"encodedPolyline":"_p~iF~ps|U_ulLnnqC_mqNvxq`@","steps":[{"travelMode":"WALK","distanceMeters":1000,"durationSeconds":600,"encodedPolyline":"_p~iF~ps|U_ulLnnqC_mqNvxq`@","instruction":"直行"}]}]}""",
            ),
        )
        val provider = BackendRoadRoutingProvider(api)
        val points = listOf(GeoPoint(38.5, -120.2), GeoPoint(43.252, -126.453))

        val matrix = provider.matrix(TravelMode.WALK, points, RouteObjective.FASTEST)
        val route = provider.directions(TravelMode.WALK, points)

        assertNull(matrix.durations[0][1])
        assertEquals(600.0, matrix.durations[1][0])
        assertEquals(3, route.segments.single().geometry.size)
        assertEquals("直行", route.segments.single().steps.single().instruction)
    }

    @Test
    fun `transit provider keeps Google line stop times and uses no invented platform`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"distanceMeters":5200,"durationSeconds":1020,"legs":[{"distanceMeters":5200,"durationSeconds":1020,"steps":[{"travelMode":"WALK","distanceMeters":200,"durationSeconds":180,"encodedPolyline":"_p~iF~ps|U_ulLnnqC"},{"travelMode":"TRANSIT","distanceMeters":4800,"durationSeconds":720,"encodedPolyline":"_ulLnnqC_mqNvxq`@","transit":{"departureStop":"Tokyo","arrivalStop":"Ueno","departureTime":"2026-07-29T00:03:00Z","arrivalTime":"2026-07-29T00:15:00Z","departureTimeZone":"Asia/Tokyo","arrivalTimeZone":"Asia/Tokyo","lineShortName":"JY","headsign":"Ueno","vehicleType":"HEAVY_RAIL","stopCount":3}},{"travelMode":"WALK","distanceMeters":200,"durationSeconds":120}]}]}""",
            ),
        )
        val journey = BackendTransitJourneyProvider(api).journey(
            from = GeoPoint(38.5, -120.2),
            to = GeoPoint(43.252, -126.453),
            query = TransitJourneyQuery(
                departureTime = "2026-07-29T08:50:00+09:00",
                routingPreference = TransitRoutingPreference.LESS_WALKING,
                transitTravelModes = setOf(TransitTravelMode.BUS, TransitTravelMode.TRAIN),
            ),
        )

        assertEquals(3, journey.legs.size)
        assertEquals("2026-07-29T00:00:00Z", journey.departureTime)
        assertEquals("2026-07-29T00:17:00Z", journey.arrivalTime)
        assertEquals(TravelMode.WALK, journey.legs.first().mode)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"transitRoutingPreference\":\"LESS_WALKING\""))
        assertTrue(body.contains("\"transitTravelModes\":[\"BUS\",\"TRAIN\"]"))
        val transit = journey.legs[1].transit!!
        assertEquals("JY", transit.line)
        assertEquals("Ueno", transit.direction)
        assertEquals("HEAVY_RAIL", transit.vehicleMode)
        assertEquals("Tokyo", transit.departureStop)
        assertEquals("Ueno", transit.arrivalStop)
        assertEquals("2026-07-29T00:03:00Z", transit.departureTime)
        assertEquals("2026-07-29T00:15:00Z", transit.arrivalTime)
        assertEquals("Asia/Tokyo", transit.departureTimeZone)
        assertEquals("Asia/Tokyo", transit.arrivalTimeZone)
        assertEquals(3, transit.stopCount)
        assertNull(transit.departurePlatform)
        assertTrue(transit.intermediateStops.isEmpty())
        assertTrue(journey.legs.all { it.source == GOOGLE_ROUTES_SOURCE })
    }

    @Test
    fun `arrive-by journey includes the trailing walk in its actual arrival`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"distanceMeters":5200,"durationSeconds":1020,"legs":[{"distanceMeters":5200,"durationSeconds":1020,"steps":[{"travelMode":"WALK","distanceMeters":200,"durationSeconds":180},{"travelMode":"TRANSIT","distanceMeters":4800,"durationSeconds":720,"transit":{"departureStop":"A","arrivalStop":"B","departureTime":"2026-07-29T09:03:00+09:00","arrivalTime":"2026-07-29T09:15:00+09:00","lineShortName":"T","vehicleType":"TRAIN","stopCount":3}},{"travelMode":"WALK","distanceMeters":200,"durationSeconds":120}]}]}""",
            ),
        )

        val journey = BackendTransitJourneyProvider(api).journey(
            from = GeoPoint(38.5, -120.2),
            to = GeoPoint(43.252, -126.453),
            query = TransitJourneyQuery(arrivalTime = "2026-07-29T09:30:00+09:00"),
        )

        assertEquals("2026-07-29T09:00:00+09:00", journey.departureTime)
        assertEquals("2026-07-29T09:17:00+09:00", journey.arrivalTime)
        assertEquals(TravelMode.WALK, journey.legs.last().mode)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"arrivalTime\":\"2026-07-29T09:30:00+09:00\""))
        assertTrue(!body.contains("departureTime"))
    }

    @Test
    fun `transit provider rejects positive route without transit step evidence`() {
        server.enqueue(
            MockResponse().setBody(
                """{"distanceMeters":1200,"durationSeconds":600,"legs":[{"distanceMeters":1200,"durationSeconds":600,"steps":[]}]}""",
            ),
        )

        val exception = assertThrows(ApiException.InvalidResponse::class.java) {
            runBlocking {
                BackendTransitJourneyProvider(api).journey(
                    from = GeoPoint(35.0, 139.0),
                    to = GeoPoint(35.01, 139.01),
                    query = TransitJourneyQuery(departureTime = "2026-07-29T09:00:00+09:00"),
                )
            }
        }

        assertTrue(exception.cause?.message.orEmpty().contains("contains no steps"))
    }

    @Test
    fun `transit provider preserves zero-distance route as a non-transit connector`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"distanceMeters":0,"durationSeconds":0,"legs":[{"distanceMeters":0,"durationSeconds":0,"steps":[]}]}""",
            ),
        )
        val coordinate = GeoPoint(35.0, 139.0)

        val journey = BackendTransitJourneyProvider(api).journey(
            from = coordinate,
            to = coordinate,
            query = TransitJourneyQuery(departureTime = "2026-07-29T09:00:00+09:00"),
        )

        assertEquals(TravelMode.WALK, journey.legs.single().mode)
        assertEquals(0.0, journey.legs.single().distanceMeters, 0.0)
        assertEquals(coordinate, journey.legs.single().from)
        assertEquals(coordinate, journey.legs.single().to)
    }

    @Test
    fun `AMap nontrivial route rejects missing GCJ02 geometry`() {
        api = BackendApi(
            httpClient = ApiHttpClient(UserAgentInterceptor("AnitabiNavigator", "test", "https://example.org")),
            tokenProvider = IdTokenProvider { "test-token" },
            baseUrl = server.url("/").toString(),
            regionDataVersion = { "TEST_ONLY-v1" },
        )
        server.enqueue(
            MockResponse().setBody(
                """{"distanceMeters":1000,"durationSeconds":600,"legs":[{"distanceMeters":1000,"durationSeconds":600,"steps":[]}],"provider":"AMAP","coordinateSystem":"GCJ02","regionDataVersion":"TEST_ONLY-v1"}""",
            ),
        )

        assertThrows(ApiException.InvalidResponse::class.java) {
            runBlocking {
                BackendRoadRoutingProvider(api).directions(
                    TravelMode.WALK,
                    listOf(GeoPoint(30.0, 120.0), GeoPoint(30.1, 120.1)),
                    RoutingProviderContext.AMAP,
                )
            }
        }
    }

    @Test
    fun `AMap transit keeps WGS84 handoff endpoints separate from GCJ02 geometry`() = runBlocking {
        api = BackendApi(
            httpClient = ApiHttpClient(UserAgentInterceptor("AnitabiNavigator", "test", "https://example.org")),
            tokenProvider = IdTokenProvider { "test-token" },
            baseUrl = server.url("/").toString(),
            regionDataVersion = { "TEST_ONLY-v1" },
        )
        server.enqueue(
            MockResponse().setBody(
                """{"distanceMeters":1000,"durationSeconds":600,"encodedPolyline":"_p~iF~ps|U_ulLnnqC","legs":[{"distanceMeters":1000,"durationSeconds":600,"steps":[{"travelMode":"TRANSIT","distanceMeters":1000,"durationSeconds":600,"encodedPolyline":"_p~iF~ps|U_ulLnnqC","transit":{"lineShortName":"T","vehicleType":"BUS"}}]}],"provider":"AMAP","coordinateSystem":"GCJ02","regionDataVersion":"TEST_ONLY-v1"}""",
            ),
        )
        val from = GeoPoint(30.0, 120.0)
        val to = GeoPoint(30.1, 120.1)

        val journey = BackendTransitJourneyProvider(api).journey(
            from = from,
            to = to,
            query = TransitJourneyQuery(departureTime = "2026-08-10T09:00:00+08:00"),
            context = RoutingProviderContext.AMAP,
        )

        val leg = journey.legs.single()
        assertEquals(from, leg.from)
        assertEquals(to, leg.to)
        assertEquals(MapProvider.AMAP, leg.provider)
        assertEquals(CoordinateSystem.GCJ02, leg.coordinateSystem)
        assertTrue(leg.geometry.isNotEmpty())
        assertTrue(leg.geometry.first() != from)
    }
}
