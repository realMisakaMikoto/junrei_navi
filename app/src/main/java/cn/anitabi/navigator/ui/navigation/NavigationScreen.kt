package cn.anitabi.navigator.ui.navigation

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.anitabi.navigator.core.model.NavigationState
import cn.anitabi.navigator.core.model.PilgrimagePoint
import cn.anitabi.navigator.core.model.TourLeg
import cn.anitabi.navigator.core.model.TourPlan
import cn.anitabi.navigator.core.model.TransitExecutionStrategy
import cn.anitabi.navigator.core.model.TravelMode
import cn.anitabi.navigator.core.model.isExternalMapNavigation
import cn.anitabi.navigator.navigation.NavigationRuntimeState
import cn.anitabi.navigator.navigation.NavigationViewModel
import cn.anitabi.navigator.navigation.ActiveTourEditUiState
import cn.anitabi.navigator.ui.map.NavigationMapView
import cn.anitabi.navigator.ui.planner.RoutePreviewMap
import cn.anitabi.navigator.ui.theme.Ink
import cn.anitabi.navigator.ui.theme.Moss
import cn.anitabi.navigator.ui.theme.MutedInk
import cn.anitabi.navigator.ui.theme.Paper
import cn.anitabi.navigator.ui.theme.Vermilion

private val WideNavigationBreakpoint = 840.dp

@Composable
fun NavigationRoute(
    viewModel: NavigationViewModel,
    availablePoints: List<PilgrimagePoint>,
    onBack: (String?) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val editState by viewModel.editState.collectAsStateWithLifecycle()
    val plan = state.plan
    BackHandler { onBack(plan?.id) }

    if (plan == null) {
        MissingNavigationState(
            message = state.errorMessage ?: "没有正在进行的巡礼路线",
            onBack = { onBack(null) },
        )
        return
    }

    Surface(color = Paper, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            NavigationTopBar(
                plan = plan,
                navigationState = state.progress?.state,
                onBack = { onBack(plan.id) },
            )
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val useSidePanel = maxWidth >= WideNavigationBreakpoint || maxWidth > maxHeight
                val externalMap = plan.executionStrategy.isExternalMapNavigation()
                val hasTransitJourney =
                    plan.mode == TravelMode.TRANSIT && plan.legs.isNotEmpty() && !externalMap
                val compactPanelHeight = minOf(maxHeight * 0.5f, 360.dp)

                if (useSidePanel) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        NavigationCanvas(
                            plan = plan,
                            state = state,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                        NavigationDetailPanel(
                            plan = plan,
                            state = state,
                            onStop = {
                                viewModel.stop()
                                if (!externalMap) onBack(plan.id)
                            },
                            onArrived = viewModel::markArrived,
                            onRefreshTransit = viewModel::refreshTransit,
                            onOpenExternalLeg = viewModel::openCurrentExternalLeg,
                            onStartNextExternalLeg = viewModel::startNextExternalLeg,
                            onPauseExternal = viewModel::pauseExternal,
                            onResumeExternal = viewModel::resumeExternal,
                            onEditFuture = { viewModel.openFutureEditor(availablePoints) },
                            modifier = Modifier
                                .fillMaxHeight()
                                .widthIn(min = 360.dp, max = 440.dp),
                            transitDetailsScrollable = hasTransitJourney,
                            fillAvailableHeight = true,
                        )
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        NavigationCanvas(
                            plan = plan,
                            state = state,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                        NavigationDetailPanel(
                            plan = plan,
                            state = state,
                            onStop = {
                                viewModel.stop()
                                if (!externalMap) onBack(plan.id)
                            },
                            onArrived = viewModel::markArrived,
                            onRefreshTransit = viewModel::refreshTransit,
                            onOpenExternalLeg = viewModel::openCurrentExternalLeg,
                            onStartNextExternalLeg = viewModel::startNextExternalLeg,
                            onPauseExternal = viewModel::pauseExternal,
                            onResumeExternal = viewModel::resumeExternal,
                            onEditFuture = { viewModel.openFutureEditor(availablePoints) },
                            modifier = if (hasTransitJourney) {
                                Modifier
                                    .fillMaxWidth()
                                    .height(compactPanelHeight)
                            } else {
                                Modifier.fillMaxWidth()
                            },
                            transitDetailsScrollable = hasTransitJourney,
                            fillAvailableHeight = hasTransitJourney,
                        )
                    }
                }
            }
        }
    }
    if (editState.isOpen) {
        ActiveFutureEditorDialog(
            state = editState,
            onDismiss = viewModel::closeFutureEditor,
            onAdd = viewModel::addFuturePoint,
            onRemove = viewModel::removeFuturePoint,
            onMove = viewModel::moveFuturePoint,
            onSave = viewModel::saveFuturePoints,
        )
    }
}

@Composable
private fun MissingNavigationState(message: String, onBack: () -> Unit) {
    Surface(color = Paper, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.titleMedium,
                color = Ink,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
            OutlinedButton(onClick = onBack, modifier = Modifier.padding(top = 16.dp).heightIn(min = 50.dp)) {
                Text("返回")
            }
        }
    }
}

@Composable
private fun NavigationTopBar(
    plan: TourPlan,
    navigationState: NavigationState?,
    onBack: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(
                    text = plan.anime.nameCn ?: plan.anime.name,
                    color = Ink,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "巡礼导航",
                    color = MutedInk,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            navigationState?.let { currentState ->
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.semantics {
                        stateDescription = "导航状态：${currentState.displayName()}"
                    },
                ) {
                    Text(
                        text = currentState.displayName(),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    )
                }
            }
            Spacer(Modifier.size(4.dp))
        }
    }
}

@Composable
private fun NavigationCanvas(
    plan: TourPlan,
    state: NavigationRuntimeState,
    modifier: Modifier = Modifier,
) {
    when {
        plan.legs.isEmpty() && !state.isRunning -> {
            SavedTourRecoveryPanel(
                plan = plan,
                completedPointIds = state.progress?.completedPointIds.orEmpty(),
                modifier = modifier,
            )
        }

        plan.executionStrategy.isExternalMapNavigation() || plan.mode == TravelMode.TRANSIT -> {
            RoutePreviewMap(
                plan = plan,
                currentLocation = state.currentLocation,
                followCurrentLocation = state.isRunning,
                modifier = modifier,
            )
        }

        else -> {
            NavigationMapView(
                onMapReady = { map -> map.uiSettings.isMapToolbarEnabled = false },
                navigationUiEnabled = true,
                modifier = modifier,
            )
        }
    }
}

@Composable
internal fun NavigationDetailPanel(
    plan: TourPlan,
    state: NavigationRuntimeState,
    onStop: () -> Unit,
    onArrived: () -> Unit,
    onRefreshTransit: () -> Unit,
    onOpenExternalLeg: () -> Unit,
    onStartNextExternalLeg: () -> Unit,
    onPauseExternal: () -> Unit,
    onResumeExternal: () -> Unit,
    onEditFuture: () -> Unit,
    modifier: Modifier = Modifier,
    transitDetailsScrollable: Boolean,
    fillAvailableHeight: Boolean,
) {
    val activeLeg = plan.legs.getOrNull(state.progress?.legIndex ?: 0)
    val targetName = activeLeg?.destinationPointId?.let { pointId ->
        plan.selectedPoints.firstOrNull { it.id == pointId }?.name
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        shadowElevation = 5.dp,
        modifier = modifier.testTag("navigation-control-panel"),
    ) {
        Column(
            modifier = if (fillAvailableHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
        ) {
            if (fillAvailableHeight) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    NavigationSummary(
                        plan = plan,
                        state = state,
                        targetName = targetName,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                    )
                    if (transitDetailsScrollable) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        TransitJourneyDetails(
                            activeLeg = activeLeg,
                            legIndex = state.progress?.legIndex ?: 0,
                            totalLegs = plan.legs.size,
                        )
                        OutlinedButton(
                            onClick = onRefreshTransit,
                            enabled = !state.isRerouting,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 8.dp)
                                .heightIn(min = 48.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("重算剩余公交行程")
                        }
                    }
                }
            } else {
                NavigationSummary(
                    plan = plan,
                    state = state,
                    targetName = targetName,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                )
            }

            if (
                state.progress?.state?.let { progressState ->
                    progressState !in setOf(
                        NavigationState.PLANNED,
                        NavigationState.COMPLETED,
                        NavigationState.ENDED,
                    ) && plan.legs.indices
                        .drop(state.progress.legIndex.coerceAtLeast(0))
                        .any { plan.legs[it].destinationPointId != null }
                } == true
            ) {
                OutlinedButton(
                    onClick = onEditFuture,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                        .heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("编辑后续点")
                }
            }
            NavigationActions(
                plan = plan,
                state = state,
                onStop = onStop,
                onArrived = onArrived,
                onOpenExternalLeg = onOpenExternalLeg,
                onStartNextExternalLeg = onStartNextExternalLeg,
                onPauseExternal = onPauseExternal,
                onResumeExternal = onResumeExternal,
            )
        }
    }
}

@Composable
private fun NavigationSummary(
    plan: TourPlan,
    state: NavigationRuntimeState,
    targetName: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Surface(
                color = Vermilion.copy(alpha = 0.1f),
                shape = RoundedCornerShape(10.dp),
            ) {
                Icon(
                    Icons.Rounded.MyLocation,
                    contentDescription = null,
                    tint = Vermilion,
                    modifier = Modifier.padding(8.dp).size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    text = state.instruction,
                    style = MaterialTheme.typography.titleLarge,
                    color = Ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Text(
                    text = (if (
                        plan.executionStrategy.isExternalMapNavigation()
                    ) {
                        "直线距离 ${state.currentTargetDistanceMeters?.let(::formatDistance) ?: "等待定位"}"
                    } else {
                        "剩余约 ${formatDistance(state.remainingDistanceMeters)}"
                    }) + "  ·  " +
                        "第 ${(state.progress?.legIndex ?: 0) + 1}/${plan.legs.size.coerceAtLeast(1)} 段",
                    color = MutedInk,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Text(
            text = when {
                state.progress?.state == NavigationState.COMPLETED -> "全部巡礼点已完成"
                state.progress?.state == NavigationState.ENDED -> "本次巡礼已结束，顺序和进度已保留"
                targetName != null -> "当前目标：$targetName"
                plan.mode == TravelMode.TRANSIT -> "当前目标：完成本换乘段"
                else -> "当前目标：返回起点"
            },
            color = MutedInk,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (
            state.isRerouting &&
            !plan.executionStrategy.isExternalMapNavigation()
        ) {
            Text(
                "检测到持续偏航，正在重算剩余路线…",
                color = Vermilion,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        state.errorMessage?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        Text(
            text = (when {
                plan.executionStrategy == TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN ->
                    "Google Maps 外部分段公交"
                plan.executionStrategy == TransitExecutionStrategy.EXTERNAL_AMAP_MAINLAND ->
                    "高德地图外部分段${plan.mode.externalModeLabel()}"
                plan.mode == TravelMode.TRANSIT -> "Google Routes"
                else -> "Google Navigation"
            }) +
                plan.legs.firstOrNull()?.source?.let { "  ·  $it" }.orEmpty(),
            color = MutedInk,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun TransitJourneyDetails(
    activeLeg: TourLeg?,
    legIndex: Int,
    totalLegs: Int,
    modifier: Modifier = Modifier,
) {
    val transit = activeLeg?.transit
    val isWalkingConnector = activeLeg?.mode == TravelMode.WALK

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (isWalkingConnector) {
                    Icons.AutoMirrored.Rounded.DirectionsWalk
                } else {
                    Icons.Rounded.DirectionsBus
                },
                contentDescription = null,
                tint = Vermilion,
            )
            Column(modifier = Modifier.padding(start = 10.dp)) {
                Text(
                    text = buildString {
                        append(if (isWalkingConnector) "步行接驳" else "公交行程")
                        append(" ${legIndex + 1}/$totalLegs")
                        transit?.line?.let { append("  ·  $it") }
                    },
                    color = Ink,
                    style = MaterialTheme.typography.titleMedium,
                )
                transit?.direction?.let { direction ->
                    Text("开往 $direction", color = MutedInk, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (transit != null) {
            TransitStop(
                label = "上车",
                name = transit.departureStop ?: "出发站",
                details = listOfNotNull(transit.departureTime, transit.departurePlatform?.let { "站台 $it" }),
                emphasized = true,
            )
            transit.stopCount?.let { stopCount ->
                Text("途经 $stopCount 站", color = MutedInk, style = MaterialTheme.typography.bodyMedium)
            }
            transit.intermediateStops.forEach { stop ->
                TransitStop(label = "途经", name = stop, details = emptyList(), emphasized = false)
            }
            TransitStop(
                label = "下车",
                name = transit.arrivalStop ?: "到达站",
                details = listOfNotNull(transit.arrivalTime, transit.arrivalPlatform?.let { "站台 $it" }),
                emphasized = true,
            )
            if (transit.cancelled) {
                Text(
                    "该班次已取消，正在重算剩余行程",
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                )
            } else if (transit.realtime) {
                Text("含实时班次信息", color = Moss, style = MaterialTheme.typography.labelMedium)
            }
        } else {
            Text(
                text = if (isWalkingConnector) "步行前往下一段行程" else "正在获取本段公交信息",
                color = MutedInk,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun TransitStop(
    label: String,
    name: String,
    details: List<String>,
    emphasized: Boolean,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(if (emphasized) 10.dp else 7.dp)
                .background(if (emphasized) Vermilion else MutedInk, CircleShape),
        )
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(label, color = MutedInk, style = MaterialTheme.typography.labelSmall)
            Text(
                text = name,
                color = Ink,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (details.isNotEmpty()) {
                Text(details.joinToString("  ·  "), color = MutedInk, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun NavigationActions(
    plan: TourPlan,
    state: NavigationRuntimeState,
    onStop: () -> Unit,
    onArrived: () -> Unit,
    onOpenExternalLeg: () -> Unit,
    onStartNextExternalLeg: () -> Unit,
    onPauseExternal: () -> Unit,
    onResumeExternal: () -> Unit,
) {
    val externalMap = plan.executionStrategy.isExternalMapNavigation()
    val progress = state.progress
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding(),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (externalMap && progress != null) {
            val terminal = progress.state in setOf(NavigationState.COMPLETED, NavigationState.ENDED)
            if (progress.isPaused && state.errorMessage?.contains("悬浮窗或可见的导航通知") == true) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    "package:${context.packageName}".toUri(),
                                ),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("悬浮窗设置") }
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("通知设置") }
                }
            }
            if (!terminal && !progress.isPaused) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    when (progress.state) {
                        NavigationState.DWELLING -> Button(
                            onClick = onStartNextExternalLeg,
                            modifier = Modifier.weight(1f).heightIn(min = 50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Vermilion),
                        ) { Text("提前离开") }
                        NavigationState.NEXT_STOP -> Button(
                            onClick = onStartNextExternalLeg,
                            modifier = Modifier.weight(1f).heightIn(min = 50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Vermilion),
                        ) { Text("开始下一段") }
                        NavigationState.NAVIGATING,
                        NavigationState.ARRIVING,
                        NavigationState.PLANNED,
                        -> Button(
                            onClick = onOpenExternalLeg,
                            modifier = Modifier.weight(1f).heightIn(min = 50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Vermilion),
                        ) { Text("打开本段") }
                        else -> Unit
                    }
                    if (
                        state.isRunning &&
                        progress.state in setOf(NavigationState.NAVIGATING, NavigationState.ARRIVING)
                    ) {
                        OutlinedButton(
                            onClick = onArrived,
                            modifier = Modifier.weight(1f).heightIn(min = 50.dp),
                        ) { Text("确认到达") }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val needsResume = externalTransitNeedsResume(state.isRunning, progress.isPaused)
                if (!terminal) {
                    OutlinedButton(
                        onClick = if (needsResume) onResumeExternal else onPauseExternal,
                        modifier = Modifier.weight(1f).heightIn(min = 50.dp),
                    ) { Text(if (needsResume) "恢复行程" else "暂停行程") }
                }
                OutlinedButton(
                    onClick = onStop,
                    enabled = !terminal,
                    modifier = Modifier.weight(1f).heightIn(min = 50.dp),
                ) { Text("结束行程") }
            }
            return@Column
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onStop,
                modifier = Modifier.weight(1f).heightIn(min = 50.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("结束导航")
            }
            Button(
                onClick = onArrived,
                enabled = state.isRunning && state.progress?.state == NavigationState.NAVIGATING,
                modifier = Modifier.weight(1f).heightIn(min = 50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Vermilion),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Rounded.Flag, contentDescription = null)
                Text("确认到达", modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

internal fun externalTransitNeedsResume(isRunning: Boolean, isPaused: Boolean): Boolean =
    isPaused || !isRunning

@Composable
private fun ActiveFutureEditorDialog(
    state: ActiveTourEditUiState,
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!state.isSaving) onDismiss() },
        title = { Text("编辑后续巡礼点") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "已完成点和当前目标已锁定；这里只会重建当前目标之后的分段。",
                    color = MutedInk,
                    style = MaterialTheme.typography.bodyMedium,
                )
                state.errorMessage?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                }
                Text("后续顺序", fontWeight = FontWeight.SemiBold)
                if (state.futurePoints.isEmpty()) {
                    Text("没有后续巡礼点", color = MutedInk)
                }
                state.futurePoints.forEachIndexed { index, point ->
                    val fixedEnd = point.id == state.fixedEndPointId
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            "${index + 1}. ${point.name}${if (fixedEnd) "（固定终点）" else ""}",
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(
                            onClick = { onMove(index, -1) },
                            enabled = index > 0 && !fixedEnd && !state.isSaving,
                        ) {
                            Text("上移")
                        }
                        TextButton(
                            onClick = { onMove(index, 1) },
                            enabled = index < state.futurePoints.lastIndex &&
                                state.futurePoints[index + 1].id != state.fixedEndPointId &&
                                !state.isSaving,
                        ) { Text("下移") }
                        TextButton(
                            onClick = { onRemove(point.id) },
                            enabled = !fixedEnd && !state.isSaving,
                        ) {
                            Text("删除")
                        }
                    }
                }
                if (state.addablePoints.isNotEmpty()) {
                    HorizontalDivider()
                    Text("可插入点", fontWeight = FontWeight.SemiBold)
                    state.addablePoints.forEach { point ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                point.name,
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(onClick = { onAdd(point.id) }, enabled = !state.isSaving) {
                                Text("插入")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = !state.isSaving) {
                Text(if (state.isSaving) "保存中…" else "保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.isSaving) { Text("取消") }
        },
    )
}

@Composable
private fun SavedTourRecoveryPanel(
    plan: TourPlan,
    completedPointIds: Set<String>,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.background(Paper),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
    ) {
        item {
            Text("已保存的巡礼顺序", style = MaterialTheme.typography.titleLarge, color = Ink)
            Text(
                "开始导航后将按此顺序前往",
                style = MaterialTheme.typography.bodyMedium,
                color = MutedInk,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )
        }
        itemsIndexed(plan.orderedPoints, key = { _, point -> point.id }) { index, point ->
            val completed = point.id in completedPointIds
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { stateDescription = if (completed) "已完成" else "待前往" }
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    color = if (completed) Moss.copy(alpha = 0.12f) else Vermilion.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(
                        1.dp,
                        if (completed) Moss.copy(alpha = 0.25f) else Vermilion.copy(alpha = 0.2f),
                    ),
                ) {
                    Text(
                        "${index + 1}",
                        color = if (completed) Moss else Vermilion,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    )
                }
                Text(
                    point.name,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    color = Ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(if (completed) "已完成" else "待前往", color = if (completed) Moss else MutedInk)
            }
            if (index < plan.orderedPoints.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

private fun NavigationState.displayName(): String = when (this) {
    NavigationState.PLANNED -> "待出发"
    NavigationState.NAVIGATING -> "前往中"
    NavigationState.ARRIVING -> "已抵达"
    NavigationState.DWELLING -> "停留中"
    NavigationState.NEXT_STOP -> "下一站"
    NavigationState.COMPLETED -> "已完成"
    NavigationState.ENDED -> "已结束"
}

private fun formatDistance(meters: Double): String =
    if (meters >= 1000.0) "%.1f km".format(meters / 1000.0) else "${meters.toInt()} m"

private fun TravelMode.externalModeLabel(): String = when (this) {
    TravelMode.DRIVE -> "驾车导航"
    TravelMode.BIKE -> "骑行导航"
    TravelMode.WALK -> "步行导航"
    TravelMode.TRANSIT -> "公交路线"
}
