package cn.anitabi.navigator.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import androidx.window.core.layout.WindowSizeClass
import cn.anitabi.navigator.AppContainer
import cn.anitabi.navigator.core.model.NavigationState
import cn.anitabi.navigator.core.model.PilgrimagePoint
import cn.anitabi.navigator.navigation.NavigationViewModel
import cn.anitabi.navigator.security.AppAppearance
import cn.anitabi.navigator.ui.about.AboutScreen
import cn.anitabi.navigator.ui.components.JournalSectionHeading
import cn.anitabi.navigator.ui.components.JournalTopBar
import cn.anitabi.navigator.ui.discovery.*
import cn.anitabi.navigator.ui.navigation.NavigationRoute
import cn.anitabi.navigator.ui.planner.PlannerRoute
import cn.anitabi.navigator.ui.planner.PlannerViewModel
import cn.anitabi.navigator.ui.search.*
import cn.anitabi.navigator.ui.trips.TripsRoute
import kotlinx.coroutines.launch

private enum class AppDestination(val route: String, val label: String) {
    MAP("map", "地图"), SEARCH("search", "搜索"), TRIPS("trips", "行程"),
}

@Composable
fun AppShell(
    container: AppContainer,
    searchViewModel: SearchViewModel,
    discoveryViewModel: DiscoveryViewModel,
    plannerViewModel: PlannerViewModel,
    navigationViewModel: NavigationViewModel,
    appearance: AppAppearance,
    onAppearanceChange: (AppAppearance) -> Unit,
    imagesEnabled: Boolean,
    onImagesEnabledChange: (Boolean) -> Unit,
    darkTheme: Boolean,
) {
    val controller = rememberNavController()
    val scope = rememberCoroutineScope()
    val backStack by controller.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: "map"
    val showMainNavigation = route in AppDestination.entries.map { it.route }
    val windowClass = currentWindowAdaptiveInfo().windowSizeClass
    val wide = windowClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    val selection by searchViewModel.state.collectAsStateWithLifecycle()
    val discovery by discoveryViewModel.state.collectAsStateWithLifecycle()
    val searchResults by discoveryViewModel.searchResults.collectAsStateWithLifecycle()
    val navigation by navigationViewModel.state.collectAsStateWithLifecycle()
    var amapConsent by remember { mutableStateOf(container.appSettingsStore.hasCurrentAmapPrivacyConsent()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> discoveryViewModel.setForeground(true)
                Lifecycle.Event.ON_STOP -> discoveryViewModel.setForeground(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        discoveryViewModel.setForeground(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); discoveryViewModel.setForeground(false) }
    }
    LaunchedEffect(selection.query) { discoveryViewModel.updateQuery(selection.query) }
    fun goTop(destination: AppDestination) {
        controller.navigate(destination.route) {
            popUpTo(controller.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    val openPlanner: () -> Unit = {
        scope.launch {
            searchViewModel.preparePlanner()?.let { id ->
                controller.navigate("planner/$id") { launchSingleTop = true }
            }
        }
    }
    val activeId = navigation.plan?.id
    val recoverable = activeId != null && navigation.errorMessage != null && navigation.progress?.state?.let {
        it != NavigationState.COMPLETED && it != NavigationState.ENDED
    } == true
    LaunchedEffect(activeId, navigation.isRunning, recoverable, selection.hiddenNavigationTourId) {
        if (activeId != null && (navigation.isRunning || recoverable) && activeId != selection.hiddenNavigationTourId && route != "navigation") {
            controller.navigate("navigation") { launchSingleTop = true }
        }
    }
    val closeNavigation: (String?) -> Unit = { id ->
        searchViewModel.closeNavigation(id)
        controller.navigate("map") { popUpTo("map") { inclusive = false }; launchSingleTop = true }
    }
    Row(Modifier.fillMaxSize()) {
        if (wide && showMainNavigation) NavigationRail {
            Spacer(Modifier.height(16.dp))
            AppDestination.entries.forEach { destination ->
                NavigationRailItem(selected = route == destination.route, onClick = { goTop(destination) },
                    icon = { DestinationIcon(destination) }, label = { Text(destination.label) })
            }
        }
        Column(Modifier.weight(1f)) {
            NavHost(
                navController = controller, startDestination = "map", modifier = Modifier.weight(1f),
                enterTransition = { EnterTransition.None }, exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None }, popExitTransition = { ExitTransition.None },
            ) {
                composable("map") {
                    DiscoveryRoute(
                        viewModel = discoveryViewModel, selection = searchViewModel,
                        privacyReady = amapConsent && container.amapPrivacyGate.isReady,
                        imagesEnabled = imagesEnabled, darkTheme = darkTheme,
                        onImagesEnabled = onImagesEnabledChange,
                        onSearch = { goTop(AppDestination.SEARCH) },
                        onSettings = { controller.navigate("about") }, onPlan = openPlanner,
                    )
                }
                composable("search") {
                    Column {
                        JournalTopBar("搜索", actions = { IconButton(onClick = { controller.navigate("about") }) { Icon(Icons.Rounded.Settings, "关于与设置") } })
                        SearchScreen(
                            state = selection, onQueryChange = searchViewModel::updateQuery,
                            onSearch = searchViewModel::search, onAnimeToggle = { anime ->
                                val known = discovery.data.snapshot?.subjectSelection(anime.subjectId)
                                if (known != null) searchViewModel.toggleDiscoveredAnime(known) else searchViewModel.toggleAnime(anime)
                            },
                            onOpenSelection = { if (selection.selectedAnimeData.isNotEmpty()) controller.navigate("selection") },
                            onOpenAbout = { controller.navigate("about") }, showHeader = false, insetNavigationBars = false,
                            localSearchItems = {
                                localResults(
                                    query = selection.query, results = searchResults,
                                    detailsLoaded = discovery.data.detailsComplete,
                                    selectedIds = selection.selectedPointIds,
                                    onSubject = { discoveryViewModel.openSubject(it); goTop(AppDestination.MAP) },
                                    onPoint = { discoveryViewModel.openPoint(it.id); goTop(AppDestination.MAP) },
                                    onToggle = { point -> discovery.data.snapshot?.subjects?.find { it.id == point.subjectId }?.let {
                                        searchViewModel.toggleDiscoveryPoint(it.anime, point.toPilgrimagePoint().copy(id = point.rawId))
                                    } },
                                    onCity = { discoveryViewModel.openCity(it); goTop(AppDestination.MAP) },
                                )
                            },
                        )
                    }
                }
                composable("trips") {
                    TripsRoute(
                        repository = container.tourRepository, selection = selection, navigation = navigation,
                        onSettings = { controller.navigate("about") },
                        onDiscover = { goTop(AppDestination.MAP) }, onPlan = openPlanner,
                        onEditSelection = { controller.navigate("selection") },
                        onResume = { searchViewModel.openNavigation(); controller.navigate("navigation") { launchSingleTop = true } },
                        onSaved = { id -> controller.navigate("saved/$id") },
                    )
                }
                composable("selection") {
                    val map = resolveSearchMapContent(selection.combinedPilgrimageData?.points.orEmpty(), container.territoryClassifier::classify, selection.selectedMapProvider)
                    BackHandler { controller.popBackStack() }
                    PilgrimageSelectionScreen(
                        state = selection, mapProvider = map.provider, mapPoints = map.points,
                        mapProviderChoices = map.providerChoices, onMapProviderSelected = searchViewModel::selectMapProvider,
                        amapRegionDataReady = map.provider == cn.anitabi.navigator.core.model.MapProvider.AMAP,
                        amapPrivacyAndKeyReady = amapConsent && container.amapPrivacyGate.isReady,
                        onBack = { controller.popBackStack() }, onTogglePoint = searchViewModel::togglePoint,
                        onBoundsChanged = searchViewModel::updateVisibleBounds,
                        onSelectVisible = { searchViewModel.selectVisiblePoints(map.points.mapTo(mutableSetOf(), PilgrimagePoint::id)) },
                        onClearSelection = searchViewModel::clearSelection, onShowList = searchViewModel::setShowList,
                        onMapUnavailable = { map.provider?.let(searchViewModel::handleMapUnavailable) }, onPlan = openPlanner,
                    )
                }
                composable("planner/{draftId}") { entry ->
                    LaunchedEffect(entry.arguments?.getString("draftId")) {
                        plannerViewModel.restoreDraft(entry.arguments?.getString("draftId").orEmpty())
                    }
                    val close: () -> Unit = { scope.launch {
                        plannerViewModel.cancelPlanning()
                        val plannerState = plannerViewModel.state.value
                        if (plannerState.draftRecoveryError != null || plannerState.draftId == null || plannerViewModel.flushDraft() != null) {
                            controller.popBackStack()
                        }
                    }; Unit }
                    BackHandler(onBack = close)
                    PlannerRoute(plannerViewModel, close, onStartNavigation = { plan ->
                        navigationViewModel.start(plan); searchViewModel.openNavigation()
                        controller.navigate("navigation") { launchSingleTop = true }
                    })
                }
                composable("navigation") {
                    NavigationRoute(navigationViewModel, selection.combinedPilgrimageData?.points.orEmpty(), closeNavigation)
                }
                composable("saved/{id}") { entry ->
                    cn.anitabi.navigator.ui.trips.SavedTourRoute(
                        repository = container.tourRepository, tourId = entry.arguments?.getString("id").orEmpty(),
                        runningTourId = navigation.plan?.id.takeIf { navigation.isRunning },
                        onBack = { controller.popBackStack() },
                        onResume = { saved -> navigationViewModel.start(saved.plan); searchViewModel.openNavigation(); controller.navigate("navigation") },
                        onReplan = { saved -> scope.launch {
                            plannerViewModel.prepareSavedDraft(saved)?.let { id -> controller.navigate("planner/$id") }
                        } },
                    )
                }
                composable("about") {
                    BackHandler { controller.popBackStack() }
                    AboutScreen(
                        onBack = { controller.popBackStack() }, telemetryConsentController = container.telemetryConsentController,
                        amapPrivacyConsentEnabled = amapConsent,
                        onAmapPrivacyConsentChange = { enabled ->
                            container.appSettingsStore.setAmapPrivacyConsent(enabled); amapConsent = enabled
                            if (enabled) container.amapPrivacyGate.prepareIfAllowed(true) else container.amapPrivacyGate.revoke()
                        },
                        appearance = appearance, onAppearanceChange = onAppearanceChange,
                        imageMarkersEnabled = imagesEnabled, onImageMarkersEnabledChange = onImagesEnabledChange,
                    )
                }
            }
            if (!wide && showMainNavigation) NavigationBar(Modifier.testTag("main-navigation")) {
                AppDestination.entries.forEach { destination ->
                    NavigationBarItem(selected = route == destination.route, onClick = { goTop(destination) },
                        icon = { DestinationIcon(destination) }, label = { Text(destination.label) })
                }
            }
        }
    }
}

@Composable
private fun DestinationIcon(destination: AppDestination) = Icon(when (destination) {
    AppDestination.MAP -> Icons.Rounded.Map
    AppDestination.SEARCH -> Icons.Rounded.Search
    AppDestination.TRIPS -> Icons.Rounded.BookmarkBorder
}, null)

private fun LazyListScope.localResults(
    query: String, results: DiscoverySearchResults, detailsLoaded: Boolean, selectedIds: Set<String>,
    onSubject: (Long) -> Unit,
    onPoint: (cn.anitabi.navigator.data.discovery.DiscoveryPoint) -> Unit,
    onToggle: (cn.anitabi.navigator.data.discovery.DiscoveryPoint) -> Unit,
    onCity: (DiscoveryCity) -> Unit,
) {
    if (query.isBlank()) return
    item("local-scope") {
        JournalSectionHeading("地图内搜索", Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            when {
                results.complete -> "搜索已加载的作品、地点与城市"
                detailsLoaded -> "地点详情已加载，本轮更新仍待核对"
                else -> "部分地点详情尚未加载，当前搜索范围仍不完整"
            })
    }
    if (results.isEmpty) item("local-empty") {
        Text(if (results.complete) "已加载地图数据中没有匹配结果" else "已加载的数据中暂无匹配，详情补齐后结果可能增加", Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
    }
    if (results.subjects.isNotEmpty()) {
        item("local-subject-heading") { JournalSectionHeading("作品 · ${results.subjects.size}", Modifier.padding(20.dp)) }
        items(results.subjects, key = { "local-subject:${it.id}" }) { subject ->
            ListItem(headlineContent = { Text(subject.name) }, supportingContent = { Text("${subject.pointIds.size} 个地点") },
                leadingContent = { DiscoveryThumbnail(subject.anime.imageUrl, Modifier.size(48.dp)) },
                trailingContent = { TextButton(onClick = { onSubject(subject.id) }) { Text("查看") } })
        }
    }
    if (results.points.isNotEmpty()) {
        item("local-point-heading") { JournalSectionHeading("地点 · ${results.points.size}", Modifier.padding(20.dp)) }
        items(results.points, key = { "local-point:${it.id}" }) { point -> DiscoveryPointRow(point, null, point.id in selectedIds, { onPoint(point) }, { onToggle(point) }) }
    }
    if (results.cities.isNotEmpty()) {
        item("local-city-heading") { JournalSectionHeading("城市 · ${results.cities.size}", Modifier.padding(20.dp), "按作品自带城市信息定位相关地点范围") }
        items(results.cities, key = { "local-city:${it.name}" }) { city ->
            ListItem(headlineContent = { Text(city.name) }, supportingContent = { Text("${city.pointIds.size} 个相关地点") },
                trailingContent = { TextButton(onClick = { onCity(city) }) { Text("在地图查看") } })
        }
    }
}
