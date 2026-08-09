package cn.anitabi.navigator.ui.map

import cn.anitabi.navigator.core.model.MapProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleLiveMapCoordinatorTest {
    @Test
    fun `provider switch destroys old lease before constructing new view`() {
        val events = mutableListOf<String>()
        val coordinator = SingleLiveMapCoordinator()
        val google = coordinator.acquire(MapProvider.GOOGLE) {
            events += "create-google"
            Any()
        }
        google.installDestroyAction { events += "destroy-google" }

        val amap = coordinator.acquire(MapProvider.AMAP) {
            events += "create-amap"
            Any()
        }
        amap.installDestroyAction { events += "destroy-amap" }

        assertEquals(
            listOf("create-google", "destroy-google", "create-amap"),
            events,
        )
        google.close()
        assertEquals(3, events.size)
        amap.close()
        assertEquals("destroy-amap", events.last())
    }

    @Test
    fun `privacy revocation destroys only an active AMap lease`() {
        val coordinator = SingleLiveMapCoordinator()
        var googleDestroyed = 0
        val google = coordinator.acquire(MapProvider.GOOGLE) { Any() }
        google.installDestroyAction { googleDestroyed += 1 }

        assertFalse(coordinator.destroyIfProvider(MapProvider.AMAP))
        assertEquals(0, googleDestroyed)

        var amapDestroyed = 0
        val amap = coordinator.acquire(MapProvider.AMAP) { Any() }
        amap.installDestroyAction { amapDestroyed += 1 }
        assertEquals(1, googleDestroyed)
        assertTrue(coordinator.destroyIfProvider(MapProvider.AMAP))
        assertEquals(1, amapDestroyed)
        assertFalse(coordinator.destroyIfProvider(MapProvider.AMAP))
    }

    @Test
    fun `destroy before lifecycle attachment invokes installed cleanup exactly once`() {
        val coordinator = SingleLiveMapCoordinator()
        val lease = coordinator.acquire(MapProvider.AMAP) { Any() }
        assertFalse(lease.isDestroyed)
        assertTrue(coordinator.destroyIfProvider(MapProvider.AMAP))
        assertTrue(lease.isDestroyed)
        var cleanups = 0

        lease.installDestroyAction { cleanups += 1 }
        lease.close()

        assertEquals(1, cleanups)
    }
}
