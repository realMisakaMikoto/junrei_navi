package cn.anitabi.navigator.ui.search

import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.core.model.PilgrimagePoint
import cn.anitabi.navigator.core.model.TerritoryRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchMapProviderTest {
    private val points = listOf(
        PilgrimagePoint("a", "A", GeoPoint(30.0, 120.0)),
        PilgrimagePoint("b", "B", GeoPoint(30.1, 120.1)),
    )

    @Test
    fun `China map content resolves explicitly to AMap`() {
        assertEquals(
            MapProvider.AMAP,
            resolveSearchMapProvider(points) { TerritoryRegion.MAINLAND_CHINA },
        )
    }

    @Test
    fun `other territory map content resolves explicitly to Google`() {
        assertEquals(
            MapProvider.GOOGLE,
            resolveSearchMapProvider(points) { TerritoryRegion.OTHER },
        )
    }

    @Test
    fun `mixed or unresolved map content never defaults to Google`() {
        assertNull(
            resolveSearchMapProvider(points) { point ->
                if (point == points.first().coordinate) TerritoryRegion.MAINLAND_CHINA else TerritoryRegion.OTHER
            },
        )
        assertNull(resolveSearchMapProvider(points) { null })
    }

    @Test
    fun `AMap search map requires region data plus privacy and key readiness`() {
        assertEquals(
            MapProvider.AMAP,
            availableSearchMapProvider(
                provider = MapProvider.AMAP,
                amapRegionDataReady = true,
                amapPrivacyAndKeyReady = true,
            ),
        )
        assertNull(availableSearchMapProvider(MapProvider.AMAP, false, true))
        assertNull(availableSearchMapProvider(MapProvider.AMAP, true, false))
        assertEquals(
            MapProvider.GOOGLE,
            availableSearchMapProvider(MapProvider.GOOGLE, false, false),
        )
    }
}
