# Frontend discovery redesign acceptance

## Scope and evidence policy

The approved September 15, 2026 brief replaces the entry experience with a complete discovery map, a single content panel, shared local search and itinerary selection, and a Material 3 redesign of every existing page. Implementation is authorized; device installation and deployment are separate. The baseline is `main@2b6d971`; development branch is `codex/frontend-discovery-redesign`.

An unchecked item is incomplete or unverified. Source inspection, synthetic JVM checks, emulator screenshots, instrumentation, actual provider rendering and physical device observations are distinct evidence. No fixture or screenshot substitutes for a real provider result. No production backend, quotas, route protocol, signatures or personal device state may change in this work.

## Required deliverables

- [ ] Current main-site compressed data contract cross-checked with the current web parser, pinned Swift loader and existing API; sanitized evidence only.
- [ ] Index-first data, dynamic pagination, composite identities, coordinate-preserving details, directory exclusion, duplicate/malformed handling.
- [ ] Independent atomic discovery cache, last valid fallback, 24-hour checks and manual refresh, serial throttling, background pause, prioritized/deduplicated subject requests.
- [ ] Explicit index/loaded/current states; missing pages and changed generations never claim completeness; final index recheck.
- [ ] Map/search/trips navigation, toolbar settings, active-navigation cold restore and home return without stopping navigation.
- [ ] Single discovery/subject/point panel, measured compact/half/expanded heights, per-content scroll and detent restoration, tablet side panel and system back.
- [ ] Viewport subject chips with covers/counts and pinned filters, location/reset/display controls, viewport and nearby discovery with honest straight-line distances.
- [ ] Complete marker membership, clusters, zoom-dependent labels and pictures, selected outline, picture-off/error fallback and maximum-zoom overlap list.
- [ ] Versioned spatial index; 300 ms idle computation; stale result suppression; main-thread incremental SDK markers; no viewport network fetch.
- [ ] Shared 250 ms local subject/point/city search, honest partial scope, explicit Bangumi action, metadata-backed subject grouping and full point/source/image details.
- [ ] Selection independent of map filters; viewport select-all, clear, list fallback, provider switching, planning and multi-subject compatibility.
- [ ] Trips grouped into ongoing, draft selection and saved; restore preserves existing settings/order/progress.
- [ ] Planner, route preview, road navigation, app transit, external handoff, future-stop editor, floating controls, notifications, onboarding and settings updated with all actions retained.
- [ ] Neutral map/warm paper themes, system/light/dark preference, image marker preference, no wallpaper palette, type scale, 48 dp controls, accessibility and reduced motion.
- [ ] Intent-aware camera with measured padding, location/last-view/first overview order, minimal pan on marker tap, gestures stop framing, fit all includes outliers.
- [ ] Canonical WGS84 and Double precision; Google unchanged, AMap one official conversion, GCJ-02 route no extra conversion, provider-space projection and correct anchors.
- [ ] Region fail-closed, privacy before AMap SDK, one active MapView, no discovery camera takeover during navigation, user/route data boundaries preserved.

## Verification matrix

- [ ] Existing JVM suite and meaningful new parsing/cache/selection/panel/search/spatial/camera tests.
- [ ] Synthetic 1k/10k/100k points: membership, computation timing, incremental changes and memory evidence.
- [ ] Existing Compose semantics plus new panel history, filtering, navigation, local search, image fallback and gesture regression coverage.
- [ ] Dedicated API 26 and API 37 emulator execution.
- [ ] Portrait phone, short landscape, tablet, light/dark, enlarged font and TalkBack evidence.
- [ ] Rendered native screenshots reviewed against approved design; all material findings resolved.
- [ ] Debug and Release Lint; Release R8; Google reflection and AMap JNI audit; APK content audit.
- [ ] Final requirement-by-requirement audit, main-site exception documentation, updated project summary and honest remaining limitations.

## Documentation consulted

- Android Navigation release notes (2026-09-15): `navigation-compose:2.10.1` is stable. <https://developer.android.com/jetpack/androidx/releases/navigation>
- Material 3 Adaptive release notes (2026-09-15): `adaptive:1.3.0` is stable. <https://developer.android.com/jetpack/androidx/releases/compose-material3-adaptive>
- Android navigation documentation: NavHost owns destination back stacks. <https://developer.android.com/develop/ui/compose/navigation>
- Provider constraints and data-field verification are recorded separately in `DISCOVERY_MAP_CONTRACT.md` and `DISCOVERY_DATA_CONTRACT.md` as verified.

## Execution record

- 2026-09-15: Read the complete brief and project constraints. Verified a clean tracked worktree, fast-forwarded the one README-only upstream commit, and created the development branch. Existing untracked user attachments and AGENTS.md were preserved. Implementation and verification are in progress.
- 2026-09-20: `testDebugUnitTest` passed 399 tests (zero failures/errors/skips); `assembleDebug`, `assembleDebugAndroidTest`, `lintDebug`, `lintRelease`, and `minifyReleaseWithR8` completed successfully. Google reflection audit, AMap JNI audit (7 classes/24 members), 7 audit mutation tests and debug APK content audit passed. These results precede the last layout, permission and synthetic app-shell test refinements and must be repeated at the final source state.
- 2026-09-20: Dedicated API 26 (`emulator-5580`) and API 37 (`emulator-5554`, confirmed AVD name `anitabi-redesign-api37`) each passed 34 instrumentation tests: 12 discovery components, 13 existing UI contracts, 5 settings migration/preferences, and 4 real MainActivity navigation/theme tests. Evidence manifests with APK hashes and native PNG captures live in ignored `build/frontend-review/api26` and `api37`. Discovery and shell test data are synthetic; SDK unavailable/privacy list fallback and component map placeholders are explicitly not real map-rendering evidence. API 37 first-run permission/service/onboarding/relaunch test separately passed; no process-death claim.
- 2026-09-20: Synthetic computation checks kept every member at 1k/10k/100k points. Observed index/query/cluster times were 1/1/4 ms, 6/1/6 ms, and 64/7/19 ms respectively; 100k JVM heap readings were 158,560/180,574 KiB before/after. These are one local JVM sample, not peak memory, device frame-time, native marker performance or a guaranteed budget.
- 2026-09-20: Independent review inspected all 19 original native API 26 component captures. It found narrow wide-screen panels, clipped short-landscape controls, and insufficient large-font half-panel space. Corrections and subsequent API 37 verdict resolved those three findings, then exposed a status-inset/control overlap. The wide-screen overlap has since been visually resolved; final enlarged-font control placement is being reverified. Whole-app captures were added subsequently; final whole-surface review remains open.

## Diagnoses and recovery

- Initial compile errors: missing saved-tour integration method, treating `mapProvider` as a function, nullable primitive-array `orEmpty`, a missing progress-indicator import and an invalid test extension import. Correct APIs/imports and the saved-tour implementation now compile; no SDK or dependency downgrade was used.
- Saved refresh tests initially failed because `StoredTourV2.from` inferred new active-point/leg fields from refreshed route legs. Refresh now publishes only a snapshot-checked in-memory route. Exact persistent entity equality and concurrent edit/progress regressions pass; saved user choices and progress are not rewritten during refresh.
- Repository audit found a dropped manual-refresh request during an active round, overlapping workers during cancellation and abandoned failed subject requests. A single guarded worker drains coalesced rounds, preserves force across pause/resume and cleans up deferred requests independently of callers. Synthetic regression checks passed in the 399-test run.
- API 37's first instrumentation startup ended with `BIND APPLICATION ANR` while R8/Lint and two emulators were running. No Java crash trace was present; a subsequent settings-only run and the full 30/34-test runs passed. Concurrent host load is a plausible cause, not a proven application root cause. Full UI work is now scheduled after builds rather than during R8.
- First API 26 UI run had three assertion failures: stale expected loading copy, scrolling to an uncomposed lazy settings item, and an exact-text assertion intended as substring matching. The loading expectation now reflects the actual unloaded state, settings scroll via its lazy container, and the link failure assertion explicitly matches a substring. Both API suites subsequently passed.
- Current live API samples (three main-index-selected subjects, 39 point detail rows) have metadata-only point records. The `lite` root `geo` is a subject center, not a point coordinate. Bangumi selections for indexed subjects now reuse the exact index coordinates, while prioritized API details only supply metadata. There are still zero comparable live old/new point-coordinate pairs; this gate remains unproven, as documented in `DISCOVERY_DATA_CONTRACT.md`.

## Remaining acceptance work

Final source-state checks, AMap native rendering/projection/anchor tests, dense native-map frame/memory checks and active navigation/return-to-home integration remain open. Final screenshots must include the last layout corrections. No physical-device installation, live navigation drive/voice claim, production deployment, GitHub release or new signed production APK has occurred.

- Subsequent source-state JVM run: **400 tests passed**, including index-backed Bangumi selection coordinate preservation. API 26 onboarding also passed, with permission dialog, service guide and discovery-home relaunch markers.
- The Google native test originally failed at its image-card pixel gate after initial projection, circle pixels and a real marker touch had succeeded. The test now waits for presentation callback revisions, samples the current view origin and asserts the SDK theme. The selected Google native test subsequently passed in 22.415 seconds. This proves synthetic marker projection/pixel/input stability on a genuine SDK view, not base-tile authorization, navigation driving or a general device-performance claim. No production image bug has been established by this sequence.
- API 37 real TalkBack 17 service was enabled for a dedicated test and original accessibility settings restored afterward. The native accessibility focus/click/selection/tab test passed in 5.164 seconds. It does not prove audible speech or human gestures; API 26 does not have TalkBack installed.
- The AMap native tests stop before SDK construction because the local debug build has `AMAP_API_KEY_CONFIGURED=false`. User authorized use of the existing CI signing configuration. Read-only CI inspection found `v025-internal-test.yml` allows only the dispatched default branch and environment `v0.2.5-release` allows protected branches only. These policies have not been weakened. The candidate branch has not yet passed that signed-build path.
- A bounded candidate extension is prepared in `FRONTEND_SIGNED_TEST_PLAN.md`: one named protected branch and an explicit exact commit, existing environment/signature/region/backend guards, and main-only SSH jobs. Activation remains subject to explicit owner approval. YAML parses, all 12 embedded shell programs pass syntax checks, and the Gradle signing opt-in rejects missing signing material before running tasks. Normal local debug builds still pass with the opt-in unset.
- The first PR CI run `35517463881` passed backend and verify. Emulator UI checks exposed a fixture/device mismatch: its actual captured home image is 320 x 640 pixels, smaller than the 900 x 640 tablet fixture. CI now requests the Pixel 2 profile used locally. The search test now scrolls the local-results section into composition before waiting for its selection control. Newer UI test setup explicitly revokes emulator location permission and AMap readiness for its synthetic active-navigation case.
- API 37 now passes **35** UI/application/settings cases, including synthetic active navigation -> home -> trips -> navigation with runtime/progress unchanged; this is not a live guidance/voice claim. The latest layout refinements replace the duplicated overview work strip with an explicit work-list mode, retaining a usable large-font point row.
- Real Google native dense workload passed all exact membership and nine-cluster pixel checks at 1k/10k/100k points (8.764 seconds for the complete test). Publication-to-visible was approximately 341/805/3200 ms; publication-to-pixels approximately 598/908/3328 ms. The 100k case sampled Java/native maxima of 73,248,992/40,384,688 bytes. Window frame reports are sparse (5/1/1 samples; the 100k report was 34.72 ms with one missed deadline), so they are not a continuous map-FPS or universally smooth-scrolling claim. Detailed scoped metrics and synthetic screenshots are in `build/frontend-review/api37/frontend-review/native-google-dense-metrics-api37.json`.
