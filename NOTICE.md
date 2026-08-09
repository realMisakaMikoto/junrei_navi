# 第三方服务、SDK、数据与署名

本项目自有代码采用 GPL-3.0-or-later，并附 `LICENSE` 末尾所载、仅用于 Google Navigation SDK for Android、Firebase Android SDK 与未修改的高德 Android 3D 地图 SDK 链接和分发的窄范围例外。该例外不改变任何第三方组件、服务或数据的许可与条款，也不替代高德开放平台技术服务许可。

v0.2.5 候选源码正在接入高德 Android SDK 与 Web 服务，但尚未发布。版权所有者已把未修改的高德 Android 3D 地图 SDK 纳入窄范围链接/分发例外；这不授予高德 SDK 本身的权利，也不解除 [`docs/V0.2.5_RELEASE_GATE.md`](docs/V0.2.5_RELEASE_GATE.md) 中的提供方书面许可、应用合规、隐私、地区数据和生产验收要求。

- 地图与道路导航：[Google Navigation SDK for Android](https://developers.google.com/maps/documentation/navigation/android-sdk)。地图与导航内容遵循 Google Maps Platform 的适用条款和署名要求。
- 路线矩阵、道路预览与公共交通：[Google Routes API](https://developers.google.com/maps/documentation/routes)。应用只显示规范化后的会话数据，并保留 Google Routes 署名。
- v0.2.5 候选中国地图显示：[高德地图 Android SDK](https://lbs.amap.com/api/android-sdk/summary/)。只有用户完成版本化隐私同意且行程被已批准地区数据明确分配给高德时才允许初始化；地图、Logo、审图号和内容须遵守高德及中国地图管理要求。
- v0.2.5 候选中国路线与坐标转换：[高德 Web 服务 API](https://lbs.amap.com/api/webservice/summary/)。Android 不持有 Web 服务 Key 或数字签名私钥；服务端只返回规范化、显式标记为 GCJ-02 的会话数据。
- 匿名鉴权与可选遥测：[Firebase Authentication、Analytics 与 Crashlytics](https://firebase.google.com/)。Analytics 和 Crashlytics 在本应用中默认关闭，分别取得用户同意后启用。
- 动漫元数据：[Bangumi API](https://bangumi.github.io/api/)。
- 巡礼点与截图：[Anitabi API](https://github.com/anitabi/anitabi.cn-document/blob/main/api.md)，仅用于非商业用途；数据采用 CC BY-NC-SA 4.0。应用保留截图的 `origin` 与 `originURL`，只缓存用户实际访问的数据。
- v0.2.4 日本国界判定：[Natural Earth](https://www.naturalearthdata.com/) 1:10m Admin-0 Countries v5.1.1。公开稳定版随包携带从该固定版本提取的日本 MultiPolygon；Natural Earth 地图数据属于公共领域，具体来源与校验值见资产目录内的 `NOTICE.txt`。它不得作为 v0.2.5 中国生产地区判定数据。
- 服务端运行时与依赖：Node.js、Fastify、SQLite 及其依赖分别遵守各自开源许可证。

v0.2.1 不再使用 MapLibre、OpenFreeMap、OpenMapTiles、openrouteservice 或 Transitous。相关 v0.2.0 文档与发布记录仅作为历史证据保留，不代表当前版本仍加载或请求这些服务。

Google、Firebase、Bangumi、Anitabi 及其他第三方服务均为独立服务，不由本项目运营，也不由本项目保证可用性。详见 [隐私说明](PRIVACY.md)。
