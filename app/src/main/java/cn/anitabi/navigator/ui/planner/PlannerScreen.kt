package cn.anitabi.navigator.ui.planner

import android.annotation.SuppressLint
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import android.text.format.DateFormat
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DirectionsBike
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.anitabi.navigator.navigation.AndroidLocationProvider
import cn.anitabi.navigator.navigation.GoogleMapsTransitLauncher
import cn.anitabi.navigator.navigation.NavigationControlAvailability
import cn.anitabi.navigator.navigation.requestGoogleNavigationTerms
import cn.anitabi.navigator.core.model.EndPolicy
import cn.anitabi.navigator.core.model.MapProvider
import cn.anitabi.navigator.core.model.PilgrimagePoint
import cn.anitabi.navigator.core.model.RouteObjective
import cn.anitabi.navigator.core.model.TourPlan
import cn.anitabi.navigator.core.model.TourLeg
import cn.anitabi.navigator.core.model.TransitExecutionStrategy
import cn.anitabi.navigator.core.model.TransitRoutingPreference
import cn.anitabi.navigator.core.model.TransitTimeMode
import cn.anitabi.navigator.core.model.TransitTravelMode
import cn.anitabi.navigator.core.model.TravelMode
import cn.anitabi.navigator.core.model.isExternalMapNavigation
import cn.anitabi.navigator.core.routing.isAmapExternalFallback
import cn.anitabi.navigator.ui.theme.Ink
import cn.anitabi.navigator.ui.theme.MutedInk
import cn.anitabi.navigator.ui.theme.NumericTextStyle
import cn.anitabi.navigator.ui.theme.Sand
import cn.anitabi.navigator.ui.theme.Vermilion
import kotlin.math.abs
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import android.content.pm.PackageManager

@SuppressLint("InlinedApi")
@Composable
fun PlannerRoute(
    viewModel: PlannerViewModel,
    onBack: () -> Unit,
    onStartNavigation: (TourPlan) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingNavigationPlan by remember { mutableStateOf<TourPlan?>(null) }
    var navigationTermsRequestInFlight by remember { mutableStateOf(false) }
    val startAfterPermissions: (TourPlan) -> Unit = { pending ->
        if (!requiresGoogleNavigationTerms(pending)) {
            onStartNavigation(pending)
        } else {
            val activity = context.findActivity()
            if (activity == null) {
                viewModel.navigationPermissionDenied("无法打开 Google 导航条款，请重新打开应用后再试")
            } else if (!navigationTermsRequestInFlight) {
                navigationTermsRequestInFlight = true
                requestGoogleNavigationTerms(
                    activity = activity,
                    onReady = {
                        navigationTermsRequestInFlight = false
                        onStartNavigation(pending)
                    },
                    onError = { message ->
                        navigationTermsRequestInFlight = false
                        viewModel.navigationPermissionDenied(message)
                    },
                )
            }
        }
    }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.any { it }) viewModel.setUseCurrentLocation() else viewModel.locationPermissionDenied()
    }
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val pending = pendingNavigationPlan
        if (pending == null) return@rememberLauncherForActivityResult
        val hasFineLocation = AndroidLocationProvider.hasFineLocationPermission(context)
        val hasControl = NavigationControlAvailability.hasExternalTransitControl(context)
        if (hasFineLocation && hasControl) {
            pendingNavigationPlan = null
            startAfterPermissions(pending)
        } else {
            viewModel.navigationPermissionDenied(
                externalTransitPermissionError(hasFineLocation, hasControl, pending.executionStrategy)
                    ?: externalMapStartFailureMessage(pending.executionStrategy),
            )
        }
    }
    val navigationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val pending = pendingNavigationPlan
        if (pending == null) return@rememberLauncherForActivityResult
        if (pending.executionStrategy.isExternalMapNavigation()) {
            val hasFineLocation = AndroidLocationProvider.hasFineLocationPermission(context)
            val hasControl = NavigationControlAvailability.hasExternalTransitControl(context)
            when {
                hasFineLocation && hasControl -> {
                    pendingNavigationPlan = null
                    startAfterPermissions(pending)
                }
                hasFineLocation -> {
                    viewModel.navigationPermissionDenied(externalMapControlMessage(pending.executionStrategy))
                    overlayPermissionLauncher.launch(overlaySettingsIntent(context))
                }
                else -> viewModel.navigationPermissionDenied(
                    externalTransitPermissionError(hasFineLocation, hasControl, pending.executionStrategy)
                        ?: externalMapStartFailureMessage(pending.executionStrategy),
                )
            }
        } else {
            pendingNavigationPlan = null
            val hasLocation = AndroidLocationProvider.hasLocationPermission(context)
            val hasNotifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            val permissionError = navigationPermissionError(hasLocation, hasNotifications)
            if (permissionError == null) {
                startAfterPermissions(pending)
            } else {
                viewModel.navigationPermissionDenied(permissionError)
            }
        }
    }
    val plan = state.plan
    if (plan == null) {
        PlannerSettingsScreen(
            state = state,
            onBack = onBack,
            onModeChange = viewModel::setMode,
            onObjectiveChange = viewModel::setObjective,
            onEndPolicyChange = viewModel::setEndPolicy,
            onStartChange = viewModel::setStartPoint,
            onUseCurrentLocation = {
                if (AndroidLocationProvider.hasLocationPermission(context)) {
                    viewModel.setUseCurrentLocation()
                } else {
                    locationPermissionLauncher.launch(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    )
                }
            },
            onFixedEndChange = viewModel::setFixedEndPoint,
            onTransitScheduleChange = viewModel::setTransitSchedule,
            onTransitPreferenceChange = viewModel::setTransitRoutingPreference,
            onTransitTravelModeToggle = viewModel::toggleTransitTravelMode,
            onDwellChange = viewModel::setDwellMinutes,
            onGenerate = viewModel::generate,
            onUseAmapExternalFallback = viewModel::useAmapExternalFallback,
        )
    } else {
        RoutePreviewScreen(
            state = state,
            plan = plan,
            onBack = viewModel::clearPlan,
            onMove = viewModel::moveDraft,
            onApplyOrder = viewModel::applyManualOrder,
            onUseAmapExternalFallback = viewModel::useAmapExternalFallback,
            onStartNavigation = {
                if (plan.executionStrategy.isExternalMapNavigation()) {
                    NavigationControlAvailability.ensureChannel(context)
                    val hasFineLocation = AndroidLocationProvider.hasFineLocationPermission(context)
                    val hasControl = NavigationControlAvailability.hasExternalTransitControl(context)
                    if (hasFineLocation && hasControl) {
                        startAfterPermissions(plan)
                    } else {
                        pendingNavigationPlan = plan
                        val notificationPermissionMissing =
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS,
                                ) != PackageManager.PERMISSION_GRANTED
                        val permissions = externalTransitRuntimePermissions(
                            hasFineLocation = hasFineLocation,
                            requestNotificationPermission = !hasControl && notificationPermissionMissing,
                        )
                        if (permissions.isNotEmpty()) {
                            navigationPermissionLauncher.launch(permissions.toTypedArray())
                        } else {
                            viewModel.navigationPermissionDenied(
                                externalMapControlMessage(plan.executionStrategy),
                            )
                            overlayPermissionLauncher.launch(overlaySettingsIntent(context))
                        }
                    }
                } else {
                    val hasLocation = AndroidLocationProvider.hasLocationPermission(context)
                    val hasNotifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED
                    if (hasLocation && hasNotifications) {
                        startAfterPermissions(plan)
                    } else {
                        pendingNavigationPlan = plan
                        val permissions = buildList {
                            if (!hasLocation) {
                                add(Manifest.permission.ACCESS_FINE_LOCATION)
                                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                            }
                            if (!hasNotifications) {
                                add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                        navigationPermissionLauncher.launch(permissions.toTypedArray())
                    }
                }
            },
        )
    }
}

@SuppressLint("InlinedApi")
internal fun externalTransitRuntimePermissions(
    hasFineLocation: Boolean,
    requestNotificationPermission: Boolean,
): List<String> = buildList {
    if (!hasFineLocation) {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    if (requestNotificationPermission) add(Manifest.permission.POST_NOTIFICATIONS)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

internal fun navigationPermissionError(
    hasLocation: Boolean,
    hasNotifications: Boolean,
): String? = when {
    !hasLocation && !hasNotifications -> "需要定位和通知权限才能开始导航"
    !hasLocation -> "需要定位权限才能开始导航"
    !hasNotifications -> "需要通知权限才能在锁屏和后台持续导航"
    else -> null
}

internal fun externalTransitPermissionError(
    hasFineLocation: Boolean,
    hasVisibleControl: Boolean,
    strategy: TransitExecutionStrategy = TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN,
): String? = when {
    !hasFineLocation && !hasVisibleControl ->
        "需要精确定位，并至少启用悬浮窗或可见的导航通知"
    !hasFineLocation -> when (strategy) {
        TransitExecutionStrategy.EXTERNAL_AMAP_MAINLAND -> "需要精确定位权限才能开始高德地图分段导航"
        TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN -> "需要精确定位权限才能开始日本公交分段导航"
        TransitExecutionStrategy.IN_APP_GOOGLE_ROUTES -> "需要精确定位权限才能开始外部分段导航"
    }
    !hasVisibleControl -> "请至少启用悬浮窗或可见的导航通知"
    else -> null
}

internal fun requiresGoogleNavigationTerms(plan: TourPlan): Boolean =
    plan.mapProvider == MapProvider.GOOGLE &&
        plan.executionStrategy == TransitExecutionStrategy.IN_APP_GOOGLE_ROUTES &&
        plan.mode != TravelMode.TRANSIT

private fun externalMapControlMessage(strategy: TransitExecutionStrategy): String = when (strategy) {
    TransitExecutionStrategy.EXTERNAL_AMAP_MAINLAND ->
        "通知当前不可见，请授权悬浮窗作为高德地图分段导航控制入口"
    TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN ->
        "通知当前不可见，请授权悬浮窗作为日本公交控制入口"
    TransitExecutionStrategy.IN_APP_GOOGLE_ROUTES ->
        "通知当前不可见，请授权悬浮窗作为外部分段导航控制入口"
}

private fun externalMapStartFailureMessage(strategy: TransitExecutionStrategy): String = when (strategy) {
    TransitExecutionStrategy.EXTERNAL_AMAP_MAINLAND -> "无法开始高德地图分段导航"
    TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN -> "无法开始日本公交分段导航"
    TransitExecutionStrategy.IN_APP_GOOGLE_ROUTES -> "无法开始外部分段导航"
}

private fun overlaySettingsIntent(context: Context): Intent =
    Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        "package:${context.packageName}".toUri(),
    )

private enum class PlannerWidthClass {
    Compact,
    Medium,
    Expanded,
}

private fun plannerWidthClass(width: Dp): PlannerWidthClass = when {
    width < 600.dp -> PlannerWidthClass.Compact
    width < 840.dp -> PlannerWidthClass.Medium
    else -> PlannerWidthClass.Expanded
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlannerSettingsScreen(
    state: PlannerUiState,
    onBack: () -> Unit,
    onModeChange: (TravelMode) -> Unit,
    onObjectiveChange: (RouteObjective) -> Unit,
    onEndPolicyChange: (EndPolicy) -> Unit,
    onStartChange: (String) -> Unit,
    onUseCurrentLocation: () -> Unit,
    onFixedEndChange: (String) -> Unit,
    onTransitScheduleChange: (TransitTimeMode, LocalDate, LocalTime) -> Unit,
    onTransitPreferenceChange: (TransitRoutingPreference) -> Unit,
    onTransitTravelModeToggle: (TransitTravelMode) -> Unit,
    onDwellChange: (String) -> Unit,
    onGenerate: () -> Unit,
    onUseAmapExternalFallback: () -> Unit = {},
    onOpenGoogleMaps: ((UnavailableRouteSegment) -> Boolean)? = null,
) {
    val context = LocalContext.current
    val googleMapsLauncher = remember(context) { GoogleMapsTransitLauncher(context) }
    var googleMapsLaunchFailed by remember(state.unavailableRouteSegment) { mutableStateOf(false) }
    val openGoogleMaps = onOpenGoogleMaps ?: { segment: UnavailableRouteSegment ->
        googleMapsLauncher.launch(segment.origin.coordinate, segment.destination.coordinate)
    }
    val zoneId = remember(state.transitZoneId) {
        runCatching { ZoneId.of(state.transitZoneId) }.getOrDefault(ZoneOffset.UTC)
    }
    val now = LocalDateTime.now(zoneId)
    val today = now.toLocalDate()
    val selectableTransitDates = remember(today) {
        val firstDate = today.minusDays(7)
        val firstDateMillis = firstDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val lastDateMillis = today.plusDays(100).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                utcTimeMillis in firstDateMillis..lastDateMillis

            override fun isSelectableYear(year: Int): Boolean = year in firstDate.year..today.plusDays(100).year
        }
    }
    var showScheduleSheet by remember { mutableStateOf(false) }
    var showTransitOptionsSheet by remember { mutableStateOf(false) }
    var showStartPointSheet by remember { mutableStateOf(false) }
    var showEndPointSheet by remember { mutableStateOf(false) }
    var showUnavailableRouteDetails by remember(state.unavailableRouteSegment) { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var pendingTimeMode by remember { mutableStateOf(TransitTimeMode.DEPART_AT) }
    var pendingDate by remember(state.transitDate) { mutableStateOf(state.transitDate) }
    var pendingTime by remember(state.transitTime) { mutableStateOf(state.transitTime) }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize().testTag("planner-settings-screen"),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            PlannerTopBar(title = "编排一日路线", onBack = onBack)
            BoxWithConstraints(modifier = Modifier.weight(1f)) {
                val widthClass = plannerWidthClass(maxWidth)
                val useTwoPane = widthClass != PlannerWidthClass.Compact || maxWidth > maxHeight
                if (useTwoPane) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                horizontal = if (widthClass == PlannerWidthClass.Expanded) 32.dp else 20.dp,
                                vertical = 20.dp,
                            ),
                        horizontalArrangement = Arrangement.spacedBy(
                            if (widthClass == PlannerWidthClass.Expanded) 28.dp else 20.dp,
                        ),
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            PlannerCoreSettings(
                                state = state,
                                onModeChange = onModeChange,
                                onUseCurrentLocation = onUseCurrentLocation,
                                onChooseStartPoint = { showStartPointSheet = true },
                                onEndPolicyChange = onEndPolicyChange,
                                onChooseEndPoint = { showEndPointSheet = true },
                            )
                        }
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .widthIn(max = 520.dp)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                        ) {
                            PlannerRouteOptions(
                                state = state,
                                today = today,
                                zoneId = zoneId,
                                onObjectiveChange = onObjectiveChange,
                                onShowSchedule = { showScheduleSheet = true },
                                onShowTransitOptions = { showTransitOptionsSheet = true },
                                onDwellChange = onDwellChange,
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        PlannerCoreSettings(
                            state = state,
                            onModeChange = onModeChange,
                            onUseCurrentLocation = onUseCurrentLocation,
                            onChooseStartPoint = { showStartPointSheet = true },
                            onEndPolicyChange = onEndPolicyChange,
                            onChooseEndPoint = { showEndPointSheet = true },
                        )
                        PlannerRouteOptions(
                            state = state,
                            today = today,
                            zoneId = zoneId,
                            onObjectiveChange = onObjectiveChange,
                            onShowSchedule = { showScheduleSheet = true },
                            onShowTransitOptions = { showTransitOptionsSheet = true },
                            onDwellChange = onDwellChange,
                        )
                    }
                }
            }
            state.errorMessage?.let {
                PlannerErrorCard(
                    message = it,
                    unavailableRouteSegment = state.unavailableRouteSegment,
                    onShowDetails = { showUnavailableRouteDetails = true },
                    showAmapExternalFallback = state.amapExternalFallbackAvailable,
                    onUseAmapExternalFallback = onUseAmapExternalFallback,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            PlannerBottomAction(
                onClick = onGenerate,
                enabled = !state.isLoading,
                label = if (state.isLoading) loadingRouteLabel(state) else "生成路线",
                isLoading = state.isLoading,
                icon = Icons.Rounded.Route,
            )
        }
    }

    if (showStartPointSheet) {
        PointPickerSheet(
            title = "选择起点",
            points = state.selectedPoints,
            selectedId = if (state.useCurrentLocation) null else state.startPointId,
            onDismiss = { showStartPointSheet = false },
            onSelect = { pointId ->
                onStartChange(pointId)
                showStartPointSheet = false
            },
        )
    }

    if (showEndPointSheet) {
        PointPickerSheet(
            title = "选择终点",
            points = state.selectedPoints.filterNot { it.id == state.startPointId },
            selectedId = state.fixedEndPointId,
            onDismiss = { showEndPointSheet = false },
            onSelect = { pointId ->
                onFixedEndChange(pointId)
                showEndPointSheet = false
            },
        )
    }

    if (showScheduleSheet) {
        val amapTransit = state.transitExecutionStrategy == TransitExecutionStrategy.EXTERNAL_AMAP_MAINLAND
        ModalBottomSheet(onDismissRequest = { showScheduleSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("选择公交时间", style = MaterialTheme.typography.titleLarge)
                Text(
                    "查询会按每个巡礼点的到达时间和停留时间继续衔接。",
                    color = MutedInk,
                    style = MaterialTheme.typography.bodyMedium,
                )
                TransitSheetChoice(
                    title = "现在出发",
                    subtitle = "查询时使用当前时间",
                    selected = state.transitTimeMode == TransitTimeMode.NOW,
                    onClick = {
                        val current = LocalDateTime.now(zoneId).withSecond(0).withNano(0)
                        onTransitScheduleChange(TransitTimeMode.NOW, current.toLocalDate(), current.toLocalTime())
                        showScheduleSheet = false
                    },
                )
                TransitSheetChoice(
                    title = "选择出发时间",
                    subtitle = "从指定日期和时间开始行程",
                    selected = state.transitTimeMode == TransitTimeMode.DEPART_AT,
                    onClick = {
                        val current = LocalDateTime.now(zoneId).withSecond(0).withNano(0)
                        pendingTimeMode = TransitTimeMode.DEPART_AT
                        pendingDate = if (state.transitTimeMode == TransitTimeMode.NOW) current.toLocalDate() else state.transitDate
                        pendingTime = if (state.transitTimeMode == TransitTimeMode.NOW) current.toLocalTime() else state.transitTime
                        showScheduleSheet = false
                        showDatePicker = true
                    },
                )
                TransitSheetChoice(
                    title = "选择到达时间",
                    subtitle = if (amapTransit) "高德公交暂不支持按到达时间查询" else "寻找在指定时间前到达的行程",
                    selected = state.transitTimeMode == TransitTimeMode.ARRIVE_BY,
                    enabled = !amapTransit,
                    onClick = {
                        val current = LocalDateTime.now(zoneId).withSecond(0).withNano(0)
                        pendingTimeMode = TransitTimeMode.ARRIVE_BY
                        pendingDate = if (state.transitTimeMode == TransitTimeMode.NOW) current.toLocalDate() else state.transitDate
                        pendingTime = if (state.transitTimeMode == TransitTimeMode.NOW) current.toLocalTime() else state.transitTime
                        showScheduleSheet = false
                        showDatePicker = true
                    },
                )
            }
        }
    }

    if (showTransitOptionsSheet) {
        val amapTransit = state.transitExecutionStrategy == TransitExecutionStrategy.EXTERNAL_AMAP_MAINLAND
        val selectedTravelModes = selectedTransitTravelModes(state.transitTravelModes)
        ModalBottomSheet(onDismissRequest = { showTransitOptionsSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("公交选项", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (amapTransit) {
                        "高德公交支持少步行和少换乘偏好，但暂不支持按交通方式筛选。"
                    } else {
                        "路线服务会尽量遵循这些偏好，必要时仍可能返回其他交通方式。"
                    },
                    color = MutedInk,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "交通方式（可多选）",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 6.dp),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    allTransitTravelModes.forEach { mode ->
                        val selected = mode in selectedTravelModes
                        FilterChip(
                            selected = selected,
                            onClick = { onTransitTravelModeToggle(mode) },
                            enabled = !amapTransit && !state.isLoading && (!selected || selectedTravelModes.size > 1),
                            label = { Text(transitTravelModeLabel(mode)) },
                            leadingIcon = if (selected) {
                                { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            } else {
                                null
                            },
                        )
                    }
                }
                Text(
                    "至少保留一种交通方式。四项全选等同于不限制。",
                    color = MutedInk,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "路线偏好",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 6.dp),
                )
                TransitPreferenceSheetChoice(
                    preference = TransitRoutingPreference.RECOMMENDED,
                    subtitle = "综合时间、步行和换乘",
                    selected = state.transitRoutingPreference == TransitRoutingPreference.RECOMMENDED,
                    onSelect = onTransitPreferenceChange,
                )
                TransitPreferenceSheetChoice(
                    preference = TransitRoutingPreference.LESS_WALKING,
                    subtitle = "优先减少步行接驳",
                    selected = state.transitRoutingPreference == TransitRoutingPreference.LESS_WALKING,
                    onSelect = onTransitPreferenceChange,
                )
                TransitPreferenceSheetChoice(
                    preference = TransitRoutingPreference.FEWER_TRANSFERS,
                    subtitle = "优先减少换乘次数",
                    selected = state.transitRoutingPreference == TransitRoutingPreference.FEWER_TRANSFERS,
                    onSelect = onTransitPreferenceChange,
                )
                Button(
                    onClick = { showTransitOptionsSheet = false },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Vermilion),
                ) {
                    Text("完成")
                }
            }
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = pendingDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = selectableTransitDates,
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    enabled = datePickerState.selectedDateMillis != null,
                    onClick = {
                        pendingDate = Instant.ofEpochMilli(requireNotNull(datePickerState.selectedDateMillis))
                            .atZone(ZoneOffset.UTC)
                            .toLocalDate()
                        showDatePicker = false
                        showTimePicker = true
                    },
                ) {
                    Text("下一步")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(
            initialHour = pendingTime.hour,
            initialMinute = pendingTime.minute,
            is24Hour = DateFormat.is24HourFormat(context),
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text(if (pendingTimeMode == TransitTimeMode.ARRIVE_BY) "选择到达时间" else "选择出发时间") },
            text = { TimePicker(state = timePickerState) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingTime = LocalTime.of(timePickerState.hour, timePickerState.minute)
                        onTransitScheduleChange(pendingTimeMode, pendingDate, pendingTime)
                        showTimePicker = false
                    },
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("取消") }
            },
        )
    }

    if (showUnavailableRouteDetails) {
        state.unavailableRouteSegment?.let { segment ->
            UnavailableRouteDetailsSheet(
                segment = segment,
                onDismiss = { showUnavailableRouteDetails = false },
                googleMapsLaunchFailed = googleMapsLaunchFailed,
                onOpenGoogleMaps = {
                    googleMapsLaunchFailed = !openGoogleMaps(segment)
                },
            )
        }
    }
}

@Composable
private fun PlannerCoreSettings(
    state: PlannerUiState,
    onModeChange: (TravelMode) -> Unit,
    onUseCurrentLocation: () -> Unit,
    onChooseStartPoint: () -> Unit,
    onEndPolicyChange: (EndPolicy) -> Unit,
    onChooseEndPoint: () -> Unit,
) {
    val selectedStart = state.selectedPoints.firstOrNull { it.id == state.startPointId }
    val selectedEnd = state.selectedPoints.firstOrNull { it.id == state.fixedEndPointId }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SettingsSection(
            title = "出行方式",
            subtitle = "选择整段巡礼主要使用的交通方式",
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeChip(
                        mode = TravelMode.DRIVE,
                        selected = state.mode,
                        label = "驾车",
                        onSelect = onModeChange,
                        enabled = !state.isLoading,
                        modifier = Modifier.weight(1f),
                    )
                    ModeChip(
                        mode = TravelMode.BIKE,
                        selected = state.mode,
                        label = "骑行",
                        onSelect = onModeChange,
                        enabled = !state.isLoading,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeChip(
                        mode = TravelMode.WALK,
                        selected = state.mode,
                        label = "步行",
                        onSelect = onModeChange,
                        enabled = !state.isLoading,
                        modifier = Modifier.weight(1f),
                    )
                    ModeChip(
                        mode = TravelMode.TRANSIT,
                        selected = state.mode,
                        label = "公交",
                        onSelect = onModeChange,
                        enabled = !state.isLoading,
                        modifier = Modifier.weight(1f),
                    )
                }
                googleRouteBetaNotice(state.mode)?.let { notice ->
                    Text(
                        notice,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }

        SettingsSection(
            title = "起点",
            subtitle = "从当前位置，或一个已选巡礼点出发",
        ) {
            SettingsSelectionRow(
                title = "当前位置",
                subtitle = "开始时使用设备位置",
                selected = state.useCurrentLocation,
                enabled = !state.isLoading,
                onClick = onUseCurrentLocation,
                leadingIcon = Icons.Rounded.LocationOn,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            PointSelectionRow(
                title = selectedStart?.name ?: "选择巡礼点",
                subtitle = if (selectedStart == null) "从已选点中选择" else "从这个巡礼点出发",
                selected = !state.useCurrentLocation && selectedStart != null,
                enabled = !state.isLoading,
                onClick = onChooseStartPoint,
            )
        }

        SettingsSection(
            title = "终点",
            subtitle = "决定完成最后一个巡礼点后的去向",
        ) {
            SettingsSelectionRow(
                title = "自由结束",
                subtitle = "在最后一个巡礼点结束",
                selected = state.endPolicy == EndPolicy.OPEN,
                enabled = !state.isLoading,
                onClick = { onEndPolicyChange(EndPolicy.OPEN) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsSelectionRow(
                title = "指定终点",
                subtitle = "选择一个巡礼点作为终点",
                selected = state.endPolicy == EndPolicy.FIXED,
                enabled = !state.isLoading,
                onClick = { onEndPolicyChange(EndPolicy.FIXED) },
            )
            if (state.endPolicy == EndPolicy.FIXED) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
                PointSelectionRow(
                    title = selectedEnd?.name ?: "选择终点",
                    subtitle = if (selectedEnd == null) "从可用巡礼点中选择" else "当前指定终点",
                    selected = selectedEnd != null,
                    enabled = !state.isLoading,
                    onClick = onChooseEndPoint,
                    inset = true,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsSelectionRow(
                title = "返回起点",
                subtitle = "完成后回到出发位置",
                selected = state.endPolicy == EndPolicy.RETURN_TO_START,
                enabled = !state.isLoading,
                onClick = { onEndPolicyChange(EndPolicy.RETURN_TO_START) },
            )
        }
    }
}

@Composable
private fun PlannerRouteOptions(
    state: PlannerUiState,
    today: LocalDate,
    zoneId: ZoneId,
    onObjectiveChange: (RouteObjective) -> Unit,
    onShowSchedule: () -> Unit,
    onShowTransitOptions: () -> Unit,
    onDwellChange: (String) -> Unit,
) {
    val transitPresentation = plannerTransitPresentation(
        mode = state.mode,
        executionStrategy = state.transitExecutionStrategy,
    )
    if (state.mode != TravelMode.TRANSIT) {
        SettingsSection(
            title = "路线偏好",
            subtitle = "选择时间或距离作为优先目标",
        ) {
            SettingsSelectionRow(
                title = "预计最快",
                subtitle = "优先缩短总行程时间",
                selected = state.objective == RouteObjective.FASTEST,
                enabled = !state.isLoading,
                onClick = { onObjectiveChange(RouteObjective.FASTEST) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsSelectionRow(
                title = "距离最短",
                subtitle = "优先减少总行程距离",
                selected = state.objective == RouteObjective.SHORTEST,
                enabled = !state.isLoading,
                onClick = { onObjectiveChange(RouteObjective.SHORTEST) },
            )
        }
    } else if (transitPresentation.showExternalProviderMessage) {
        SettingsSection(
            title = "日本公交分段导航",
            subtitle = EXTERNAL_JAPAN_TRANSIT_PROVIDER_MESSAGE,
        ) {
            Text(
                "应用只安排巡礼点访问顺序；每一段都由你手动打开 Google 地图。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
            OutlinedTextField(
                value = state.dwellMinutesInput,
                onValueChange = onDwellChange,
                enabled = !state.isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                label = { Text("每个景点停留（分钟）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
    } else {
        SettingsSection(
            title = "公交行程",
            subtitle = "设置出发时间、路线偏好与景点停留时间",
        ) {
            if (transitPresentation.showTransitSchedule) {
                TransitSettingRow(
                    icon = Icons.Rounded.Schedule,
                    label = "行程时间",
                    value = transitScheduleLabel(
                        mode = state.transitTimeMode,
                        date = state.transitDate,
                        time = state.transitTime,
                        today = today,
                    ),
                    enabled = !state.isLoading,
                    onClick = onShowSchedule,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (transitPresentation.showTransitFilters) {
                TransitSettingRow(
                    icon = Icons.Rounded.Tune,
                    label = "路线选项",
                    value = transitOptionsSummaryLabel(
                        state.transitRoutingPreference,
                        state.transitTravelModes,
                    ),
                    enabled = !state.isLoading,
                    onClick = onShowTransitOptions,
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
            }
            OutlinedTextField(
                value = state.dwellMinutesInput,
                onValueChange = onDwellChange,
                enabled = !state.isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                label = { Text("每个景点停留（分钟）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Text(
                "时间以设备时区 ${zoneId.id} 为准",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
            )
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    subtitle: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column {
        Text(
            title,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
        )
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            content = { Column(content = content) },
        )
    }

}

@Composable
private fun SettingsSelectionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    leadingIcon: ImageVector? = null,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                role = Role.RadioButton
                this.selected = selected
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != null) {
                Icon(
                    leadingIcon,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(24.dp)
                        .padding(end = 2.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            SelectionIndicator(selected = selected)
        }
    }
}

@Composable
private fun SelectionIndicator(selected: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        border = BorderStroke(
            2.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
        modifier = Modifier.size(24.dp),
    ) {
        if (selected) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun PointSelectionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    inset: Boolean = false,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f) else Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                stateDescription = if (selected) "已选择" else "未选择"
            },
    ) {
        Row(
            modifier = Modifier.padding(
                start = if (inset) 28.dp else 14.dp,
                end = 14.dp,
                top = 12.dp,
                bottom = 12.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Place,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    title,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Icon(
                Icons.Rounded.ArrowDropDown,
                contentDescription = "展开巡礼点选择",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PointPickerSheet(
    title: String,
    points: List<PilgrimagePoint>,
    selectedId: String?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filteredPoints = remember(points, query) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) points
        else points.filter { it.name.contains(normalizedQuery, ignoreCase = true) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "共 ${points.size} 个已选巡礼点",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 2.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp, bottom = 8.dp),
                placeholder = { Text("搜索巡礼点") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Rounded.Clear, contentDescription = "清除搜索")
                        }
                    }
                } else {
                    null
                },
                singleLine = true,
            )
            if (filteredPoints.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("没有匹配的巡礼点", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 440.dp),
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    items(filteredPoints, key = PilgrimagePoint::id) { point ->
                        SettingsSelectionRow(
                            title = point.name,
                            subtitle = "%.5f, %.5f".format(
                                point.coordinate.latitude,
                                point.coordinate.longitude,
                            ),
                            selected = point.id == selectedId,
                            enabled = true,
                            onClick = { onSelect(point.id) },
                            leadingIcon = Icons.Rounded.Place,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun TransitSettingRow(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                Text(
                    value,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Icon(
                Icons.Rounded.ArrowDropDown,
                contentDescription = "展开$label",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TransitSheetChoice(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(1.dp, if (selected) Vermilion else Sand),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Ink, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = MutedInk, style = MaterialTheme.typography.bodyMedium)
            }
            if (selected) {
                Icon(Icons.Rounded.Check, contentDescription = "已选择", tint = Vermilion)
            }
        }
    }
}

@Composable
private fun TransitPreferenceSheetChoice(
    preference: TransitRoutingPreference,
    subtitle: String,
    selected: Boolean,
    onSelect: (TransitRoutingPreference) -> Unit,
) {
    TransitSheetChoice(
        title = transitPreferenceLabel(preference),
        subtitle = subtitle,
        selected = selected,
        onClick = { onSelect(preference) },
    )
}

internal fun transitScheduleLabel(
    mode: TransitTimeMode,
    date: LocalDate,
    time: LocalTime,
    today: LocalDate,
): String {
    if (mode == TransitTimeMode.NOW) return "现在出发"
    val dateLabel = when {
        date == today -> "今天"
        date.year == today.year -> "${date.monthValue}月${date.dayOfMonth}日"
        else -> "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
    }
    val timeLabel = time.format(DateTimeFormatter.ofPattern("HH:mm"))
    val action = if (mode == TransitTimeMode.ARRIVE_BY) "前到达" else "出发"
    return "$dateLabel $timeLabel $action"
}

internal fun transitPreferenceLabel(preference: TransitRoutingPreference): String = when (preference) {
    TransitRoutingPreference.RECOMMENDED -> "最佳路线"
    TransitRoutingPreference.LESS_WALKING -> "少步行"
    TransitRoutingPreference.FEWER_TRANSFERS -> "少换乘"
}

internal fun transitTravelModeLabel(mode: TransitTravelMode): String = when (mode) {
    TransitTravelMode.BUS -> "公交"
    TransitTravelMode.SUBWAY -> "地铁"
    TransitTravelMode.TRAIN -> "火车"
    TransitTravelMode.LIGHT_RAIL -> "轻轨"
}

internal fun transitTravelModesLabel(storedModes: Set<TransitTravelMode>): String {
    if (storedModes.isEmpty()) return "全部方式"
    return allTransitTravelModes
        .filter(storedModes::contains)
        .joinToString("、", transform = ::transitTravelModeLabel)
}

internal fun transitOptionsSummaryLabel(
    preference: TransitRoutingPreference,
    storedModes: Set<TransitTravelMode>,
): String = "${transitPreferenceLabel(preference)} · ${transitTravelModesLabel(storedModes)}"

private fun loadingRouteLabel(state: PlannerUiState): String {
    if (state.transitExecutionStrategy == TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN) {
        return "正在生成分段行程"
    }
    if (state.mode != TravelMode.TRANSIT || state.totalTransitSegments <= 0) return "正在生成路线"
    val completed = state.plannedTransitSegments.coerceIn(0, state.totalTransitSegments)
    return if (completed < state.totalTransitSegments) {
        "正在查询第 ${completed + 1}/${state.totalTransitSegments} 段"
    } else {
        "正在整理完整路线"
    }
}

@Composable
private fun RoutePreviewScreen(
    state: PlannerUiState,
    plan: TourPlan,
    onBack: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onApplyOrder: () -> Unit,
    onUseAmapExternalFallback: () -> Unit,
    onStartNavigation: () -> Unit,
) {
    val transitSections = remember(plan.legs) { groupTransitJourneySections(plan.legs) }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            PlannerTopBar(title = "路线预览", onBack = onBack)
            BoxWithConstraints(modifier = Modifier.weight(1f)) {
                val widthClass = plannerWidthClass(maxWidth)
                val useTwoPane = widthClass != PlannerWidthClass.Compact || maxWidth > maxHeight
                val availableHeight = maxHeight
                if (useTwoPane) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        RoutePreviewMap(
                            plan = plan,
                            modifier = Modifier
                                .weight(if (widthClass == PlannerWidthClass.Expanded) 1.35f else 1f)
                                .fillMaxHeight(),
                        )
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        )
                        RoutePreviewDetails(
                            state = state,
                            plan = plan,
                            transitSections = transitSections,
                            onMove = onMove,
                            onApplyOrder = onApplyOrder,
                            onUseAmapExternalFallback = onUseAmapExternalFallback,
                            onStartNavigation = onStartNavigation,
                            modifier = Modifier
                                .weight(1f)
                                .widthIn(max = 520.dp)
                                .fillMaxHeight(),
                        )
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        RoutePreviewMap(
                            plan = plan,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(if (availableHeight < 640.dp) 190.dp else 238.dp),
                        )
                        RoutePreviewDetails(
                            state = state,
                            plan = plan,
                            transitSections = transitSections,
                            onMove = onMove,
                            onApplyOrder = onApplyOrder,
                            onUseAmapExternalFallback = onUseAmapExternalFallback,
                            onStartNavigation = onStartNavigation,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun RoutePreviewDetails(
    state: PlannerUiState,
    plan: TourPlan,
    transitSections: List<TransitJourneySection>,
    onMove: (Int, Int) -> Unit,
    onApplyOrder: () -> Unit,
    onStartNavigation: () -> Unit,
    modifier: Modifier = Modifier,
    onUseAmapExternalFallback: () -> Unit = {},
    onOpenGoogleMaps: ((UnavailableRouteSegment) -> Boolean)? = null,
) {
    val context = LocalContext.current
    val amapExternalFallback = plan.isAmapExternalFallback()
    val transitPresentation = plannerTransitPresentation(
        mode = plan.mode,
        executionStrategy = plan.executionStrategy,
    )
    val googleMapsLauncher = remember(context) { GoogleMapsTransitLauncher(context) }
    var googleMapsLaunchFailed by remember(state.unavailableRouteSegment) { mutableStateOf(false) }
    val openGoogleMaps = onOpenGoogleMaps ?: { segment: UnavailableRouteSegment ->
        googleMapsLauncher.launch(segment.origin.coordinate, segment.destination.coordinate)
    }
    var showUnavailableRouteDetails by remember(state.unavailableRouteSegment) { mutableStateOf(false) }
    val startLocked = state.draftOrder.firstOrNull()?.id == state.startPointId
    val endLocked = state.endPolicy == EndPolicy.FIXED
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .testTag("route-preview-details"),
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                if (amapExternalFallback) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.testTag("planner-amap-fallback-notice"),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                "高德地图 · 仅按当前顺序分段",
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "此方案不含路线详情、距离或预计时间，也不会调用 Google 或路线后端；" +
                                    "开始后将按当前顺序逐段打开设备上的高德地图。",
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                } else if (transitPresentation.showExternalProviderMessage) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                "日本公交 · 外部 Google 地图",
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "$EXTERNAL_JAPAN_TRANSIT_PROVIDER_MESSAGE。此处只显示巡礼点访问顺序。",
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                } else if (transitPresentation.showRouteEstimateSummary) {
                    if (plan.mode == TravelMode.TRANSIT) {
                        TransitJourneySummaryCard(plan)
                    } else {
                        RouteSummary(plan)
                    }
                }
            }
            state.errorMessage?.let { message ->
                item {
                    PlannerErrorCard(
                        message = message,
                        unavailableRouteSegment = state.unavailableRouteSegment,
                        onShowDetails = { showUnavailableRouteDetails = true },
                        showAmapExternalFallback = state.amapExternalFallbackAvailable,
                        onUseAmapExternalFallback = onUseAmapExternalFallback,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
            googleRouteBetaNotice(plan.mode).takeIf { plan.mapProvider == MapProvider.GOOGLE }?.let { notice ->
                item {
                    Text(
                        notice,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (transitPresentation.showTransitJourneyDetails && !amapExternalFallback) {
                item {
                    Column {
                        Text(
                            "公交行程",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "按顺序查看步行、乘车与到站信息",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
                        )
                        TransitJourneyTimeline(
                            sections = transitSections,
                            plan = plan,
                        )
                    }
                }
            }
            item {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        "巡礼点顺序",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "长按拖动；读屏用户可使用上移、下移动作",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            itemsIndexed(state.draftOrder, key = { _, point -> point.id }) { index, point ->
                val locked = (startLocked && index == 0) ||
                    (endLocked && index == state.draftOrder.lastIndex)
                val canMoveUp = !locked && index > 0 && !(startLocked && index == 1)
                val canMoveDown = !locked && index < state.draftOrder.lastIndex &&
                    !(endLocked && index == state.draftOrder.lastIndex - 1)
                ReorderPointCard(
                    point = point,
                    index = index,
                    locked = locked,
                    canMoveUp = canMoveUp,
                    canMoveDown = canMoveDown,
                    onMove = onMove,
                )
            }
            item {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(
                        if (plan.mapProvider == MapProvider.AMAP) "高德地图" else "Google Maps",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    plan.attribution.forEach { attribution ->
                        Text(
                            attribution,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        PlannerBottomAction(
            onClick = if (state.orderChanged) onApplyOrder else onStartNavigation,
            enabled = !state.isLoading,
            label = when {
                state.isLoading && amapExternalFallback -> "正在更新分段顺序"
                state.isLoading -> "正在重新生成路线"
                state.orderChanged && amapExternalFallback -> "按此顺序更新分段"
                state.orderChanged -> "按此顺序重新生成"
                amapExternalFallback -> "开始高德分段导航"
                plan.executionStrategy == TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN ->
                    "开始日本公交行程"
                plan.mode == TravelMode.TRANSIT -> "开始公交行程"
                else -> "开始连续导航"
            },
            isLoading = state.isLoading,
            icon = if (state.orderChanged) Icons.Rounded.Route else null,
        )
    }

    if (showUnavailableRouteDetails) {
        state.unavailableRouteSegment?.let { segment ->
            UnavailableRouteDetailsSheet(
                segment = segment,
                onDismiss = { showUnavailableRouteDetails = false },
                googleMapsLaunchFailed = googleMapsLaunchFailed,
                onOpenGoogleMaps = {
                    googleMapsLaunchFailed = !openGoogleMaps(segment)
                },
            )
        }
    }
}

@Composable
private fun PlannerErrorCard(
    message: String,
    unavailableRouteSegment: UnavailableRouteSegment?,
    onShowDetails: () -> Unit,
    modifier: Modifier = Modifier,
    showAmapExternalFallback: Boolean = false,
    onUseAmapExternalFallback: () -> Unit = {},
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Assertive },
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Rounded.ErrorOutline, contentDescription = null)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "暂时无法生成路线",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    if (unavailableRouteSegment != null) {
                        TextButton(
                            onClick = onShowDetails,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("planner-no-route-details-action"),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = RouteDetailsLinkBlue),
                        ) {
                            Text(
                                "详细信息",
                                fontWeight = FontWeight.Bold,
                                textDecoration = TextDecoration.Underline,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
                Text(message, style = MaterialTheme.typography.bodyMedium)
                if (showAmapExternalFallback) {
                    Text(
                        "可选择只保存当前顺序并逐段打开高德地图；不会显示路线、距离或预计时间。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    TextButton(
                        onClick = onUseAmapExternalFallback,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("planner-use-amap-external-fallback"),
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 6.dp),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Text(
                            "仅按当前顺序分段打开高德地图",
                            fontWeight = FontWeight.Bold,
                            textDecoration = TextDecoration.Underline,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnavailableRouteDetailsSheet(
    segment: UnavailableRouteSegment,
    onDismiss: () -> Unit,
    googleMapsLaunchFailed: Boolean,
    onOpenGoogleMaps: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("planner-no-route-details-sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "无路线详细信息",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "第 ${segment.segmentNumber}/${segment.segmentCount} 段未找到可用的公交或步行路线",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    UnavailableRouteEndpointDetails(
                        label = "起点",
                        endpoint = segment.origin,
                        modifier = Modifier.testTag("failed-segment-origin"),
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    UnavailableRouteEndpointDetails(
                        label = "终点",
                        endpoint = segment.destination,
                        modifier = Modifier.testTag("failed-segment-destination"),
                    )
                }
            }
            Text(
                "将以公交模式打开 Google 地图，并把这两个精确坐标填入起点和终点。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            if (googleMapsLaunchFailed) {
                Text(
                    "无法打开 Google 地图或网页链接，请确认设备已安装可处理地图链接的应用。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag("planner-google-maps-launch-error"),
                )
            }
            Button(
                onClick = onOpenGoogleMaps,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .testTag("planner-open-google-maps-route"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("在 Google 地图中查看路线")
            }
        }
    }
}

@Composable
private fun UnavailableRouteEndpointDetails(
    label: String,
    endpoint: UnavailableRouteEndpoint,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            endpoint.name,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "纬度 ${formatCoordinate(endpoint.coordinate.latitude)}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium.merge(NumericTextStyle),
        )
        Text(
            "经度 ${formatCoordinate(endpoint.coordinate.longitude)}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium.merge(NumericTextStyle),
        )
    }
}

private fun formatCoordinate(value: Double): String = String.format(Locale.ROOT, "%.6f", value)

private val RouteDetailsLinkBlue = Color(0xFF0B57D0)

@Composable
private fun PlannerBottomAction(
    onClick: () -> Unit,
    enabled: Boolean,
    label: String,
    isLoading: Boolean,
    icon: ImageVector? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .semantics {
                if (isLoading) {
                    liveRegion = LiveRegionMode.Polite
                    stateDescription = label
                }
            },
    ) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .heightIn(min = 52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(10.dp))
            } else if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(label)
        }
    }
}

@Composable
private fun TransitJourneySummaryCard(plan: TourPlan) {
    val transitLegs = plan.legs.mapNotNull(TourLeg::transit)
    val firstTransit = transitLegs.firstOrNull()
    val lastTransit = transitLegs.lastOrNull()
    val departure = plan.departureTime ?: firstTransit?.departureTime
    val arrival = plan.arrivalTime ?: lastTransit?.arrivalTime
    val walkingDistance = plan.legs.filter { it.mode == TravelMode.WALK }.sumOf(TourLeg::distanceMeters)
    val lineLabels = transitLegs.mapNotNull { transit ->
        transit.line?.takeIf(String::isNotBlank) ?: transitVehicleLabel(transit.vehicleMode)
    }.distinct()
    val largeText = LocalDensity.current.fontScale >= 1.5f
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.DirectionsBus,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                ) {
                    Text(
                        "公交路线",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        if (departure != null || arrival != null) {
                            "${formatTransitTime(departure, firstTransit?.departureTimeZone)} → " +
                                formatTransitTime(arrival, lastTransit?.arrivalTimeZone)
                        } else {
                            "完整步行与换乘行程"
                        },
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = 14.dp),
            )
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    maxItemsInEachRow = if (maxWidth < 440.dp || largeText) 2 else 4,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LightSummaryValue(
                        formatDuration(plan.estimatedDurationSeconds),
                        "总用时",
                        Modifier.weight(1f),
                    )
                    LightSummaryValue("${plan.orderedPoints.size} 站", "巡礼点", Modifier.weight(1f))
                    LightSummaryValue("${transitLegs.size} 段", "乘车", Modifier.weight(1f))
                    LightSummaryValue(formatDistance(walkingDistance), "步行", Modifier.weight(1f))
                }
            }
            Text(
                "${transitPreferenceLabel(plan.transitRoutingPreference)} · " +
                    transitTravelModesLabel(plan.transitTravelModes),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp),
            )
            if (lineLabels.isNotEmpty()) {
                Text(
                    "途经 ${lineLabels.take(5).joinToString(" · ")}" +
                        if (lineLabels.size > 5) " 等 ${lineLabels.size} 条线路" else "",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal data class TransitJourneySection(
    val legs: List<TourLeg>,
    val destinationPointId: String?,
)

private fun groupTransitJourneySections(legs: List<TourLeg>): List<TransitJourneySection> {
    val result = mutableListOf<TransitJourneySection>()
    val pending = mutableListOf<TourLeg>()
    legs.forEach { leg ->
        pending += leg
        if (leg.destinationPointId != null) {
            result += TransitJourneySection(pending.toList(), leg.destinationPointId)
            pending.clear()
        }
    }
    if (pending.isNotEmpty()) result += TransitJourneySection(pending.toList(), null)
    return result
}

@Composable
private fun TransitJourneyTimeline(
    sections: List<TransitJourneySection>,
    plan: TourPlan,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            sections.forEachIndexed { index, section ->
                val destination = section.destinationPointId?.let { pointId ->
                    plan.selectedPoints.firstOrNull { it.id == pointId }?.name
                } ?: if (index == sections.lastIndex && plan.endPolicy == EndPolicy.RETURN_TO_START) {
                    "返回起点"
                } else {
                    "下一巡礼点"
                }
                if (index > 0) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(start = 60.dp),
                    )
                }
                Row(
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.size(32.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                "${index + 1}",
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp),
                    ) {
                        Text(
                            "前往 $destination",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${formatDuration(section.legs.sumOf(TourLeg::durationSeconds))} · " +
                                formatDistance(section.legs.sumOf(TourLeg::distanceMeters)),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                section.legs.forEachIndexed { legIndex, leg ->
                    TransitTimelineLegRow(
                        leg = leg,
                        showConnector = !(index == sections.lastIndex && legIndex == section.legs.lastIndex),
                    )
                }
            }
        }
    }
}

@Composable
private fun TransitTimelineLegRow(
    leg: TourLeg,
    showConnector: Boolean,
) {
    val transit = leg.transit
    val walking = leg.mode == TravelMode.WALK
    val walkingInstruction = leg.steps.firstOrNull()?.instruction?.takeIf(String::isNotBlank)
    val stopLabel = transit?.let {
        listOfNotNull(it.departureStop, it.arrivalStop).joinToString(" → ").takeIf(String::isNotBlank)
    }
    val hasExtraDetails = if (walking) {
        walkingInstruction.orEmpty().length > 64
    } else {
        transit != null && (
            transit.departurePlatform != null ||
                transit.arrivalPlatform != null ||
                transit.intermediateStops.isNotEmpty() ||
                stopLabel.orEmpty().length > 64
            )
    }
    var expanded by remember(leg) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .width(36.dp)
                .fillMaxHeight(),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (showConnector) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(50),
                modifier = Modifier.size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (walking) Icons.AutoMirrored.Rounded.DirectionsWalk else Icons.Rounded.DirectionsBus,
                        contentDescription = null,
                        tint = if (walking) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp, bottom = 16.dp),
        ) {
            Text(
                if (walking) "步行接驳" else transit?.let { it.line ?: transitVehicleLabel(it.vehicleMode) }
                    ?: "公共交通",
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            if (walking) {
                Text(
                    "约 ${formatDuration(leg.durationSeconds)} · ${formatDistance(leg.distanceMeters)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                walkingInstruction?.let { instruction ->
                    Text(
                        instruction,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else if (transit != null) {
                val departure = formatTransitTime(transit.departureTime, transit.departureTimeZone)
                val arrival = formatTransitTime(transit.arrivalTime, transit.arrivalTimeZone)
                Text(
                    "$departure → $arrival · ${formatDuration(leg.durationSeconds)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                transit.direction?.takeIf(String::isNotBlank)?.let {
                    Text("开往 $it", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                stopLabel?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                transit.stopCount?.let {
                    Text("途经 $it 站", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (expanded) {
                    transit.departurePlatform?.let {
                        Text("上车站台：$it", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    transit.arrivalPlatform?.let {
                        Text("下车站台：$it", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    transit.intermediateStops.takeIf { it.isNotEmpty() }?.let { stops ->
                        Text(
                            "中途站：${stops.joinToString(" → ")}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (transit.cancelled) {
                    Text(
                        "该班次已取消，需要重新查询",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                } else if (transit.realtime) {
                    Text(
                        "含实时信息",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            } else {
                Text(
                    "约 ${formatDuration(leg.durationSeconds)} · ${formatDistance(leg.distanceMeters)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (hasExtraDetails) {
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 6.dp),
                ) {
                    Text(if (expanded) "收起详情" else "查看详情")
                    Icon(
                        if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(18.dp),
                    )
                }
            }
        }
    }
}

private fun transitVehicleLabel(vehicleMode: String): String {
    val normalized = vehicleMode.uppercase()
    return when {
        "SUBWAY" in normalized || "METRO" in normalized -> "地铁"
        "TRAM" in normalized || "LIGHT_RAIL" in normalized -> "有轨电车"
        "RAIL" in normalized || "TRAIN" in normalized -> "铁路"
        "BUS" in normalized -> "公交"
        "FERRY" in normalized -> "轮渡"
        else -> "公共交通"
    }
}

internal fun googleRouteBetaNotice(mode: TravelMode): String? = when (mode) {
    TravelMode.WALK, TravelMode.BIKE ->
        "Google 地图的步行和骑行路线仍为测试版，请以现场道路和交通规则为准。"
    TravelMode.DRIVE, TravelMode.TRANSIT -> null
}

@Composable
private fun ReorderPointCard(
    point: PilgrimagePoint,
    index: Int,
    locked: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int, Int) -> Unit,
) {
    var dragDistance by remember { mutableFloatStateOf(0f) }
    val accessibilityActions = buildList {
        if (canMoveUp) {
            add(
                CustomAccessibilityAction("上移") {
                    onMove(index, index - 1)
                    true
                },
            )
        }
        if (canMoveDown) {
            add(
                CustomAccessibilityAction("下移") {
                    onMove(index, index + 1)
                    true
                },
            )
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                stateDescription = if (locked) "顺序已锁定" else "可调整顺序"
                customActions = accessibilityActions
            }
            .pointerInput(index, locked) {
                if (!locked) {
                    detectDragGesturesAfterLongPress(
                        onDragEnd = { dragDistance = 0f },
                        onDragCancel = { dragDistance = 0f },
                    ) { change, dragAmount ->
                        change.consume()
                        dragDistance += dragAmount.y
                        if (abs(dragDistance) >= 48f) {
                            onMove(index, index + if (dragDistance > 0) 1 else -1)
                            dragDistance = 0f
                        }
                    }
                }
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${index + 1}",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(point.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "%.5f, %.5f".format(point.coordinate.latitude, point.coordinate.longitude),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Icon(
                if (locked) Icons.Rounded.Lock else Icons.Rounded.DragHandle,
                contentDescription = if (locked) "顺序已锁定" else "长按拖动",
                tint = if (locked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun RouteSummary(plan: TourPlan) {
    val totalDistance = plan.legs.sumOf { it.distanceMeters }
    val largeText = LocalDensity.current.fontScale >= 1.5f
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Route,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "路线概览",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = 14.dp),
            )
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    maxItemsInEachRow = if (maxWidth < 440.dp || largeText) 2 else 3,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LightSummaryValue("${plan.orderedPoints.size} 站", "巡礼点", Modifier.weight(1f))
                    LightSummaryValue(
                        formatDuration(plan.estimatedDurationSeconds),
                        "预计用时",
                        Modifier.weight(1f),
                    )
                    LightSummaryValue(
                        if (totalDistance > 0) "%.1f km".format(totalDistance / 1000) else "—",
                        "总距离",
                        Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun LightSummaryValue(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            value,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium.merge(NumericTextStyle),
            maxLines = 2,
            textAlign = TextAlign.Center,
        )
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun PlannerTopBar(title: String, onBack: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .heightIn(min = 56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun ModeChip(
    mode: TravelMode,
    selected: TravelMode,
    label: String,
    onSelect: (TravelMode) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val icon = when (mode) {
        TravelMode.DRIVE -> Icons.Rounded.DirectionsCar
        TravelMode.BIKE -> Icons.AutoMirrored.Rounded.DirectionsBike
        TravelMode.WALK -> Icons.AutoMirrored.Rounded.DirectionsWalk
        TravelMode.TRANSIT -> Icons.Rounded.DirectionsBus
    }
    val isSelected = selected == mode
    Card(
        onClick = { onSelect(mode) },
        enabled = enabled,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        border = BorderStroke(
            1.dp,
            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .testTag("planner-mode-${mode.name}")
            .semantics {
                role = Role.RadioButton
                this.selected = isSelected
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 58.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(21.dp),
            )
            Text(
                label,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

private fun formatDuration(seconds: Double): String {
    val totalMinutes = (seconds / 60).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

internal fun formatTransitTime(value: String?, timeZone: String?): String = value?.let {
    runCatching {
        val parsed = OffsetDateTime.parse(it)
        val localTime = timeZone?.let(ZoneId::of)?.let(parsed::atZoneSameInstant)?.toLocalTime()
            ?: parsed.toLocalTime()
        localTime.format(DateTimeFormatter.ofPattern("HH:mm"))
    }.getOrDefault(it)
} ?: "时间未知"

private fun formatDistance(meters: Double): String =
    if (meters >= 1000.0) "%.1f km".format(meters / 1000.0) else "${meters.toInt()} m"
