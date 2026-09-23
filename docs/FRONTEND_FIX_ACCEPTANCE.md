# PR #37 整合修复验收（进行中）

2026-09-23；基线 `6759c8d3e2e7dddb17db51f509e2a30f1d845b75`。沿用用户完整 v2 计划与新增定位点/手机朝向要求。未合并、Ready、发布或部署。

| 项目 | 当前事实 | 尚需完成 |
|---|---|---|
| F1 详情权威性 | 原13项一致性测试中7项失败；修复后19项一致性测试全过，保留20轮受控竞态 | 新 HEAD 整体/设备验证 |
| F2 完整性/重试 | 空/部分响应、独立图片元数据补充、缓存修复通过上述测试；相关64项核心测试全过 | 新 HEAD 整体/设备验证 |
| F3 视野选点 | 8项生产 VM 基线7失败；20项修复回归及2项数据准备屏障测试全过，已接入地图/按钮事件；14项发现界面契约复测通过 | 原生移动/布局期间即时点击验证 |
| F4 持久草稿 | 原5项恢复测试全部失败；持久草稿/恢复/手动顺序/保存行程保护共39项通过，包含道路默认时间模式及跨设备时区回归 | 真实后台 PID 死亡恢复；旧AppShell用例之间的草稿隔离修正复测 |
| F5 附近排序 | 仍在 Composable 中，风险已确认，未测得性能根因 | 基线、后台计算及次数/规模验证 |
| F6 加载性能 | 预算已固定；默认关闭的本地trace基础设施及8项测试通过，尚未接入测量 | B1/B2 同条件至少20次及完整矩阵 |
| F7 图片链路 | B0规范3/3、旧前缀0/3；修复后受控Android6/6成功，缩略图重试/全屏尺寸选择通过；公网桌面6张旧路径404、规范路径200且解码成功 | 真实样本Android绑定、离线缓存/迟到回调/重定向和双地图图片验收 |
| 新增定位显示 | 定位点、传感器与前台15秒新鲜位置更新已接线；位置状态12项/方向时序4项JVM全过；API26方向矩阵2项/图标2项通过 | 双地图原生与真实手机朝向验证 |

此表不宣称任何整项已经完成。旧CI、旧签名 APK 和旧截图仅保留各自历史范围，不能证明本次工作树通过。

## 定位显示官方约束

2026-09-23 核对 [Google Navigation GoogleMap](https://developers.google.com/maps/documentation/navigation/android-sdk/reference/com/google/android/gms/maps/GoogleMap)：`setLocationSource` 仅编译兼容，自定义源不工作。因此使用应用判区后的独立定位 Marker，不启用不可过滤的原生定位源，也不引入另一套 Maps SDK。

[Android Location](https://developer.android.com/reference/android/location/Location) 的 bearing 是移动方向，不能当手机朝向。方向来自北向旋转传感器，经[屏幕坐标重映射](https://developer.android.com/develop/sensors-and-location/sensors/sensors_position)和本地 [GeomagneticField](https://developer.android.com/reference/android/hardware/GeomagneticField) 磁偏角修正。未知、低精度、过期方向只显示圆点。

[Google Marker](https://developers.google.com/maps/documentation/navigation/android-sdk/reference/com/google/android/gms/maps/model/Marker)旋转顺时针，[高德 Marker](https://a.amap.com/lbs/static/unzip/Android_Map_Doc/3D/com/amap/api/maps/model/Marker.html)逆时针；适配器分别转换。定位点独立于作品成员/聚合/选点，朝向更新不得重算目录或抢回用户手动视野。模拟输入与真实手机方向验收分开记录。

## 证据入口

- [图片诊断](IMAGE_LOADING_DIAGNOSIS.md)：B0 APK与真实受控解码结果、支架错误及修正。
- [加载性能](DISCOVERY_LOAD_PERFORMANCE.md)：固定预算与尚未执行的测量。
- 本地 `build/frontend-fix-v2/b0-jvm/`：修复前24项/16失败/0框架错误的XML及源文件哈希。
- `build/frontend-fix-v2/f3-red-and-core-fixed/`：64项核心全过，同时8项视野基线7失败；首轮测试代码的可空返回类型编译错误已修正后才取得此结果。
- `build/frontend-fix-v2/viewport-draft-heading-fixed/`：20项视野、37项草稿/手动顺序、4项方向时序测试全部通过，共61项；源文件哈希与 XML 单独存档，不与64项混称一次运行。

## 集成构建与测试支架问题

2026-09-23 的集成 Debug 应用和测试 APK 构建成功，身份在 `build/frontend-fix-v2/integrated-0923-apks/identity.json`。全量 JVM 运行在新定位测试退出时未结束：只读线程栈确认 `runTest` 清理阶段不断推进仍活动的周期定位任务，JUnit `@After` 的 ViewModel 清理尚未执行。只停止了核对过父进程的本次测试 Executor；这次运行不计全量通过。修正方向为在每个 `runTest` 正文的 finally 中关闭 ViewModel，再重跑；不修改生产定位周期来迁就测试。记录为 `jvm-harness-stall.json`。

该测试清理修正后，全量运行完成517项，仅道路保存草稿的一项时间检查失败。根因是道路模型保留 `DEPART_AT` 默认值但不保存公交时间锚点；检查已限制到公交模式，保留其它一致性保护，并新增跨设备时区正向回归。第三次全量 **518项、0失败、0错误**，Debug应用/测试APK同时构建成功。证据与全部XML/源文件哈希在 `build/frontend-fix-v2/integrated-0923b-apks/identity.json` 和 `jvm/`。

该组精确APK在API26专用AVD保留数据更新：图片网络/真实Compose像素/重试/尺寸选择与方向矩阵/图标共7项全过，另一次发现界面14项全过。它们是不同执行，分别保留 `android-execution.json` 和 `discovery-ui-execution.json`。AppShell六项中4过2失败；只读检查确认测试包持久草稿已有一个合成选点，旧测试设置未隔离草稿。已加入每例前清空合成测试草稿、每例后还原原草稿的夹具边界，等待复测；不修改生产持久性来迎合旧测试。

最新 `integrated-0923c-apks`：应用 `a0eaf49a4cab375994ddcaada023c3a10b810924db95fbdd227927379293512a`，测试 `6e5add6e3a76ab6c80308199294facaa370f3362a5661bc3160865405acf902d`。**526项JVM、Debug/Release Lint、双Debug APK构建通过**，其中8项是尚未接入运行流程的trace单测。补上此前未显示的定位/草稿保存提示，并将独立AppShell用例的草稿恢复到测试前状态后，专用API26上的**15项发现UI＋6项AppShell共21项全部通过**。证据包括 `identity.json`、`build-result.json`、`jvm/`、双Lint XML和 `ui-execution.json`。这次未重跑7项HTTPS/方向测试，其通过记录仍只绑定上一组079f/d23c产物；新源码最终签名验收仍需统一复测。

2026-09-24 本地 Release R8、Google反射审计、高德7类/24成员审计及7项守卫变异测试通过；当前Debug APK内容审计与已暂存源码凭据审计通过。保留上游Navigation翻译资源及高德final-R资源的非致命构建警告，没有修改SDK或关闭现有检查。此处尚未构建新的受保护签名Release，也不宣称其最终DEX/设备验收通过。

API26 原生高德仍未实测；继续仅按用户之前批准的 API37 原生高德＋API26 UI/Google/安全回退解释覆盖。个人手机只读身份核对不等于新修复真机验收。
