# Protected frontend candidate build

On **2026-09-21**, the user explicitly approved the bounded protected-candidate activation below using the existing CI signing configuration. The local AMap Android key is absent; CI holds the Android keys, fixed signer, backend base and approved region asset. No secret values need to be retrieved into chat or source.

## Existing controls

The `v0.2.5-release` environment allows protected branches only. The internal workflow originally builds only the default branch. Main, the public Release/tag and the production deployment remain outside this candidate promotion.

## Approved activation and execution

The initially reviewed head is `3e4069a614a49b3819b64d86b078b78f49886ccd`: normal CI `35527164214` passed all four required checks, and independent reads of both emulator evidence artifacts confirmed 51 UI cases each. After approval, the coordinating task created `codex/frontend-signed-candidate` at this exact SHA and applied/read back matching main protection: four checks bound to GitHub Actions app 15368, strict mode, administrator enforcement, conversation resolution, and no force pushes/deletion. Main protection and the environment policy were independently read back unchanged.

The first protection request was rejected with HTTP 422 because it included both deprecated `contexts` and `checks`, which the API treats as mutually exclusive. Resending only the app-bound `checks` succeeded; no protection was weakened. Only `build-signed-apk` was dispatched in run `35541425419`; both SSH jobs were skipped.

That run failed before restoring signing materials: the pinned setup-android action defaults to `tools platform-tools`, while the current repository no longer supplies the legacy `tools` package. The action's pinned `action.yml` and current v4 defaults were inspected. The workflow now explicitly requests only `platform-tools`; the following explicit API 37/build-tools installation remains unchanged. This correction must pass normal CI at a new exact commit before the protected candidate is advanced and signing retried. No signed artifact exists from the failed attempt.

`v025-internal-test.yml` accepts one additional named branch, `codex/frontend-signed-candidate`, only when its dispatched SHA exactly equals the nonempty 40-character `reviewed_candidate_commit` input. The environment's protected-branch requirement remains in force; an ordinary development branch cannot use this path.

Each promotion copies an exact reviewed CI-passing commit to that dedicated branch by fast-forward, retaining its protection. Existing main protection and environment rules remain unchanged; authorization alone never substitutes for branch/readback or build evidence.

The authorized dispatch is only `build-signed-apk`. Both SSH jobs have an additional job-level main-only guard, so the candidate branch cannot enter inspection or provisioning even if the wrong default operation is chosen. The existing region hash/version, backend compatibility, R8, APK contents and fixed-signer guards remain mandatory.

The workflow additionally builds a debug test target and its instrumentation APK using the same protected fixed signer (`ANITABI_SIGN_INTERNAL_TEST_APKS=true`). This allows SDK tests to satisfy Android key package/signature restrictions. The default local debug signing remains unchanged. Both additional APKs must pass the fixed-signer audit; the target also passes the APK/region audit. The app uses the existing test backend URL through the existing protected variable. Materials are cleaned up by the existing final step.

All three APK artifacts receive separate SHA-256 files. Artifacts are test candidates, not a public release. Installation is confined to dedicated emulators; these APKs do not authorize personal-device installation or publication. This plan does not mark frontend acceptance complete or change the release gate document.

## Authorized Google readiness execution

The actual Google SDK notice was accepted on both dedicated AVDs through `GoogleNavigationConsentFixtureTest` with explicit `navigationTermsConsentApproved=true`, SDK button/callback and acceptance readback. No consent preferences were written/reset and no NoToS API was used. Ordinary CI does not silently select this fixture.

The combined consent-setup + `NativeNavigationThemeInstrumentedTest` invocation passed on API 37 in **9.535 s** and API 26 in **5.799 s** (two cases each, one being consent setup). Both evidence JSONs record Navigator ready, navigation UI enabled, the same View across themes and fresh night-mode events `[true,false]`. SDK 7.8.0 emitted no initial registration event in these runs; that absence is recorded, while explicit DARK/LIGHT event assertions remain strict. There were no destinations/routes/guidance, temporary location permissions were restored and production code was unchanged. Exact hashes/files are in the [matrix](FRONTEND_REQUIREMENT_MATRIX.md). The two test source files and workflow correction still need commit/new-head normal CI before candidate fast-forward/retry.

The approved coordinate replacement in [DISCOVERY_DATA_CONTRACT.md](DISCOVERY_DATA_CONTRACT.md) still requires actual AMap projection. Google readiness success does not produce the missing signed AMap artifact or complete frontend acceptance.
