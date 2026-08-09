# 地区判定数据格式 v1

v0.2.5 的 Android 与后端必须从同一份经授权、经审核的 WGS84 数据派生出字节一致的生产资产。当前仓库没有这份生产数据；缺失时两端都必须拒绝路线请求，不能回退到矩形、Natural Earth、中国地址文本、时区、网络 Geocoder 或任一地图提供方。

## JSON 文档

生产文档是一个 UTF-8 JSON 对象：

```json
{
  "schemaVersion": 1,
  "regionDataVersion": "approved-version",
  "source": {
    "name": "licensed source name",
    "url": "https://source.example/record"
  },
  "license": {
    "name": "redistribution terms",
    "url": "https://source.example/license"
  },
  "review": {
    "authority": "review authority",
    "approvalId": "review record identifier",
    "reviewedAt": "2026-01-01T00:00:00Z",
    "approved": true
  },
  "checksumSha256": "64 lowercase hexadecimal characters",
  "features": []
}
```

`features` 必须恰好提供完成判定所需的以下地区，允许每个地区由一个或多个 feature 组成：

- `MAINLAND_CHINA`
- `CHINA_OFFICIAL_MAP_ONLY`
- `HONG_KONG_SAR`
- `MACAO_SAR`
- `CHINA_TAIWAN`
- `JAPAN`

未进入上述任何多边形的确定点为 `OTHER`。feature 的 `geometry` 只允许 GeoJSON `Polygon` 或 `MultiPolygon`；坐标顺序为 `[longitude, latitude]`，坐标系固定为 WGS84。环必须闭合、至少有三个不同顶点且面积非零；孔洞使用 GeoJSON 环顺序表达。

每个 feature 使用紧凑对象 `{ "region": "...", "geometry": { ... } }`。它不是完整 GeoJSON `Feature`，没有 `properties` 或额外的 `type: "Feature"` 包装。

## 校验值

`checksumSha256` 是删除该字段后对文档执行以下规范化所得 UTF-8 字节的 SHA-256：

1. 对象键按 Unicode 码点顺序递归排序。
2. 数组顺序保持不变。
3. 字符串和数字使用 JSON 表示，不添加空白或换行。
4. 输出小写十六进制 SHA-256。

Android 与后端必须独立复算校验值，并分别验证 schema、元数据、几何边界和必需地区；只比较文件名或版本字符串不够。生产候选还必须证明两端打包/挂载资产的完整文件 SHA-256 相同。

## 判定规则

- 点在多边形边界上、同时命中两个地区、几何损坏、元数据缺失、校验值不符或版本不一致时，结果是 `REGION_UNRESOLVED`，不得调用任何地图上游。
- `MAINLAND_CHINA` 与 `CHINA_OFFICIAL_MAP_ONLY` 只允许高德；香港、澳门、台湾、日本和 `OTHER` 只允许 Google。
- 一次行程的当前位置/显式起点和全部目的地都必须解析，且只能得到一个提供方；否则返回 `MIXED_MAP_PROVIDERS` 或 `REGION_UNRESOLVED`。
- 客户端声明的地区、提供方或坐标系不能覆盖本地/后端的独立判定。v2 请求体只传 WGS84 坐标，后端响应才声明实际提供方、响应坐标系和数据版本。
- 测试可注入带明显 `TEST_ONLY` 标识的合成多边形，但测试资产不得被 Release source set、Docker context 或生产部署读取。

## 更新流程

每次数据更新都必须重新完成许可与地图审核核对、生成两端镜像、复算校验值、运行跨端一致性夹具，并更新 [`V0.2.5_RELEASE_GATE.md`](V0.2.5_RELEASE_GATE.md) 与精确发布记录。仅修改 `regionDataVersion` 或审核字段不能使未经批准的数据变成可发布数据。
