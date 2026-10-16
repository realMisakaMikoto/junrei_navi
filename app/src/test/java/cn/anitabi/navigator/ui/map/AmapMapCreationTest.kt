package cn.anitabi.navigator.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AmapMapCreationTest {
    @Test
    fun `missing privacy readiness prevents library lookup and view creation`() {
        val events = mutableListOf<String>()

        assertThrows(IllegalStateException::class.java) {
            createAmapMapIfReady(
                privacyReady = false,
                nativeLibraryAvailable = {
                    events += "lookup"
                    true
                },
                create = {
                    events += "create"
                    Any()
                },
            )
        }

        assertTrue(events.isEmpty())
    }

    @Test
    fun `unavailable native library prevents view creation`() {
        val events = mutableListOf<String>()

        assertThrows(IllegalStateException::class.java) {
            createAmapMapIfReady(
                privacyReady = true,
                nativeLibraryAvailable = {
                    events += "lookup"
                    false
                },
                create = {
                    events += "create"
                    Any()
                },
            )
        }

        assertEquals(listOf("lookup"), events)
    }

    @Test
    fun `ready gate checks library before creating and returns created view`() {
        val events = mutableListOf<String>()
        val view = Any()

        val result = createAmapMapIfReady(
            privacyReady = true,
            nativeLibraryAvailable = {
                events += "lookup"
                true
            },
            create = {
                events += "create"
                view
            },
        )

        assertEquals(listOf("lookup", "create"), events)
        assertSame(view, result)
    }

    @Test
    fun `view creation failure reaches the unavailable handler`() {
        val failure = IllegalStateException("Synthetic construction failure")

        val actual = assertThrows(IllegalStateException::class.java) {
            createAmapMapIfReady(
                privacyReady = true,
                nativeLibraryAvailable = { true },
                create = { throw failure },
            )
        }

        assertSame(failure, actual)
    }
}
