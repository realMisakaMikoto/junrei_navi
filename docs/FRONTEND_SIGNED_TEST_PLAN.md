# Protected frontend candidate build

On **2026-09-21**, the user explicitly approved the bounded protected-candidate activation below using the existing CI signing configuration. The local AMap Android key is absent; CI holds the Android keys, fixed signer, backend base and approved region asset. No secret values need to be retrieved into chat or source.

## Existing controls

The `v0.2.5-release` environment allows protected branches only. The internal workflow originally builds only the default branch. Main, the public Release/tag and the production deployment remain outside this candidate promotion.

## Approved activation and execution

### Current result at `3f14597`

Normal CI **`35547547202`** at `3f1459739a4f6dd196fb6e72f09f5e86c6aeb9e7` passed all four checks; independent in-memory reads of artifacts `10617013808` / `10617312882` confirmed **53 UI/bitmap cases per API**. The protected candidate was fast-forwarded/read back at this SHA. Signed run **`35549085053` succeeded**, including every guard, R8/content/fixed-signer audit and cleanup; both SSH jobs skipped. This validates the split-process memory correction in an actual signed build.

Downloaded artifacts were independently checked for SHA-256 and the fixed single signer/v2 scheme; Release AMap JNI class definitions also passed the independent audit. `build/frontend-signed-35549085053/verified-artifacts.json` records:

| Internal candidate | Bytes | SHA-256 |
| --- | --- | --- |
| Release | 101,549,466 | `85188d019ad70a1b9493fd02113904946e6b5882dad798edd22d36c1feb7e655` |
| Debug target | 132,171,350 | `eb6c2e1ad64d132f19c3a421330d0ac4c4106a437e0914650ead91f933239b2e` |
| Instrumentation | 1,428,316 | `7d6899bffd109919461334c9a4ab42b95b5350c9e9732932533763c4cc24ed94` |

These packages were installed only on dedicated AVDs; switching the local debug signer to the fixed signer recreated their synthetic test packages. Personal devices were untouched. AMap's libraries are ARM/ARM64-only, while Google's x86 libraries caused default x86_64 package selection: API 26 without a native bridge failed EGL context creation, and API 37 lacked AMap native libraries and showed black output. API 37's native bridge permits the same APK bytes installed with `--abi arm64-v8a -r`, preserving data; actual AMap tiles then rendered. **All three AMap discovery cases still time out at `awaitPoint`; tile rendering is not projection/anchor acceptance.**

API 37 signed ARM execution also passed Google consent setup + native night mode in **12.287 s**, with the same View, events `[true,false]`, no routes/guidance and restored permissions (`build/frontend-review/signed-35549085053-api37-arm64-night/`). JDI yielded no useful camera fields, the target ended and forwarding was removed; no acceptance credit comes from it.

The new official API 26 ARM64 AVD cannot currently boot: emulator 37 rejects ARM, and isolated official 34.2.16/31.3.14 stop at the HDA/no-PCI error. It has no running device/APK; old AVDs/global emulator remain intact. The user has not answered whether to retain the native API 26 AMap gate with a compatible ARM environment or approve the explicit API 37-native/API 26-UI-Google-static substitute. No choice or waiver is assumed.

The uncommitted map-loaded/projection, asynchronous camera/acknowledgement, ABI fallback and diagnostic repairs now pass the full local build in **9m 57s**: **417 JVM, zero failures/skips**, debug/test assembly, both Lints and R8; fresh Google/AMap 7-class/24-member/current debug APK audits and seven mutations also pass. A new signed-x86 unsupported-ABI instrumentation case compiles with Debug Lint (1m 51s), but is **not executed**; ordinary CI selection remains 53.

API 26 passed two artwork, seven Google native (**21.761 s**) and two performance (**21.896 s**) regressions on repaired local debug target `e2eddfc43a447e09c7f9f429c25918e1e31e54f028e5e6b166c3b3bb916711b7`, test `9ea77782fb4bce0e7d1fe739c240390a10524bcde287f8ccaf8a4cc22974ded9` (`build/frontend-review/amap-fix-google-api26/marker-font-evidence.json`). Only API 26's synthetic packages switched back to the local debug signer for this run; API 37 remains on the earlier fixed-signed ARM candidate. The repair still requires exact-head CI and a fresh signed artifact for repaired AMap and API 37 Google checks. The API 26 environment/substitute question remains unanswered.

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

The approved coordinate replacement in [DISCOVERY_DATA_CONTRACT.md](DISCOVERY_DATA_CONTRACT.md) still requires actual AMap projection. Google readiness success does not produce the missing signed AMap artifact or complete frontend acceptance.
