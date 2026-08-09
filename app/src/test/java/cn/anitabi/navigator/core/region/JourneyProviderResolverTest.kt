package cn.anitabi.navigator.core.region

import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.core.model.TerritoryRegion
import cn.anitabi.navigator.core.model.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class JourneyProviderResolverTest {
    private val start = GeoPoint(30.0, 120.0)
    private val destination = GeoPoint(30.1, 120.1)

    @Test
    fun `start and destinations resolve to one AMap provider`() {
        val resolved = resolveJourneyProvider(start, listOf(destination), TravelMode.DRIVE) {
            TerritoryRegion.MAINLAND_CHINA
        }

        assertEquals(MapProvider.AMAP, resolved.provider)
        assertEquals(setOf(TerritoryRegion.MAINLAND_CHINA), resolved.regions)
    }

    @Test
    fun `unresolved start stops before routing`() {
        assertThrows(JourneyProviderResolutionException.RegionUnresolved::class.java) {
            resolveJourneyProvider(start, listOf(destination), TravelMode.WALK) { point ->
                if (point == start) null else TerritoryRegion.MAINLAND_CHINA
            }
        }
    }

    @Test
    fun `mixed start and destination providers are rejected`() {
        assertThrows(JourneyProviderResolutionException.MixedMapProviders::class.java) {
            resolveJourneyProvider(start, listOf(destination), TravelMode.BIKE) { point ->
                if (point == start) TerritoryRegion.MAINLAND_CHINA else TerritoryRegion.OTHER
            }
        }
    }

    @Test
    fun `Japan and other Google transit regions are rejected`() {
        assertThrows(JourneyProviderResolutionException.MixedTransitRegions::class.java) {
            resolveJourneyProvider(start, listOf(destination), TravelMode.TRANSIT) { point ->
                if (point == start) TerritoryRegion.JAPAN else TerritoryRegion.OTHER
            }
        }
    }

    @Test
    fun `Japan and other Google road regions can share Google`() {
        val resolved = resolveJourneyProvider(start, listOf(destination), TravelMode.WALK) { point ->
            if (point == start) TerritoryRegion.JAPAN else TerritoryRegion.OTHER
        }

        assertEquals(MapProvider.GOOGLE, resolved.provider)
    }
}
