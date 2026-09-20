# Protected frontend candidate build

The user selected the existing CI signing configuration for the AMap/native acceptance tests. The local AMap Android key is absent. Existing CI already holds the Android keys, fixed signing key, backend base and approved region asset. No secret values need to be retrieved into chat or source.

## Existing controls

The `v0.2.5-release` environment allows protected branches only. The internal workflow originally builds only the default branch. Main, the public Release/tag and the production deployment remain outside this candidate promotion.

## Prepared change, pending explicit activation approval

`v025-internal-test.yml` accepts one additional named branch, `codex/frontend-signed-candidate`, only when its dispatched SHA exactly equals the nonempty 40-character `reviewed_candidate_commit` input. The environment's protected-branch requirement remains in force; an ordinary development branch cannot use this path.

After explicit approval, the exact candidate that passes the existing required Android CI checks would be copied to that dedicated branch. The new branch would require those checks, strict up-to-date validation and administrator enforcement, and prohibit force pushes/deletion. Existing main protection and environment rules would be unchanged. No candidate branch or protection has yet been created.

Only `build-signed-apk` would be dispatched. Both SSH jobs have an additional job-level main-only guard, so the candidate branch cannot enter inspection or provisioning even if the wrong default operation is chosen. The existing region hash/version, backend compatibility, R8, APK contents and fixed-signer guards remain mandatory.

The workflow additionally builds a debug test target and its instrumentation APK using the same protected fixed signer (`ANITABI_SIGN_INTERNAL_TEST_APKS=true`). This allows SDK tests to satisfy Android key package/signature restrictions. The default local debug signing remains unchanged. Both additional APKs must pass the fixed-signer audit; the target also passes the APK/region audit. The app uses the existing test backend URL through the existing protected variable. Materials are cleaned up by the existing final step.

All three APK artifacts receive separate SHA-256 files. Artifacts are test candidates, not a public release. Installation is confined to dedicated emulators; these APKs do not authorize personal-device installation or publication. This plan does not mark frontend acceptance complete or change the release gate document.
