package cn.anitabi.navigator.data.discovery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.*
import org.junit.Test

class DiscoveryParserTest {
    @Test fun indexPreservesPrecisionAndScopesRepeatedPointIds() {
        val index = DiscoveryParser.index(indexFixture())
        assertEquals(2, index.pageCount)
        assertEquals(3, index.points.map { it.id }.distinct().size)
        assertEquals("1::shared", index.points.first().id)
        assertEquals(10.123456789, index.points.first().coordinate.latitude, 0.0)
        assertEquals(20.987654321, index.points.first().coordinate.longitude, 0.0)
        assertEquals("https://image.anitabi.cn/covers/test.jpg", index.subjects.first().anime.imageUrl)
    }

    @Test fun currentWebSlotsWinOverTheIncompatibleSwiftSlots() {
        val index = DiscoveryParser.index(indexFixture())
        val merged = DiscoveryParser.merge(index, DiscoveryParser.page(pageFixture(listOf(1, 2))))
        val point = merged.points.first()
        assertEquals("Local name", point.nameCn)
        assertEquals("Group name", point.groupName)
        assertEquals("group-id", point.groupId)
        assertEquals("2.5", point.episode)
        assertEquals(72.0, point.timecodeSeconds!!, 0.0)
        assertEquals("Note", point.description)
        assertEquals(index.points.first().coordinate, point.coordinate)
        assertFalse(merged.detailsComplete)
        assertFalse(merged.detailsCurrent)
    }

    @Test fun sparseApiDetailsNeverReplaceIndexCoordinatesOrCreateNewPoints() {
        val index = DiscoveryParser.index(indexFixture())
        val api = Json.parseToJsonElement("""[{"id":"shared","name":"API name","geo":[55,66],"ep":0},{"id":"other","geo":[55,66]}]""")
        val merged = DiscoveryParser.merge(index, listOf(DiscoveryParser.apiDetails(1, api)))
        assertEquals(index.points.size, merged.points.size)
        assertEquals(index.points.first().coordinate, merged.points.first().coordinate)
        assertEquals("0", merged.points.first().episode)
    }

    @Test fun foldersAreNotNavigationPoints() {
        val index = DiscoveryParser.index(indexFixture())
        val api = Json.parseToJsonElement("""[{"id":"shared","isFolder":true}]""")
        val merged = DiscoveryParser.merge(index, listOf(DiscoveryParser.apiDetails(1, api)))
        assertEquals(2, merged.points.size)
        assertTrue(merged.subjects.first().pointIds.isEmpty())
    }

    @Test fun folderDirectoryNamesAreResolvedWithoutAddingDirectoryMarkers() {
        val index = DiscoveryParser.index(indexFixture())
        val api = Json.parseToJsonElement("""[{"id":"shared","fid":"folder"},{"id":"folder","name":"Synthetic group","isFolder":true}]""")
        val merged = DiscoveryParser.merge(index, listOf(DiscoveryParser.apiDetails(1, api)))
        assertEquals("Synthetic group", merged.points.first().groupName)
        assertEquals(index.points.size, merged.points.size)
    }

    @Test fun invalidStructureAndConflictingDuplicateIdsFailClosed() {
        val root = indexFixture() as JsonArray
        val rows = root[0] as JsonArray
        val duplicateSubject = JsonArray(listOf(JsonArray(rows + rows.first()), root[1], root[2]))
        assertThrows(DiscoveryFormatException::class.java) { DiscoveryParser.index(duplicateSubject) }
        for (json in listOf("{}", "[[],0,1]", indexFixture().toString().replace("10.123456789", "999"),
            indexFixture().toString().replace("20.987654321,0", "20.987654321"))) {
            assertThrows(DiscoveryFormatException::class.java) { DiscoveryParser.index(Json.parseToJsonElement(json)) }
        }
    }

    @Test fun disallowedImagesAndMalformedOptionalValuesDoNotHidePoints() {
        val index = DiscoveryParser.index(indexFixture())
        val api = Json.parseToJsonElement("""[{"id":"shared","image":"https://external.invalid/image.jpg","name":{},"ep":[],"s":-1}]""")
        val merged = DiscoveryParser.merge(index, listOf(DiscoveryParser.apiDetails(1, api)))
        assertEquals(index.points.size, merged.points.size)
        assertNull(merged.points.first().imageUrl)
        assertNull(merged.points.first().name)
        assertNull(merged.points.first().timecodeSeconds)
        assertNull(DiscoveryParser.allowedImage("//evil.invalid/image.jpg"))
        assertNull(DiscoveryParser.allowedImage("https://user:password@image.anitabi.cn/image.jpg"))
    }

    @Test fun nextGenerationCarriesOnlyExplicitlyStaleDetails() {
        val old = DiscoveryParser.index(indexFixture())
        val populated = DiscoveryParser.merge(old, DiscoveryParser.page(pageFixture(listOf(1, 2, 3))))
        val next = DiscoveryParser.carryDetails(DiscoveryParser.index(indexFixture(modified = 200)), populated)
        assertTrue(next.detailsComplete)
        assertFalse(next.detailsCurrent)
        assertTrue(next.loadedPages.isEmpty())
        assertNotEquals(next.version, next.points.first().detailsVersion)
    }

    @Test fun refreshedDetailsClearFieldsThatTheNewGenerationRemoved() {
        val index = DiscoveryParser.index(indexFixture())
        val old = DiscoveryParser.merge(index, DiscoveryParser.page(pageFixture(listOf(1, 2, 3))))
        val empty = Json.parseToJsonElement("""[{"id":"shared"}]""")
        val refreshed = DiscoveryParser.merge(old, listOf(DiscoveryParser.apiDetails(1, empty)))
        assertNull(refreshed.points.first().imageUrl)
        assertNull(refreshed.points.first().description)
    }
}
