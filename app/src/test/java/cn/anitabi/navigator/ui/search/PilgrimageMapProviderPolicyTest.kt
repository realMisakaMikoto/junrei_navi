package cn.anitabi.navigator.ui.search

import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.PilgrimagePoint
import org.junit.Assert.assertFalse
import org.junit.Test

class PilgrimageMapProviderPolicyTest {
    @Test
    fun `empty AMap viewport sentinel clears visible WGS84 selection`() {
        listOf(
            PilgrimagePoint("mainland", "Mainland", GeoPoint(30.0, 120.0)),
            PilgrimagePoint("edge", "Edge", GeoPoint(-90.0, -180.0)),
        ).forEach { point ->
            assertFalse(EMPTY_VISIBLE_MAP_BOUNDS.contains(point))
        }
    }
}
