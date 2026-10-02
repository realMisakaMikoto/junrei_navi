package cn.anitabi.navigator.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmapMapReadinessTest {
    @Test
    fun `map load before listener is retained and delivered`() {
        val readiness = AmapMapReadiness()
        var deliveries = 0

        readiness.onMapLoaded()
        assertTrue(readiness.loaded)
        readiness.listen { deliveries += 1 }

        assertEquals(1, deliveries)
    }

    @Test
    fun `listener waits for map load before delivering`() {
        val readiness = AmapMapReadiness()
        var deliveries = 0

        readiness.listen { deliveries += 1 }
        assertFalse(readiness.loaded)
        assertEquals(0, deliveries)
        readiness.onMapLoaded()

        assertTrue(readiness.loaded)
        assertEquals(1, deliveries)
    }

    @Test
    fun `closing before map load prevents late delivery`() {
        val readiness = AmapMapReadiness()
        var deliveries = 0
        readiness.listen { deliveries += 1 }

        readiness.close()
        readiness.onMapLoaded()

        assertFalse(readiness.loaded)
        assertEquals(0, deliveries)
    }

    @Test
    fun `closing a loaded map prevents replay to a late listener`() {
        val readiness = AmapMapReadiness()
        var deliveries = 0
        readiness.onMapLoaded()

        readiness.close()
        readiness.listen { deliveries += 1 }
        readiness.onMapLoaded()

        assertEquals(0, deliveries)
    }
}
