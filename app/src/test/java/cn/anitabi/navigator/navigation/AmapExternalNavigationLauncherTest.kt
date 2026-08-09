package cn.anitabi.navigator.navigation

import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmapExternalNavigationLauncherTest {
    private val origin = GeoPoint(12.34567849, -98.76543249)
    private val destination = GeoPoint(-0.0000004, 2.5)

    @Test
    fun `uri contains bounded encoded names deterministic WGS84 coordinates and dev flag`() {
        val spec = amapRouteUrlSpec(
            sourceApplication = "cn.anitabi.navigator",
            originWgs84 = origin,
            destinationWgs84 = destination,
            originName = " 起 点/一 ",
            destinationName = "终点? 二",
            mode = TravelMode.DRIVE,
        )

        assertEquals("amapuri", spec.scheme)
        assertEquals("route", spec.authority)
        assertEquals("/plan/", spec.path)
        assertEquals(
            listOf(
                "sourceApplication" to "cn.anitabi.navigator",
                "sname" to "起 点/一",
                "slat" to "12.345678",
                "slon" to "-98.765432",
                "dname" to "终点? 二",
                "dlat" to "0",
                "dlon" to "2.5",
                "dev" to "1",
                "t" to "0",
            ),
            spec.queryParameters,
        )
        assertEquals(
            "amapuri://route/plan/?sourceApplication=cn.anitabi.navigator" +
                "&sname=%E8%B5%B7%20%E7%82%B9%2F%E4%B8%80" +
                "&slat=12.345678&slon=-98.765432" +
                "&dname=%E7%BB%88%E7%82%B9%3F%20%E4%BA%8C" +
                "&dlat=0&dlon=2.5&dev=1&t=0",
            amapRouteUrl(spec),
        )
    }

    @Test
    fun `all four modes map to the official AMap route type`() {
        val expected = mapOf(
            TravelMode.DRIVE to "0",
            TravelMode.TRANSIT to "1",
            TravelMode.WALK to "2",
            TravelMode.BIKE to "3",
        )

        expected.forEach { (mode, routeType) ->
            val spec = amapRouteUrlSpec("app", origin, destination, "from", "to", mode)
            assertEquals(routeType, spec.queryParameters.single { it.first == "t" }.second)
        }
    }

    @Test
    fun `launcher targets only the installed AMap package and never falls back`() {
        val attempts = mutableListOf<Pair<String, String>>()
        val launcher = AmapExternalNavigationLauncher(
            sourceApplication = "app",
            urlFactory = AmapRouteUrlFactory(::amapRouteUrl),
            starter = AmapRouteStarter { url, packageName ->
                attempts += url to packageName
                false
            },
        )

        assertFalse(launcher.launch(origin, destination, "from", "to", TravelMode.WALK))
        assertEquals(1, attempts.size)
        assertEquals(AmapExternalNavigationLauncher.AMAP_PACKAGE, attempts.single().second)
        assertTrue(attempts.single().first.startsWith("amapuri://route/plan/?"))
    }

    @Test
    fun `blank oversized and control-character labels fail closed`() {
        listOf(
            " ",
            "x".repeat(257),
            "bad\nname",
        ).forEach { invalidName ->
            assertTrue(
                runCatching {
                    amapRouteUrlSpec("app", origin, destination, invalidName, "to", TravelMode.BIKE)
                }.isFailure,
            )
        }
        listOf(" ", "x".repeat(129), "bad\tapp").forEach { invalidSource ->
            assertTrue(
                runCatching {
                    amapRouteUrlSpec(invalidSource, origin, destination, "from", "to", TravelMode.BIKE)
                }.isFailure,
            )
        }
    }

    @Test
    fun `resolved starter failures remain explicit launch failures`() {
        assertFalse(startResolvableAmapIntent(hasHandler = false) { error("must not start") })
        assertFalse(startResolvableAmapIntent(hasHandler = true) { throw SecurityException("blocked") })
        assertTrue(startResolvableAmapIntent(hasHandler = true) {})
    }
}
