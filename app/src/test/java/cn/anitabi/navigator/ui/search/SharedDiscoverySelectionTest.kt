package cn.anitabi.navigator.ui.search

import cn.anitabi.navigator.core.model.Anime
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.PilgrimagePoint
import cn.anitabi.navigator.core.model.StoredTourV2
import cn.anitabi.navigator.core.model.TravelMode
import cn.anitabi.navigator.core.model.RouteObjective
import cn.anitabi.navigator.core.model.EndPolicy
import cn.anitabi.navigator.data.repository.PilgrimageData
import org.junit.Assert.*
import org.junit.Test

class SharedDiscoverySelectionTest {
    @Test fun lateWorkApiResponseCannotReplacePointsSelectedDuringLoading() {
        val anime = Anime(1, "Synthetic A")
        val selected = PilgrimagePoint("p", "Synthetic point", GeoPoint(10.123456789, 20.987654321))
        val existing = PilgrimageData(anime, listOf(selected), 1)
        val fetched = PilgrimageData(anime, listOf(selected.copy(coordinate = GeoPoint(12.0, 22.0))), 1)
        assertEquals(selected, mergeLoadedSubject(existing, fetched).points.single())
        assertEquals(selected, mergeLoadedSubject(existing, fetched.copy(points = emptyList())).points.single())
    }
    @Test fun savedDiscoverySelectionSurvivesMissingOrChangedPublicCache() {
        val anime = Anime(1, "Synthetic A")
        val original = PilgrimagePoint("1::p", "Synthetic point", GeoPoint(10.123456789, 20.987654321))
        val stored = StoredTourV2(
            id = "synthetic", displayAnime = anime, selectedAnimes = listOf(anime),
            selectedPoints = listOf(original), manualOrderPointIds = listOf(original.id), start = original.coordinate,
            mode = TravelMode.WALK, objective = RouteObjective.FASTEST, endPolicy = EndPolicy.OPEN,
        )
        val withoutCache = requireNotNull(restoreSearchSelection(stored, emptyList()))
        assertEquals(setOf(original.id), withoutCache.selectedPointIds)
        assertEquals(original.coordinate, withoutCache.animeData.getValue(1).points.single().coordinate)
        val updated = PilgrimageData(anime, listOf(original.copy(id = "p", coordinate = GeoPoint(11.0, 21.0))), 1)
        val restored = requireNotNull(restoreSearchSelection(stored, listOf(updated)))
        assertEquals(original.coordinate, restored.animeData.getValue(1).points.single().coordinate)
    }
    @Test fun repeatedRawIdsAcrossSubjectsRemainIndependent() {
        val point = PilgrimagePoint("p", "Synthetic point", GeoPoint(10.0, 20.0))
        val state = SearchUiState().withDiscoveryPoints(Anime(1, "Synthetic A"), listOf(point))
            .withDiscoveryPoints(Anime(2, "Synthetic B"), listOf(point))
        assertEquals(setOf("1::p", "2::p"), state.selectedPointIds)
        assertEquals(2, state.combinedPilgrimageData!!.points.size)
    }

    @Test fun detailOrIndexRefreshCannotMoveAnAlreadySelectedPoint() {
        val anime = Anime(1, "Synthetic A")
        val point = PilgrimagePoint("p", "Synthetic point", GeoPoint(10.123456789, 20.987654321))
        val state = SearchUiState().withDiscoveryPoints(anime, listOf(point))
            .withDiscoveryPoints(anime, listOf(point.copy(coordinate = GeoPoint(11.0, 21.0))))
        assertEquals(point.coordinate, state.combinedPilgrimageData!!.points.single().coordinate)
    }
}
