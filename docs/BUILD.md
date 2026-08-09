# 从源码构建巡礼手帳

本文面向希望自行编译应用的开发者。普通用户请直接从项目的 [GitHub Releases](https://github.com/realMisakaMikoto/anitabi/releases) 下载 APK。

## 环境

- JDK 17
- Android SDK Platform 37
- Android Build-Tools 37.0.0
- Android Platform-Tools

## 本地配置

在项目根目录创建不提交的 `local.properties`，配置 Android SDK 路径。需要在本机真机查看地图时，也可以把 Google Navigation SDK Android Key 和高德 Android Key 放在这个文件中：

```properties
sdk.dir=C:\\Users\\your-name\\AppData\\Local\\Android\\Sdk
ANITABI_NAVIGATION_API_KEY=your-local-key
ANITABI_AMAP_API_KEY=your-local-android-key
```

也可以将这两个 Android Key 作为 Gradle 属性或环境变量提供。以下任选一种，不要提交实际 Key；Gradle 属性和环境变量优先于 `local.properties`：

- 在用户级 `~/.gradle/gradle.properties` 中设置对应属性。
- 构建时传入 `-PANITABI_NAVIGATION_API_KEY=...` 和 `-PANITABI_AMAP_API_KEY=...`。
- 设置同名环境变量。

从 Firebase 项目下载 Android 配置到不提交的 `app/google-services.json`。应用包名必须为 `cn.anitabi.navigator`，并启用 Firebase Anonymous Auth。

Firebase 项目 ID 必须与应用所连接的后端相匹配，否则匿名 Token 会被拒绝。第三方自行构建时，若要使用完整路线功能，需要部署自己的配套后端、将 [`BackendApi.BASE_URL`](../app/src/main/java/cn/anitabi/navigator/data/network/backend/BackendApi.kt) 改为该后端地址，并按 [`backend/README.md`](../backend/README.md) 配置同一个 Firebase/Google 项目。官方生产后端不接受任意自建 Firebase 项目签发的 Token。

Navigation SDK Key 必须限制为：

- 包名 `cn.anitabi.navigator`
- 实际使用的调试或正式 SHA-1 证书
- Google Navigation SDK API

对应 Google Cloud 项目还需要启用 Navigation SDK 并满足 Google 当前的结算要求。

高德 Android Key 必须是独立的 Android 平台 Key，并限制为包名 `cn.anitabi.navigator` 与实际使用的调试或正式签名。不得把高德 Web 服务 Key 或数字签名私钥作为 `ANITABI_AMAP_API_KEY`；这些材料只允许由后端的只读 secret 文件注入。

v0.2.5 Release 还要求把经过许可、地图审核和双人复核的地区资产放在不提交的生产输入位置 `app/src/main/assets/approved_regions/territory_regions_v1.json`。格式见 [地区数据格式](REGION_DATA_FORMAT_v1.md)。没有该资产时 Debug 构建仍可用于不含真实边界的单元测试，但地区规划必须 fail closed；Release 构建会拒绝继续。不得以测试夹具、Natural Earth 中国边界或矩形代替生产资产。

服务账号 JSON 只能放在服务端，绝不能进入 Android 工程、APK 或 Git。

## 构建 Debug APK

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

生成的 APK 位于 `app\build\outputs\apk\debug\app-debug.apk`。

## 正式签名

本项目的官方发布 APK 必须沿用公开版本的 RSA-4096 固定签名。第三方构建可以使用自己的签名，但不能覆盖安装官方版本。私钥和密码只能位于工作区外或 GitHub Actions 加密 Secrets 中。Gradle 会拒绝缺少完整签名参数、任一 Android 地图 Key 或已批准地区资产的 Release 构建。

GitHub Actions 使用以下加密 Secrets：

- `ANITABI_KEYSTORE_BASE64`
- `ANITABI_STORE_PASSWORD`
- `ANITABI_KEY_ALIAS`
- `ANITABI_KEY_PASSWORD`
- `ANITABI_GOOGLE_SERVICES_JSON_BASE64`
- `ANITABI_NAVIGATION_API_KEY`
- `ANITABI_AMAP_API_KEY`
- `ANITABI_V025_APPROVED_COMMIT`
- `ANITABI_V025_REGION_DATA_URL`
- `ANITABI_V025_REGION_DATA_BEARER_TOKEN`（仅在私有下载端要求 Bearer Token 时设置）
- `ANITABI_V025_REGION_DATA_SHA256`
- `ANITABI_V025_REGION_DATA_VERSION`

`vX.Y.Z-rc.N` 标签会创建预发布版本，`vX.Y.Z` 标签会创建稳定版本。发布工作流会运行后端和 Android 测试、后端镜像构建、Release Lint、R8、源码与 APK 密钥审计、签名验证和 SHA-256 生成。

v0.2.5 还受 [双地图发布门禁](V0.2.5_RELEASE_GATE.md) 约束。全部门禁完成后，复核人须把其中机器可读状态改为 `releaseGateStatus: READY`，并在启用“必须复核、发起人不得自批”的 `v0.2.5-release` GitHub Environment 中登记精确候选提交、私有 HTTPS 地区资产地址、完整文件 SHA-256 和 `regionDataVersion`。标签必须直接指向获批提交；工作流会先下载到忽略目录，再独立验证提交、传输大小、完整文件哈希、文档内部校验值和版本。当前门禁为 `BLOCKED`，不得填写这些生产值或发布标签。

发布前请执行 [发布检查清单](RELEASE_CHECKLIST.md)。
