package cn.anitabi.navigator.ui.trips

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cn.anitabi.navigator.core.model.NavigationState
import cn.anitabi.navigator.data.repository.SavedTour
import cn.anitabi.navigator.data.repository.TourRepository
import cn.anitabi.navigator.navigation.NavigationRuntimeState
import cn.anitabi.navigator.ui.components.JournalSectionHeading
import cn.anitabi.navigator.ui.components.JournalTopBar
import cn.anitabi.navigator.ui.search.SearchUiState
import kotlinx.coroutines.CancellationException

@Composable
fun TripsRoute(
    repository: TourRepository,
    selection: SearchUiState,
    navigation: NavigationRuntimeState,
    onSettings: () -> Unit,
    onDiscover: () -> Unit,
    onPlan: () -> Unit,
    onEditSelection: () -> Unit,
    onResume: () -> Unit,
    onSaved: (String) -> Unit,
) {
    var saved by remember { mutableStateOf<List<SavedTour>>(emptyList()) }
    var error by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(repository, refresh, navigation.plan?.id, navigation.progress?.state) {
        loading = true
        try { saved = repository.getSavedTours(); error = false }
        catch (exception: CancellationException) { throw exception }
        catch (_: Exception) { error = true }
        finally { loading = false }
    }
    TripsScreen(saved, selection, navigation, loading, error,
        onRetry = { refresh++ }, onSettings, onDiscover, onPlan, onEditSelection, onResume, onSaved)
}

@Composable
internal fun TripsScreen(
    saved: List<SavedTour>, selection: SearchUiState, navigation: NavigationRuntimeState,
    loading: Boolean, error: Boolean, onRetry: () -> Unit, onSettings: () -> Unit,
    onDiscover: () -> Unit, onPlan: () -> Unit, onEditSelection: () -> Unit,
    onResume: () -> Unit, onSaved: (String) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize().testTag("trips-screen")) {
        Column {
            JournalTopBar("我的行程", actions = { IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "关于与设置") } })
            LazyColumn(
                Modifier.widthIn(max = 840.dp).fillMaxWidth().align(Alignment.CenterHorizontally),
                contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                val running = navigation.plan?.takeIf {
                    navigation.progress?.state !in setOf(NavigationState.COMPLETED, NavigationState.ENDED)
                }
                if (running != null) item("ongoing") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        JournalSectionHeading("进行中的巡礼")
                        Text(running.anime.nameCn ?: running.anime.name, style = MaterialTheme.typography.titleLarge)
                        Text("${navigation.progress?.completedPointIds?.size ?: 0} / ${running.selectedPoints.size} 个地点已完成", style = MaterialTheme.typography.bodyMedium)
                        navigation.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Button(onClick = onResume) { Text("返回导航") }
                    }
                    HorizontalDivider(Modifier.padding(top = 20.dp))
                }
                item("draft") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        JournalSectionHeading("这次想去", description = "${selection.selectedAnimes.size} 部作品 · ${selection.selectedPointIds.size} 个已选地点")
                        if (selection.selectedPointIds.isEmpty()) {
                            Text("在地图和搜索中收藏想去的地点，再一起规划路线。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick = onDiscover) { Text("去地图发现") }
                        } else {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = onPlan, enabled = selection.selectedPointIds.size >= 2) { Text("规划行程") }
                                TextButton(onClick = onEditSelection) { Text("编辑已选地点") }
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(top = 20.dp))
                }
                item("saved-heading") { JournalSectionHeading("已保存行程", description = "地点、顺序与进度保存在本机，路线使用时重新获取。") }
                if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (error) item {
                    Column { Text("暂时无法读取已保存行程", color = MaterialTheme.colorScheme.error); TextButton(onClick = onRetry) { Text("重试") } }
                } else if (!loading && saved.isEmpty()) item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.BookmarkBorder, null)
                        Text("生成路线后，会在这里留下你的巡礼记录。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(saved.filterNot { it.plan.id == running?.id }, key = { it.plan.id }) { tour ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tour.storedTour.displayAnime.nameCn ?: tour.storedTour.displayAnime.name, style = MaterialTheme.typography.titleMedium)
                        Text("${tour.storedTour.selectedPoints.size} 个地点 · ${tour.storedTour.completedPointIds.size} 个已完成", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { onSaved(tour.plan.id) }) { Text("查看行程") }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun SavedTourRoute(
    repository: TourRepository, tourId: String,
    runningTourId: String? = null,
    onBack: () -> Unit, onResume: (SavedTour) -> Unit, onReplan: (SavedTour) -> Unit,
) {
    var saved by remember(tourId) { mutableStateOf<SavedTour?>(null) }
    var loaded by remember(tourId) { mutableStateOf(false) }
    var failed by remember(tourId) { mutableStateOf(false) }
    LaunchedEffect(tourId) {
        try { saved = repository.get(tourId) }
        catch (exception: CancellationException) { throw exception }
        catch (_: Exception) { failed = true }
        finally { loaded = true }
    }
    BackHandler(onBack = onBack)
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column {
            JournalTopBar("行程记录", onBack)
            val tour = saved
            if (tour == null) {
                Text(if (!loaded) "正在读取行程" else if (failed) "行程恢复失败，请返回后重试" else "这份行程已不可用", Modifier.padding(20.dp))
            } else LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(20.dp)) {
                item {
                    Text(tour.storedTour.displayAnime.nameCn ?: tour.storedTour.displayAnime.name, style = MaterialTheme.typography.headlineSmall)
                    Text("${tour.storedTour.completedPointIds.size} / ${tour.storedTour.selectedPoints.size} 个地点已完成", Modifier.padding(vertical = 12.dp))
                    if (tour.routeNeedsRefresh) Text("地点和顺序已恢复；时间、距离与路线需要联网刷新。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val inProgress = tour.storedTour.navigationState !in setOf(NavigationState.PLANNED, NavigationState.COMPLETED, NavigationState.ENDED)
                    val anotherRunning = runningTourId != null && runningTourId != tour.plan.id
                    if (anotherRunning) Text("另一份行程正在导航，请先返回并结束它。", Modifier.padding(vertical = 12.dp))
                    if (inProgress) {
                        Button(onClick = { onResume(tour) }, enabled = !anotherRunning && tour.routingError == null,
                            modifier = Modifier.padding(vertical = 12.dp)) { Text("继续行程") }
                    } else {
                        Button(onClick = { onReplan(tour) }, enabled = runningTourId == null && tour.plan.selectedPoints.size >= 2 && tour.routingError == null,
                            modifier = Modifier.padding(vertical = 12.dp)) { Text("按原顺序刷新路线") }
                    }
                    tour.routingError?.let { Text("当前地区资料无法安全恢复这份行程，请更新应用后重试。", color = MaterialTheme.colorScheme.error) }
                    HorizontalDivider()
                }
                itemsIndexed(tour.plan.orderedPoints, key = { _, point -> point.id }) { index, point ->
                    ListItem(headlineContent = { Text(point.name) }, supportingContent = {
                        Text(if (point.id in tour.storedTour.completedPointIds) "已完成" else "待前往")
                    }, leadingContent = { Text((index + 1).toString()) })
                }
            }
        }
    }
}
