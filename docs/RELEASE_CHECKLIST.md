# v0.2.5 发布检查清单

v0.2.5 将中国大陆/中国官方地图专用区与其他地区严格分流到不同地图提供方。任何书面授权、地区数据许可/审图、独立密钥、额度或真实隔离证据缺失时，发布状态必须保持 `BLOCKED`。合成数据、JVM、模拟器、Debug APK 或网页文档均不能替代这些门禁。

每个 RC 或稳定候选在 `docs/releases/v0.2.5.md` 记录精确提交和实际证据。未执行项保持未勾选；历史 v0.2.4 及更早记录不得改写。

## 硬发布门禁

- [ ] Google 书面确认同一 Android 应用按行程地区互斥使用 Google Navigation/Routes 与高德地图/Web 服务的具体方式，且 Google 内容不会与非 Google 地图共同显示或混用。
- [ ] 高德书面确认 Android 地图 SDK、Web 服务、坐标转换、路线规划和高德地图 App URI 交接的实际用途、包名、签名和生产域名。
- [ ] 版权所有者已明确处理 `LICENSE` 中尚未覆盖高德 SDK 的链接/分发例外，并完成第三方 SDK 许可复核。
- [ ] 中国生产地区数据来自官方或有明确再分发权的渠道，具备来源、版本、许可、源/派生 SHA-256、审图机关、审图号/结论和审核日期。
- [ ] Android 与后端生产数据字节/规范化 SHA-256、`regionDataVersion` 和全部权威分类夹具完全一致。
- [ ] Android 高德 Key 与高德 Web 服务 Key/数字签名私钥相互独立；实际值均位于仓库和附件外的受限 secret 系统。
- [ ] Google/高德各计费类别的生产日/月硬上限、预算告警、责任人和恢复流程已书面确认。
- [ ] v0.2.5 精确正式发布时间和其后 14 天的 UTC v1 停止时间已记录。
- [ ] 隐私说明、首次同意、关于页、第三方署名、地图审核展示和应用商店披露完成复核。

门禁的证据格式和当前状态见 [`V0.2.5_RELEASE_GATE.md`](V0.2.5_RELEASE_GATE.md)。

## 版本、协议与地区数据

- [ ] Android 为 `versionCode=11`、`versionName=0.2.5`；后端 package/镜像为 `0.2.5` / `anitabi-api:0.2.5`。
- [ ] Room schema 保持 2；v0.2.4 记录覆盖安装后保留导览、选择、顺序、设置、进度和用户 WGS84 坐标，并在首次使用前重新分类旧行程。
- [ ] 生产构建不打包、挂载或回退到 TEST_ONLY 多边形、Natural Earth 中国边界、矩形、文本地址、时区或网络 Geocoder。
- [ ] 地区资产缺失、损坏、校验不符、审核元数据缺失、点在边界、地区重叠或版本不一致时，Android 与后端均 fail closed。
- [ ] 起点/当前位置与全部目的地都参与判定；中国大陆和官方地图专用区只得到 `AMAP`，香港/澳门/台湾/日本/其他只得到 `GOOGLE`。
- [ ] v2 POST 同时要求 Firebase Token、HTTPS JSON、`X-Anitabi-App-Version` 和 `X-Anitabi-Region-Data-Version`；请求体不能选择提供方或坐标系。
- [ ] `/v2/policy`、`/v2/health`、`/v2/matrix`、`/v2/route`、`/v2/navigation/reserve` 的字段、状态和安全错误体与 [`API_V2.md`](API_V2.md) 一致。
- [ ] v1 兼容期内中国 matrix/route 在上游和额度前返回 426；停止时间后全部 v1 POST 返回 426；Android v0.2.5 不自动回退 v1。

## 提供方与坐标系隔离

- [ ] Google Routes/Navigation 内容只在 Google 地图/SDK 使用，高德路线/内容只在高德地图使用；任何跨提供方响应被丢弃。
- [ ] 屏幕和进程 UI 所有权始终只有一个活动 MapView；提供方变化时先完成旧视图 lifecycle/destroy/cleanup，再创建新视图。
- [ ] 地图提供方只由固定行程分类或用户明确的提供方入口切换，不随 viewport、搜索结果滚动或瞬时相机范围变化。
- [ ] Room、设置、行程、外部交接和 v2 请求只使用 WGS84。高德官方转换后的 GCJ-02 只存在于内存响应并只画在高德地图。
- [ ] 每个 route/leg/step 的提供方、坐标系和地区版本与顶层元数据一致；不从提供方名称猜测坐标系，不重复偏移或反向转换。
- [ ] 搜索/选择界面按提供方分组并提供明确切换；混合提供方在定位、额度和上游前显示可操作错误。

## 高德 Android 与外部交接

- [ ] 固定使用经授权的 `com.amap.api:3dmap-location-search:11.2.000_loc11.2.000_sea9.8.0`，并记录发布时当前官方版本差异和继续固定旧版本的批准依据。
- [ ] `MapsInitializer.updatePrivacyShow` 与 `updatePrivacyAgree` 在任何高德 SDK 接口/MapView 之前调用；未同意、Key 缺失或生产地区数据未就绪时不初始化 SDK。
- [ ] Android Key 只通过忽略的本地 Gradle 属性/环境变量和 CI Secret 注入；Release 缺 Key 时构建失败。
- [ ] 高德 Marker/折线只接收明确 GCJ-02 坐标；保存点仍是 WGS84，官方 `CoordinateConverter` 只用于高德显示所需的点位转换。
- [ ] 驾车、公交、步行、骑行外部 URI 使用官方 `amapuri://route/plan/`、定向 `com.autonavi.minimap`、编码名称、WGS84 起终点和 `dev=1`，模式映射分别为 0/1/2/3。
- [ ] 高德 App 不可用或交接失败时只保留当前高德分段和明确重试，不打开 Google、不伪造 ETA/班次/到达结果。
- [ ] 到达时间和高德不支持的公交方式筛选在 UI 禁用且后端调用前拒绝；少走路/少换乘只映射到高德明确支持的策略。

## 后端、安全与费用

- [ ] AMap 只访问源码固定的官方 HTTPS 主机/路径；数字签名按参数名升序、包含 Web Key、拼接私钥后 MD5，Key/私钥只从只读文件读取。
- [ ] WGS84→GCJ-02 官方转换每批最多 40 个点；转换、逆地理、距离、v5 路线整组在任一请求前于 SQLite 原子预留。
- [ ] `upstream_usage` 为独立 STRICT 表，按 provider/bucket/UTC day/UTC month 原子限制；不修改旧 `quota_usage` CHECK 或 UID 历史行。
- [ ] 高德额度缺失、账本损坏、恢复回退或 billing disabled 时高德不可用；恢复同时关闭所有计费提供方，人工对账后才启用。
- [ ] AMap 全进程上游并发不超过配置上限；超时、重定向、超大/畸形 JSON、异常数字/坐标/折线和未知错误均安全失败。
- [ ] AMap 请求坐标最多六位小数；响应 GCJ-02 折线转换为 precision-5 encoded polyline，并带显式 `coordinateSystem=GCJ02`。
- [ ] 上游错误按官方代码区分每日/余额耗尽、QPS 限速、无路线和服务异常；不返回或记录 `info`、URL、Key、sig、坐标或响应正文。
- [ ] HMAC-IP 限速 Map 有界并可淘汰；日志继续只允许端点模板、状态、延迟区间和安全错误码。
- [ ] 容器非 root、只读根、cap-drop、no-new-privileges、loopback-only、资源限制、只读 secrets、v2 healthcheck 和七日一致性备份保持有效。
- [ ] 部署具备候选健康、精确切换、可重复回滚、备份/恢复和高德官方域名出口限制；不影响个人网站或无关容器。

## 自动验证

- [ ] 后端 `npm run typecheck`、`npm test`、`npm run build`、`npm audit --omit=dev` 和 Docker build 全部通过。
- [ ] 地区测试覆盖大陆、官方地图专用区、香港、澳门、台湾、日本、其他、孔洞、边界、重叠、无效坐标、损坏元数据/校验和与大资产限制。
- [ ] API 测试覆盖两个版本 header、最低稳定版本、v2 provider/CRS metadata、混合/未解析 422、地区版本 409、客户端/v1 426 和 health/policy readiness。
- [ ] AMap 测试覆盖签名、固定主机、六位坐标、≤40 转换、所有模式、逆地理、precision-5 折线、响应大小、超时、重定向、官方错误码、全局并发和 secret 脱敏。
- [ ] 12+ 并发 SQLite 连接证明 Google 月度和高德日/月额度不超限；组预留全成或全败，失败不退款，旧账本可直接打开。
- [ ] Android `testDebugUnitTest`、`lintDebug`、`assembleDebug`、`assembleDebugAndroidTest`、Release Lint/R8/assemble 和目标测试全部通过。
- [ ] Android 测试覆盖跨端 checksum 向量、起点判定、混合提供方、旧存储重分类、provider/CRS guard、一个活动 MapView、destroy-before-create、隐私顺序和四种 URI。
- [ ] 原有 200 点排序、10 点矩阵窗口、12 位置预览、25 目的地批次、日本公交状态机、离线恢复、悬浮/通知、首次导览和 Room 1→2 回归全部通过。
- [ ] tracked-source、Navigation R8、APK 内容和签名审计通过；APK 不含 Web Key、签名私钥、服务账号、VPS 凭据、keystore 或旧 Provider。
- [ ] API 26/API 37 均通过冷启动、首次导览、迁移、离线恢复、前台服务和无崩溃 instrumentation。

## 真实提供方、真机与发布

- [ ] 使用正式候选和受限测试额度证明中国请求从未到 Google、非中国请求从未到高德；证据只记录脱敏计数。
- [ ] 至少五段中国大陆路线覆盖所需模式、地图显示、提供方切换、断网、额度耗尽、交接失败、暂停/恢复和到达流程。
- [ ] 无线设备通过 `adb connect 192.168.31.36:5555` 显式选择唯一 transport；不运行会卸载/清数据的整套 connected Gradle 任务。
- [ ] 正式签名 v0.2.5 从公开 v0.2.4 原位覆盖，固定 RSA-4096 证书不变，Room 2 与用户进度保留。
- [ ] 精确 Release APK 在 API 26/API 37 下载、哈希、签名、版本、安装、冷启动、首次导览和 crash buffer 检查通过。
- [ ] RC 仅在全部硬门禁和候选部署证据完成后创建为 Prerelease；稳定版仅在 RC 闭环后创建为 Latest。
- [ ] `v0.2.5-release` GitHub Environment 要求独立复核且禁止发起人自批；受保护的获批提交 SHA 与标签提交完全一致。
- [ ] Environment 只允许受保护默认分支部署；发布从默认分支手动输入已存在的 `v0.2.5` / `v0.2.5-rc.N` 标签，通用 `v*` 标签不能触发该流程。
- [ ] Release 工作流所有第三方 Action 均固定到经独立核对的完整提交 SHA，且最小权限 `ANITABI_RELEASE_TOKEN` 只注入独立发布任务的最后一步。
- [ ] 工作流从受保护的私有 HTTPS 位置恢复地区资产，并核对完整文件 SHA-256、内部校验值和 `regionDataVersion`。
- [ ] 构建任务核对原始地区资产与 APK 内 `assets/approved_regions/territory_regions_v1.json` 的 SHA-256；独立发布任务下载只读构建产物后再次核对 APK 哈希和内置地区资产哈希。
- [ ] 发布任务在 `gh release create` 前立即重新解析远端标签对象和最终提交，并与构建时记录及受保护候选提交完全比对。
- [ ] 发布说明诚实列出双地图授权/数据版本、隐私变化、v1 停止时间、GMS/高德要求、额度、已知限制和未完成真机边界。
