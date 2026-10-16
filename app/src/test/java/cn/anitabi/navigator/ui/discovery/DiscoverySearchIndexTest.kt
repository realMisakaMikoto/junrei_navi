package cn.anitabi.navigator.ui.discovery

import cn.anitabi.navigator.core.model.Anime
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.data.discovery.DiscoveryPoint
import cn.anitabi.navigator.data.discovery.DiscoverySnapshot
import cn.anitabi.navigator.data.discovery.DiscoverySubject
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverySearchIndexTest {
    @Test
    fun matchesLocalizedOriginalAndEnglishSubjectNamesWithoutChangingCategories() {
        val index = DiscoverySearchIndex(snapshot())

        listOf(" LOCAL ALPHA ", "original alpha", "ENGLISH ALPHA").forEach { query ->
            val result = index.search(query)
            assertEquals(listOf(101L), result.subjects.map { it.id })
            assertTrue(result.points.isEmpty())
            assertTrue(result.cities.isEmpty())
            assertTrue(result.complete)
        }
    }

    @Test
    fun pointNamesKeepCompositeIdentityWhenRawIdsAreSharedAcrossSubjects() {
        val index = DiscoverySearchIndex(snapshot())

        val result = index.search("synthetic point")
        assertEquals(setOf("101::shared", "102::shared"), result.points.map { it.id }.toSet())
        assertTrue(result.subjects.isEmpty())
        assertTrue(result.cities.isEmpty())
        assertEquals("101::shared", index.search("localized point").points.single().id)
    }

    @Test
    fun cityResultsCollectAllAssociatedSubjectPointsWithoutInventingBoundaries() {
        val result = DiscoverySearchIndex(snapshot()).search(" TEST CITY ")

        assertEquals("Test City", result.cities.single().name)
        assertEquals(setOf("101::shared", "102::shared"), result.cities.single().pointIds)
        assertTrue(result.subjects.isEmpty())
        assertTrue(result.points.isEmpty())
    }

    @Test
    fun unloadedPointDetailsAreExcludedAndAnEmptyResultDoesNotClaimCompleteness() {
        val data = snapshot().let { it.copy(
            points = it.points.map { point -> point.copy(detailsVersion = null) },
            loadedPages = emptySet(),
            endVersionVerified = false,
        ) }
        val index = DiscoverySearchIndex(data)

        val result = index.search("synthetic point")
        assertTrue(result.isEmpty)
        assertFalse(result.complete)
        assertEquals(1, index.search("local alpha").subjects.size)
        assertEquals(2, index.search("test city").cities.single().pointIds.size)
    }

    @Test
    fun staleDetailsRemainSearchableButCannotAnnounceACompleteCurrentSearch() {
        val data = snapshot().let { it.copy(
            points = it.points.map { point -> point.copy(detailsVersion = "previous") },
        ) }

        val result = DiscoverySearchIndex(data).search("synthetic point")
        assertEquals(2, result.points.size)
        assertFalse(result.complete)
    }

    @Test
    fun missingPageOrUnverifiedEndVersionKeepsSearchIncomplete() {
        val data = snapshot()
        listOf(data.copy(loadedPages = emptySet()), data.copy(endVersionVerified = false)).forEach {
            val result = DiscoverySearchIndex(it).search("no synthetic match")
            assertTrue(result.isEmpty)
            assertFalse(result.complete)
        }
        assertTrue(DiscoverySearchIndex(data).search("no synthetic match").complete)
    }

    @Test
    fun completenessCanUpdateWithoutRebuildingUnchangedSearchEntries() {
        val index = DiscoverySearchIndex(snapshot().copy(endVersionVerified = false))

        assertFalse(index.search("alpha").complete)
        val current = index.search("alpha", complete = true)
        assertTrue(current.complete)
        assertEquals(listOf(101L), current.subjects.map { it.id })
    }

    @Test
    fun blankQueryDoesNotDumpEveryCategoryAndRetainsCompleteness() {
        val index = DiscoverySearchIndex(snapshot())

        val result = index.search(" \n\t ")
        assertTrue(result.isEmpty)
        assertTrue(result.complete)
        assertFalse(index.search("", complete = false).complete)
    }

    @Test(expected = CancellationException::class)
    fun cancelledSearchStopsBeforePublishingPartialResults() {
        DiscoverySearchIndex(snapshot()).search("synthetic") { throw CancellationException() }
    }

    @Test(expected = CancellationException::class)
    fun cancelledIndexBuildStopsBeforePublishingSearchEntries() {
        DiscoverySearchIndex(snapshot()) { throw CancellationException() }
    }

    private fun snapshot(): DiscoverySnapshot {
        // Entirely synthetic names, coordinates, IDs, and city membership.
        val points = listOf(
            DiscoveryPoint(101, "shared", GeoPoint(0.0, 0.0), name = "Synthetic Point A",
                nameCn = "Localized Point A", detailsVersion = "fixture"),
            DiscoveryPoint(102, "shared", GeoPoint(0.0, 1.0), name = "Synthetic Point B",
                detailsVersion = "fixture"),
        )
        return DiscoverySnapshot(
            version = "fixture", modified = 1, pageSize = 2,
            subjects = listOf(
                DiscoverySubject(Anime(101, "Original Alpha", "Local Alpha"),
                    city = "Test City", pointIds = listOf("101::shared"), englishName = "English Alpha"),
                DiscoverySubject(Anime(102, "Original Beta"), city = "Test City",
                    pointIds = listOf("102::shared", "102::shared")),
            ),
            points = points, loadedPages = setOf(0), endVersionVerified = true,
        )
    }

    @Test
    fun bangumiWorkSelectionUsesExactIndexCoordinatesAndRawIds() {
        val data = snapshot()
        val selected = requireNotNull(data.subjectSelection(101))
        assertEquals("shared", selected.points.single().id)
        assertEquals(data.points.first().coordinate, selected.points.single().coordinate)
        assertEquals(101L, selected.anime.subjectId)
        assertEquals(1, selected.expectedPointCount)
        assertTrue(data.subjectSelection(999) == null)
    }
}
