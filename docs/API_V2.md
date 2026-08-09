# 巡礼手帳路线 API v2

v2 在现有 Firebase 匿名鉴权、HTTPS、JSON、HMAC-IP 防滥用和会话内路线边界之上增加独立地区判定、地图提供方分流、坐标系元数据和客户端/地区数据版本门禁。请求不能指定提供方，后端必须根据全部 WGS84 位置重新判定。

当前 v0.2.5 生产门禁仍为 `BLOCKED`；没有已批准地区数据时，后端必须报告未就绪并拒绝路线请求。详见 [`V0.2.5_RELEASE_GATE.md`](V0.2.5_RELEASE_GATE.md)。

## 公共读取端点

### `GET /v2/health`

返回数据库、地区数据、Google 和高德四项独立就绪状态。只有全部必需项均为 `ok` 时返回 HTTP 200；任一项不可用时返回 503。响应不包含 Key、额度数值、路径、主机、数据库路径或上游错误正文。

### `GET /v2/policy`

返回：

- `apiVersion`: 固定为 `2`
- `regionDataVersion`: 客户端后续请求必须发送的精确版本
- `minimumAppVersion`: 服务端允许的最低稳定应用版本
- `v1SunsetAt`: UTC `Z` 时间
- `providers.google` / `providers.amap`: `enabled` 或 `disabled`

该端点用于启动协商，不要求 Firebase Token。它不能覆盖客户端本机地区分类，也不能使缺失的生产地区资产变为可用。

## v2 POST 通用要求

每个 POST 请求都必须：

- 经可信反向代理使用 HTTPS，并设置 `Content-Type: application/json`。
- 设置 `Authorization: Bearer <Firebase anonymous ID token>`。
- 设置 `X-Anitabi-App-Version: major.minor.patch`；只接受稳定三段版本，低于最低版本或带预发布/构建后缀时返回 `426 CLIENT_UPGRADE_REQUIRED`。
- 设置 `X-Anitabi-Region-Data-Version`，值必须与后端当前批准数据完全相同；缺失或不一致时返回 `409 REGION_DATA_OUTDATED`。
- 只传 WGS84 纬度/经度，不传 `provider`、`coordinateSystem` 或客户端判定的地区。

后端在任何上游调用或额度预留前检查起点和全部目的地。边界、重叠、损坏数据或无法确定的点返回 `422 REGION_UNRESOLVED`；一次行程需要两个提供方时返回 `422 MIXED_MAP_PROVIDERS`。

## 路线端点

### `POST /v2/matrix`

请求体沿用 v1 的 2–10 个 `coordinates`、道路 `mode`、`objective` 和可选 `departureTime`。Google 返回 WGS84；高德请求先通过官方坐标转换，上游 GCJ-02 结果以相同 precision-5 encoded-polyline 算法规范化（若响应包含折线）。

### `POST /v2/route`

请求体沿用 v1 的 2–12 个 `locations`；公交仍只允许两个端点。高德不支持的到达时间或交通方式筛选必须在调用上游前拒绝，客户端也应在高德模式禁用对应控件。

### `POST /v2/navigation/reserve`

请求体为：

```json
{
  "origin": { "latitude": 0, "longitude": 0 },
  "destinations": [{ "latitude": 0, "longitude": 0 }]
}
```

`destinations` 为 1–25 个。Google 行程在调用 Navigation SDK 前原子预留目的地额度，返回 `GOOGLE_NAVIGATION_SDK`；中国大陆高德行程返回 `EXTERNAL_AMAP_MAINLAND`，由用户逐段主动交给高德地图 App，不调用 Google Navigation SDK。

## 成功响应元数据

矩阵和路线响应在规范化业务字段之外必须包含：

- `provider`: `GOOGLE` 或 `AMAP`
- `coordinateSystem`: `WGS84` 或 `GCJ02`
- `regionDataVersion`: 后端实际使用的版本

导航预留还包含 `reservedDestinations` 和 `executionStrategy`。客户端必须同时核对提供方、坐标系和数据版本；不一致时丢弃响应，不能猜测、重标或在另一提供方地图上绘制。

## v1 兼容窗口

- v0.2.5 正式发布时间由部署配置记录，`v1SunsetAt` 固定为其后 14 天。
- 兼容期内，中国大陆或中国官方地图专用区的 v1 矩阵/路线请求立即返回 `426`，不会调用 Google。
- 到达停止时间后，全部 v1 POST 在鉴权和上游之前返回 `426`；`GET /v1/health` 可保留用于旧部署诊断。
- Android v0.2.5 不自动回退 v1。兼容端点只服务既有客户端，不能用来绕过 v2 判定或额度。

## 错误与日志

错误体固定为 `{ "error": { "code": "...", "message": "..." } }`。公开代码包括原 v1 错误，以及 `MIXED_MAP_PROVIDERS`、`MIXED_TRANSIT_REGIONS`、`REGION_UNRESOLVED`、`REGION_DATA_OUTDATED` 和 `CLIENT_UPGRADE_REQUIRED`。日志只记录端点模板、状态、延迟区间和安全错误码，不记录 Token、原始 IP、坐标、作品/搜索文本、请求体或上游正文。
