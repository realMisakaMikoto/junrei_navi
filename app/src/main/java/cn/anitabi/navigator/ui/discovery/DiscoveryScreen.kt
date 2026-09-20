package cn.anitabi.navigator.ui.discovery

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.core.routing.TourOptimizer
import cn.anitabi.navigator.data.discovery.*
import cn.anitabi.navigator.navigation.AndroidLocationProvider
import cn.anitabi.navigator.ui.discovery.map.DiscoveryMap
import cn.anitabi.navigator.ui.discovery.map.DiscoveryMapPadding
import cn.anitabi.navigator.ui.search.SearchViewModel
import cn.anitabi.navigator.ui.theme.MapSurfaceTheme
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.Locale

@Composable
fun DiscoveryRoute(
    viewModel: DiscoveryViewModel,
    selection: SearchViewModel,
    privacyReady: Boolean,
    imagesEnabled: Boolean,
    darkTheme: Boolean,
    onImagesEnabled: (Boolean) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onPlan: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selected by selection.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.locate()
    }
    LaunchedEffect(Unit) { viewModel.initializeLocation(AndroidLocationProvider.hasLocationPermission(context)) }
    val togglePoint: (DiscoveryPoint) -> Unit = { point ->
        state.data.snapshot?.subjects?.find { it.id == point.subjectId }?.let { subject ->
            selection.toggleDiscoveryPoint(subject.anime, point.toPilgrimagePoint().copy(id = point.rawId))
        }
    }
    val selectVisible: () -> Unit = {
        state.visibleIds.mapNotNull(state.pointsById::get).groupBy { it.subjectId }.forEach { (id, points) ->
            state.data.snapshot?.subjects?.find { it.id == id }?.let { subject ->
                selection.selectDiscoveryPoints(subject.anime, points.map { it.toPilgrimagePoint().copy(id = it.rawId) })
            }
        }
    }
    DiscoveryScreen(
        state = state, selectedIds = selected.selectedPointIds, privacyReady = privacyReady,
        imagesEnabled = imagesEnabled, darkTheme = darkTheme,
        onImagesEnabled = onImagesEnabled, onSearch = onSearch, onSettings = onSettings,
        onProvider = viewModel::selectProvider, onFilter = viewModel::toggleFilter,
        onLocate = {
            if (AndroidLocationProvider.hasLocationPermission(context)) viewModel.locate()
            else locationPermission.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
        }, onResetBearing = viewModel::resetBearing,
        onListMode = viewModel::setListMode, onBatchMode = viewModel::setBatchMode,
        onNearby = viewModel::setNearby, onRefresh = viewModel::refresh,
        onPoint = { point, fromMap ->
            if (state.batchMode && fromMap) togglePoint(point) else viewModel.openPoint(point.id, fromMap)
        },
        onSubject = { viewModel.openSubject(it) }, onTogglePoint = togglePoint,
        onBackPanel = viewModel::backPanel, onPanelPresentation = viewModel::rememberPanel,
        onGroupByEpisode = viewModel::setGroupByEpisode,
        onFitAll = { ids -> viewModel.fitAll(ids) },
        onVisibleIds = viewModel::setVisibleIds, onOverlap = viewModel::openOverlap,
        onCameraChanged = viewModel::cameraChanged, onManualMove = viewModel::manualMove,
        onUnavailable = viewModel::mapUnavailable, onSelectVisible = selectVisible,
        onClearSelection = selection::clearSelection, onPlan = onPlan,
        onMapDetached = viewModel::mapDetached,
        onCameraCommandApplied = viewModel::cameraCommandApplied,
        onRetryDetails = viewModel::retrySubjectDetails,
    )
}

@Composable
internal fun DiscoveryScreen(
    state: DiscoveryUiState,
    selectedIds: Set<String>,
    privacyReady: Boolean,
    imagesEnabled: Boolean,
    darkTheme: Boolean,
    onImagesEnabled: (Boolean) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onProvider: (MapProvider) -> Unit,
    onFilter: (Long) -> Unit,
    onLocate: () -> Unit,
    onResetBearing: () -> Unit,
    onListMode: (Boolean) -> Unit,
    onBatchMode: (Boolean) -> Unit,
    onNearby: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onPoint: (DiscoveryPoint, Boolean) -> Unit,
    onSubject: (Long) -> Unit,
    onTogglePoint: (DiscoveryPoint) -> Unit,
    onBackPanel: () -> Unit,
    onPanelPresentation: (String, PanelPresentation) -> Unit,
    onGroupByEpisode: (Boolean) -> Unit,
    onFitAll: (Set<String>?) -> Unit,
    onVisibleIds: (Set<String>) -> Unit,
    onOverlap: (List<String>) -> Unit,
    onCameraChanged: (cn.anitabi.navigator.ui.discovery.map.DiscoveryCameraPosition) -> Unit,
    onManualMove: () -> Unit,
    onUnavailable: () -> Unit,
    onSelectVisible: () -> Unit,
    onClearSelection: () -> Unit,
    onPlan: () -> Unit,
    mapContent: (@Composable (Modifier) -> Unit)? = null,
    onMapDetached: () -> Unit = {},
    onCameraCommandApplied: (Long, cn.anitabi.navigator.ui.discovery.map.DiscoveryCameraPosition) -> Unit = { _, _ -> },
    onRetryDetails: (Long) -> Unit = { onRefresh() },
) {
    BackHandler(state.panel.canGoBack, onBackPanel)
    MapSurfaceTheme {
        BoxWithConstraints(Modifier.fillMaxSize().testTag("discovery-screen")) {
            val wide = maxWidth >= 840.dp || maxWidth > maxHeight
            val density = LocalDensity.current
            val sidePanel = wide && !state.listMode
            val sidePanelWidth = minOf(380.dp, maxWidth * 0.44f)
            val systemTop = WindowInsets.statusBars.getTop(density)
            var toolbarHeight by remember { mutableIntStateOf(0) }
            var panelWidth by remember { mutableIntStateOf(0) }
            var panelHeight by remember { mutableIntStateOf(0) }
            var measuredPanelKey by remember { mutableStateOf<String?>(null) }
            val filteredMapPoints = remember(state.mapPoints, state.filters, state.provider) {
                state.mapPoints.filter { it.provider == state.provider && (state.filters.isEmpty() || it.subjectId in state.filters) }
            }
            val regionReady = state.provider in state.providerChoices
            LaunchedEffect(state.data.indexAvailable, regionReady, state.provider, privacyReady) {
                if (state.data.indexAvailable && (!regionReady || state.provider == MapProvider.AMAP && !privacyReady)) onUnavailable()
            }
            if (!state.listMode && state.data.indexAvailable && regionReady) {
                val currentDetached by rememberUpdatedState(onMapDetached)
                DisposableEffect(Unit) { onDispose { currentDetached() } }
                val mapModifier = Modifier.fillMaxSize().testTag("discovery-map")
                if (mapContent != null) mapContent(mapModifier) else DiscoveryMap(
                    dataVersion = "${state.mapDataVersion}:${state.filters.sorted()}",
                    points = filteredMapPoints, provider = state.provider, privacyReady = privacyReady,
                    selectedIds = selectedIds,
                    focusedPointId = (state.panel.current as? DiscoveryPanel.Point)?.pointId,
                    imagesEnabled = imagesEnabled, darkTheme = darkTheme,
                    padding = DiscoveryMapPadding(
                        left = if (wide) panelWidth else 0,
                        top = toolbarHeight,
                        bottom = if (wide) 0 else panelHeight,
                    ),
                    cameraCommand = state.cameraCommand.takeIf { measuredPanelKey == state.panel.current.key && toolbarHeight > 0 },
                    onVisibleIdsChanged = onVisibleIds,
                    onPointClick = { id -> state.pointsById[id]?.let { onPoint(it, true) } },
                    onOverlapClick = onOverlap, onCameraChanged = onCameraChanged,
                    onManualMove = onManualMove, onUnavailable = onUnavailable, modifier = mapModifier,
                    onCameraCommandApplied = onCameraCommandApplied,
                )
            } else if (!state.data.indexAvailable) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (!state.data.initialized || state.data.refreshing) CircularProgressIndicator()
                        Text(if (state.data.refreshing || !state.data.initialized) "正在准备巡礼地图" else "暂时没有可用的地图数据")
                        TextButton(onClick = onRefresh) { Text("重新加载") }
                    }
                }
            }
            var displayMenu by remember { mutableStateOf(false) }
            val inlineControls = state.listMode || (!wide && (state.panel.presentation.detent == PanelDetent.EXPANDED ||
                density.fontScale > 1.2f && state.panel.presentation.detent == PanelDetent.HALF))
            val controls: @Composable (Modifier, Boolean) -> Unit = { controlModifier, horizontal ->
                FlowRow(controlModifier, maxItemsInEachRow = if (horizontal) 3 else 1,
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MapAction("定位", Icons.Rounded.MyLocation, onLocate)
                    MapAction("地图回正", Icons.Rounded.Explore, onResetBearing)
                    Box {
                        MapAction("地图显示设置", Icons.Rounded.Layers, { displayMenu = true })
                        DropdownMenu(expanded = displayMenu, onDismissRequest = { displayMenu = false }) {
                            DropdownMenuItem(text = { Text(if (imagesEnabled) "关闭图片标记" else "开启图片标记") }, onClick = { onImagesEnabled(!imagesEnabled); displayMenu = false })
                            DropdownMenuItem(text = { Text(if (state.listMode) "显示地图" else "切换地点列表") }, onClick = { onListMode(!state.listMode); displayMenu = false })
                            DropdownMenuItem(text = { Text(if (state.batchMode) "退出批量选点" else "批量选点") }, onClick = { onBatchMode(!state.batchMode); displayMenu = false })
                            DropdownMenuItem(text = { Text("刷新发现数据") }, onClick = { onRefresh(); displayMenu = false })
                            DropdownMenuItem(text = { Text("显示全部点位") }, onClick = { onFitAll(null); displayMenu = false })
                        }
                    }
                }
            }
            Column(
                Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .padding(start = if (sidePanel) sidePanelWidth else 0.dp)
                    .onSizeChanged { toolbarHeight = it.height }.statusBarsPadding(),
            ) {
                Surface(
                    onClick = onSearch, shape = RoundedCornerShape(28.dp), shadowElevation = 2.dp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth().testTag("discovery-search"),
                ) {
                    Row(Modifier.heightIn(min = 56.dp).padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Search, null)
                        Text("搜索作品、地点、城市", Modifier.weight(1f).padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        IconButton(onClick = { onListMode(!state.listMode) }, modifier = Modifier.testTag("discovery-map-list-toggle")) {
                            Icon(if (state.listMode) Icons.Rounded.Map else Icons.AutoMirrored.Rounded.List,
                                if (state.listMode) "显示地图" else "显示地点列表")
                        }
                        IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "关于与设置") }
                    }
                }
                val counts = remember(state.visibleIds, state.pointsById) {
                    state.visibleIds.mapNotNull(state.pointsById::get).groupingBy { it.subjectId }.eachCount()
                }
                val chips = remember(state.data.snapshot?.subjects, counts, state.filters) {
                    state.data.snapshot?.subjects.orEmpty().filter { it.id in counts || it.id in state.filters }
                        .sortedByDescending { it.id in state.filters }
                }
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(chips, key = { it.id }) { subject ->
                        FilterChip(
                            selected = subject.id in state.filters, onClick = { onFilter(subject.id) },
                            label = {
                                Row(Modifier.widthIn(max = 180.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(subject.name, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(" · ${counts[subject.id] ?: 0}")
                                }
                            },
                            leadingIcon = {
                                Box(Modifier.size(28.dp)) {
                                    DiscoveryThumbnail(subject.anime.imageUrl, Modifier.fillMaxSize())
                                    if (subject.id in state.filters) Icon(Icons.Rounded.CheckCircle, null, Modifier.size(14.dp).align(Alignment.BottomEnd), tint = MaterialTheme.colorScheme.primary)
                                }
                            },
                            colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.heightIn(min = 48.dp).testTag("subject-filter-${subject.id}"),
                        )
                    }
                }
                if (state.providerChoices.size > 1) LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.providerChoices.toList()) { provider ->
                        FilterChip(selected = state.provider == provider, onClick = { onProvider(provider) },
                            label = { Text(if (provider == MapProvider.GOOGLE) "Google 地图" else "高德地图") },
                            colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface))
                    }
                }
                if (inlineControls) controls(Modifier.align(Alignment.End).padding(horizontal = 12.dp, vertical = 8.dp), true)
            }
            if (!inlineControls) controls(
                Modifier.align(Alignment.TopEnd).padding(top = with(density) { toolbarHeight.toDp() } + 8.dp, end = 12.dp),
                maxHeight < 420.dp,
            )
            val panelModifier = when {
                state.listMode -> Modifier.fillMaxWidth().padding(top = with(density) { toolbarHeight.toDp() })
                wide -> Modifier.align(Alignment.TopStart).statusBarsPadding().width(sidePanelWidth)
                else -> Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            }.onSizeChanged { panelWidth = it.width; panelHeight = it.height; measuredPanelKey = state.panel.current.key }
            val availableHeight = (maxHeight - with(density) { (if (sidePanel) systemTop else toolbarHeight).toDp() }).coerceAtLeast(80.dp)
            val detent = if (state.listMode || wide) PanelDetent.EXPANDED else state.panel.presentation.detent
            val maxPanelHeight = when (detent) {
                PanelDetent.COLLAPSED -> availableHeight
                PanelDetent.HALF -> availableHeight * (0.58f * density.fontScale.coerceAtLeast(1f)).coerceAtMost(0.9f)
                PanelDetent.EXPANDED -> availableHeight
            }
            val panelStateHolder = rememberSaveableStateHolder()
            panelStateHolder.SaveableStateProvider(state.panel.current.key) {
            DiscoveryPanelContent(
                state = state, selectedIds = selectedIds, detent = detent,
                onPoint = { onPoint(it, false) }, onSubject = onSubject, onTogglePoint = onTogglePoint,
                onBack = onBackPanel, onPresentation = onPanelPresentation,
                onNearby = onNearby, onGroupByEpisode = onGroupByEpisode,
                onFitAll = onFitAll, onRefresh = onRefresh,
                onBatchMode = onBatchMode, onSelectVisible = onSelectVisible,
                onClearSelection = onClearSelection, onPlan = onPlan,
                modifier = panelModifier.heightIn(max = maxPanelHeight),
                canChangeDetent = !wide && !state.listMode,
                onRetryDetails = onRetryDetails,
            )
            }
        }
    }
}

@Composable
private fun MapAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Surface(shape = CircleShape, shadowElevation = 2.dp) {
        IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) { Icon(icon, label) }
    }
}

@Composable
private fun DiscoveryPanelContent(
    state: DiscoveryUiState,
    selectedIds: Set<String>,
    detent: PanelDetent,
    onPoint: (DiscoveryPoint) -> Unit,
    onSubject: (Long) -> Unit,
    onTogglePoint: (DiscoveryPoint) -> Unit,
    onBack: () -> Unit,
    onPresentation: (String, PanelPresentation) -> Unit,
    onNearby: (Boolean) -> Unit,
    onGroupByEpisode: (Boolean) -> Unit,
    onFitAll: (Set<String>?) -> Unit,
    onRefresh: () -> Unit,
    onBatchMode: (Boolean) -> Unit,
    onSelectVisible: () -> Unit,
    onClearSelection: () -> Unit,
    onPlan: () -> Unit,
    modifier: Modifier,
    canChangeDetent: Boolean,
    onRetryDetails: (Long) -> Unit,
) {
    val panel = state.panel.current
    val subjects = remember(state.data.snapshot?.subjects) { state.data.snapshot?.subjects.orEmpty().associateBy { it.id } }
    val point = (panel as? DiscoveryPanel.Point)?.pointId?.let(state.pointsById::get)
    val subject = (panel as? DiscoveryPanel.Subject)?.subjectId?.let(subjects::get)
    var collapsedGroups by rememberSaveable { mutableStateOf(listOf<String>()) }
    var showWorks by rememberSaveable { mutableStateOf(false) }
    val visiblePoints = remember(state.pointsById, state.visibleIds, state.filters, state.listMode, state.nearby, state.location) {
        val source = if (state.listMode || state.nearby) state.pointsById.values else state.visibleIds.mapNotNull(state.pointsById::get)
        val filtered = source.filter { state.filters.isEmpty() || it.subjectId in state.filters }
        if (state.nearby && state.location != null) filtered.sortedBy { TourOptimizer.haversineMeters(state.location, it.coordinate) } else filtered
    }
    val title = when (panel) {
        is DiscoveryPanel.Point -> point?.displayName ?: "地点暂不可用"
        is DiscoveryPanel.Subject -> subject?.name ?: "作品暂不可用"
        DiscoveryPanel.Overlap -> "重叠的 ${state.overlapIds.size} 个地点"
        DiscoveryPanel.Overview -> if (state.nearby) "附近地点" else if (state.listMode) "巡礼地点" else "视野内 ${visiblePoints.size} 个地点"
    }
    val listState = remember(panel.key) { LazyListState(state.panel.presentation.firstVisibleItem, state.panel.presentation.scrollOffset) }
    val currentPresentation by rememberUpdatedState(state.panel.presentation)
    val currentOnPresentation by rememberUpdatedState(onPresentation)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }.distinctUntilChanged().collect { (index, offset) ->
            currentOnPresentation(panel.key, currentPresentation.copy(firstVisibleItem = index, scrollOffset = offset))
        }
    }
    val changeDetent: (Int) -> Unit = { direction ->
        onPresentation(panel.key, state.panel.presentation.copy(detent = PanelDetent.entries[(detent.ordinal + direction).coerceIn(0, 2)]))
    }
    var drag by remember { mutableFloatStateOf(0f) }
    Surface(
        modifier = modifier.testTag("discovery-panel").semantics { paneTitle = title },
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), shadowElevation = 3.dp,
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 8.dp, end = 8.dp)
                    .then(if (canChangeDetent) Modifier.draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { drag += it },
                        onDragStopped = { if (drag < -20) changeDetent(1) else if (drag > 20) changeDetent(-1); drag = 0f },
                    ) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.panel.canGoBack) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回上一层") }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                    if (panel == DiscoveryPanel.Overview) Text(discoveryDataLabel(state.data), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (canChangeDetent) {
                    if (detent != PanelDetent.COLLAPSED) IconButton(onClick = { changeDetent(-1) }) { Icon(Icons.Rounded.ExpandMore, "收起面板") }
                    if (detent != PanelDetent.EXPANDED) IconButton(onClick = { changeDetent(1) }) { Icon(Icons.Rounded.ExpandLess, "展开面板") }
                }
            }
            if (detent != PanelDetent.COLLAPSED) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                LazyColumn(
                    state = listState, modifier = Modifier.weight(1f, fill = false).testTag("discovery-panel-list"),
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    state.message?.let { item { PanelNotice(it) } }
                    if (state.unresolvedCount > 0) item { PanelNotice("${state.unresolvedCount} 个地点的地区尚未确认，可在列表查看，暂不发送给地图。") }
                    when (panel) {
                        DiscoveryPanel.Overview -> {
                            item {
                                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(selected = !state.nearby && !showWorks, onClick = { showWorks = false; onNearby(false) }, label = { Text(if (state.listMode) "全部地点" else "视野内地点") })
                                    FilterChip(selected = showWorks, onClick = { showWorks = true; onNearby(false) }, label = { Text("作品") })
                                    if (state.location != null) FilterChip(selected = state.nearby, onClick = { showWorks = false; onNearby(true) }, label = { Text("附近") })
                                    if (state.batchMode) FilterChip(selected = true, onClick = { onBatchMode(false) }, label = { Text("退出批量") })
                                }
                            }
                            if (state.data.error != null) item { TextButton(onClick = onRefresh, Modifier.padding(horizontal = 8.dp)) { Text("更新未完成，重试") } }
                            if (state.batchMode) item {
                                FlowRow(Modifier.padding(horizontal = 16.dp)) {
                                    TextButton(onClick = onSelectVisible, enabled = state.visibleIds.isNotEmpty()) { Text("视野全选") }
                                    TextButton(onClick = onClearSelection, enabled = selectedIds.isNotEmpty()) { Text("清空选择") }
                                }
                            }
                            if (visiblePoints.isEmpty()) item { PanelNotice(if (state.data.indexAvailable) "移动地图或调整作品筛选，发现更多地点" else "联网加载后即可浏览发现地图") }
                            if (showWorks) {
                                val counts = visiblePoints.groupingBy { it.subjectId }.eachCount()
                                items(counts.keys.mapNotNull(subjects::get), key = { "work:${it.id}" }) { subject ->
                                    ListItem(
                                        modifier = Modifier.clickable { onSubject(subject.id) }.heightIn(min = 80.dp),
                                        headlineContent = { Text(subject.name) },
                                        supportingContent = { Text("${counts[subject.id]} 个地点") },
                                        leadingContent = { DiscoveryThumbnail(subject.anime.imageUrl, Modifier.size(56.dp)) },
                                    )
                                }
                            } else items(visiblePoints, key = { it.id }) { entry ->
                                DiscoveryPointRow(entry, subjects[entry.subjectId]?.name, entry.id in selectedIds,
                                    onOpen = { onPoint(entry) }, onToggle = { onTogglePoint(entry) },
                                    distance = if (state.nearby) state.location?.let { straightLineDistance(TourOptimizer.haversineMeters(it, entry.coordinate)) } else null)
                            }
                        }
                        is DiscoveryPanel.Subject -> if (subject != null) {
                            item { SubjectHeader(subject, onFitAll = { onFitAll(subject.pointIds.toSet()) }) }
                            val points = subject.pointIds.mapNotNull(state.pointsById::get)
                            val episodeMode = state.groupByEpisode && points.any { it.episode != null }
                            if (points.any { it.episode != null }) item {
                                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(!episodeMode, { onGroupByEpisode(false) }, label = { Text("按原分组") })
                                    FilterChip(episodeMode, { onGroupByEpisode(true) }, label = { Text("按集数") })
                                }
                            }
                            points.groupBy { if (episodeMode) it.episode?.let { "第 $it 集" } ?: "未分组" else it.groupName ?: "未分组" }.forEach { (group, entries) ->
                                val groupKey = "${subject.id}:${state.groupByEpisode}:$group"
                                val collapsed = groupKey in collapsedGroups
                                item(key = "group:$group") {
                                    TextButton(onClick = {
                                        collapsedGroups = if (collapsed) collapsedGroups - groupKey else collapsedGroups + groupKey
                                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp).semantics {
                                        heading(); stateDescription = if (collapsed) "已收起" else "已展开"
                                    }) {
                                        Text("$group · ${entries.size}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                        Icon(if (collapsed) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess, null)
                                    }
                                }
                                if (!collapsed) items(entries, key = { it.id }) { entry -> DiscoveryPointRow(entry, null, entry.id in selectedIds, { onPoint(entry) }, { onTogglePoint(entry) }) }
                            }
                        }
                        is DiscoveryPanel.Point -> if (point != null) item {
                            PointDetails(point, subjects[point.subjectId], state.data, onSubject, onRetryDetails)
                        }
                        DiscoveryPanel.Overlap -> items(state.overlapIds.mapNotNull(state.pointsById::get), key = { it.id }) { entry ->
                            DiscoveryPointRow(entry, subjects[entry.subjectId]?.name, entry.id in selectedIds, { onPoint(entry) }, { onTogglePoint(entry) })
                        }
                    }
                }
            }
            if (point != null) {
                Button(onClick = { onTogglePoint(point) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("discovery-point-select")) {
                    Text(if (point.id in selectedIds) "移出行程" else "加入行程")
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("已选 ${selectedIds.size} 个地点", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onPlan, enabled = selectedIds.size >= 2, modifier = Modifier.testTag("discovery-plan")) { Text("规划行程") }
                }
            }
        }
    }
}

@Composable
private fun SubjectHeader(subject: DiscoverySubject, onFitAll: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        DiscoveryThumbnail(subject.anime.imageUrl, Modifier.width(64.dp).height(88.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(subject.name, style = MaterialTheme.typography.titleLarge)
            Text(listOfNotNull(subject.city, "${subject.pointIds.size} 个地点").joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onFitAll) { Text("显示全部点位") }
        }
    }
}

@Composable
private fun PointDetails(point: DiscoveryPoint, subject: DiscoverySubject?, data: DiscoveryState, onSubject: (Long) -> Unit, onRetry: (Long) -> Unit) {
    var showImage by rememberSaveable(point.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        point.imageUrl?.let { url ->
            AsyncImage(model = url, contentDescription = "地点截图，点击查看大图", contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 240.dp).aspectRatio(16f / 9f).clickable { showImage = true })
        }
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            subject?.let { TextButton(onClick = { onSubject(it.id) }) { Text(it.name) } }
            if (!point.detailsLoaded) {
                Text(when {
                    point.subjectId in data.loadingSubjectIds -> "正在补充地点详情；地图位置已可用"
                    data.paused -> "详情加载已暂停，返回前台后继续"
                    data.error != null -> "地点详情暂时无法加载，地图位置仍可用"
                    else -> "地点详情尚未加载，地图位置已可用"
                }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (point.subjectId !in data.loadingSubjectIds) TextButton(onClick = { onRetry(point.subjectId) }) { Text("重试地点详情") }
            } else if (point.detailsVersion != data.snapshot?.version) Text("当前详情尚未与最新索引核对", color = MaterialTheme.colorScheme.onSurfaceVariant)
            val timing = listOfNotNull(point.episode?.let { "第 $it 集" }, point.timecodeSeconds?.let(::formatTimecode)).joinToString(" · ")
            if (timing.isNotBlank()) Text(timing, style = MaterialTheme.typography.bodyMedium)
            point.description?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            point.source?.let { Text("来源：$it", style = MaterialTheme.typography.bodyMedium) }
            val uriHandler = LocalUriHandler.current
            point.sourceUrl?.let { url -> TextButton(onClick = { runCatching { uriHandler.openUri(url) } }) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("查看原始来源")
            } }
        }
    }
    if (showImage && point.imageUrl != null) DiscoveryImageViewer(point.imageUrl, onClose = { showImage = false })
}

@Composable
internal fun DiscoveryPointRow(
    point: DiscoveryPoint, subject: String?, selected: Boolean,
    onOpen: () -> Unit, onToggle: () -> Unit, distance: String? = null,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).heightIn(min = 80.dp).padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DiscoveryThumbnail(point.imageUrl, Modifier.size(56.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(point.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            (distance ?: subject)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        IconToggleButton(checked = selected, onCheckedChange = { onToggle() }) {
            Icon(if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline, if (selected) "移出行程" else "加入行程")
        }
    }
    HorizontalDivider(Modifier.padding(start = 88.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
internal fun DiscoveryThumbnail(url: String?, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Place, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun PanelNotice(message: String) {
    Text(message, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

internal fun discoveryDataLabel(data: DiscoveryState): String = when {
    !data.indexAvailable -> if (data.refreshing || !data.initialized) "正在加载地图索引" else "发现数据尚未加载"
    data.refreshing -> "地点已可浏览，正在补充详情"
    data.error != null -> "更新未完成，正在显示可用缓存"
    data.detailsCurrent -> "数据已更新"
    data.paused -> "详情更新已暂停"
    else -> "详情尚未全部加载"
}

private fun formatTimecode(seconds: Double): String = "%02d:%02d".format(Locale.ROOT, seconds.toLong() / 60, seconds.toLong() % 60)
internal fun straightLineDistance(meters: Double): String = if (meters < 1_000) "直线 ${meters.toInt()} 米" else "直线 %.1f 千米".format(Locale.ROOT, meters / 1_000)
