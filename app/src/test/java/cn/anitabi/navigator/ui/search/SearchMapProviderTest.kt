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

    @Test
    fun `mixed results allow explicit maps without mixing their markers`() {
        val classifier: (GeoPoint) -> TerritoryRegion? = { coordinate ->
            if (coordinate == points[0].coordinate) TerritoryRegion.MAINLAND_CHINA else TerritoryRegion.JAPAN
        }
        val initial = resolveSearchMapContent(points, classifier, null)
        assertNull(initial.provider)
        assertEquals(emptyList<PilgrimagePoint>(), initial.points)
        assertEquals(setOf(MapProvider.GOOGLE, MapProvider.AMAP), initial.providerChoices)

        val amap = resolveSearchMapContent(points, classifier, MapProvider.AMAP)
        val google = resolveSearchMapContent(points, classifier, MapProvider.GOOGLE)
        assertEquals(listOf(points[0]), amap.points)
        assertEquals(listOf(points[1]), google.points)
        assertEquals(MapProvider.AMAP, amap.provider)
        assertEquals(MapProvider.GOOGLE, google.provider)
        assertEquals(2, points.size)
    }

    @Test
    fun `unresolved results stay off maps while resolved group can be chosen`() {
        val classifier: (GeoPoint) -> TerritoryRegion? = { coordinate ->
            if (coordinate == points[0].coordinate) TerritoryRegion.OTHER else null
        }
        assertNull(resolveSearchMapContent(points, classifier, null).provider)
        val selected = resolveSearchMapContent(points, classifier, MapProvider.GOOGLE)
        assertEquals(listOf(points[0]), selected.points)
        assertEquals(setOf(MapProvider.GOOGLE), selected.providerChoices)
        val unavailableSelection = resolveSearchMapContent(points, classifier, MapProvider.AMAP)
        assertNull(unavailableSelection.provider)
        assertEquals(emptyList<PilgrimagePoint>(), unavailableSelection.points)
    }

    @Test
    fun `stale provider choice cannot show new points on the wrong map`() {
        val updated = resolveSearchMapContent(points, { TerritoryRegion.MAINLAND_CHINA }, MapProvider.GOOGLE)
        assertEquals(MapProvider.AMAP, updated.provider)
        assertEquals(points, updated.points)
        assertEquals(emptySet<MapProvider>(), updated.providerChoices)
        assertNull(resolveSearchMapContent(emptyList(), { TerritoryRegion.JAPAN }, MapProvider.GOOGLE).provider)
    }
}
