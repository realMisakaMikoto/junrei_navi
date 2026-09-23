# Discovery loading performance: measurement contract

Prepared 2026-09-21 for PR #37. Status: **measurement setup; no F6 performance run recorded here yet**.

The source baseline is `6759c8d3e2e7dddb17db51f509e2a30f1d845b75`. Read-only handset identity now binds the report to the previous signed internal Release (see [image diagnosis](IMAGE_LOADING_DIAGNOSIS.md)); no handset loading trace has been captured. No application bottleneck, speedup, native tile success rate, or physical-device performance result is established by this document. Record results below before closing F6.

September 23 setup update: `DiscoveryLoadTrace` now provides disabled-by-default, bounded monotonic events, explicit outcomes, scalar request/byte/cache counters and drop accounting. Its eight JVM tests pass within the 526-test run; it has no runtime hooks yet and supplies no performance result. The live desktop input sample observed an index with 51,828 point tuples, but its HTTP timings/Pillow decoding do not measure Android or SDK speed. Budgets below remain unchanged; B1/B2 runs and F5 optimization remain pending.

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

### Current repository capabilities

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

Prefer the existing optimized Release target, with a narrowly gated local-profiling manifest setting for the internal measurement build and sanitized application trace points. Keep it non-debuggable, keep R8/resource shrinking, and retain the fixed signature. The profiling option should default off outside the internal measurement artifact; record the manifest and option in its identity. This configuration is **proposed, not implemented** here.

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
