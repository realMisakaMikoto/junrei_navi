# 图片加载诊断与修复验收

核对日期：2026-09-23。PR #37 仍为 Draft；本文件是进行中的证据记录，不是完成声明。

## 问题设备与证据边界

用户反馈来自实体手机，安装的是上一轮内测 Release。无线 ADB 只读核对得到 API37/ARM64、0.2.5(11)、非 debuggable，APK SHA-256 为 `573db32f9743dff1471e9dbe1d33f3ea760cec2d86b705c48a01123906efb450`，与签名运行 `35557984317` 的内测包一致。没有在手机安装、启动、停止应用、清数据或改权限。

尚未取得该手机的实际失败请求或加载 trace。下面的路径复现证明具体代码缺陷，不能证明它是该手机全部失败或地图慢的唯一原因。

## B0：修复前的受控 HTTPS 与真实 Android 解码

基础提交：`6759c8d3e2e7dddb17db51f509e2a30f1d845b75`。只加入等价生产 ImageLoader 工厂注入缝及测试支架，Parser 路径逻辑未修复。专用 AVD `anitabi-pr37-images-api26`，API26/x86_64，本地 Debug 签名，无高德 Key；不是性能测量包。

| 产物 | SHA-256 |
|---|---|
| 目标 APK | `65015dbd955725d0e753a633902dc6c875b651c5b83d02b7584c11a67f719098` |
| 测试 APK | `00d7db68641e0211cc9e7574db7660315de8a8c6de77c25a2aa9aed852fa4be2` |

实际执行前再次核对 AVD 名称与两份已安装 APK 的哈希。测试经过真实 `DiscoveryParser.page`、共享生产 Coil 3.5.0/OkHttp 5.4.0、受控 HTTPS 网络与 Android PNG 解码器。仅传输目标和合成证书在测试中注入；主机名校验保留，私钥只在主机 JVM 内存中生成。

| 输入 | 请求数 | 响应 | PNG 像素/尺寸验证 |
|---|---:|---|---:|
| 三种规范路径家族 | 3 | 200、image/png、每张75字节 | 3/3 |
| 对应多余 `/images/` 前缀 | 3 | **测试服务注入的**404、text/plain | 0/3 |

三种家族为 points、user、bangumi，全部使用合成对象。六个请求均保留 `plan=h160` 和生产技术 UA，未携带 Authorization/Cookie/X-Goog-Api-Key。失败断言恰为“静态路径输入应解码成功”：期望3，实际0；规范路径正对照已全部成功。

证据位置：`build/frontend-fix-v2/b0-image-host-apks/identity.json`（源文件哈希）、`execution-0923.json`（运行状态）、`image-network-0923.json`（标量结果），以及 `build/frontend-fix-v2/b0-host-https-0923/requests.json`。只保留计数/类别，无真实 URL、对象标识或设备响应正文。测试结束后移除本次 ADB reverse 映射，服务收到停止文件并以0退出。

### 已解决的测试支架错误

第一次将 MockWebServer 5.4 服务端放在 Android26，服务端读取 `ExtendedSSLSession.requestedServerNames` 得 null，发生 NPE，未生成解码报告。这次失败不计图片路径复现。核对 pinned OkHttp 源码后，将 HTTPS 服务移到电脑 JVM，仍使用原 Android 生产客户端/解码器；上述正对照成功验证支架修正。未修改生产 TLS、升级依赖或更换公网节点。

## 当前修复与待验收范围

共享 `AnitabiImageReference` 只剥离开头一次 `/images/<已知家族>`，保留对象后缀和版本查询；拒绝不安全语法。列表/地图请求 h160，详情/查看器默认 h360，原图由用户主动请求。未知查询参数保留原语义，避免盲改签名。请求边界兼容旧缓存/已选/已保存引用；图片元数据版本与上游详情版本独立。

当前正在补齐并验证：请求绑定的 UI 状态、限定范围重试、地图失败退避、旧 null 元数据刷新、重定向检查、同包真实 UI 解码和双地图图片标记。以上实现尚不能计为验收通过。

2026-09-23 集成包第一次设备复测：应用 `bd01ae3086fb4a0f6aa8599d96fd18a4f21c41c8f96e2d5836c55321ff5a9118`，测试包 `61dc5bad5e0c8fc7ff45894b1853786a48287de6902bf7be49e04451a7d4a971`。同一专用 API26 AVD、保留数据更新并核对已安装哈希。规范输入3/3与旧前缀输入3/3均成功通过真实 HTTPS/PNG 像素检查，六次请求全部使用规范路径、h160、正确 UA 且无敏感请求头。结果在 `build/frontend-fix-v2/integrated-0923-apks/image-network.json`。这一结果证明受控路径修复，未冒充公网或性能证据。

同次两项新增 UI 测试未通过：查看器首次屏幕采样未达到目标图片像素；重试测试使用100dp非紧凑布局，点击后未观察到第二次请求。正在调整为等待实际像素稳定、测试生产56dp紧凑缩略图，并再次执行。未把响应200当作这两项UI通过。原始计数、异常类别及运行状态保留在同目录 `android-execution.json` 与 `image-ui-1-*.json`。

后续包：应用 `079ff21fede9d19b6ee31a8319c394cab25a585239e2762673025d8c5408140a`，测试 `d23c796c94b4db3e8775f0930a5da50a7ce106abd9c3b6b8a27c5bf3ebd8dad7`。同一API26设备重新核对哈希并保留数据更新后，上述三个图片测试全部通过：6个解析输入均真实解码；生产56dp缩略图经历404→点击重试→200并达到目标像素，切换新资源后继续成功；全屏初始请求h360，实际稳定像素通过，点击原图按钮后才发不带plan的请求。未通过随机URL或假ImageLoader绕过缓存。证据为 `build/frontend-fix-v2/integrated-0923b-apks/image-network.json`、两份 `image-ui-*.json`、`android-execution.json` 与 `fullscreen-loaded.png`；已查看真实屏幕，关闭/原图操作可见。图片仍为自造PNG，不能称为公网作品截图成功。

主机夹具在测试Android框架stub位于classpath时打印一次平台探测警告（`android.util.Log.isLoggable not mocked`），随后实际回退JVM TLS，以上HTTPS测试成功且服务以0退出。启动脚本已改为排除主机服务不需要的android.jar；这项夹具清理还需下一次启动读回验证，不影响已记录TLS结果。

## 公网桌面小样本（独立证据）

2026-09-23 以生产技术UA、默认TLS校验、关闭重定向、至少1050ms请求起始间隔，读取一份批准的索引及一个对应分页。索引包含1536个作品条目、51828个点元组，分页250个作品ID在内存中匹配索引分片。选取4部作品的6张不同图片，未继续抓取其它分页。

| 路径家族 | 图片数 | 原多余前缀路径 | 规范h160路径及桌面解码 |
|---|---:|---|---|
| user | 3 | 3个实测404 | 3个200/WebP成功 |
| points | 1 | 1个实测404 | 1个200/JPEG成功 |
| bangumi | 2 | 2个实测404 | 2个200/JPEG成功 |

六张解码输入合计74305字节，全部高度160。这里记录的是读取的响应体字节，不是网络线上总流量。请求/响应正文、真实地址、ID、名称与坐标均未落盘或打印。脱敏结果位于 `build/frontend-fix-v2/live-image-desktop.json`，已执行脚本 `live-image-desktop-observed.py` 的SHA-256为 `5ba48c697c4820d35b8247b10bfb7b26a5962cdb0f5a3314eefc986b2c249655`。

这一次使用Python结构化规范化镜像和Pillow，未运行生产Kotlin Parser/Coil，也未复核结束索引。未观测实际发出的敏感头明细，因此不以“没有显式配置凭据”冒充无鉴权头实测；Android生产加载器的头检查属于前述独立受控测试。真实样本的Android/UI绑定仍待执行，不能把桌面成功当成用户手机复测。

## 一手契约

- [官方图片 API](https://navi.anitabi.cn/docs/api/)：图片域名与 h160/h360 尺寸语义。
- [固定 Swift 来源](https://github.com/anitabi/anitabi-swift-app/blob/51e597120c32932d5f9af129a2e2f9b08a74d3d6/anitabi/Home/AnitabiModels.swift)：开头 `/images` 的路径转换；未复制其其它图片域名或宽松来源规则。
- [Coil 网络](https://coil-kt.github.io/coil/network/)与[Compose](https://coil-kt.github.io/coil/compose/)：实现使用项目锁定3.5.0，并按该版本实际 API 编译；不升级到在线文档的新版本。
