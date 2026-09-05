package cn.anitabi.navigator.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.anitabi.navigator.core.model.Anime
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.core.model.PilgrimagePoint
import cn.anitabi.navigator.core.model.StoredTourV2
import cn.anitabi.navigator.data.network.ApiException
import cn.anitabi.navigator.data.network.bangumi.BangumiApi
import cn.anitabi.navigator.data.repository.PilgrimageData
import cn.anitabi.navigator.data.repository.PilgrimageRepository
import cn.anitabi.navigator.data.repository.TourRepository
import cn.anitabi.navigator.data.repository.mergePilgrimageData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SearchViewModel(
    private val bangumiApi: BangumiApi,
    private val pilgrimageRepository: PilgrimageRepository,
    private val tourRepository: TourRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val stored = runCatching { tourRepository.getMostRecent()?.storedTour }.getOrNull()
                ?: return@launch
            val cached = stored.selectedAnimes.mapNotNull { anime ->
                runCatching { pilgrimageRepository.loadCached(anime.subjectId) }.getOrNull()
            }
            val restored = restoreSearchSelection(stored, cached) ?: return@launch
            mutableState.update { current ->
                if (current.selectedAnimeData.isNotEmpty() || current.selectedPointIds.isNotEmpty()) {
                    current
                } else {
                    current.copy(
                        selectedAnimeData = restored.animeData,
                        selectedPointIds = restored.selectedPointIds,
                    )
                }
            }
        }
    }

    fun updateQuery(query: String) {
        mutableState.update { it.copy(query = query, errorMessage = null) }
    }

    fun search() {
        val keyword = state.value.query.trim()
        if (keyword.isEmpty()) {
            mutableState.update { it.copy(errorMessage = "请输入动漫名称") }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching { bangumiApi.searchAnime(keyword) }
                .onSuccess { results ->
                    mutableState.update {
                        it.copy(searchResults = results, isLoading = false)
                    }
                }
                .onFailure(::handleFailure)
        }
    }

    fun toggleAnime(anime: Anime) {
        val current = state.value
        if (anime.subjectId in current.selectedAnimeData) {
            mutableState.update { value ->
                val remainingData = value.selectedAnimeData - anime.subjectId
                val remainingPointIds = mergePilgrimageData(remainingData.values)
                    ?.points
                    .orEmpty()
                    .mapTo(mutableSetOf(), PilgrimagePoint::id)
                value.copy(
                    selectedAnimeData = remainingData,
                    selectedPointIds = value.selectedPointIds.intersect(remainingPointIds),
                    errorMessage = null,
                )
            }
            return
        }
        if (anime.subjectId in current.loadingAnimeIds) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(loadingAnimeIds = it.loadingAnimeIds + anime.subjectId, errorMessage = null)
            }
            runCatching { pilgrimageRepository.load(anime.subjectId) }
                .onSuccess { data ->
                    mutableState.update {
                        it.copy(
                            selectedAnimeData = it.selectedAnimeData + (anime.subjectId to data),
                            loadingAnimeIds = it.loadingAnimeIds - anime.subjectId,
                        )
                    }
                }
                .onFailure { throwable ->
                    mutableState.update { it.copy(loadingAnimeIds = it.loadingAnimeIds - anime.subjectId) }
                    handleFailure(throwable)
                }
        }
    }

    fun openSelection() {
        mutableState.update { current ->
            if (current.selectedAnimeData.isEmpty()) {
                current.copy(errorMessage = "请至少选择一部动画")
            } else {
                current.copy(selectionOpen = true, showList = false, errorMessage = null)
            }
        }
    }

    fun backToResults() {
        mutableState.update { it.copy(selectionOpen = false, errorMessage = null) }
    }

    fun togglePoint(pointId: String) {
        mutableState.update { current ->
            val selected = current.selectedPointIds
            when {
                pointId in selected -> current.copy(selectedPointIds = selected - pointId, errorMessage = null)
                else -> current.copy(selectedPointIds = selected + pointId, errorMessage = null)
            }
        }
    }

    fun updateVisibleBounds(bounds: GeoBounds) {
        mutableState.update { it.copy(visibleBounds = bounds) }
    }

    fun selectVisiblePoints(displayedPointIds: Set<String>) {
        mutableState.update { current ->
            val visible = current.combinedPilgrimageData?.points.orEmpty()
                .filter { point ->
                    point.id in displayedPointIds && current.visibleBounds?.contains(point) == true
                }
                .map(PilgrimagePoint::id)
            current.copy(
                selectedPointIds = current.selectedPointIds + visible,
                errorMessage = null,
            )
        }
    }

    fun clearSelection() {
        mutableState.update { it.copy(selectedPointIds = emptySet(), errorMessage = null) }
    }

    fun setShowList(showList: Boolean) {
        mutableState.update { it.copy(showList = showList, errorMessage = null) }
    }

    fun selectMapProvider(provider: MapProvider) {
        mutableState.update {
            it.copy(
                selectedMapProvider = provider,
                showList = false,
                visibleBounds = null,
                errorMessage = null,
            )
        }
    }

    fun handleMapUnavailable(provider: MapProvider) {
        mutableState.update {
            it.copy(
                showList = true,
                visibleBounds = null,
                errorMessage = "${provider.searchMapLabel()}暂时无法加载，已切换为列表选点",
            )
        }
    }

    fun openPlanner() {
        mutableState.update { current ->
            if (current.selectedPointIds.size < 2) {
                current.copy(errorMessage = "至少选择 2 个巡礼点才能规划路线")
            } else {
                current.copy(plannerOpen = true, errorMessage = null)
            }
        }
    }

    fun closePlanner() {
        mutableState.update { it.copy(plannerOpen = false, errorMessage = null) }
    }

    fun openNavigation() {
        mutableState.update { it.copy(navigationOpen = true, hiddenNavigationTourId = null) }
    }

    fun closeNavigation(tourId: String?) {
        mutableState.update {
            it.copy(navigationOpen = false, hiddenNavigationTourId = tourId)
        }
    }

    fun openAbout() {
        mutableState.update { it.copy(aboutOpen = true) }
    }

    fun closeAbout() {
        mutableState.update { it.copy(aboutOpen = false) }
    }

    private fun handleFailure(throwable: Throwable) {
        if (throwable is CancellationException) throw throwable
        val message = when (throwable) {
            is ApiException.NotFound -> "Anitabi 暂无这部作品的巡礼数据"
            is ApiException.RateLimited -> "请求过于频繁，请稍后再试"
            is ApiException.Forbidden -> "当前公网 IP 被公共服务拒绝，请更换网络后重试"
            is ApiException.Network -> "网络不可用，可查看已缓存作品"
            is ApiException.Server -> "公共服务暂时不可用，请稍后再试"
            is ApiException.InvalidResponse -> "公共服务返回了无法识别的数据"
            is ApiException.Http -> "公共服务请求失败，请稍后再试"
            else -> throwable.message ?: "加载失败，请稍后再试"
        }
        mutableState.update { it.copy(isLoading = false, errorMessage = message) }
    }

    class Factory(
        private val bangumiApi: BangumiApi,
        private val pilgrimageRepository: PilgrimageRepository,
        private val tourRepository: TourRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SearchViewModel(bangumiApi, pilgrimageRepository, tourRepository) as T
        }
    }
}

internal data class RestoredSearchSelection(
    val animeData: Map<Long, PilgrimageData>,
    val selectedPointIds: Set<String>,
)

internal fun restoreSearchSelection(
    stored: StoredTourV2,
    cached: List<PilgrimageData>,
): RestoredSearchSelection? {
    val selectedAnimeIds = stored.selectedAnimes.mapTo(linkedSetOf(), Anime::subjectId)
    if (selectedAnimeIds.isEmpty()) return null
    val animeData = cached
        .filter { it.anime.subjectId in selectedAnimeIds }
        .associateBy { it.anime.subjectId }
    if (animeData.keys != selectedAnimeIds) return null
    val availablePointIds = mergePilgrimageData(animeData.values)
        ?.points
        .orEmpty()
        .mapTo(mutableSetOf(), PilgrimagePoint::id)
    val storedPointIds = stored.selectedPoints.mapTo(mutableSetOf(), PilgrimagePoint::id)
    return RestoredSearchSelection(
        animeData = animeData,
        selectedPointIds = storedPointIds.intersect(availablePointIds),
    )
}

data class SearchUiState(
    val query: String = "",
    val searchResults: List<Anime> = emptyList(),
    val selectedAnimeData: Map<Long, PilgrimageData> = emptyMap(),
    val loadingAnimeIds: Set<Long> = emptySet(),
    val selectedPointIds: Set<String> = emptySet(),
    val visibleBounds: GeoBounds? = null,
    val isLoading: Boolean = false,
    val showList: Boolean = false,
    val selectedMapProvider: MapProvider? = null,
    val selectionOpen: Boolean = false,
    val plannerOpen: Boolean = false,
    val navigationOpen: Boolean = false,
    val hiddenNavigationTourId: String? = null,
    val aboutOpen: Boolean = false,
    val errorMessage: String? = null,
) {
    val selectedAnimes: List<Anime>
        get() = selectedAnimeData.values.map(PilgrimageData::anime)

    val combinedPilgrimageData: PilgrimageData?
        get() = mergePilgrimageData(selectedAnimeData.values)

    val mapContentKey: String
        get() = selectedAnimeData.keys.sorted().joinToString(separator = ",")
}
