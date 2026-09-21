# Protected frontend candidate build

On **2026-09-21**, the user explicitly approved the bounded protected-candidate activation below using the existing CI signing configuration. The local AMap Android key is absent; CI holds the Android keys, fixed signer, backend base and approved region asset. No secret values need to be retrieved into chat or source.

## Existing controls

The `v0.2.5-release` environment allows protected branches only. The internal workflow originally builds only the default branch. Main, the public Release/tag and the production deployment remain outside this candidate promotion.

## Approved activation and execution

### Final coverage approval on 2026-09-21

After reviewing the completed signed results and the API 26 ARM environment failures, the user explicitly selected option **"1"**: **API 37 native AMap + API 26 UI/Google/safe-fallback coverage**. This closes the coverage decision through an approved substitution. **API 26 native AMap remains untested**; safe fallback is not native rendering. The initial AMap tap transient, original unavailable old/new coordinate comparison and other evidence limits remain recorded. Final documentation retains the repository's required PR checks; this approval does not publish, deploy, merge main or install on a personal device.

### Current candidate at `cb89265`

CI **`35556295376`** passed all four checks for `cb892650ad34512749760139ab8791df349ee541`; independent artifacts `10620898633` / `10621063446` confirm **53 cases per API**. Fresh controls/environment checks preceded protected candidate fast-forward/readback; main remained `2b6d971`. Signed **`35557984317` succeeded**, cleanup succeeded and both SSH jobs skipped. Independent `build/frontend-signed-35557984317/verified-artifacts.json` confirms fixed signer/v2 and these hashes:

| Candidate APK | Bytes | SHA-256 |
| --- | --- | --- |
| Release | 101,565,850 | `573db32f9743dff1471e9dbe1d33f3ea760cec2d86b705c48a01123906efb450` |
| Debug target | 132,187,734 | `2dca03399f2c08f3377c9fa55487bc53cd7c198a92fe76f54df944cdb357c3a1` |
| Instrumentation | 1,434,188 | `3870416aad5a048db43ad76cc04db1c08d2a0f0e8a6a41d040a83b8cecc4939e` |

An initial download failed before destination-directory creation; its exact cause was not retained. Retrying the unchanged artifact succeeded and verification passed. This is not a confirmed network root cause.

The signed API 26 x86 unsupported-ABI case passed with matched installed hashes before/after (`signed-35557984317-api26-abi`). It verifies safe unavailability without SDK View construction, **not API 26 AMap rendering/projection**. API 37's ARM installation retained data and matched the verified bytes. Its first three-case AMap run had one failure waiting five seconds for the initial marker click callback; marker metadata and provider lifecycle passed. A focused same-byte rerun passed, then all three passed in `signed-35557984317-api37-amap-repeat`. The initial failure remains in `signed-35557984317-api37-amap`; no concrete production/fixture defect or root cause was established, and a startup race is only plausible. Record this transient limitation without adding a new mandatory gate or claiming a source bug was fixed.

The same-byte visual fixture passed (`signed-35557984317-api37-amap-visual/visual-evidence.json`). Reviewed frames 07/08 show light-dot/dark-image native markers without material presentation issues; early 04/06 loading frames are not acceptance evidence. These synthetic-grid frames do not newly prove authenticated geographic tiles; the older `3f14597` tile observation remains separate.

Current signed API 37 Google artwork **2**, native **7** and performance **2** cases all pass in `signed-35557984317-api37-google/marker-font-evidence.json`. Consent setup + night **2** also pass in `signed-35557984317-api37-night`: actual Navigator/navigation UI/same View, events `[true,false]`, no initial registration event, no routes/guidance and restored fine/coarse permissions. The user's accepted substitute above closes the API 26 coverage decision without claiming native AMap execution there.

### Verified earlier signed snapshot `3f14597`

Normal CI **`35547547202`** at `3f1459739a4f6dd196fb6e72f09f5e86c6aeb9e7` passed all four checks; independent in-memory reads of artifacts `10617013808` / `10617312882` confirmed **53 UI/bitmap cases per API**. The protected candidate was fast-forwarded/read back at this SHA. Signed run **`35549085053` succeeded**, including every guard, R8/content/fixed-signer audit and cleanup; both SSH jobs skipped. This validates the split-process memory correction in an actual signed build.

Downloaded artifacts were independently checked for SHA-256 and the fixed single signer/v2 scheme; Release AMap JNI class definitions also passed the independent audit. `build/frontend-signed-35549085053/verified-artifacts.json` records:

| Internal candidate | Bytes | SHA-256 |
| --- | --- | --- |
| Release | 101,549,466 | `85188d019ad70a1b9493fd02113904946e6b5882dad798edd22d36c1feb7e655` |
| Debug target | 132,171,350 | `eb6c2e1ad64d132f19c3a421330d0ac4c4106a437e0914650ead91f933239b2e` |
| Instrumentation | 1,428,316 | `7d6899bffd109919461334c9a4ab42b95b5350c9e9732932533763c4cc24ed94` |

These packages were installed only on dedicated AVDs; switching the local debug signer to the fixed signer recreated their synthetic test packages. Personal devices were untouched. AMap's libraries are ARM/ARM64-only, while Google's x86 libraries caused default x86_64 package selection: API 26 without a native bridge failed EGL context creation, and API 37 lacked AMap native libraries and showed black output. API 37's native bridge permits the same APK bytes installed with `--abi arm64-v8a -r`, preserving data; actual AMap tiles then rendered. **All three AMap discovery cases still time out at `awaitPoint`; tile rendering is not projection/anchor acceptance.**

API 37 signed ARM execution also passed Google consent setup + native night mode in **12.287 s**, with the same View, events `[true,false]`, no routes/guidance and restored permissions (`build/frontend-review/signed-35549085053-api37-arm64-night/`). JDI yielded no useful camera fields, the target ended and forwarding was removed; no acceptance credit comes from it.

The official API 26 ARM64 AVD could not boot: emulator 37 rejects ARM, and isolated official 34.2.16/31.3.14 stop at the HDA/no-PCI error. It has no running device/APK; old AVDs/global emulator remain intact. Those failures led to the explicit coverage question, subsequently resolved by the user's option "1" above. They are not relabeled as a successful ARM test.

The committed `cb89265` repair passes local build in **9m 57s**: **417 JVM, zero failures/skips**, both Lints/R8 and fresh audits. Full ordinary CI, signed artifacts, signed-x86 fallback and the current API 37 executions have the scoped results above. The original AMap click transient and accepted API 26 substitution remain explicit.

Earlier API 26 local-debug regressions passed two artwork, seven Google native (**21.761 s**) and two performance (**21.896 s**) cases, bound to target `e2eddfc43a447e09c7f9f429c25918e1e31e54f028e5e6b166c3b3bb916711b7` and test `9ea77782fb4bce0e7d1fe739c240390a10524bcde287f8ccaf8a4cc22974ded9`. Current signed results above use separate `2dca...`/`3870...` hashes; the accepted substitution does not conflate these artifacts or claim API 26 native AMap execution.

### Earlier attempts

The initially reviewed head is `3e4069a614a49b3819b64d86b078b78f49886ccd`: normal CI `35527164214` passed all four required checks, and independent reads of both emulator evidence artifacts confirmed 51 UI cases each. After approval, the coordinating task created `codex/frontend-signed-candidate` at this exact SHA and applied/read back matching main protection: four checks bound to GitHub Actions app 15368, strict mode, administrator enforcement, conversation resolution, and no force pushes/deletion. Main protection and the environment policy were independently read back unchanged.

The first protection request was rejected with HTTP 422 because it included both deprecated `contexts` and `checks`, which the API treats as mutually exclusive. Resending only the app-bound `checks` succeeded; no protection was weakened. Only `build-signed-apk` was dispatched in run `35541425419`; both SSH jobs were skipped.

That run failed before restoring signing materials: the pinned setup-android action defaults to `tools platform-tools`, while the current repository no longer supplies the legacy `tools` package. The action's pinned `action.yml` and current v4 defaults were inspected. The workflow now explicitly requests only `platform-tools`; the following explicit API 37/build-tools installation remains unchanged. This correction must pass normal CI at a new exact commit before the protected candidate is advanced and signing retried. No signed artifact exists from the failed attempt.

The retry preflight also found that this workflow still called both R8 auditors with their old mapping-file default. The current AGP build writes `mapping.txt` under `app/build/intermediates/mapping/release/minifyReleaseWithR8/`, while seeds/usage/configuration remain in the outputs directory. The candidate audit now passes the same two explicit paths as the already-passing ordinary CI; its checks are unchanged. Both auditors were rerun against the existing release outputs before committing this workflow correction.

Normal CI `35545330748` at `c8cac6eef416535528f0bb2a8680cd315e5d584f` passed all four checks. UI-log ZIP assets `10615883669` / `10616623637` were independently read in memory and confirm 53 cases per API (`build/frontend-ci-35545330748-evidence.json`). After fresh main/candidate protection and protected-only environment equality checks, the candidate was fast-forwarded from `3e4069a` to `c8cac6e`; main stayed `2b6d971`.

Signed retry `35546582406` then failed terminally in the combined Gradle build: `java.lang.OutOfMemoryError: Java heap space` with repository `-Xmx2048m`, affecting `lintAnalyzeRelease`, `packageDebug` and `minifyReleaseWithR8`. No credential-specific caused-by exception was found in the inspected failures. SDK setup, region/backend preflights passed; material cleanup succeeded, both SSH jobs skipped and no signed artifact was produced.

The bounded workflow-only correction retains the original five tasks in three fresh invocations: tests + `lintRelease`, `assembleRelease`, then debug + instrumentation assembly. Each uses `--no-daemon --max-workers=2` and an explicit 4GB heap; all guards/audits/cleanup remain. Official documentation and 12 Bash/YAML blocks were checked; a 14s local `help` run confirmed 4096MB/two workers. Subsequent exact-head CI and successful signed run `35549085053` above now establish actual runtime success for this correction. Later AMap application repairs are a separate unverified source state.

`v025-internal-test.yml` accepts one additional named branch, `codex/frontend-signed-candidate`, only when its dispatched SHA exactly equals the nonempty 40-character `reviewed_candidate_commit` input. The environment's protected-branch requirement remains in force; an ordinary development branch cannot use this path.

Each promotion copies an exact reviewed CI-passing commit to that dedicated branch by fast-forward, retaining its protection. Existing main protection and environment rules remain unchanged; authorization alone never substitutes for branch/readback or build evidence.

The authorized dispatch is only `build-signed-apk`. Both SSH jobs have an additional job-level main-only guard, so the candidate branch cannot enter inspection or provisioning even if the wrong default operation is chosen. The existing region hash/version, backend compatibility, R8, APK contents and fixed-signer guards remain mandatory.

The workflow additionally builds a debug test target and its instrumentation APK using the same protected fixed signer (`ANITABI_SIGN_INTERNAL_TEST_APKS=true`). This allows SDK tests to satisfy Android key package/signature restrictions. The default local debug signing remains unchanged. Both additional APKs must pass the fixed-signer audit; the target also passes the APK/region audit. The app uses the existing test backend URL through the existing protected variable. Materials are cleaned up by the existing final step.

All three APK artifacts receive separate SHA-256 files. Artifacts are test candidates, not a public release. Installation is confined to dedicated emulators; these APKs do not authorize personal-device installation or publication. This plan does not mark frontend acceptance complete or change the release gate document.

## Authorized Google readiness execution

The actual Google SDK notice was accepted on both dedicated AVDs through `GoogleNavigationConsentFixtureTest` with explicit `navigationTermsConsentApproved=true`, SDK button/callback and acceptance readback. No consent preferences were written/reset and no NoToS API was used. Ordinary CI does not silently select this fixture.

The combined consent-setup + `NativeNavigationThemeInstrumentedTest` invocation passed on API 37 in **9.535 s** and API 26 in **5.799 s** (two cases each, one being consent setup). Both evidence JSONs record Navigator ready, navigation UI enabled, the same View across themes and fresh night-mode events `[true,false]`. SDK 7.8.0 emitted no initial registration event; explicit DARK/LIGHT event assertions remain strict. There were no destinations/routes/guidance, temporary permissions were restored and no production change was needed for this readiness test. These files are in the green `67e07a4` snapshot; the subsequent font/mapping head has the separate promotion gate above. Exact hashes/files remain in the [matrix](FRONTEND_REQUIREMENT_MATRIX.md).

The approved replacement in [DISCOVERY_DATA_CONTRACT.md](DISCOVERY_DATA_CONTRACT.md) has actual API 37 AMap projection alongside its web/Google/saved-coordinate evidence. The separate API 26 coverage substitution is now explicitly accepted; original live old/new coordinate equality remains unproven.
