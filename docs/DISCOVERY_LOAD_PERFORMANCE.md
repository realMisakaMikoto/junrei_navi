# Discovery loading performance: measurement contract

Prepared 2026-09-21 for PR #37. Status: **measurement setup; no F6 performance run recorded here yet**.

October 2 checkpoint: production retry and draft-image correctness fixes pass 602 JVM cases in the normal local build. Gated Release compilation and Lint also pass with the native visual probe, before the later ready-span change. The probe projects the actual SDK markers and copies the native SurfaceView or TextureView pixels; scalar reports retain current viewport/member/photo match counts, capture cost and stale-result rejection. The matcher has not been qualified on a device, so these are prepared mechanisms, not observed native point/image timings. A SurfaceView copy does not establish final Window composition or successful geographic tiles. Fresh official [PixelCopy](https://developer.android.com/reference/android/view/PixelCopy) and [Google Projection](https://developers.google.com/maps/documentation/navigation/android-sdk/reference/com/google/android/gms/maps/Projection) constraints informed this separation.

`READY_VIEWPORT_SELECTION` is a separate paired duration beginning after legal projection/index input preparation, and ending only when the ViewModel accepts the current token. It retains the existing 300 ms delay; rejection, detach and cancellation end without successful timing. Its new native correctness tests use Compose virtual time and cannot supply performance samples. `VIEWPORT_SELECTION_READY` remains the end-to-end milestone. B1/B2 samples, current protected measurement artifacts and F5 optimization remain pending; the fixed budgets below are unchanged.

The collector/statistics/process-recovery Python suites pass 40 host-only tests. The collector's `ready_viewport` condition requires full 10k membership, six settled photos and quiescent repository/native-copy work before the list-to-map interval; it rejects new application data/image fetches in that interval. Pixel offsets come only from actual current native-copy matching, and remain provisional pending device qualification. Two real Google SDK ready-span correctness cases pass on API26 in `checkpoint-apks-fixed-1002/`, with virtual time and exact APK hashes in the repair checklist. Neither result supplies B1 data.

Protected candidate5f703c5 passed all four ordinary CI checks, including independently verified 59 UI cases per API. Signing run36958692125 stopped in its first JVM command with two fixture failures, before packaging or the optional measurement step; it produced no artifact and cleanup succeeded. Explicit IO/job-completion test barriers subsequently pass 19 cases ×20 runs and all603 JVM cases with both Lints. No production optimization was made. The prepared localhost host and both supervised AVDs were later confirmed stopped after their tool handles disappeared; no measurement attempt had run, and no certificate/reverse/bootstrap was installed for measurement. B1/B2 remain empty until new protected artifacts pass device qualification.

aef9056 ordinary CI36961783850 also passes all four checks. Signing36964124477 passes the ordinary build and its audits, plus optional measurement Lint/R8/provider audits, then rejects the measurement APK because the shared content check expects the production backend domain. Upload is skipped and cleanup succeeds, so there is no downloadable artifact. The exact marker absence is established; optimizer reachability is an inference. The corrected explicit measurement content mode first verifies the actual Manifest/DEX/non-debug/profiling/DUMP role, then requires the closed localhost HTTPS fixture marker; ordinary backend requirements and every material/region/signer/provider gate remain. Seven integrated CLI controls pass, with real artifact validation still pending.

The separate, explicit nearby UI probe is compiled but unrun. It uses actual Repository/ViewModel/list composition with synthetic coordinates, full 1k/10k/100k membership and 20 relevant location changes. It records the actual paired nearby-sort phase, distance/sort counters, execution lane and UI distance settlement. This is signed Debug-to-Debug evidence only; its results cannot replace the non-debuggable Release loading matrix. The fixed-camera Release groups still deny actual location and do not measure nearby sorting. Neither probe has produced a B1/B2 result.

cfa705e CI36968112961 passes all four checks. Signing36970012064 passes ordinary package/audits, but optional measurement role proof rejects `measurement_dex_definition_missing`; upload0 and cleanup success. The confirmed source-set configuration used unsupported `AndroidSourceSet.java` additions for AGP built-in Kotlin. It now registers the gated directory through `kotlin.directories.add`, following fresh [official migration documentation](https://developer.android.com/build/migrate-to-built-in-kotlin#4-migrate-the-kotlin-sourcesets-dsl-if-necessary). Local flagged Kotlin/R8 subsequently produces actual class files and all three required DEX definitions with114source hashes unchanged. Earlier task-success records lacked class inclusion proof and do not establish a functioning fixture. Actual signed APK, ordinary-mode exclusion, device qualification and B1/B2 remain pending; no production optimization or measurement sample is claimed.

Follow-up local verification completes ordinary-mode exclusion: flagged Lint reports0Fatal/0Error/23Warning, then flagsfalse ordinary compile/R8 has0measurement namespace definitions, its ordinary Application and all7AMap classes. Google/AMap audits and7mutations pass, with117source hashes unchanged in `measurement-lint-ordinary-r8-1002/`. This is actual local class/DEX evidence only, not a signed APK or runtime sample. Runtime helper reviews also corrected failure exit/cleanup gating, same-PID offline pixel waiting and truncated instrumentation-result rejection in ignored helpers. A scoped Bash Python shim bypasses this host's failing WindowsApps python3 alias without global configuration changes; no device/service execution has occurred.

The source baseline is `6759c8d3e2e7dddb17db51f509e2a30f1d845b75`. Read-only handset identity now binds the report to the previous signed internal Release (see [image diagnosis](IMAGE_LOADING_DIAGNOSIS.md)); no handset loading trace has been captured. No application bottleneck, speedup, native tile success rate, or physical-device performance result is established by this document. Record results below before closing F6.

September 23 setup update: `DiscoveryLoadTrace` now provides disabled-by-default, bounded monotonic events, explicit outcomes, scalar request/byte/cache counters and drop accounting. Its eight JVM tests pass within the 526-test run; it has no runtime hooks yet and supplies no performance result. The live desktop input sample observed an index with 51,828 point tuples, but its HTTP timings/Pillow decoding do not measure Android or SDK speed. Budgets below remain unchanged; B1/B2 runs and F5 optimization remain pending.

September 24 update (supersedes that setup status): runtime hooks now cover application/container/region initialization, shell draw, SDK lifecycle/readiness, the existing map-loaded callbacks, cache/HTTP/JSON/model parsing, snapshot-lock wait, classification/map preparation/search/spatial indexing, marker commits, valid viewport publication and the **unchanged Composable nearby sort**. Window FrameMetrics and 500 ms sampled Java/native/PSS values are opt-in. The 10 trace-core, 13 data and 2 span-helper JVM tests passed in the 543-test local build; seven HTTP image-observer tests now pass in the 572-test build, and three Coil/Android observer cases passed on API26. Image load/fetch/decode/cache/error/cancel are recorded separately, with consumed decompressed bytes and final HTTP status after redirects. These hooks never certify drawn pixels. This is instrumentation preparation, **not B1 or evidence of a bottleneck**. No F5/F6 optimization or budget change has occurred.

The opt-in `ANITABI_DISCOVERY_PROFILING=true` Gradle property enables the scalar diagnostics provider and `<profileable>`; default is false, independent of telemetry. The provider requires Android `DUMP` permission and shell/root caller identity, accepts only reset/read operations, and streams a bounded scalar snapshot instead of exposing app files. `content read --uri content://cn.anitabi.navigator.discovery-diagnostics/snapshot` reads it. No current protected CI artifact includes this new option yet. API26 supports the application/Window measurements but not shell profileable coverage.

Important definitions in the implementation:

- The Source observer measures consumed response bytes after transparent HTTP decompression. HTTP transfer ends when the body has been read; JSON and typed model parsing have separate spans. This differs from the live-image smoke network interceptor's pre-decompression source-byte count.
- Cache write count means attempts; written bytes include completed payload and valid-current backup copy. Read bytes include old-current validation during writes. Snapshot-lock wait is separate from work while holding the lock.
- SDK marker commit events occur when mutation calls return. They do **not** emit `first_viewport_points_drawn` or `first_visible_image`; those still require actual pixel evidence in the measurement harness. Shell draw likewise does not prove native basemap pixels.
- Main-thread segment totals around conversion/marker loops exclude cooperative coroutine yields. Frame reports are Window durations, not SDK private-surface FPS. Dropped reports and bounded-buffer loss are exported.
- A trace reset invalidates old span tokens. The harness must stop old work before resetting, because independent request counters are interval observations rather than a new workload identity.

The first data-instrumentation test run had one malformed-JSON fixture failure: pinned kotlinx serialization 1.11 accepts the bare `not-json` string as a JsonLiteral. An actual JAR check confirmed this; the fixture now uses an unfinished object, and a separate Repository test verifies the primitive fails typed directory parsing. No parser permissiveness or production behavior was changed. Original XML is retained in `build/frontend-fix-v2/trace-first-jvm/`; the corrected 13 data tests passed.

Fresh official constraints checked September 24: Navigation SDK map-loaded callback is distinct from map-ready and may never arrive on connection failure; FrameMetrics values must be copied/read during the callback because the object is reused; unavailable metrics return -1; `<profileable>` begins at API29 and its `enabled` attribute at API30. The runtime uses supported scalar monotonic spans rather than pairing thread-bound Android Trace calls across coroutines. Sources are linked in sections 3–6 below.

### Optimized measurement harness preparation

The explicit `ANITABI_DISCOVERY_MEASUREMENT=true` property requires profiling, adds only `src/discoveryMeasurement` to the existing Release source set, and requires the existing internal signing configuration for APK packaging. Ordinary Release/Debug excludes this fixture. It keeps the application ID, non-debuggable Release, R8/resource shrinking, fixed signer and every existing audit. Local flagged Release Kotlin compilation and Lint passed; **no measurement APK has yet been signed or run**. Subsequent fixture precondition corrections need their own final compile check. Source-set configuration follows [Android build variants](https://developer.android.com/build/build-variants).

The fixture uses the real MainActivity, Repository, parser, Coil/decoder, classifier and map adapters. A shell/DUMP-only provider accepts a fixed enum configuration, a fixed local public certificate and an external bootstrap sentinel. It never accepts arbitrary network targets. Dedicated Discovery/image/draft paths isolate test data; only enumerated presentation/camera preference keys are backed up/restored locally. Existing AMap consent must already be present; explicit preparation uses its official converter for the fixture camera. Bootstrap skips the application's container and map initialization path; library ContentProviders retain normal process-start behavior (the ordinary APK contains Firebase/AndroidX initializers). Route overrides fail and count before network calls. Fine/coarse location must already be denied for the fixed-camera group, because the normal initial Locate path can otherwise replace a restored camera; this group does not represent permission-granted startup or synthetic-location nearby sorting.

The localhost-only HTTPS host has eight prebuilt provider/size datasets, production-Parser validated before listening: 1k/10k/100k/51,828 points per provider, 128 points per subject and 16 subjects per page. Every dataset has **15 authored distinct coordinates** (nine dense overlap sites and six image singletons). Thus 51,828 matches the observed directory's point count only, not its subject/spatial distribution or real directory performance. Six image identities have actual 240×160 and 540×360 PNG variants. Delay profiles preserve 750ms data-response delay and 16KiB/150ms image throttling. No provider/private SDK networking is intercepted.

Host validation at `build/frontend-fix-v2/discovery-host-validation-fixed-0924/` fetched and checked all eight index/layout hashes, decoded all 12 PNG variants with the desktop decoder, and observed both delay profiles over trusted local HTTPS. The host then stopped normally. This validates the fixture, **not Android performance or image rendering**. Its initial isolated classpath omitted the application's compiled JAR needed by the production Parser; adding only application build JARs to the already pinned JVM runtime fixed the startup failure. Keep `discovery-host-validation-0924.log` as the failed attempt.

The optional protected-workflow input defaults false and is restricted to the reviewed candidate's exact SHA. Ordinary artifacts are copied and audited before the extra fixture build. `audit-discovery-build.py` reads actual Manifest/DEX definitions in memory, checks non-debuggable/ordinary-versus-measurement classes, profiling and DUMP permissions, and emits only scalar metadata. Seventeen mutation/control tests pass after reproducing three fail-open conditions in the new audit helper; an old signed ordinary Release is a separate positive control, not current-source validation. The forthcoming artifact must still pass its actual audit.

`scripts/summarize-discovery-performance.py` has 15 passing statistical tests: strict same-condition/environment/harness matching, nearest-rank percentiles, all attempts/failures and missing/null stages preserved. "Completed" means a controlled workload reached its expected state; it never means F6 acceptance. The external driver is still under review and native point/image/geographic pixel observation remains an explicit gap. No sample, speedup, or completed B1/B2 comparison has been produced by this setup.

## 1. Baseline identity and comparison order

Keep three distinct checkpoints. Record the full application SHA, measurement-harness SHA, build execution SHA, target/test APK SHA-256, signer verification, and measurement configuration for each. Instrumentation added to an old source snapshot is a separate measured artifact and must be identified as such.

| Checkpoint | Required behavior | Permitted comparison |
| --- | --- | --- |
| B0: original image failure | Preserve the production parser/request failure with controlled synthetic inputs before repair | Explains the broken path and failure handling; not a fair successful-image throughput baseline |
| B1: working images, before performance changes | Canonical image references, production network fetch and decoder succeed; F1-F4 correctness prerequisites identified | The baseline for image-enabled loading and application-stage optimization |
| B2: optimized candidate | Same functional workload, image success criteria, build mode, device, layout, network profile and dataset as B1 | The before/after comparison used for F6 claims |

Image request success count, bytes transferred, cache hits, decode dimensions and failures accompany all image timing. An old request that fails quickly cannot be called faster than a successful new request. Each stage has its own sample population; do not add historical test counts or mix previous signed APKs into the new candidate's measurements.

## 2. Fixed execution targets

The following targets are selected from the existing dedicated-test setup, not from a fresh device inspection. Reconfirm their identity before any run; this document does not assert that they are currently booted or installed with a particular APK.

| Target | API / installed application ABI | Planned scope | Existing boundary |
| --- | --- | --- | --- |
| `anitabi-redesign-api37` | API 37; explicit `arm64-v8a` for the signed application | Google and AMap native map, images, startup and return-to-map comparison | AMap requires the explicit ARM installation selection already documented in the signed test plan |
| `anitabi-redesign-api26` | API 26; `x86_64` | Google native map, UI, cache/recovery, application counters and Window frame durations | AMap native execution is untested; unsupported-ABI fallback is a separate functional result |
| Authorized physical device, if made available for this run | Record model/API/ABI before use | Reproduce the user's operating conditions and perform physical-device performance acceptance | No physical target is selected or newly authorized by this document |

Before B1, create an immutable local configuration record containing: AVD/system-image revision or physical model, API, actual installed ABI, emulator/GPU mode and host runtime, app/window pixel size, density, refresh rate, font scale, theme, orientation, map padding, permissions/consent state, Play services availability, dataset hash and counts, network profile, compilation mode, and image/cache state. Keep the same configuration for B2. Serial numbers and account/device identifiers are not published. A necessary environment change starts a new comparison group; it does not replace an inconvenient baseline.

Primary comparison uses portrait, font scale 1.0, the existing application layout, and default image visibility. Keep dark mode, font scale 2.0 and landscape as explicit supplemental regression cases. The existing authored native fixture uses padding values left 24, top 48, right 12 and bottom 120; retain those values for comparisons using that fixture and record the actual content rectangle size. Do not silently substitute its layout for the production screen.

Only the dedicated synthetic test environment may reset task-owned Discovery or image fixture cache entries. Never clear Room, drafts, journeys, navigation progress, consent, the whole application, or SDK tile caches to manufacture a cold result. A cold process does not imply a cold filesystem or SDK tile cache; record these independently. No device operation was performed while preparing this file.

## 3. Build and measurement setup

### Repository capabilities at the original baseline

Inspection of `settings.gradle.kts`, `app/build.gradle.kts`, the main manifest and existing workflows establishes:

- The project currently contains `:app` with `debug` and `release` build types. Release enables R8 and resource shrinking. There is no Macrobenchmark module, benchmark build type or `<profileable>` declaration at this checkpoint.
- Existing tasks include `testDebugUnitTest`, `lintDebug`, `lintRelease`, `assembleDebug`, `:app:assembleDebugAndroidTest`, `:app:minifyReleaseWithR8`, and `assembleRelease`. Do not present a new benchmark task as already available.
- The protected internal workflow already builds Release separately from signed Debug/AndroidTest artifacts. Its three Gradle invocations use a 4 GiB heap and at most two workers. Retain the exact-SHA checks, fixed signer, region/key inputs, R8/JNI/content audits and cleanup.
- `build/run-signed-amap.ps1` verifies the selected dedicated AVD and installed APK hashes before its native checks. `build/run-marker-font-native.ps1` can install its configured Debug artifacts and includes a synthetic performance selector. These are ignored local helpers, not a reproducible standalone F6 benchmark or permission to run them on a personal device.

The existing verified task names can continue to establish correctness:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug --stacktrace
.\gradlew.bat :app:assembleDebugAndroidTest --stacktrace
```

The protected internal build already performs these separately, with its existing inputs and audits:

```text
testDebugUnitTest lintRelease
assembleRelease
assembleDebug assembleDebugAndroidTest
```

These are setup references, not a record that these commands were run for F6.

### Minimal proposed measurement configuration

Prefer the existing optimized Release target, with a narrowly gated local-profiling manifest setting for the internal measurement build and sanitized application trace points. Keep it non-debuggable, keep R8/resource shrinking, and retain the fixed signature. The profiling option defaults off outside the internal measurement artifact; record the manifest and option in its identity. The manifest and scalar runtime instrumentation are now implemented as described in the September 24 update; the protected optimized measurement artifact and external harness are still pending.

For API 37, an external Perfetto/UI Automator harness against that target is the minimal route without inventing a Gradle module. If Macrobenchmark is selected instead, add a real separate test module and inspect its generated tasks before publishing commands. The Android guide recommends a non-debuggable release-like target and reports configuration problems for debuggable targets/emulators; do not suppress those checks and label the result physical-device release acceptance. [Android Macrobenchmark setup](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview).

`<profileable>` starts at API 29. Therefore API 26 must not be reported as having the same profileable or modern frame-timeline coverage. Use supported Window metrics and application monotonic timing/counters in an explicitly identified controlled harness; any Debug/instrumentation measurements remain a separate build-mode group. If equivalent optimized API 26 timing cannot be collected, retain that gap. [Android profileable manifest element](https://developer.android.com/guide/topics/manifest/profileable-element).

API 37 system traces may use Perfetto after recording its actual supported data sources. API 26 trace availability must be verified independently; unsupported sources remain unavailable. System-trace capture must be locally scoped, without logcat/request-body capture or SDK private-network interception. [Perfetto Android tracing](https://perfetto.dev/docs/quickstart/android-tracing).

The existing Debug native tests remain required functional controls. They cannot be compared directly to an optimized Release candidate to claim speedup.

## 4. Event definitions and proposed observation points

Use one monotonic clock domain for application durations; retain the monotonic origin in local results. Wall time is only run metadata. A stage record contains status (`observed`, `empty`, `failed`, `cancelled`, `not_observed` or `unsupported`) and a nullable duration. Missing callbacks and timeouts must never become `0 ms`.

| Event/span | Proposed observation point and completion rule |
| --- | --- |
| `launch` | Harness launch request with verified cold-process precondition; record process start separately where available |
| `discovery_enter` | Current navigation entry into Discovery; a warm return is a new visit in the same confirmed process |
| `shell_drawn` | First drawn application shell for that visit; does not establish map or data readiness |
| `sdk_view_created` | Existing `NavigationView`/AMap view creation boundary; count creation and release, correlated only by an ephemeral visit ID |
| `sdk_ready` | Existing provider object/readiness callback, after applicable privacy/SDK prerequisites |
| `basemap_render_observed` | Provider render-complete observation plus visual confirmation where needed; a synthetic grid or SDK object alone does not prove geographic tiles |
| `cache_read`, `index_fetch`, `index_parse` | Actual cache read, queued/network index request, and production parsing separately; distinguish queue time from transfer/parse |
| `classify`, `convert`, `index_build` | Region classification, official display-coordinate conversion and spatial-index construction; retain full membership and provider correctness |
| `first_viewport_points_drawn` | First nonempty valid current-range marker batch committed and observed; empty range is reported as empty |
| `viewport_selection_ready` | Complete current viewport membership has valid provider/data/filter/camera/layout identity and batch selection is permitted |
| `first_visible_image` | First visible image fetched/decoded through production components and displayed; a placeholder or fake `SuccessResult` does not qualify |
| `visible_images_settled` | The fixed visible request set has reached success/error/cancelled outcomes; record each count, resource/size dedup counts and transferred bytes |
| `details_sync_complete` | Validated pages and matching final index recheck; it is not a prerequisite for shell, cached points or initial selection readiness |

Google's Navigation SDK `OnMapLoadedCallback` runs on the UI thread, is one-shot, and may never fire if connectivity fails or the map continually changes. Keep an explicit not-observed result and re-arm only for a new defined observation. `onMapReady` is a distinct event. Use the Navigation SDK reference and the locked 7.8.0 dependency; do not add the separate Maps SDK. [Navigation SDK map-loaded contract](https://developers.google.com/maps/documentation/navigation/android-sdk/reference/com/google/android/gms/maps/GoogleMap.OnMapLoadedCallback).

AMap already has a loaded-listener gate in `AmapMapView.kt`. Observe that existing callback without replacing the readiness/camera-completion sequence, and retain a separate visual tile check. No new AMap render-callback semantics are assumed here.

Synchronous `Trace.beginSection`/`endSection` must pair on the same thread. Do not wrap a suspending coroutine across dispatchers with those calls. Use supported asynchronous spans where available or record begin/end monotonic events and correlate them in the harness; platform async Trace begins at API 29. [Android Trace API](https://developer.android.com/reference/android/os/Trace).

## 5. Matrix and fixed sample policy

### Data and interaction workloads

| Workload | Data | Required outcomes |
| --- | --- | --- |
| Controlled ready-viewport | Fixed 10,000-point synthetic dataset, valid index/projection ready, no added network wait | Separate first marker batch and full selectable-viewport times; complete membership and safe selection |
| Scale/overlap | Fixed 1,000 / 10,000 / 100,000 synthetic members; stable fixture hash | Membership conservation, overlap expansion, selections retained, throughput, frame durations, sampled memory |
| Catalog loading | Actual approved directory size recorded only as aggregate count, plus structurally equivalent synthetic snapshots | Index-first usability, progressive details, correct page/version validation and cache behavior |
| Image-enabled viewport | Fixed visible image set using valid synthetic image bytes through production network/decoder components | Request path/variant correctness, decode success, bytes, bounded requests and unchanged marker membership |
| Return and manipulation | Enter list then return; scripted viewport move, panel changes, image/name arrival, nearby list | Reuse/release behavior, recalculation counts, obsolete work rejected, no stale selection |

For each supported provider/target, run cold-process/no-Discovery-cache, cold-process/valid-Discovery-cache, and same-process warm return. Subdivide image memory/disk cache state instead of inferring it from process state. SDK tile-cache state stays observed/unknown and is never represented by the application's cache flag.

For those start states, use normal network, controlled delay and offline conditions. Inject data-API and image failures independently; SDK/base-map failure is a separate controlled-device scenario and cannot be simulated by failing the app's data API. Prefer bounded test-source/transport injection for application failures. Device-wide network changes require a dedicated authorized test target and a checked restoration procedure.

The controlled-delay profile is pinned to 750 ms added response latency per application data request and 150 ms per 16 KiB image-body chunk, with the production one-second public-data request spacing retained. These values define a repeatable test condition, not a claim about the user's connection. Offline fixtures fail the selected application transport promptly and separately exercise pre-existing valid image cache hits. Do not cause artificial production traffic failures.

### Repetitions

- Every core B1/B2 provider/target/start-state comparison has **20 measured runs per condition**. The ready-viewport and scale workloads also have 20 runs per dataset/provider/target. Preserve failures and timeouts within the attempted sample count.
- Use the same 20-run policy for controlled application data/image delay and offline comparisons. Separate SDK failure checks may have fewer observed samples if the environment is unavailable; report the exact count and keep them outside completed core claims.
- Perform one excluded harness/precondition check per comparison group, with no unreported warmup after measurement begins. Warm-return setup first opens Discovery normally and confirms the PID stays unchanged. Cold setup confirms the process is absent before launch and records the new process identity locally.
- Real public image sampling remains a small, paced smoke check (up to the task's approximately ten images), not a 20-times full-catalog download. Repeated controlled runs reuse authored fixtures and real decoders. Live smoke results and controlled performance samples are reported separately.
- Persist one raw record per attempted run. Report attempted/completed/failed counts, success rate, p50/p95/max and missing-stage counts. Use nearest-rank percentiles consistently; with 20 valid samples, p95 is the nineteenth sorted value. Also report timeouts/failures so survivor-only timing cannot imply full success.

## 6. Counters and budgets fixed before optimization

Collect request counts by endpoint category, queue wait/transport/parse duration, bytes, timeout/error category, cache hits, page requests, full-index rebuilds, nearby distance computations/sorts, cache writes and bytes, snapshot-lock wait, marker updates, image decode/update counts, cancellation/restart counts, and SDK-view creation/release counts. No full URLs, IDs, titles, coordinates, tokens, queries or response bodies belong in trace labels or exported counters.

| Measure | Initial budget or acceptance rule |
| --- | --- |
| 10k controlled viewport application work | p95 under 1,000 ms from ready input publication to complete valid `viewport_selection_ready`; time first batch separately |
| Confirmed slow application-stage bottleneck | Initial target at least 30% p95 improvement versus B1 under the same conditions; interpret alongside sample count and failures |
| Membership/selection | Exact expected membership at 1k/10k/100k; no hidden points, truncation, coordinate changes or cross-provider mixing |
| Images | Valid production fetch/decode/display path succeeds; default images remain enabled; error paths preserve points and selection |
| Other principal stages | No unexplained regression in cold startup, warm return, basemap observation, image success/bytes or sampled memory; investigate a p95 increase over 10% as a comparison signal, not an automatic excuse to discard samples |
| Watchdog | Existing native tests use 120,000 ms; retain it as a failure timeout, never as an acceptable product loading target |
| 100k stress | Report throughput, memory and frames with lossless membership; no public-service request amplification or unmeasured 1-second promise |

If pre-optimization evidence shows the initial 10k budget is unsuitable for a supported target/density, record the evidence and replacement budget **before B2**, retaining the original value and rationale. Do not change the device, viewport membership or timing origin to make a result pass.

Window `FrameMetrics` are available on these target APIs, but deadline/overrun metrics require API 31+. Copy scalar values in the callback, report dropped metric reports, and separate first-draw frames. Window durations do not establish Navigation SDK surface FPS. Keep API 26 deadline values unavailable. [Android FrameMetrics](https://developer.android.com/reference/android/view/FrameMetrics), [Macrobenchmark metric availability](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics).

Report heap values as sampled Java/native maxima, with sample count and harness overhead. They exclude unobserved GPU allocation; do not call them true whole-system peaks. If RSS/PSS or Perfetto memory counters are collected, label that different metric separately. Keep periodic screenshots and heavy heap inspection out of the primary measured interval, or explicitly report their overhead in a separate diagnostic run.

## 7. Existing evidence and open observations

`DiscoveryNativeMapPerformanceTest` currently runs two Google-only methods over the three sizes. It verifies authored native projection/membership/pixels and image reuse, records Window timing and sampled Java/native heaps, and includes screenshot polling in pixel latency. Its viewport image interceptor returns a generated bitmap without network decoding. These checks remain useful, but do not establish B1 image success, full startup, native tile authentication, persistent FPS, 20-run distributions, or the user's device behavior.

`DiscoveryStartupInstrumentedTest` covers camera/location/startup state ordering. It is not a cold-process or no-cache latency benchmark. Existing ignored helpers' output is tied to their own APK hashes and must not be relabeled as the new F6 candidate.

The following are candidate sources of delay only: serial request scheduling, full-cache serialization/fsync while publishing, repeated classification/conversion/index construction, broad recomputation keys, image decode/bitmap updates, SDK creation/readiness and external tile availability. Select changes after their measured contribution is known. In particular, the repository already uses a background scope; parsing on the main thread is not assumed.

### Run ledger

| Group | Source / APK / configuration | Attempted / completed | Key results | Status |
| --- | --- | --- | --- | --- |
| B0 original image failure | Baseline source identified above; runtime artifact not yet recorded here | 0 / 0 | Not measured in this document | Pending |
| B1 working-image baseline | Not yet pinned | 0 / 0 | No startup/viewport/image/SDK distribution | Pending |
| B2 optimized comparison | Not yet pinned | 0 / 0 | No before/after performance claim | Pending |
| User-device reproduction | APK/device identity unavailable | 0 / 0 | Total slow-load cause unobserved | Unverified |
| API 26 native AMap | Compatible environment unavailable under the existing record | 0 / 0 | Fallback is not native rendering evidence | Unverified |

Store raw records and local traces under a new task-owned ignored directory such as `build/frontend-fix-performance/<checkpoint>/<group>/`; record exact paths and hashes when created. A committed aggregate report must retain stage/status definitions, sample counts, failures, supported metrics and trace provenance. No directory or measurement artifact was created by preparing this document.

F6 can be closed only after the working-image baseline and same-condition candidate results support the claimed improvement, required correctness stays intact, and all unobserved SDK/network/physical-device cases are explicitly retained. A configuration plan or a green functional test suite alone does not close the reported loading problem.
