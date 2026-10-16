package cn.anitabi.navigator.data.images

import cn.anitabi.navigator.core.model.Anime
import cn.anitabi.navigator.core.model.EndPolicy
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.NavigationState
import cn.anitabi.navigator.core.model.PilgrimagePoint
import cn.anitabi.navigator.core.model.PlannerDraft
import cn.anitabi.navigator.core.model.RouteObjective
import cn.anitabi.navigator.core.model.StoredTourV2
import cn.anitabi.navigator.core.model.TravelMode
import cn.anitabi.navigator.data.discovery.DiscoveryPoint
import cn.anitabi.navigator.data.discovery.DiscoverySnapshot
import cn.anitabi.navigator.data.discovery.DiscoverySubject
import cn.anitabi.navigator.data.discovery.FileDiscoveryCache
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Synthetic legacy payloads use the real cache/schema and shared request-bound resolver. */
class LegacyImageMetadataTest {
    @get:Rule val temporary = TemporaryFolder()
    private val oldImage = "https://image.anitabi.cn/images/user/synthetic-owner/upload.png?v=legacy-content"
    private val oldCover = "https://image.anitabi.cn/images/bangumi/synthetic-work/cover.png?v=legacy-cover"
    private val coordinate = GeoPoint(1.125, 2.25)
    private val anime = Anime(17, "SYNTHETIC", imageUrl = oldCover)
    private val point = PilgrimagePoint("17::alpha", "SYNTHETIC_POINT", coordinate, imageUrl = oldImage)

    @Test fun currentCompleteCacheRepairsRequestsWithoutNetworkRefreshOrMetadataRewrite() {
        val cache = FileDiscoveryCache(temporary.root)
        val stored = snapshot(100)
        cache.write(stored)
        val file = File(temporary.root, "discovery/current.json")
        val bytesBefore = file.readBytes()
        val restored = requireNotNull(cache.read())
        assertTrue(restored.detailsCurrent)
        assertCanonical(restored.points.single().imageUrl, AnitabiImageVariant.THUMBNAIL)
        assertCanonical(restored.subjects.single().anime.imageUrl, AnitabiImageVariant.THUMBNAIL)
        assertEquals(stored.points, restored.points)
        assertEquals(coordinate, restored.points.single().coordinate)
        assertArrayEquals(bytesBefore, file.readBytes())
    }

    @Test fun previousCacheFallbackPreservesCoordinatesAndRepairsItsOwnLegacyReference() {
        val cache = FileDiscoveryCache(temporary.root)
        val old = snapshot(100)
        cache.write(old)
        cache.write(snapshot(200))
        File(temporary.root, "discovery/current.json").writeText("Synthetic corruption")
        val previous = File(temporary.root, "discovery/previous.json")
        val bytesBefore = previous.readBytes()
        val restored = requireNotNull(cache.read())
        assertEquals(old.version, restored.version)
        assertFalse(restored.endVersionVerified)
        assertEquals(old.points, restored.points)
        assertCanonical(restored.points.single().imageUrl, AnitabiImageVariant.DISPLAY)
        assertArrayEquals(bytesBefore, previous.readBytes())
    }

    @Test fun savedTourImageRequestsLeaveSelectionOrderCoordinatesAndProgressUntouched() {
        val original = StoredTourV2(id = "synthetic-tour", displayAnime = anime, selectedAnimes = listOf(anime),
            selectedPoints = listOf(point), manualOrderPointIds = listOf(point.id), start = coordinate,
            mode = TravelMode.WALK, objective = RouteObjective.FASTEST, endPolicy = EndPolicy.OPEN,
            completedPointIds = setOf(point.id), activePointId = point.id, navigationState = NavigationState.DWELLING,
            dwellingUntilEpochMillis = 123456L, isPaused = true, pausedAtEpochMillis = 123400L)
        val bytes = Json.encodeToString(StoredTourV2.serializer(), original)
        val restored = Json.decodeFromString(StoredTourV2.serializer(), bytes)
        assertCanonical(restored.selectedPoints.single().imageUrl, AnitabiImageVariant.DISPLAY)
        assertCanonical(restored.selectedAnimes.single().imageUrl, AnitabiImageVariant.THUMBNAIL)
        assertEquals(original, restored)
        assertEquals(bytes, Json.encodeToString(StoredTourV2.serializer(), restored))
    }

    @Test fun selectedDraftImageRequestsDoNotReplaceItsStoredWgs84Snapshot() {
        val original = PlannerDraft(draftId = "synthetic-draft", selectedAnimes = listOf(anime), displayAnime = anime,
            selectedPoints = listOf(point), transitDate = "2026-09-24", transitTime = "12:00", transitZoneId = "UTC")
        val bytes = Json.encodeToString(PlannerDraft.serializer(), original)
        val restored = Json.decodeFromString(PlannerDraft.serializer(), bytes)
        restored.validate()
        assertCanonical(restored.selectedPoints.single().imageUrl, AnitabiImageVariant.THUMBNAIL)
        assertCanonical(restored.displayAnime.imageUrl, AnitabiImageVariant.THUMBNAIL)
        assertEquals(original, restored)
        assertEquals(bytes, Json.encodeToString(PlannerDraft.serializer(), restored))
    }

    private fun snapshot(modified: Long): DiscoverySnapshot {
        val version = "$modified:synthetic"
        val cached = DiscoveryPoint(17, "alpha", coordinate, imageUrl = oldImage, detailsVersion = version)
        return DiscoverySnapshot(version, modified, 1,
            listOf(DiscoverySubject(anime, pointIds = listOf(cached.id))), listOf(cached),
            loadedPages = setOf(0), currentSubjectIds = setOf(17), endVersionVerified = true)
    }

    private fun assertCanonical(source: String?, variant: AnitabiImageVariant) {
        val request = requireNotNull(AnitabiImageReference.request(source, variant))
        assertFalse(request.contains("/images/"))
        assertTrue(request.contains("plan=${variant.plan}"))
        assertTrue(request.contains("v=legacy-"))
        assertEquals(request, AnitabiImageReference.request(request, variant))
    }
}
