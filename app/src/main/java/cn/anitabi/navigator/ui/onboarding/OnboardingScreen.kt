package cn.anitabi.navigator.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.anitabi.navigator.R
import cn.anitabi.navigator.navigation.AndroidLocationProvider
import cn.anitabi.navigator.security.AppSettingsStore
import cn.anitabi.navigator.ui.theme.Ink
import cn.anitabi.navigator.ui.theme.Moss
import cn.anitabi.navigator.ui.theme.MutedInk
import cn.anitabi.navigator.ui.theme.Paper
import cn.anitabi.navigator.ui.theme.Vermilion

private const val WELCOME_STEP = 0
private const val PERMISSION_STEP = 1
private const val SERVICE_STEP = 2
private val StepLabels = listOf("了解", "权限", "服务")

@Composable
fun OnboardingRoute(
    settingsStore: AppSettingsStore,
    onAmapPrivacyConsentChanged: (Boolean) -> Unit,
    onComplete: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var currentStep by rememberSaveable { mutableIntStateOf(WELCOME_STEP) }
    var hasLocationPermission by remember {
        mutableStateOf(AndroidLocationProvider.hasLocationPermission(context))
    }
    var hasNotificationPermission by remember {
        mutableStateOf(hasNotificationPermission(context))
    }
    var batteryOptimizationDisabled by remember {
        mutableStateOf(isIgnoringBatteryOptimizations(context))
    }
    var hasOverlayPermission by remember {
        mutableStateOf(Settings.canDrawOverlays(context))
    }
    var permissionAttempted by rememberSaveable { mutableStateOf(false) }
    var permissionSettingsError by rememberSaveable { mutableStateOf<String?>(null) }
    var setupError by rememberSaveable { mutableStateOf<String?>(null) }
    var amapPrivacyAccepted by rememberSaveable {
        mutableStateOf(settingsStore.hasCurrentAmapPrivacyConsent())
    }

    fun refreshPermissions() {
        hasLocationPermission = AndroidLocationProvider.hasLocationPermission(context)
        hasNotificationPermission = hasNotificationPermission(context)
        batteryOptimizationDisabled = isIgnoringBatteryOptimizations(context)
        hasOverlayPermission = Settings.canDrawOverlays(context)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionAttempted = true
        refreshPermissions()
    }

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        refreshPermissions()
    }

    val openSystemSettings: (Intent) -> Unit = { intent ->
        permissionSettingsError = null
        if (runCatching { settingsLauncher.launch(intent) }.isFailure) {
            runCatching { settingsLauncher.launch(appDetailsSettingsIntent(context)) }
                .onFailure {
                    permissionSettingsError = "无法打开系统设置，请手动在设置中找到“巡礼手帳”"
                }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val goBack: () -> Unit = {
        if (currentStep > WELCOME_STEP) {
            currentStep -= 1
            permissionSettingsError = null
            setupError = null
        }
    }
    BackHandler(enabled = currentStep > WELCOME_STEP, onBack = goBack)

    val requestPermissions: () -> Unit = {
        refreshPermissions()
        if (hasLocationPermission && hasNotificationPermission) {
            currentStep = SERVICE_STEP
        } else {
            val missingPermissions = buildList {
                if (!hasLocationPermission) {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
                if (!hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    val finishOnboarding: () -> Unit = {
        refreshPermissions()
        val readiness = OnboardingReadiness(
            hasLocationPermission = hasLocationPermission,
            hasNotificationPermission = hasNotificationPermission,
        )
        if (!readiness.canFinish) {
            currentStep = PERMISSION_STEP
            permissionAttempted = true
        } else {
            runCatching {
                settingsStore.setAmapPrivacyConsent(amapPrivacyAccepted)
                settingsStore.markOnboardingComplete()
                onAmapPrivacyConsentChanged(amapPrivacyAccepted)
            }
                .onSuccess { onComplete() }
                .onFailure { setupError = "设置无法保存，请释放设备空间后重试" }
        }
    }

    Surface(color = Paper, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            OnboardingHeader(currentStep = currentStep, onBack = goBack)
            BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
                val horizontalPadding = if (maxWidth >= 600.dp) 32.dp else 20.dp
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = 640.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = horizontalPadding, vertical = 24.dp),
                ) {
                    when (currentStep) {
                        WELCOME_STEP -> WelcomeStep()
                        PERMISSION_STEP -> PermissionStep(
                            hasLocationPermission = hasLocationPermission,
                            hasNotificationPermission = hasNotificationPermission,
                            batteryOptimizationDisabled = batteryOptimizationDisabled,
                            hasOverlayPermission = hasOverlayPermission,
                            permissionAttempted = permissionAttempted,
                            settingsError = permissionSettingsError,
                            onOpenSettings = {
                                openSystemSettings(appDetailsSettingsIntent(context))
                            },
                            onOpenBatterySettings = {
                                openSystemSettings(
                                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                                )
                            },
                            onOpenOverlaySettings = {
                                openSystemSettings(overlaySettingsIntent(context))
                            },
                        )
                        else -> ServiceStep(
                            error = setupError,
                            amapPrivacyAccepted = amapPrivacyAccepted,
                            onAmapPrivacyAcceptedChange = { accepted ->
                                amapPrivacyAccepted = accepted
                                setupError = null
                            },
                        )
                    }
                }
            }
            OnboardingActionBar(
                currentStep = currentStep,
                permissionsReady = hasLocationPermission,
                onClick = when (currentStep) {
                    WELCOME_STEP -> ({ currentStep = PERMISSION_STEP })
                    PERMISSION_STEP -> requestPermissions
                    else -> finishOnboarding
                },
            )
        }
    }
}

@Composable
private fun OnboardingHeader(currentStep: Int, onBack: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.statusBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (currentStep > WELCOME_STEP) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回上一步")
                    }
                } else {
                    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        androidx.compose.foundation.Image(
                            painter = painterResource(R.drawable.anitabi_brand_mark),
                            contentDescription = "巡礼手帳标识",
                            modifier = Modifier.size(34.dp),
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("初次使用设置", style = MaterialTheme.typography.titleMedium, color = Ink)
                    Text(
                        "巡礼手帳",
                        style = MaterialTheme.typography.labelMedium,
                        color = MutedInk,
                    )
                }
            }
            StepProgress(currentStep)
        }
    }
}

@Composable
private fun StepProgress(currentStep: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StepLabels.forEachIndexed { index, label ->
            val isCurrent = index == currentStep
            val isComplete = index < currentStep
            Column(
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        selected = isCurrent
                        stateDescription = when {
                            isCurrent -> "当前步骤"
                            isComplete -> "已完成"
                            else -> "未开始"
                        }
                    },
            ) {
                Text(
                    "${index + 1}  $label",
                    color = if (isCurrent || isComplete) Ink else MutedInk,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            if (isCurrent || isComplete) Vermilion else MaterialTheme.colorScheme.outlineVariant,
                        ),
                )
            }
        }
    }
}

@Composable
private fun WelcomeStep() {
    Text("开始前的三件事", style = MaterialTheme.typography.headlineMedium, color = Ink)
    Text(
        "确认权限、路线服务和隐私设置后，就可以进入地图。这个导览只会出现一次。",
        style = MaterialTheme.typography.bodyLarge,
        color = MutedInk,
        modifier = Modifier.padding(top = 10.dp),
    )
    InformationBlock(
        title = "定位与通知",
        body = "定位用于选择当前位置、导航和偏航重算；通知用于锁屏和后台导航。",
        modifier = Modifier.padding(top = 24.dp),
    )
    InformationBlock(
        title = "路线与费用",
        body = "路线通过项目自建服务请求 Google。达到项目共享的月度免费额度上限后会停止请求，不会自动产生额外费用。",
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun PermissionStep(
    hasLocationPermission: Boolean,
    hasNotificationPermission: Boolean,
    batteryOptimizationDisabled: Boolean,
    hasOverlayPermission: Boolean,
    permissionAttempted: Boolean,
    settingsError: String?,
    onOpenSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
) {
    val permissionsReady = hasLocationPermission
    Text("设置导航所需权限", style = MaterialTheme.typography.headlineMedium, color = Ink)
    Text(
        "先完成系统授权，再按需要调整后台设置。巡礼手帳只会在对应功能需要时使用这些权限。",
        style = MaterialTheme.typography.bodyLarge,
        color = MutedInk,
        modifier = Modifier.padding(top = 10.dp),
    )
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            PermissionStatus(
                icon = Icons.Rounded.LocationOn,
                title = "定位",
                description = "选择当前位置、导航和偏航重算",
                granted = hasLocationPermission,
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(start = 64.dp),
            )
            PermissionStatus(
                icon = Icons.Rounded.NotificationsActive,
                title = "通知",
                description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    "可选；锁屏或切到后台时继续显示导航"
                } else {
                    "此 Android 版本无需单独授权"
                },
                granted = hasNotificationPermission,
            )
        }
    }
    Text(
        "后台导航建议",
        style = MaterialTheme.typography.titleLarge,
        color = Ink,
        modifier = Modifier.padding(top = 24.dp),
    )
    Text(
        "以下设置不会阻止你继续，但能减少锁屏或切换应用后导航被系统暂停。",
        style = MaterialTheme.typography.bodyMedium,
        color = MutedInk,
        modifier = Modifier.padding(top = 6.dp),
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .testTag("onboarding-background-guidance"),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            BackgroundSetupStatus(
                icon = Icons.Rounded.BatterySaver,
                title = "关闭电池优化",
                description = "在系统列表中找到巡礼手帳并设为“不优化”，减少锁屏后导航被暂停的可能。",
                stateLabel = if (batteryOptimizationDisabled) "已关闭电池优化" else "建议设置",
                completed = batteryOptimizationDisabled,
                actionLabel = if (batteryOptimizationDisabled) "查看设置" else "去关闭",
                actionTag = "onboarding-battery-settings",
                onClick = onOpenBatterySettings,
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(start = 64.dp),
            )
            BackgroundSetupStatus(
                icon = Icons.Rounded.Lock,
                title = "在后台锁定应用",
                description = "打开最近任务，长按或下拉巡礼手帳卡片并选择锁定。不同系统入口可能不同；没有此选项可跳过。",
                stateLabel = "需手动完成",
                completed = false,
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(start = 64.dp),
            )
            BackgroundSetupStatus(
                icon = Icons.Rounded.Layers,
                title = "允许悬浮窗",
                description = "日本公交切到 Google 地图后显示可拖动控制入口；不开启时仍可使用可见通知。",
                stateLabel = if (hasOverlayPermission) "已允许悬浮窗" else "尚未允许",
                completed = hasOverlayPermission,
                actionLabel = if (hasOverlayPermission) "查看设置" else "去开启",
                actionTag = "onboarding-overlay-settings",
                onClick = onOpenOverlaySettings,
            )
        }
    }
    settingsError?.let {
        Text(
            it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(top = 12.dp)
                .testTag("onboarding-settings-error")
                .semantics { liveRegion = LiveRegionMode.Assertive },
        )
    }
    if (permissionAttempted && !permissionsReady) {
        Text(
            onboardingPermissionError(hasLocationPermission, hasNotificationPermission).orEmpty(),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(top = 16.dp)
                .semantics { liveRegion = LiveRegionMode.Assertive },
        )
        OutlinedButton(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 52.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(Icons.Rounded.Settings, contentDescription = null)
            Text("打开系统设置", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun BackgroundSetupStatus(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    stateLabel: String,
    completed: Boolean,
    actionLabel: String? = null,
    actionTag: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.semantics(mergeDescendants = true) {
                stateDescription = stateLabel
            },
            verticalAlignment = Alignment.Top,
        ) {
            Icon(icon, contentDescription = null, tint = if (completed) Moss else Vermilion)
            Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Ink)
                Text(
                    stateLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (completed) Moss else Vermilion,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedInk,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        if (actionLabel != null && onClick != null) {
            OutlinedButton(
                onClick = onClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 40.dp, top = 12.dp)
                    .heightIn(min = 48.dp)
                    .then(if (actionTag == null) Modifier else Modifier.testTag(actionTag)),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun PermissionStatus(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    granted: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                stateDescription = if (granted) "已授权" else "未授权"
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (granted) Moss else Vermilion)
        Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Ink)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MutedInk)
        }
        Icon(
            if (granted) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (granted) Moss else MutedInk,
        )
    }
}

@Composable
private fun ServiceStep(
    error: String?,
    amapPrivacyAccepted: Boolean,
    onAmapPrivacyAcceptedChange: (Boolean) -> Unit,
) {
    Text(
        "路线服务已准备好",
        style = MaterialTheme.typography.headlineMedium,
        color = Ink,
        modifier = Modifier.testTag("onboarding-service-step"),
    )
    Text(
        "无需申请或填写 API Key。首次生成路线时，应用会自动创建 Firebase 匿名标识，用于验证对项目路线服务的访问。",
        style = MaterialTheme.typography.bodyLarge,
        color = MutedInk,
        modifier = Modifier.padding(top = 10.dp),
    )
    InformationBlock(
        title = "规划路线时发送",
        body = "坐标、出行方式和必要的出发时间。不会发送动漫名、搜索词或路线正文日志；新路线需要联网。",
        modifier = Modifier.padding(top = 24.dp),
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .testTag("onboarding-amap-privacy"),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = amapPrivacyAccepted,
                onCheckedChange = onAmapPrivacyAcceptedChange,
            )
            Text(
                "可选同意：大陆及仅允许官方地图的地区会使用高德地图 SDK；地图显示前会向高德提交隐私授权状态，定位和路线坐标仅在选择高德提供方时处理。不同意仍可使用不依赖高德的功能。",
                color = Ink,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
    InformationBlock(
        title = "匿名标识与遥测",
        body = "匿名标识不需要邮箱、姓名或密码。Analytics 与 Crashlytics 默认关闭，只有你明确同意才会启用，并可随时撤回。",
        modifier = Modifier.padding(top = 12.dp),
    )
    error?.let {
        Text(
            it,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .padding(top = 16.dp)
                .testTag("onboarding-service-error")
                .semantics { liveRegion = LiveRegionMode.Assertive },
        )
    }
}

@Composable
private fun InformationBlock(title: String, body: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Ink)
            Text(
                body,
                color = MutedInk,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun OnboardingActionBar(
    currentStep: Int,
    permissionsReady: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                onClick = onClick,
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .testTag(
                        when (currentStep) {
                            WELCOME_STEP -> "onboarding-start"
                            PERMISSION_STEP -> if (permissionsReady) {
                                "onboarding-permission-continue"
                            } else {
                                "onboarding-permission-request"
                            }
                            else -> "onboarding-service-submit"
                        },
                    ),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Vermilion),
            ) {
                Text(
                    when (currentStep) {
                        WELCOME_STEP -> "开始设置"
                        PERMISSION_STEP -> if (permissionsReady) "主要权限已就绪，继续" else "授权定位与通知"
                        else -> "确认并进入地图"
                    },
                )
            }
        }
    }
}

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

private fun appDetailsSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())

private fun overlaySettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri())

private fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
        ?.isIgnoringBatteryOptimizations(context.packageName) == true
