package cn.anitabi.navigator.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.anitabi.navigator.R
import cn.anitabi.navigator.core.model.Anime
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.core.model.NavigationState
import cn.anitabi.navigator.core.model.PilgrimagePoint
import cn.anitabi.navigator.core.model.TerritoryRegion
import cn.anitabi.navigator.core.model.mapProvider
import cn.anitabi.navigator.data.repository.PilgrimageWarning
import cn.anitabi.navigator.ui.theme.Ink
import cn.anitabi.navigator.ui.theme.MutedInk
import cn.anitabi.navigator.ui.theme.Paper
import cn.anitabi.navigator.ui.theme.Sand
import cn.anitabi.navigator.ui.theme.Vermilion
import cn.anitabi.navigator.ui.planner.PlannerRoute
import cn.anitabi.navigator.ui.planner.PlannerViewModel
import cn.anitabi.navigator.navigation.NavigationViewModel
import cn.anitabi.navigator.ui.navigation.NavigationRoute
import cn.anitabi.navigator.ui.about.AboutScreen
import cn.anitabi.navigator.telemetry.TelemetryConsentController
import cn.anitabi.navigator.security.AppSettingsStore
import cn.anitabi.navigator.ui.map.AmapPrivacyGate
import coil3.compose.AsyncImage

@Composable
fun SearchRoute(
    viewModel: SearchViewModel,
    plannerViewModel: PlannerViewModel,
    navigationViewModel: NavigationViewModel,
    telemetryConsentController: TelemetryConsentController,
    appSettingsStore: AppSettingsStore,
    amapPrivacyGate: AmapPrivacyGate,
    classifyTerritory: (GeoPoint) -> TerritoryRegion?,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigationState by navigationViewModel.state.collectAsStateWithLifecycle()
    val navigationPlanId = navigationState.plan?.id
    val selectionMapProvider = resolveSearchMapProvider(
        points = state.combinedPilgrimageData?.points.orEmpty(),
        classifyTerritory = classifyTerritory,
    )
    val hasRecoverableNavigation = navigationPlanId != null &&
        navigationState.errorMessage != null &&
        navigationState.progress?.state?.let { progressState ->
            progressState !in setOf(NavigationState.COMPLETED, NavigationState.ENDED)
        } == true
    val showNavigation = state.navigationOpen ||
        ((navigationState.isRunning || hasRecoverableNavigation) &&
            navigationPlanId != state.hiddenNavigationTourId)
    val closePlanner = {
        plannerViewModel.cancelPlanning()
        viewModel.closePlanner()
    }
    BackHandler(
        enabled = !showNavigation && (state.aboutOpen || state.plannerOpen || state.selectionOpen),
        onBack = when {
            state.aboutOpen -> viewModel::closeAbout
            state.plannerOpen -> closePlanner
            else -> viewModel::backToResults
        },
    )

    if (showNavigation) {
        NavigationRoute(
            viewModel = navigationViewModel,
            availablePoints = state.combinedPilgrimageData?.points.orEmpty(),
            onBack = viewModel::closeNavigation,
        )
    } else if (state.aboutOpen) {
        AboutScreen(
            onBack = viewModel::closeAbout,
            telemetryConsentController = telemetryConsentController,
            amapPrivacyConsentEnabled = appSettingsStore.hasCurrentAmapPrivacyConsent(),
            onAmapPrivacyConsentChange = { enabled ->
                appSettingsStore.setAmapPrivacyConsent(enabled)
                if (enabled) {
                    amapPrivacyGate.prepareIfAllowed(true)
                } else {
                    amapPrivacyGate.revoke()
                }
            },
        )
    } else if (state.plannerOpen) {
        PlannerRoute(
            viewModel = plannerViewModel,
            onBack = closePlanner,
            onStartNavigation = { plan ->
                navigationViewModel.start(plan)
                viewModel.openNavigation()
            },
        )
    } else if (!state.selectionOpen) {
        SearchScreen(
            state = state,
            onQueryChange = viewModel::updateQuery,
            onSearch = viewModel::search,
            onAnimeToggle = viewModel::toggleAnime,
            onOpenSelection = viewModel::openSelection,
            onOpenAbout = viewModel::openAbout,
        )
    } else {
        PilgrimageSelectionScreen(
            state = state,
            mapProvider = selectionMapProvider,
            amapRegionDataReady = selectionMapProvider == MapProvider.AMAP,
            amapPrivacyAndKeyReady = amapPrivacyGate.isReady,
            onBack = viewModel::backToResults,
            onTogglePoint = viewModel::togglePoint,
            onBoundsChanged = viewModel::updateVisibleBounds,
            onSelectVisible = viewModel::selectVisiblePoints,
            onClearSelection = viewModel::clearSelection,
            onShowList = viewModel::setShowList,
            onMapUnavailable = viewModel::handleMapUnavailable,
            onPlan = {
                state.combinedPilgrimageData?.let { data ->
                    val points = data.points.filter { it.id in state.selectedPointIds }
                    plannerViewModel.configure(data.anime, points)
                    viewModel.openPlanner()
                }
            },
        )
    }
}

@Composable
internal fun SearchScreen(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onAnimeToggle: (Anime) -> Unit,
    onOpenSelection: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    Surface(
        color = Paper,
        modifier = Modifier.fillMaxSize().testTag("search-screen"),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SearchHeader(onOpenAbout = onOpenAbout)
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("search-content"),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                item {
                    SearchForm(
                        query = state.query,
                        isLoading = state.isLoading,
                        onQueryChange = onQueryChange,
                        onSearch = onSearch,
                    )
                }
                item { StatusMessage(state.errorMessage) }
                if (state.selectedAnimes.isNotEmpty()) {
                    item {
                        SelectedAnimeStrip(
                            selectedAnimes = state.selectedAnimes,
                            onAnimeToggle = onAnimeToggle,
                        )
                    }
                }
                when {
                    state.isLoading -> item { LoadingState("正在加载搜索结果…") }
                    state.searchResults.isEmpty() -> item {
                        EmptySearchState(hasQuery = state.query.isNotBlank())
                    }
                    else -> animeResults(
                        results = state.searchResults,
                        selectedAnimeIds = state.selectedAnimeData.keys,
                        loadingAnimeIds = state.loadingAnimeIds,
                        onAnimeToggle = onAnimeToggle,
                    )
                }
            }
            AnimeSelectionFooter(
                animeCount = state.selectedAnimes.size,
                pointCount = state.combinedPilgrimageData?.points?.size.orZero(),
                onOpenSelection = onOpenSelection,
            )
        }
    }
}

@Composable
private fun SearchForm(
    query: String,
    isLoading: Boolean,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = "查找作品",
            color = Ink,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "输入动画名称，加入这次巡礼",
            color = MutedInk,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
        )
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("动漫名称") },
            placeholder = { Text("例如：吹响吧！上低音号") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Rounded.Clear, contentDescription = "清空")
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Vermilion,
                focusedLabelColor = Vermilion,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
            ),
        )
        Button(
            onClick = onSearch,
            enabled = !isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .heightIn(min = 50.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Vermilion),
        ) {
            Text("搜索 Bangumi")
        }
        Text(
            text = "作品与别名索引由 Bangumi 提供",
            color = MutedInk,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 8.dp),
        )
    }
}

@Composable
private fun SearchHeader(onOpenAbout: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.anitabi_brand_mark),
                contentDescription = "巡礼手帳标识",
                modifier = Modifier.size(40.dp),
                contentScale = ContentScale.Fit,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(
                    text = "巡礼手帳",
                    color = Ink,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "动画取景地路线",
                    color = MutedInk,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            IconButton(
                onClick = onOpenAbout,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = "关于、隐私与数据来源",
                    tint = MutedInk,
                )
            }
        }
    }
}

@Composable
private fun SelectedAnimeStrip(
    selectedAnimes: List<Anime>,
    onAnimeToggle: (Anime) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)) {
        Column(modifier = Modifier.padding(vertical = 10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "已选作品",
                    color = Ink,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${selectedAnimes.size} 部 · 点按移除",
                    color = MutedInk,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            LazyRow(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(selectedAnimes, key = Anime::subjectId) { anime ->
                    val title = anime.nameCn ?: anime.name
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .width(212.dp)
                            .heightIn(min = 58.dp)
                            .toggleable(
                                value = true,
                                role = Role.Checkbox,
                                onValueChange = { onAnimeToggle(anime) },
                            )
                            .semantics {
                                selected = true
                                stateDescription = "已选择，点按移除"
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AsyncImage(
                                model = anime.imageUrl,
                                contentDescription = "$title 封面",
                                modifier = Modifier
                                    .size(width = 34.dp, height = 46.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Sand),
                                contentScale = ContentScale.Crop,
                            )
                            Text(
                                text = title,
                                color = Ink,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 10.dp),
                            )
                            Icon(
                                imageVector = Icons.Rounded.Clear,
                                contentDescription = null,
                                tint = MutedInk,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.animeResults(
    results: List<Anime>,
    selectedAnimeIds: Set<Long>,
    loadingAnimeIds: Set<Long>,
    onAnimeToggle: (Anime) -> Unit,
) {
    item {
        Text(
            text = "搜索结果 · ${results.size} 部",
            style = MaterialTheme.typography.labelLarge,
            color = MutedInk,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        )
    }
    items(results, key = Anime::subjectId) { anime ->
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            val selected = anime.subjectId in selectedAnimeIds
            val loading = anime.subjectId in loadingAnimeIds
            val title = anime.nameCn ?: anime.name
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.56f)
                        else Color.Transparent,
                    )
                    .toggleable(
                        value = selected,
                        enabled = !loading,
                        role = Role.Checkbox,
                        onValueChange = { onAnimeToggle(anime) },
                    )
                    .semantics {
                        this.selected = selected
                        stateDescription = when {
                            loading -> "正在读取巡礼点"
                            selected -> "已选择"
                            else -> "未选择"
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = anime.imageUrl,
                    contentDescription = "$title 封面",
                    modifier = Modifier
                        .size(width = 60.dp, height = 80.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Sand),
                    contentScale = ContentScale.Crop,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 14.dp),
                ) {
                    Text(
                        text = title,
                        color = Ink,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (anime.nameCn != null) {
                        Text(
                            text = anime.name,
                            color = MutedInk,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    Text(
                        text = "Bangumi #${anime.subjectId}",
                        color = MutedInk,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                when {
                    loading -> CircularProgressIndicator(
                        modifier = Modifier
                            .size(24.dp)
                            .semantics {
                                liveRegion = LiveRegionMode.Polite
                                stateDescription = "正在读取巡礼点"
                            },
                        strokeWidth = 2.dp,
                        color = Vermilion,
                    )
                    selected -> Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = Vermilion,
                        modifier = Modifier.size(26.dp),
                    )
                    else -> Text(
                        text = "选择",
                        color = Vermilion,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun AnimeSelectionFooter(
    animeCount: Int,
    pointCount: Int,
    onOpenSelection: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
        modifier = Modifier.testTag("search-selection-footer"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        stateDescription = "已选 $animeCount 部动画，合计 $pointCount 个巡礼点"
                    },
            ) {
                Text("已选 $animeCount 部作品", color = Ink, fontWeight = FontWeight.SemiBold)
                Text("$pointCount 个巡礼地点", color = MutedInk, style = MaterialTheme.typography.bodyMedium)
            }
            Button(
                onClick = onOpenSelection,
                enabled = pointCount > 0,
                modifier = Modifier.heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Vermilion),
            ) {
                Text("选择地点")
            }
        }
    }
}

@Composable
internal fun PilgrimageSelectionScreen(
    state: SearchUiState,
    mapProvider: MapProvider? = null,
    amapRegionDataReady: Boolean = false,
    amapPrivacyAndKeyReady: Boolean = false,
    onBack: () -> Unit,
    onTogglePoint: (String) -> Unit,
    onBoundsChanged: (GeoBounds) -> Unit,
    onSelectVisible: () -> Unit,
    onClearSelection: () -> Unit,
    onShowList: (Boolean) -> Unit,
    onMapUnavailable: () -> Unit,
    onPlan: () -> Unit,
    forceListMode: Boolean = false,
) {
    val data = state.combinedPilgrimageData
    val availableMapProvider = availableSearchMapProvider(
        provider = mapProvider,
        amapRegionDataReady = amapRegionDataReady,
        amapPrivacyAndKeyReady = amapPrivacyAndKeyReady,
    )
    BoxWithConstraints(modifier = Modifier.fillMaxSize().testTag("point-selection-screen")) {
        val widthClass = contentWidthClass(maxWidth)
        val useDualPane = availableMapProvider != null && !forceListMode &&
            (widthClass == ContentWidthClass.Expanded || maxWidth > maxHeight)
        Surface(color = Paper, modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                SelectionToolbar(
                    title = data?.anime?.nameCn ?: data?.anime?.name.orEmpty(),
                    pointCount = data?.points?.size,
                    partialData = data?.warnings?.contains(PilgrimageWarning.PARTIAL_DATA) == true,
                    onBack = onBack,
                )
                StatusMessage(
                    state.errorMessage ?: if (data != null && availableMapProvider == null) {
                        if (mapProvider == MapProvider.AMAP) {
                            "高德地图地区数据、隐私同意或 Android Key 尚未就绪，已切换为列表"
                        } else {
                            "地图地区无法安全判定或包含不同地图提供方，已切换为列表"
                        }
                    } else {
                        null
                    },
                )

                when {
                    state.isLoading || data == null -> Box(modifier = Modifier.weight(1f)) {
                        LoadingState("正在加载地图…")
                    }
                    useDualPane -> Row(modifier = Modifier.weight(1f)) {
                        PointList(
                            points = data.points,
                            selectedPointIds = state.selectedPointIds,
                            onTogglePoint = onTogglePoint,
                            modifier = Modifier
                                .weight(0.42f)
                                .fillMaxHeight(),
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        )
                        PilgrimageMapPane(
                            contentKey = state.mapContentKey,
                            points = data.points,
                            selectedPointIds = state.selectedPointIds,
                            onPointToggle = onTogglePoint,
                            onVisibleBoundsChanged = onBoundsChanged,
                            onSelectVisible = onSelectVisible,
                            onMapUnavailable = onMapUnavailable,
                            mapProvider = requireNotNull(availableMapProvider),
                            amapRegionDataReady = amapRegionDataReady,
                            modifier = Modifier
                                .weight(0.58f)
                                .fillMaxHeight(),
                        )
                    }
                    state.showList || forceListMode || availableMapProvider == null -> PointList(
                        points = data.points,
                        selectedPointIds = state.selectedPointIds,
                        onTogglePoint = onTogglePoint,
                        modifier = Modifier.weight(1f),
                    )
                    else -> PilgrimageMapPane(
                        contentKey = state.mapContentKey,
                        points = data.points,
                        selectedPointIds = state.selectedPointIds,
                        onPointToggle = onTogglePoint,
                        onVisibleBoundsChanged = onBoundsChanged,
                        onSelectVisible = onSelectVisible,
                        onMapUnavailable = onMapUnavailable,
                        mapProvider = requireNotNull(availableMapProvider),
                        amapRegionDataReady = amapRegionDataReady,
                        modifier = Modifier.weight(1f),
                    )
                }

                SelectionFooter(
                    selectedCount = state.selectedPointIds.size,
                    showList = state.showList,
                    useDualPane = useDualPane,
                    onShowList = onShowList,
                    onClear = onClearSelection,
                    onPlan = onPlan,
                )
            }
        }
    }
}

private enum class ContentWidthClass {
    Compact,
    Medium,
    Expanded,
}

private fun contentWidthClass(width: Dp): ContentWidthClass = when {
    width < 600.dp -> ContentWidthClass.Compact
    width < 840.dp -> ContentWidthClass.Medium
    else -> ContentWidthClass.Expanded
}

private fun Int?.orZero(): Int = this ?: 0

internal fun availableSearchMapProvider(
    provider: MapProvider?,
    amapRegionDataReady: Boolean,
    amapPrivacyAndKeyReady: Boolean,
): MapProvider? = when (provider) {
    MapProvider.AMAP -> provider.takeIf { amapRegionDataReady && amapPrivacyAndKeyReady }
    MapProvider.GOOGLE -> provider
    null -> null
}

@Composable
private fun PilgrimageMapPane(
    contentKey: String,
    points: List<PilgrimagePoint>,
    selectedPointIds: Set<String>,
    onPointToggle: (String) -> Unit,
    onVisibleBoundsChanged: (GeoBounds) -> Unit,
    onSelectVisible: () -> Unit,
    onMapUnavailable: () -> Unit,
    mapProvider: MapProvider,
    amapRegionDataReady: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        PilgrimageMap(
            contentKey = contentKey,
            points = points,
            selectedPointIds = selectedPointIds,
            onPointToggle = onPointToggle,
            onVisibleBoundsChanged = onVisibleBoundsChanged,
            onMapUnavailable = onMapUnavailable,
            provider = mapProvider,
            amapRegionDataReady = amapRegionDataReady,
            modifier = Modifier.fillMaxSize(),
        )
        OutlinedButton(
            onClick = onSelectVisible,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp)
                .heightIn(min = 48.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("选择地图范围", modifier = Modifier.padding(start = 6.dp))
        }
        Attribution(
            mapProvider = mapProvider,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp),
        )
    }
}

internal fun resolveSearchMapProvider(
    points: List<PilgrimagePoint>,
    classifyTerritory: (GeoPoint) -> TerritoryRegion?,
): MapProvider? {
    if (points.isEmpty()) return null
    val providers = points.mapTo(mutableSetOf()) { point ->
        classifyTerritory(point.coordinate)?.mapProvider ?: return null
    }
    return providers.singleOrNull()
}

@Composable
private fun SelectionToolbar(
    title: String,
    pointCount: Int?,
    partialData: Boolean,
    onBack: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回", tint = Ink)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            ) {
                Text(
                    text = title.ifBlank { "选择巡礼地点" },
                    color = Ink,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        pointCount == null -> "正在读取地点"
                        partialData -> "$pointCount 个可用地点 · 数据可能不完整"
                        else -> "$pointCount 个巡礼地点"
                    },
                    color = if (partialData) MaterialTheme.colorScheme.error else MutedInk,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Text(
                text = "选择地点",
                color = MutedInk,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
    }
}

@Composable
private fun PointList(
    points: List<PilgrimagePoint>,
    selectedPointIds: Set<String>,
    onTogglePoint: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    ) {
        items(points, key = PilgrimagePoint::id) { point ->
            val selected = point.id in selectedPointIds
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.56f)
                        else Color.Transparent,
                    )
                    .toggleable(
                        value = selected,
                        role = Role.Checkbox,
                        onValueChange = { onTogglePoint(point.id) },
                    )
                    .semantics {
                        this.selected = selected
                        stateDescription = if (selected) "已选择" else "未选择"
                    }
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = point.imageUrl,
                    contentDescription = "${point.name} 巡礼截图",
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Sand),
                    contentScale = ContentScale.Crop,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                ) {
                    Text(
                        text = point.name,
                        color = Ink,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "%.5f, %.5f".format(
                            point.coordinate.latitude,
                            point.coordinate.longitude,
                        ),
                        color = MutedInk,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (!point.origin.isNullOrBlank()) {
                        Text(
                            text = "截图来源：${point.origin}",
                            color = Vermilion,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .heightIn(min = 48.dp)
                                .clickable(enabled = !point.originUrl.isNullOrBlank()) {
                                    point.originUrl?.let(uriHandler::openUri)
                                },
                        )
                    }
                }
                if (selected) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = Vermilion,
                        modifier = Modifier.size(26.dp),
                    )
                } else {
                    Text(
                        text = "选择",
                        color = Vermilion,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun SelectionFooter(
    selectedCount: Int,
    showList: Boolean,
    useDualPane: Boolean,
    onShowList: (Boolean) -> Unit,
    onClear: () -> Unit,
    onPlan: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            if (useDualPane) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.List,
                        contentDescription = null,
                        tint = MutedInk,
                        modifier = Modifier.size(20.dp),
                    )
                    Icon(
                        imageVector = Icons.Rounded.Map,
                        contentDescription = null,
                        tint = MutedInk,
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .size(20.dp),
                    )
                    Text(
                        text = "列表与地图并排显示",
                        color = MutedInk,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            } else {
                ViewModeSwitcher(
                    showList = showList,
                    onShowList = onShowList,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "已选 $selectedCount 个地点",
                    color = Ink,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .semantics {
                            liveRegion = LiveRegionMode.Polite
                            stateDescription = "已选择 $selectedCount 个巡礼地点"
                        },
                )
                AnimatedVisibility(visible = selectedCount > 0) {
                    TextButton(
                        onClick = onClear,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("清空")
                    }
                }
                Button(
                    onClick = onPlan,
                    enabled = selectedCount >= 2,
                    modifier = Modifier.heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Vermilion),
                ) {
                    Text("规划路线")
                }
            }
        }
    }
}

@Composable
private fun ViewModeSwitcher(
    showList: Boolean,
    onShowList: (Boolean) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(3.dp)
                .selectableGroup(),
        ) {
            ViewModeOption(
                label = "地图",
                icon = { Icon(Icons.Rounded.Map, contentDescription = null, modifier = Modifier.size(20.dp)) },
                selected = !showList,
                onClick = { onShowList(false) },
                modifier = Modifier.weight(1f),
            )
            ViewModeOption(
                label = "列表",
                icon = {
                    Icon(
                        Icons.AutoMirrored.Rounded.List,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                selected = showList,
                onClick = { onShowList(true) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ViewModeOption(
    label: String,
    icon: @Composable () -> Unit,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
            )
            .semantics {
                this.selected = selected
                stateDescription = if (selected) "当前视图" else "切换到$label"
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun Attribution(mapProvider: MapProvider, modifier: Modifier = Modifier) {
    Text(
        text = when (mapProvider) {
            MapProvider.GOOGLE -> "Google Maps"
            MapProvider.AMAP -> "高德地图"
        },
        color = Ink,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.White.copy(alpha = 0.88f))
            .padding(horizontal = 5.dp, vertical = 3.dp),
    )
}

@Composable
private fun StatusMessage(message: String?) {
    AnimatedVisibility(visible = message != null) {
        Text(
            text = message.orEmpty(),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun LoadingState(message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = LiveRegionMode.Polite
                stateDescription = message
            }
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = Vermilion)
        Text(message, color = MutedInk, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun EmptySearchState(hasQuery: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 32.dp, vertical = 52.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (hasQuery) "没有搜索结果" else "搜索动画作品",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (hasQuery) "换一个译名或原名再试试。" else "支持中文译名、日文原名和英文名。",
            color = MutedInk,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
