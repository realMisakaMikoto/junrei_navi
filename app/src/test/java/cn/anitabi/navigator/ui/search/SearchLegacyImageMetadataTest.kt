package cn.anitabi.navigator.ui.search

import cn.anitabi.navigator.core.model.Anime
import cn.anitabi.navigator.core.model.EndPolicy
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.NavigationState
import cn.anitabi.navigator.core.model.PilgrimagePoint
import cn.anitabi.navigator.core.model.RouteObjective
import cn.anitabi.navigator.core.model.StoredTourV2
import cn.anitabi.navigator.core.model.TravelMode
import cn.anitabi.navigator.data.repository.PilgrimageData
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchLegacyImageMetadataTest {
    private val anime = Anime(101, "TEST_ONLY_IMAGE_METADATA")
    private val image = "https://image.anitabi.cn/points/101/test-only.jpg?v=2"
    private val selected = PilgrimagePoint("point", "TEST_ONLY_SELECTED", GeoPoint(1.0, 2.0))
    private val refreshed = selected.copy(name = "TEST_ONLY_REFRESHED", coordinate = GeoPoint(8.0, 9.0), imageUrl = image)

    @Test
    fun `loaded subject fills null image without replacing selected point snapshot`() {
        val merged = mergeLoadedSubject(data(selected), data(refreshed))

        assertEquals(selected.copy(imageUrl = image), merged.points.single())
    }

    @Test
    fun `already selected discovery point fills null image without changing selection or coordinates`() {
        val current = SearchUiState(
            selectedAnimeData = mapOf(anime.subjectId to data(selected)),
            selectedPointIds = setOf("101::point"),
        )

        val updated = current.withDiscoveryPoints(anime, listOf(refreshed))

        assertEquals(current.selectedPointIds, updated.selectedPointIds)
        assertEquals(selected.copy(imageUrl = image), updated.selectedAnimeData.getValue(101).points.single())
    }

    @Test
    fun `saved selection restores missing image from matching cache while retaining saved snapshot`() {
        val stored = stored(selected.copy(id = "101::point"))

        val restored = requireNotNull(restoreSearchSelection(stored, listOf(data(refreshed))))

        assertEquals(setOf("101::point"), restored.selectedPointIds)
        assertEquals(selected.copy(imageUrl = image), restored.animeData.getValue(101).points.single())
        assertEquals(stored, stored(selected.copy(id = "101::point")))
    }

    @Test
    fun `existing valid image survives loaded and discovery metadata changes`() {
        val existing = selected.copy(imageUrl = "https://image.anitabi.cn/user/test-only/original.jpg?v=1")
        val current = SearchUiState(
            selectedAnimeData = mapOf(101L to data(existing)),
            selectedPointIds = setOf("101::point"),
        )

        assertEquals(existing, mergeLoadedSubject(data(existing), data(refreshed)).points.single())
        assertEquals(existing, current.withDiscoveryPoints(anime, listOf(refreshed)).selectedAnimeData.getValue(101).points.single())
    }

    @Test
    fun `saved null image stays null when cache point identity does not match`() {
        val restored = requireNotNull(restoreSearchSelection(
            stored(selected.copy(id = "101::point")),
            listOf(data(refreshed.copy(id = "other"))),
        ))

        assertEquals(selected, restored.animeData.getValue(101).points.single { it.id == "point" })
    }

    @Test
    fun `supplemented legacy image path is normalized without changing other point metadata`() {
        val merged = mergeLoadedSubject(data(selected), data(refreshed.copy(
            imageUrl = "https://image.anitabi.cn/images/points/101/test-only.jpg?v=2",
        )))

        assertEquals(selected.copy(imageUrl = image), merged.points.single())
    }

    @Test
    fun `invalid supplemental image cannot populate an absent selected reference`() {
        for (invalid in listOf("http://image.anitabi.cn/points/test-only.jpg", "https://unapproved.invalid/test-only.jpg")) {
            val merged = mergeLoadedSubject(data(selected), data(refreshed.copy(imageUrl = invalid)))
            assertEquals(selected, merged.points.single())
        }
    }

    private fun data(point: PilgrimagePoint) = PilgrimageData(anime, listOf(point), 1)

    private fun stored(point: PilgrimagePoint) = StoredTourV2(
        id = "TEST_ONLY_TOUR",
        displayAnime = anime,
        selectedAnimes = listOf(anime),
        selectedPoints = listOf(point),
        manualOrderPointIds = listOf(point.id),
        start = GeoPoint(0.0, 0.0),
        mode = TravelMode.WALK,
        objective = RouteObjective.FASTEST,
        endPolicy = EndPolicy.OPEN,
        navigationState = NavigationState.COMPLETED,
        completedPointIds = setOf(point.id),
    )
}
