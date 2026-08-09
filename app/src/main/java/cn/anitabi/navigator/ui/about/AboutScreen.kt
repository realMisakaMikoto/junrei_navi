package cn.anitabi.navigator.ui.about

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.anitabi.navigator.BuildConfig
import cn.anitabi.navigator.R
import cn.anitabi.navigator.telemetry.TelemetryConsentController
import cn.anitabi.navigator.ui.theme.Ink
import cn.anitabi.navigator.ui.theme.MutedInk
import cn.anitabi.navigator.ui.theme.Paper
import cn.anitabi.navigator.ui.theme.Vermilion

@Composable
fun AboutScreen(
    onBack: () -> Unit,
    telemetryConsentController: TelemetryConsentController,
    amapPrivacyConsentEnabled: Boolean = false,
    onAmapPrivacyConsentChange: (Boolean) -> Unit = {},
) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    var telemetryConsent by remember(telemetryConsentController) {
        mutableStateOf(telemetryConsentController.currentConsent())
    }
    var amapPrivacyConsent by remember(amapPrivacyConsentEnabled) {
        mutableStateOf(amapPrivacyConsentEnabled)
    }

    Surface(
        color = Paper,
        modifier = Modifier.fillMaxSize().testTag("about-screen"),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AboutTopBar(onBack)
            LazyColumn(
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item {
                    AppIdentity(modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth())
                }
                item {
                    AboutSection(
                        title = "隐私",
                        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                    ) {
                        Text(
                            "不含广告或云同步。路线与进度只保存在本机；路线响应不会持久化。",
                            color = Ink,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            "规划或偏航重算时，必要坐标、模式和出发时间会经自建服务发送给当前地区对应的路线提供方。Firebase 匿名身份不需要邮箱、姓名或密码；Analytics 与 Crashlytics 默认关闭。",
                            color = MutedInk,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                item {
                    Column(modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                        SectionHeading("高德地图隐私授权")
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            TelemetryConsentRow(
                                title = "允许高德地图 SDK",
                                description = "仅在选择高德提供方时使用。关闭会先销毁正在显示的高德地图，再撤回 SDK 隐私授权；Google 地区和本地行程仍可使用。",
                                checked = amapPrivacyConsent,
                                onCheckedChange = { enabled ->
                                    onAmapPrivacyConsentChange(enabled)
                                    amapPrivacyConsent = enabled
                                },
                            )
                        }
                    }
                }
                item {
                    Column(modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                        SectionHeading("可选遥测")
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Column {
                                Text(
                                    "两项默认关闭，可分别选择加入并随时撤回。不会记录坐标、动漫名、搜索词或路线正文。",
                                    color = MutedInk,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(16.dp),
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                TelemetryConsentRow(
                                    title = "匿名使用分析",
                                    description = "允许 Firebase Analytics 进行基础测量；应用自定义事件仅限版本、设备能力、模式、点数区间、延迟区间与错误类型。",
                                    checked = telemetryConsent.analyticsEnabled,
                                    onCheckedChange = { enabled ->
                                        telemetryConsentController.setAnalyticsConsent(enabled)
                                        telemetryConsent = telemetryConsent.copy(analyticsEnabled = enabled)
                                    },
                                )
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    modifier = Modifier.padding(start = 16.dp),
                                )
                                TelemetryConsentRow(
                                    title = "崩溃报告",
                                    description = "允许 Firebase Crashlytics 在崩溃后发送技术报告；关闭后会立即删除尚未发送的报告，并在下次启动完全停止采集。",
                                    checked = telemetryConsent.crashlyticsEnabled,
                                    onCheckedChange = { enabled ->
                                        telemetryConsentController.setCrashlyticsConsent(enabled)
                                        telemetryConsent = telemetryConsent.copy(crashlyticsEnabled = enabled)
                                    },
                                )
                            }
                        }
                    }
                }
                item {
                    AboutSection(
                        title = "地图、路线与公共交通",
                        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                    ) {
                        SourceLink("Google Navigation SDK 与 Routes API") {
                            uriHandler.openUri("https://developers.google.com/maps/documentation/navigation/android-sdk")
                        }
                        SourceLink("高德地图 Android SDK") {
                            uriHandler.openUri("https://lbs.amap.com/api/android-sdk/summary")
                        }
                        SourceLink("Firebase") {
                            uriHandler.openUri("https://firebase.google.com/")
                        }
                        Text(
                            "路线会先按起点和所有目的地解析为单一地图提供方。Google 与高德的路线和地图内容不会混合显示；高德道路及公交由后端规划，导航执行交给高德地图。日本公交仍在本机排序并逐段交给 Google 地图。",
                            color = MutedInk,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Text(
                            "地区判定只使用经过批准且带版本和校验信息的离线数据；数据缺失、损坏、边界重叠或无法判定时会停止地图与路线请求。日本与日本以外点不能混合生成同一条公交行程。",
                            color = MutedInk,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                item {
                    AboutSection(
                        title = "动漫与巡礼数据",
                        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                    ) {
                        SourceLink("Bangumi API") { uriHandler.openUri("https://bangumi.github.io/api/") }
                        SourceLink("Anitabi API · CC BY-NC-SA 4.0") {
                            uriHandler.openUri("https://github.com/anitabi/anitabi.cn-document/blob/main/api.md")
                        }
                        Text(
                            "只缓存用户实际访问的作品；截图旁保留原始来源和链接。",
                            color = MutedInk,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                item {
                    AboutSection(
                        title = "开源与联系",
                        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                    ) {
                        Text(
                            "应用代码采用 GPL-3.0-or-later，并附仅用于 Google Navigation/Firebase SDK 的窄范围链接例外；项目自有代码仍保持开源。第三方服务和数据分别遵循其自身条款。",
                            color = Ink,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        SourceLink("源代码、GPL 与链接例外") {
                            uriHandler.openUri("https://github.com/realMisakaMikoto/anitabi/blob/main/LICENSE")
                        }
                        SourceLink("项目联系人：realMisakaMikoto") {
                            uriHandler.openUri("https://github.com/realMisakaMikoto")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutTopBar(onBack: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .heightIn(min = 56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
            }
            Text(
                "关于",
                color = Ink,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun AppIdentity(modifier: Modifier = Modifier) {
    Row(modifier = modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Image(
                painter = painterResource(R.drawable.anitabi_brand_mark),
                contentDescription = "巡礼手帳标识",
                modifier = Modifier.padding(12.dp).size(52.dp),
            )
        }
        Column(modifier = Modifier.padding(start = 18.dp)) {
            Text("巡礼手帳", color = Ink, style = MaterialTheme.typography.headlineMedium)
            Text(
                "版本 ${BuildConfig.VERSION_NAME}",
                color = MutedInk,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                "本地路线记录  ·  可选遥测  ·  GPL-3.0-or-later",
                color = MutedInk,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun TelemetryConsentRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .semantics(mergeDescendants = true) {
                stateDescription = if (checked) "已开启" else "已关闭"
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Ink, fontWeight = FontWeight.SemiBold)
            Text(
                description,
                color = MutedInk,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
        )
    }
}

@Composable
private fun AboutSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier) {
        SectionHeading(title)
        Surface(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun SectionHeading(title: String) {
    Text(
        title,
        color = Ink,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun SourceLink(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 2.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Vermilion, modifier = Modifier.weight(1f))
        Icon(
            Icons.AutoMirrored.Rounded.OpenInNew,
            contentDescription = "打开网页",
            tint = Vermilion,
            modifier = Modifier.size(20.dp),
        )
    }
}
