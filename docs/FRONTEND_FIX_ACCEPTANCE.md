# PR #37 整合修复验收（进行中）

核对日期：2026-10-02；基线 `6759c8d3e2e7dddb17db51f509e2a30f1d845b75`，修复检查点已推送 `3c4a2aed98aff88da8beda489f17213a25a6f2e3`，限定后端依赖修正见下文。沿用用户完整 v2 计划与新增定位点/手机朝向要求；未合并、Ready、发布或部署。

| 项目 | 当前事实 | 尚需完成 |
|---|---|---|
| F1 详情权威性 | 原13项一致性测试中7项失败；修复后19项一致性测试全过，保留20轮受控竞态 | 新 HEAD 整体/设备验证 |
| F2 完整性/重试 | 空/部分响应、独立图片元数据补充、缓存修复通过上述测试；相关64项核心测试全过 | 新 HEAD 整体/设备验证 |
| F3 视野选点 | 8项生产 VM 基线7失败；20项修复回归及2项数据准备屏障测试全过，已接入地图/按钮事件；14项发现界面契约复测通过 | 原生移动/布局期间即时点击验证 |
| F4 持久草稿 | 原5项恢复测试全部失败；10月2日3c4a2ae源码API26实际后台进程死亡5种情况通过；持久完成清理的2项真实容器/Room集成保留各自包范围；图片元数据与生成关联/完成所有权包含在602项JVM通过中 | 当前源码API37恢复及签名复测；旧容器结果不移用 |
| F5 附近排序 | 仍在 Composable 中，风险已确认，未测得性能根因 | 基线、后台计算及次数/规模验证 |
| F6 加载性能 | 默认关闭的trace已接入数据、缓存、地图、Window帧和Coil请求/解码；受门控Release测量源集及原生像素探针已编译/Lint通过，尚未取得B1或B2样本 | 测量包实际设备资格验证、同条件至少20次及完整矩阵；F5仍未优化 |
| F7 图片链路 | B0规范3/3、旧前缀0/3；旧包公网实际6图及缩略图/h360像素通过，保留首次超时；10月2日修复可见集超过256项绕过退避、旧null图片补充和后台暂停丢失重试机会，固定包602项JVM通过；同包API26点位列表真实HTTPS/解码像素/重试1项通过 | 双地图原生图片及最终签名验收；原手机仍未复测 |
| 新增定位显示 | 位置/方向逻辑回归已过；10月2日API26 Google真实SDK定位用例1项通过，合成位置/方向的圆点、箭头和过期回退均验证实际像素 | 当前API37 Google/高德原生验证及真实手机传感器/GNSS验证 |

此表不宣称任何整项已经完成。旧CI、旧签名 APK 和旧截图仅保留各自历史范围，不能证明本次工作树通过。

## 2026-10-02：当前固定产物与新增回归

`build/frontend-fix-v2/current-apks-1002/identity.json` 记录 **602项JVM、0失败、0错误**，构建前后源文件哈希差异为0；普通Debug应用/测试APK均构建成功，profiling/measurement均关闭、本地无高德Key。应用SHA-256为 `e3b745302b417610413f500983a0944f9dd98b23d251ce8ce791d26842667a54`，测试为 `969ed65c9960d037e2a171fa07c9023f42d613391949fe869e68fd367c9c9a40`。后续正在修改的视野就绪配对测量不在这组固定产物内，不能沿用该结果证明后续源码。

两包仅保留数据覆盖安装到专用API26 AVD，已独立核对安装哈希。`native-location.json` 的Google原生用例1项通过：实际SDK投影/像素核对定位圆点、方向箭头及2秒过期后的圆点回退，位置变化未自动跟随镜头，定位点未成为巡礼选点。输入经过Android方向矩阵/重映射与生产方向处理链，但位置和方向由测试合成；这不证明原手机传感器、GNSS、权限或前台定位轮询的真机表现。

同包另行执行的 `search-image.json` 记录点位列表1项通过：受控HTTPS→生产Coil→Android真实解码→72dp缩略图实际像素，无图不请求、失败后显式重试不误选点。不是公网请求；服务正常退出，自有reverse已移除。此项与定位用例是两次执行，不合称一次完整地图验收。

新增失败与修正分别保留，不以全量总数代替缺陷回归：

| 缺陷与修复前证据 | 确认原因及修正 | 修正后证据 |
|---|---|---|
| Search旧null图片：5项/3失败，0编译错误，`search-null-red-1002/` | 已选/恢复快照整体保留旧点，连缺失图片也被保留；三处合并只补严格规范化的缺失图片，不覆盖用户坐标或其它字段 | Search7项通过 |
| 图片退避：9项/3失败；草稿图片关联：19项/2失败，均0编译错误，`image-overflow-draft-metadata-red-1002/` | 256项LRU驱逐仍可见的失败记录，使下一轮提前重试；改为按当前可见装饰集合保留失败，离开集合才移除。图片元数据原先推进普通输入版本并丢失正式行程关联；现以所有权版本区分图片显示补充与实际规划输入编辑 | 退避9项、草稿23项、持久完成7项、恢复20项、Search7项、协调器5项，同次共71项全部通过，`f7-boundaries-jvm-fixed-1002/` |
| 多作品元数据补充：6项/1失败，0编译错误，`multi-subject-image-pause-red-1002/` | 首个请求被后台暂停取消后，循环仍消费下一作品的自动尝试机会；逐次读取最新暂停状态，取消时撤销本次记录并停止循环 | 修正后包含在602项全量通过中 |
| 首次集成：601项/1失败，`integrated-first-1002/` | 测试已重置Main调度器，Default工作协程才恢复；夹具在重置前清理ViewModel并等待所有者Job结束，生产调度不变 | 后续601项、双Lint及双Debug构建通过；再加入多作品暂停修复后602项通过。双Lint仍仅绑定此前601项快照 |

正式完成清理只由成功持久写入的信号触发，再核对持久终态与草稿所有权；图片补充仅增加存储版本，保留坐标、顺序、规划参数和生成关联。真实用户编辑、重新关联或旧所有权仍拒绝迟到清理。补图观察器只对当前草稿已知来源的过期图片元数据补充，当前明确无图不会强制请求；后台暂停不消耗尚未实际执行的重试机会。

后续源码构建的602项中，暂停回归出现一次第二作品请求尚未开始的断言失败，原XML保留于 `pause-barrier-red-1002/`。测试只等待虚拟调度器空闲，未等待真实IO缓存发布；夹具增加第二请求开始的显式屏障，生产代码不变。修正后的 `checkpoint-apks-fixed-1002/` 全量602项、双Lint、应用/测试构建全部通过，构建前后源码0差异：app=`5cfb33529c5e1f19a1716d2044af4d4fc917c6dc3aaeee81cf578473f0b64378`、test=`0b466f03242354c13b951953c46ee96da429d2f3fd2ed90751194df94766fcdc`。该包只保留数据更新专用API26，新增真实Google SDK视野计时接收/拒绝、防抖期间卸载2项通过；计时使用Compose虚拟时钟，仅证明跨度正确性，不是性能采样。定位/Search两项仍属于上述e3b/969e固定包。

3c4a2ae对应普通包158个源码文件与Git规范化比较0差异，Release R8、Google反射、高德JNI/7变异、APK内容和源码凭据审计全过。最新门控原生像素探针/Release测量源集编译及Lint也过，记录为 `measurement-final-compile-1002.log`；没有已签名测量包设备资格验证、性能样本或地理底图证明。旧CI `35886806901` 的68d图片夹具失败保留；新CI `36955609907` 运行中但backend审计失败，新受保护签名尚未执行。个人手机未操作。

同源码显式debug恢复夹具APK为 `02715c4a3c66d5920cadb85529fef5c5362a437c9a191bef428817ca80e35f5a`。`recovery-current-process-1002/` 在专用API26对正常、缺失、损坏、不支持schema、已清空共5项逐项确认后台保存状态、旧PID消失、最近任务恢复新PID、同task/Bundle及预期输入或恢复错误；被动路线尝试均0。原私有文件逐哈希还原，普通5cfb应用已保留数据恢复。不是自然内存压力、API37或手机证据。

## 2026-10-02：新 CI 后端依赖审计修正

提交 `3c4a2aed98aff88da8beda489f17213a25a6f2e3` 的 CI `36955609907`，后端 job `110677785291` 在 `npm audit --omit=dev` 退出1；此前类型检查、78项测试（78通过、0失败、0跳过）及构建已完成。锁定的 Fastify 5.12.3、fast-uri 3.1.7 和嵌套4.1.4触发两项中等风险依赖报告，共三个公告；该提交没有修改后端业务源码。

后续 `5f703c50be20e469fdf4dfca3f35f906c6eeeb6e` 的CI `36956367954`四项全部成功；独立内存核对API26资产11206956373、API37资产11207370625各59项UI通过，两份完成清理标量报告均符合预期、source/route请求均0，ZIP摘要与GitHub digest一致。摘要位于 `ci36956367954-scalar.json`。此CI不覆盖下列后续夹具修改。

## 2026-10-02：签名构建前单测夹具屏障

保护/环境与main重新比对后，候选已快进5f703c5并触发 `36958692125`；秘密材料清理成功、两SSH作业跳过。首个Gradle命令的602项中2失败，尚未组装/签名、执行APK审计或可选测量构建，产物为0。旧签名结果没有移用。

`PlannerDraftImagesTest.legacyNullUsesOneProductionSubjectMergeAndKeepsTheSavedCoordinate`为断言失败：虚拟调度器空闲并未等待实际IO缓存写入及补图观察器完成。所有相关用例改为实际请求/加载状态/草稿状态屏障，保持请求次数、坐标和所有权断言，增加受控真实IO写入屏障用例证明此时序差异；没有生产延时/超时修改。`DiscoveryLocationTrackingTest.boundedTimeoutLeavesAnExplicitRetryAvailable`为正文开始前的`UncaughtExceptionsBeforeTest`，短日志未提供前序异常生产者；源码确认测试只取消VM且推进虚拟调度，未等待Default后台任务，现清理等待所有者Job结束后再resetMain。确切异常生产者继续标为未证实。

两类共19项独立JVM任务重复20轮，全380次通过；源文件哈希0差异，XML保留于 `fixture-barriers-1002/round-*/`。随后 `checkpoint-fixtures-final-1002/` 全量603 JVM、双Lint、普通应用/测试构建通过，源码0差异：app=`601ea8d9bc58f178c1eb8d43b59191ec9ceba870df89e4d599376bd01956fddb`、test=`0b466f03242354c13b951953c46ee96da429d2f3fd2ed90751194df94766fcdc`。本次只改两个JVM夹具；应用源码仍3c4a2ae，新保护签名验证仍待。

## 2026-10-02：测量包身份审计与设备用例准备

aef9056普通CI `36961783850`四项全部成功。保护与环境重新核对后候选快进aef，并触发 `36964124477`。该次普通签名三包的单测/构建/内容/Manifest-DEX/固定签名审计成功；可选测量包Lint、R8组装、Google反射与高德JNI也成功，随后内容审计因没有固定Anitabi生产后端域名字符串失败。上传跳过、产物0，秘密清理成功。字符串缺失是实测门禁结果；R8移除未使用后端路径仅为源代码推断，没有最终APK/mapping可独立复核。

测量模式内容审计现在要求四参数：APK、保护地区哈希、显式测量模式、精确源码SHA。实际非debug Manifest/DEX/profiling/DUMP身份必须先通过，才使用固定localhost HTTPS端点要求；普通1/2参数仍要求固定生产后端域名。禁止材料、AMap标记、地区资产、签名与提供方审计均保留，未植入无用域名字符串。7项集成CLI/实际Python解析器控制测试、17项Manifest/DEX变异通过；仅可选测量步骤使用新模式，新控制测试加入普通CI。真实测量APK验证仍待保护构建。

新增 `DiscoveryNativeViewportSelectionInstrumentedTest` 的Google/高德两选择器绑定真实SDK手势、生产VM方法和点击处理时的有效性；用冻结Compose时钟保持旧按钮启用，避免只证明灰色按钮保护。新增 `DiscoveryNearbyUiBenchmarkInstrumentedTest` 在实际VM/列表中用1k/10k/100k合成成员和20次相关位置变化采集附近排序、计算次数/执行线程与UI完成时间，比较同模式签名Debug，不与Release加载数据混用。两新文件及测试APK已编译成功，源码哈希0差异；都未执行，不构成F3原生或F5/B1/B2通过。生产代码与默认图片功能未变。

当日核对维护者公告：[Fastify GHSA-4mh8-r7rc-xpvc](https://github.com/fastify/fastify/security/advisories/GHSA-4mh8-r7rc-xpvc) 指定修复版本5.12.5；[fast-uri GHSA-hrr3-gc8f-f4qj](https://github.com/fastify/fast-uri/security/advisories/GHSA-hrr3-gc8f-f4qj) 指定3.1.8/4.1.5；[GHSA-jvvf-x445-j334](https://github.com/fastify/fast-uri/security/advisories/GHSA-jvvf-x445-j334) 指定4.1.5。仅在 `backend/package.json` 精确更新Fastify至5.12.5，并将锁文件中的两条fast-uri解析更新至3.1.8和4.1.5；公开registry完整性值随对应版本更新，其它包、业务代码和审计门禁不变。没有使用 `npm audit fix --force`，没有部署。

本地Node 24.14.0/npm 11.18.0实际执行 `npm ci`、类型检查、测试、构建及 `npm audit --omit=dev --json`，全部退出0；78项测试全部通过且无跳过，生产依赖审计所有风险等级及总数均为0。文件SHA-256：package=`492ca7920251243df2195226dc40e55d291621c3569f43132b16ef9095853152`，lock=`cd0dea64b7ca10321d477ac75ff9e78a456f68d7d485d6123cfda176712d03fc`。这只证明本地依赖修正；新提交CI和容器构建仍待，不将原失败run写为通过。源码没有HTTP/2配置、注册响应trailer或直接导入fast-uri，属于适用性观察，不能替代依赖审计，也不声称观察到实际攻击。

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

## 2026-09-23至24：集成构建与测试支架历史

2026-09-23 的集成 Debug 应用和测试 APK 构建成功，身份在 `build/frontend-fix-v2/integrated-0923-apks/identity.json`。全量 JVM 运行在新定位测试退出时未结束：只读线程栈确认 `runTest` 清理阶段不断推进仍活动的周期定位任务，JUnit `@After` 的 ViewModel 清理尚未执行。只停止了核对过父进程的本次测试 Executor；这次运行不计全量通过。修正方向为在每个 `runTest` 正文的 finally 中关闭 ViewModel，再重跑；不修改生产定位周期来迁就测试。记录为 `jvm-harness-stall.json`。

该测试清理修正后，全量运行完成517项，仅道路保存草稿的一项时间检查失败。根因是道路模型保留 `DEPART_AT` 默认值但不保存公交时间锚点；检查已限制到公交模式，保留其它一致性保护，并新增跨设备时区正向回归。第三次全量 **518项、0失败、0错误**，Debug应用/测试APK同时构建成功。证据与全部XML/源文件哈希在 `build/frontend-fix-v2/integrated-0923b-apks/identity.json` 和 `jvm/`。

该组精确APK在API26专用AVD保留数据更新：图片网络/真实Compose像素/重试/尺寸选择与方向矩阵/图标共7项全过，另一次发现界面14项全过。它们是不同执行，分别保留 `android-execution.json` 和 `discovery-ui-execution.json`。AppShell六项中4过2失败；只读检查确认测试包持久草稿已有一个合成选点，旧测试设置未隔离草稿。加入每例前清空合成测试草稿、每例后还原原草稿的夹具边界，复测结果如下；不修改生产持久性来迎合旧测试。

随后 `integrated-0923c-apks`：应用 `a0eaf49a4cab375994ddcaada023c3a10b810924db95fbdd227927379293512a`，测试 `6e5add6e3a76ab6c80308199294facaa370f3362a5661bc3160865405acf902d`。**526项JVM、Debug/Release Lint、双Debug APK构建通过**，其中8项是当时尚未接入运行流程的trace单测。补上此前未显示的定位/草稿保存提示，并将独立AppShell用例的草稿恢复到测试前状态后，专用API26上的**15项发现UI＋6项AppShell共21项全部通过**。证据包括 `identity.json`、`build-result.json`、`jvm/`、双Lint XML和 `ui-execution.json`。这次未重跑7项HTTPS/方向测试，其通过记录仍只绑定上一组079f/d23c产物；新源码最终签名验收仍需统一复测。

2026-09-24 本地 Release R8、Google反射审计、高德7类/24成员审计及7项守卫变异测试通过；当前Debug APK内容审计与已暂存源码凭据审计通过。保留上游Navigation翻译资源及高德final-R资源的非致命构建警告，没有修改SDK或关闭现有检查。此处尚未构建新的受保护签名Release，也不宣称其最终DEX/设备验收通过。

API26 原生高德仍未实测；继续仅按用户之前批准的 API37 原生高德＋API26 UI/Google/安全回退解释覆盖。个人手机只读身份核对不等于新修复真机验收。

## 2026-09-24：固定产物与实际恢复

提交 `68d7457ae875e2126726b08f1e42ff0560804b7f` 的 CI `35886806901`：verify/backend成功，API26/37各54项UI中同一项失败。旧 `FrontendRemainingFlowsTest` 的 fake loader 只接受无尺寸URL，新的h360请求在请求计数之前被拒绝，导致等待超时。已修正严格期望地址及真实网络错误文案，保留关闭/返回和请求次数断言；该用例在下述新本地包通过。远端新CI尚未运行，不能称旧CI全绿。脱敏诊断在 `build/frontend-fix-v2/ci-35886806901-ui-diagnosis.json`。

加入数据/运行阶段测量和恢复夹具后，普通Debug构建与**543项JVM全部通过**。`build/frontend-fix-v2/diagnostic-normal-0924/identity.json` 绑定各源文件哈希及APK：应用 `37e64f76e4614638dd7db118a7be6be330e0854c461d9ecf9d5ad6fe9f602972`，测试 `9b7ee410a470d0b0816b36607741550e0c898ed6ee66d135ba56d8f63c370522`。本地调试签名、无高德Key、profiling和恢复夹具均关闭；不同于已提交68d，不能将旧CI结果转移到这些工作树改动。

恢复夹具单独构建应用 `cd9b5ef43298a0f28be100a48188cebb2fe090322436b3f2397231a90012c993`，仅安装专用 `anitabi-pr37-images-api26`。生产MainActivity、ViewModel、导航图和草稿存储保持；合成数据与失败即计数的路线提供方只用于测试。先HOME并确认Activity已保存状态和停止，再 `am kill`，确认旧PID消失，通过最近任务真实点击恢复。五项均观察到新PID、同一任务、saved Bundle恢复，且路线尝试数为0：

| 草稿情况 | 结果 |
|---|---|
| 正常 | 同一draftId、磁盘/Planner全部输入及Search选点一致 |
| 文件缺失 | 明确恢复错误与安全返回，未进入可操作空规划页 |
| 文件损坏 | 同上，损坏输入未当作正常草稿 |
| 不支持的schema | 同上，未猜测解析 |
| 已清空墓碑 | 同上，旧草稿未复活 |

这是**受控后台进程死亡**，不是自然内存压力、旋转或手机验收。原草稿/偏好逐文件哈希还原成功，随后保留数据恢复普通37e64应用。原始记录 `build/frontend-fix-v2/recovery-process-0924d/`，夹具源身份 `recovery-fixture-0924/`；脚本 `scripts/verify-planner-process-recovery.py` 可复现，无清Room/全应用数据操作。完成清理的后续代码尚不在此产物内。

此前三次夹具失败分别为：API26没有外部`test`命令（改用shell内建）；不支持`--activity-new-task`（只读验证后用等价数值标志作初始设置）；UI dump到标准输出未返回XML（改为专用临时文件，读入内存后立即删除）。首项在修改设备前停止，后二项均完成原文件还原。保留 `recovery-process-0924`、`0924b`、`0924c`，不计通过、不改生产恢复逻辑。

另补“取消选择后再次点击更新地点”生产Search回归：5项中1项失败，证实旧合并优先保留所有缓存点，导致未选点重新选择仍用旧坐标。失败XML及修复前源哈希在 `build/frontend-fix-v2/reselect-red/`；后续修复必须同时保持已选快照不变。

该重新选择修复与生成行程关联/完成清理的首轮52项回归通过，随后全量566项及双Lint通过。但独立复核发现导航运行态先于Room完成写入发布，失败写入可回退运行态；直接据其清草稿会过早删除输入。真实TourRepository＋受控DAO屏障的2项回归均产生预期断言失败（`completion-commit-red/`）。最终改为整个持久写入操作成功后发布变更，包含补偿写入；AppContainer串行收集信号，读取正式记录，草稿仓库再核对持久终态及读取前后的草稿版本。启动时仍作本地持久状态恢复检查，无路线请求。

加入未提交/失败写入、补偿回滚、同ID重新关联及查询失败/超时六项后，**572项JVM全部通过**，两份Debug APK构建成功。产物 `build/frontend-fix-v2/persisted-completion-0924/`：应用 `7d59e2082717592eabc1f679946891c7f221b7c987e2321d01d06416a1a5ac01`，测试 `579a1546380d2bcc26ddc977dd7f41349cb6de6649b88075ebb0d9694a3e482a`。这次没有重跑双Lint；双Lint只绑定前一566项快照，新的容器接线仍需Android集成复测。

随后补齐真实AppContainer＋Room＋默认文件草稿的两项Android集成。未由测试调用完成方法或终态flush，实际容器串行订阅完成清理，FileObserver观察到原子墓碑写入，独立Room连接核对正式记录保留；旧/无关完成保存不影响新草稿。**两项通过**，包含COMPLETED/ENDED两种终态，后者是同一用例的两个输入，不额外计数。目标应用保持7d59，最新测试 `4afc4a76280d9765358fc6566d719770240be6e45b29507f98b45021d83f6a59`；此次普通Debug/Release Lint都通过。与14项图片检查同次共16项通过，身份与报告在 `final-image-test-0924/`。

测试对象使用独立Context命名空间；普通测试Runner启动仍在隔离范围之外。修正API26清理命令后，两项容器检查另行独立复跑通过，默认草稿和数据库/WAL的前后哈希一致、测试目录与偏好全部清理（`container-repeat/result.json`）。未启动任何Discovery/路线请求。新代码的API37、受保护签名与原生地图验收尚待，F5/F6仍未完成。
