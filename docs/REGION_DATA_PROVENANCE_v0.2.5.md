# v0.2.5 生产地区数据来源记录

本文件只记录公开来源、非敏感审核标识、哈希和派生步骤；不提交许可原件、账号、Token 或生产几何。生产资产仍位于 Git 忽略路径 `app/src/main/assets/approved_regions/territory_regions_v1.json`，发布时只能由受保护的私有 HTTPS 输入恢复。

## 结论

- 地区数据版本：`2025.09-cn-gs2024-0650-jp-gm2.1-r1`
- 完整生产文件 SHA-256：`a625c9511afe2d38389347b9e497b231e31f262930e9c5c45cedb201b23c1806`
- 文档内部规范化 SHA-256：`9a4cffd8f651a58a516d53683a121327525025bf613b561ef3fd9fb29774ddbd`
- 文件大小：3,815,499 bytes
- 几何预算：36 features、1,667 polygons、1,677 rings、129,547 positions
- 复核时间：`2026-08-09T14:52:08Z`

该文件能够分别判定 `MAINLAND_CHINA`、`CHINA_OFFICIAL_MAP_ONLY`、`HONG_KONG_SAR`、`MACAO_SAR`、`CHINA_TAIWAN`、`JAPAN` 和 `OTHER`。它只用于提供方政策判定，不作为底图、行政区划展示或路线几何返回。

## 中国来源

- 提供方：中华人民共和国自然资源部主办、国家基础地理信息中心承办的天地图服务中心。
- 正式下载页：<https://cloudcenter.tianditu.gov.cn/administrativeDivision/>
- 数据资源目录：<https://cloudcenter.tianditu.gov.cn/dataSource>
- 下载选项：中国 / 省 / GeoJSON。
- 页面标注的数据更新时间：2025 年 9 月；更新内容：局部数据更新。
- 页面标注审图号：`GS（2024）0650号`。
- 原始文件：`中国_省.geojson`，1,698,398 bytes。
- 原始文件 SHA-256：`3af8294f9ad61cc2bf84c1bb7e4bbf86a6336c68d754b699a0e6ddc33ef81486`。
- 原始结构：42 features，其中 34 个省级 `Polygon`/`MultiPolygon` 和 8 个境界线 `MultiLineString`。派生过程只读取面要素；源文件完整哈希固定了被忽略境界线的同一版本。

大陆 31 个省级面保持原坐标和环结构。香港、澳门单独映射。台湾源要素的 16 个 polygon components 中，以下三个经精确组件哈希固定为 `CHINA_OFFICIAL_MAP_ONLY`，其余 13 个保持为 `CHINA_TAIWAN`：

| 组件 SHA-256 | 经度范围 | 纬度范围 |
| --- | --- | --- |
| `746acfa6c5742bca6a796a5191f9580708f72a2ad1b7e39ae62914ffe695b67c` | 123.453570–123.570517 | 25.716332–25.783662 |
| `fa862fc273e6cd5dbb9e6a6ca7c699a8eea9818a2b6ac1f3becfda25dcf48607` | 123.676001–123.688549 | 25.919377–25.928623 |
| `43a1323b8fec892ed4834b067759bd181724a4961084287ed6135109454bd305` | 124.527811–124.593807 | 25.893209–25.960175 |

选择依赖组件内容哈希而不是数组序号或矩形阈值；源文件任何变化都会先因完整 SHA-256 不符而停止构建。

## 日本来源

- 提供方：Geospatial Information Authority of Japan (GSI)。
- 官方数据页：<https://www.gsi.go.jp/kankyochiri/gm_japan_e.html>
- 数据集：Global Map Japan version 2.1 Boundary（2015）。
- 官方下载：<https://www1.gsi.go.jp/geowww/globalmap-gsi/download/data/gm-japan/gm-jpn-bnd_u_2_1.zip>
- 原始归档：`gm-jpn-bnd_u_2_1.zip`，3,350,961 bytes。
- 原始归档 SHA-256：`826a2b54630f2d15376bc1831aafe77f72f107e1c39ef0034bb41b184330faaa`。
- 使用图层：`polbnda_jpn.shp`，2,914 polygon features。
- 派生：按源顺序、每批 100 个面使用固定版本 `polygon-clipping@0.15.7` 做 union，再依次合并批次；结果为 1,110 polygons。

## License and review

天地图行政区划页把该下载限定为地图可视化使用。生产资产会被打包进公开 APK，因而必须按可提取、可再分发的几何数据处理；它同时作为后端内部的地区提供方判定输入。天地图公开版权声明要求使用时注明来源，项目在关于页、隐私说明和本来源记录中保留来源。公开条款：

- <https://www.tianditu.gov.cn/about/service>
- <https://www.tianditu.gov.cn/about/copyright>

GSI 数据页明确适用 GSI Website Terms of Use；其当前条款引用 Public Data License 1.0，并要求注明来源，派生使用时说明已编辑：

- <https://www.gsi.go.jp/ENGLISH/page_e30286.html>
- <https://www.digital.go.jp/en/resources/open_data/public_data_license_v1.0>

生产文档的非敏感审核标识为 `GS(2024)0650; GSI-PDL1.0`。发布负责人于 2026-08-09 确认仓库外保存的许可覆盖 APK 再分发和后端使用；公开网页本身不替代该许可原件。本记录也不把这一地区数据复核解释为 Google/高德双提供方授权或整个 v0.2.5 发布门禁已经 READY。

## 坐标与派生规则

- 两个来源都提供经度、纬度顺序的地理坐标；天地图 GeoJSON 不含 `crs` 字段，GSI shapefile 的 `.prj` 标注 ITRF 1994 / GRS 80。
- 构建器不做 GCJ-02 偏移、逆偏移、插值、简化或精度截断，原始经纬度数值保持不变，并按 WGS84-compatible longitude/latitude 仅用于国家/地区级点落面判定。
- Android 持久化和 v2 请求仍只接受 WGS84；高德 GCJ-02 只来自高德官方转换或上游响应，绝不写入该地区资产。
- 任何点落在多边形边界、多个地区重叠、数据缺失、哈希不符或版本不一致时，两端均 fail closed。

## 可重复构建

构建器位于 `backend/tools/build-region-data.mjs`，依赖已固定在 `backend/package-lock.json`。它同时验证两个原始下载 SHA-256、中国省级代码集合、日本源要素数量、官方地图专用区组件哈希和日本溶解结果数量；任何不一致都会停止且不覆盖现有输出。生产 Docker 镜像不会复制 `backend/tools`。

```powershell
Set-Location backend
npm ci
node tools/build-region-data.mjs `
  --china C:\approved-input\china-provinces.geojson `
  --japan-archive C:\approved-input\gm-jpn-bnd_u_2_1.zip `
  --japan-shp C:\approved-input\gm-jpn-bnd_u_2_1\polbnda_jpn.shp `
  --output ..\app\src\main\assets\approved_regions\territory_regions_v1.json
```

构建结果必须再次通过后端 `TerritoryRegionClassifier.load`、Android `packaged production region asset is approved when supplied by release CI` 测试、完整文件 SHA-256 和 APK 内资产 SHA-256 四重核对。
