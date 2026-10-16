package cn.anitabi.navigator.data.discovery

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiscoveryCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun atomicVersionSwitchKeepsOnlyCurrentAndPreviousGeneration() {
        val cache = FileDiscoveryCache(temporary.root)
        val first = DiscoveryParser.index(indexFixture())
        val second = DiscoveryParser.index(indexFixture(modified = 200))
        val third = DiscoveryParser.index(indexFixture(modified = 300))
        cache.write(first)
        cache.write(second)
        cache.write(third)
        assertEquals(third, cache.read())
        assertEquals(setOf("current.json", "previous.json"), File(temporary.root, "discovery").list()!!.toSet())
        File(temporary.root, "discovery/current.json").writeText("broken")
        assertEquals(second, cache.read())
    }

    @Test fun corruptedNewFileCannotDestroyLastUsableGeneration() {
        val cache = FileDiscoveryCache(temporary.root)
        val first = DiscoveryParser.index(indexFixture())
        cache.write(first)
        cache.write(DiscoveryParser.index(indexFixture(modified = 200)))
        File(temporary.root, "discovery/current.json").writeText("broken")
        val third = DiscoveryParser.index(indexFixture(modified = 300))
        cache.write(third)
        File(temporary.root, "discovery/current.json").writeText("broken again")
        assertEquals(first, cache.read())
    }

    @Test fun invalidSnapshotCannotReplaceValidCache() {
        val cache = FileDiscoveryCache(temporary.root)
        val original = DiscoveryParser.index(indexFixture())
        cache.write(original)
        assertThrows(IllegalArgumentException::class.java) { cache.write(original.copy(pageSize = 0)) }
        assertEquals(original, cache.read())
    }

    @Test fun previousGenerationFallbackCannotClaimFreshness() {
        val cache = FileDiscoveryCache(temporary.root)
        val index = DiscoveryParser.index(indexFixture())
        val complete = DiscoveryParser.merge(index, DiscoveryParser.page(pageFixture(listOf(1, 2, 3))))
            .copy(loadedPages = setOf(0, 1), endVersionVerified = true)
        cache.write(complete)
        cache.write(DiscoveryParser.index(indexFixture(modified = 200)))
        File(temporary.root, "discovery/current.json").writeText("broken")
        assertTrue(cache.read()!!.detailsComplete)
        assertFalse(cache.read()!!.detailsCurrent)
    }
}
