"""Host-only parsing and evidence tests. Nothing here operates a device or measures performance."""

import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


DRIVER = load("measure_discovery", ROOT / "scripts/measure-discovery.py")
SUMMARY = load("summarize_discovery", ROOT / "scripts/summarize-discovery-performance.py")


def manifest():
    return '''N: android=http://schemas.android.com/apk/res/android
  E: manifest (line=1)
    A: package="cn.anitabi.navigator" (Raw: "cn.anitabi.navigator")
    E: application (line=2)
      A: android:name(0x01010003)="cn.anitabi.navigator.measurement.DiscoveryMeasurementApplication"
      A: android:debuggable(0x0101000f)=(type 0x12)0x0
      E: profileable (line=3)
        A: android:enabled(0x0101000e)=(type 0x12)0xffffffff
        A: android:shell(0x01010594)=(type 0x12)0xffffffff
      E: provider (line=4)
        A: android:authorities(0x01010018)="cn.anitabi.navigator.discovery-measurement"
        A: android:enabled(0x0101000e)=(type 0x12)0xffffffff
        A: android:permission(0x01010006)="android.permission.DUMP"
      E: provider (line=5)
        A: android:authorities(0x01010018)="cn.anitabi.navigator.discovery-diagnostics"
        A: android:permission(0x01010006)="android.permission.DUMP"
'''


def visual_report():
    return {"viewportSnapshotValid": True, "viewportEligible": True, "viewportMemberCount": 10000,
            "expectedPhotoCount": 6, "visiblePhotoCount": 6,
            "nativeVisual": {"outcome": "OBSERVED", "allCurrentMarkerPixelsMatch": True,
                "currentMarkerCount": 8, "eligibleMarkerCount": 8, "matchedMarkerCount": 8,
                "currentMemberCount": 10000, "matchedMemberCount": 10000,
                "committedPhotoCount": 6, "eligiblePhotoCount": 6, "matchedPhotoCount": 6,
                "sampleInFlight": False, "captureMethod": "SURFACE_VIEW_PIXEL_COPY",
                "timingDefinition": "first_verified_copy_upper_bound_including_sampling_cost",
                "firstViewportPointsDrawnOffsetNanos": 12_000_000, "firstVisibleImageOffsetNanos": 30_000_000}}


class MeasurementDriverTest(unittest.TestCase):
    def test_executed_driver_is_ascii_only(self):
        self.assertTrue((ROOT / "scripts/measure-discovery.py").read_bytes().isascii())

    def test_manifest_requires_non_debuggable_profileable_and_guarded_measurement_target(self):
        self.assertFalse(DRIVER.inspect_manifest(manifest())["debuggable"])
        for text in (
            manifest().replace("android:debuggable(0x0101000f)=(type 0x12)0x0", "android:debuggable(0x0101000f)=(type 0x12)0xffffffff"),
            manifest().replace("android:shell(0x01010594)=(type 0x12)0xffffffff", "android:shell(0x01010594)=(type 0x12)0x0"),
            manifest().replace("android.permission.DUMP", "TEST_ONLY_WRONG"),
        ):
            with self.assertRaises(DRIVER.Failure):
                DRIVER.inspect_manifest(text)

    def test_full_android_namespace_matches_short_alias_measurement_fields(self):
        qualified = manifest().replace("android:", "http://schemas.android.com/apk/res/android:")
        self.assertEqual(DRIVER.parse_manifest_tree(manifest()), DRIVER.parse_manifest_tree(qualified))
        self.assertEqual(DRIVER.inspect_manifest(manifest()), DRIVER.inspect_manifest(qualified))

    def test_foreign_or_lookalike_namespace_cannot_supply_measurement_identity(self):
        for namespace in ("https://schemas.android.com/apk/res/android:",
                          "http://schemas.android.com/apk/res/android/foreign:",
                          "http://example.invalid/apk/res/android:"):
            with self.subTest(namespace=namespace), self.assertRaises(DRIVER.Failure):
                DRIVER.inspect_manifest(manifest().replace("android:", namespace))

    def test_mixed_namespace_duplicates_cannot_override_guarded_manifest_fields(self):
        text = manifest().replace("      E: provider (line=5)\n",
            "      E: provider (line=5)\n        A: android:enabled(0x0101000e)=(type 0x12)0xffffffff\n")
        lines = text.splitlines()
        unsafe_values = (
            ('android:name(0x01010003)="cn.anitabi.navigator.measurement.DiscoveryMeasurementApplication"',
             'android:name(0x01010003)="cn.anitabi.navigator.AnitabiApplication"'),
            ("android:debuggable(0x0101000f)=(type 0x12)0x0", "android:debuggable(0x0101000f)=(type 0x12)0xffffffff"),
            ("android:enabled(0x0101000e)=(type 0x12)0xffffffff", "android:enabled(0x0101000e)=(type 0x12)0x0"),
            ("android:shell(0x01010594)=(type 0x12)0xffffffff", "android:shell(0x01010594)=(type 0x12)0x0"),
            ('android:authorities(0x01010018)="cn.anitabi.navigator.discovery-measurement"',
             'android:authorities(0x01010018)="cn.anitabi.navigator.unprotected"'),
            ('android:authorities(0x01010018)="cn.anitabi.navigator.discovery-diagnostics"',
             'android:authorities(0x01010018)="cn.anitabi.navigator.unprotected"'),
            ('android:permission(0x01010006)="android.permission.DUMP"',
             'android:permission(0x01010006)="TEST_ONLY_WRONG"'),
        )
        for index, line in enumerate(lines):
            for original, unsafe in unsafe_values:
                if original not in line:
                    continue
                qualified = line.replace(original, unsafe).replace("android:", "http://schemas.android.com/apk/res/android:")
                for full_first in (False, True):
                    duplicate = [qualified, line] if full_first else [line, qualified]
                    changed = "\n".join(lines[:index] + duplicate + lines[index + 1:])
                    with self.subTest(line=index, field=original, full_first=full_first):
                        with self.assertRaises(DRIVER.Failure) as raised:
                            DRIVER.inspect_manifest(changed)
                        self.assertEqual("duplicate_manifest_attribute", raised.exception.code)
                        self.assertTrue(raised.exception.fatal)

    def test_art_filter_only_comes_from_exact_installed_apk(self):
        text = "path: /data/app/other/base.apk\n x86_64: [status=verify]\npath: /data/app/test/base.apk\n arm64: [status=speed]\n"
        self.assertEqual(["speed"], DRIVER.art_filters(text, "/data/app/test/base.apk"))
        self.assertEqual([], DRIVER.art_filters(text, "/data/app/absent/base.apk"))
        self.assertEqual(["speed"], DRIVER.art_filters(text.replace("path: /data/app/test/base.apk", "path:\n /data/app/test/base.apk"), "/data/app/test/base.apk"))

    def test_permission_absence_is_unknown_not_denied(self):
        permission = "android.permission.ACCESS_FINE_LOCATION"
        self.assertTrue(DRIVER.permission_denied(permission + ": granted=false", permission))
        self.assertFalse(DRIVER.permission_denied(permission + ": granted=true", permission))
        with self.assertRaises(DRIVER.Failure):
            DRIVER.permission_denied("unavailable", permission)

    def test_commit_and_sdk_callback_never_become_native_pixel_proof(self):
        phases = {"SDK_READY", "BASEMAP_RENDER_OBSERVED", "FIRST_VISIBLE_IMAGE", "VIEWPORT_MARKERS_COMMITTED"}
        events = [{"phase": p, "outcome": "OBSERVED", "offsetNanos": 5_000_000, "durationNanos": None} for p in phases]
        result = DRIVER.stage_values({"trace": {"events": events}}, phases)
        self.assertEqual({"outcome": "observed", "millis": 5.0}, result["SDK_READY"])
        self.assertEqual({"outcome": "not_observed", "millis": None}, result["FIRST_VISIBLE_IMAGE"])
        self.assertEqual({"outcome": "not_observed", "millis": None}, result["BASEMAP_RENDER_OBSERVED"])

    def test_dropped_ring_does_not_relabel_later_event_as_initial_stage(self):
        last = {"phase": "INDEX_PARSE", "outcome": "OBSERVED", "durationNanos": 500_000, "offsetNanos": 100_000_000}
        diagnostics = {"trace": {"droppedEvents": 1, "events": [last]}}
        self.assertIsNone(DRIVER.stage_values(diagnostics, {"INDEX_PARSE"})["INDEX_PARSE"]["millis"])
        diagnostics["harness"] = {"earlyTrace": {"events": [dict(last, durationNanos=2_000_000)]}}
        self.assertEqual(2.0, DRIVER.stage_values(diagnostics, {"INDEX_PARSE"})["INDEX_PARSE"]["millis"])

    def test_phase_summaries_preserve_early_and_late_observed_stages_after_ring_loss(self):
        diagnostics = {"trace": {"droppedEvents": 100, "events": [], "stageEvents": [
            {"phase": "INDEX_PARSE", "outcome": "OBSERVED", "durationNanos": 2_000_000},
            {"phase": "DETAILS_SYNC_COMPLETE", "outcome": "OBSERVED", "offsetNanos": 50_000_000},
        ]}, "harness": {"earlyTrace": {"events": []}}}
        result = DRIVER.stage_values(diagnostics, {"INDEX_PARSE", "DETAILS_SYNC_COMPLETE"})
        self.assertEqual(2.0, result["INDEX_PARSE"]["millis"])
        self.assertEqual(50.0, result["DETAILS_SYNC_COMPLETE"]["millis"])

    def test_ready_selection_is_a_paired_duration_and_never_a_launch_offset(self):
        event = {"phase": "READY_VIEWPORT_SELECTION", "outcome": "OBSERVED", "offsetNanos": 9_000_000_000}
        diagnostics = {"trace": {"stageEvents": [event]}}
        result = DRIVER.stage_values(diagnostics, {event["phase"]})
        self.assertEqual({"outcome": "observed", "millis": None}, result[event["phase"]])
        event["durationNanos"] = 350_000_000
        self.assertEqual(350.0, DRIVER.stage_values(diagnostics, {event["phase"]})[event["phase"]]["millis"])
        event["outcome"] = "CANCELLED"
        self.assertEqual("cancelled", DRIVER.stage_values(diagnostics, {event["phase"]})[event["phase"]]["outcome"])

    def test_pixel_timings_require_actual_current_native_copy_fields_and_preserve_geographic_gap(self):
        report = visual_report()
        diagnostics = {"trace": {"events": [{"phase": phase, "outcome": "OBSERVED", "offsetNanos": 1}
                            for phase in DRIVER.PIXEL_PHASES]}, "harness": {"providerStatus": report}}
        stages = DRIVER.stage_values(diagnostics, DRIVER.PIXEL_PHASES)
        self.assertEqual({"outcome": "observed", "millis": 12.0}, stages["FIRST_VIEWPORT_POINTS_DRAWN"])
        self.assertEqual({"outcome": "observed", "millis": 30.0}, stages["FIRST_VISIBLE_IMAGE"])
        self.assertEqual({"outcome": "not_observed", "millis": None}, stages["BASEMAP_RENDER_OBSERVED"])
        self.assertEqual(["native_point_pixels", "visible_image_pixels", "geographic_basemap"], DRIVER.GAPS)
        report["nativeVisual"]["matchedPhotoCount"] = 5
        self.assertIsNone(DRIVER.stage_values(diagnostics, DRIVER.PIXEL_PHASES)["FIRST_VISIBLE_IMAGE"]["millis"])
        report["nativeVisual"]["matchedMemberCount"] = 9999
        self.assertIsNone(DRIVER.stage_values(diagnostics, DRIVER.PIXEL_PHASES)["FIRST_VIEWPORT_POINTS_DRAWN"]["millis"])

    def test_stale_unknown_capture_and_invalid_timing_cannot_supply_pixel_timings(self):
        for field, value in (("outcome", "NOT_OBSERVED"), ("allCurrentMarkerPixelsMatch", False),
                             ("eligibleMarkerCount", 7), ("captureMethod", "SCREENSHOT"),
                             ("timingDefinition", "callback"), ("firstViewportPointsDrawnOffsetNanos", True),
                             ("firstViewportPointsDrawnOffsetNanos", -1)):
            report = visual_report()
            report["nativeVisual"][field] = value
            diagnostics = {"harness": {"providerStatus": report}}
            with self.subTest(field=field, value=value):
                self.assertIsNone(DRIVER.stage_values(diagnostics, DRIVER.PIXEL_PHASES)["FIRST_VIEWPORT_POINTS_DRAWN"]["millis"])

    def test_ready_condition_requires_frozen_10k_normal_images_and_keeps_twenty_attempts(self):
        args = SimpleNamespace(start_state="ready_viewport", size=10000, data_profile="normal", image_profile="normal")
        DRIVER.validate_ready_condition(args)
        self.assertEqual(20, DRIVER.ATTEMPTS)
        for field, value in (("size", 1000), ("data_profile", "delayed"), ("image_profile", "offline")):
            changed = SimpleNamespace(**vars(args))
            setattr(changed, field, value)
            with self.subTest(field=field), self.assertRaises(DRIVER.Failure):
                DRIVER.validate_ready_condition(changed)

    def test_ready_interval_rejects_data_work_and_app_network_but_allows_image_cache(self):
        clean = {"trace": {"enabled": True, "droppedSpans": 0, "listenerFailureCount": 0,
                          "stageEvents": [], "events": [], "pendingSpans": [], "requests": [], "counters": {}}}
        self.assertTrue(DRIVER.ready_interval_clean(clean))
        clean["trace"]["requests"] = [{"endpoint": "IMAGE", "completedCount": 6, "caches": {"DISK": 6}}]
        self.assertTrue(DRIVER.ready_interval_clean(clean))
        for trace_change in ({"events": [{"phase": "INDEX_PARSE", "outcome": "FAILED"}]},
                             {"stageEvents": [{"phase": "PAGE_FETCH", "outcome": "OBSERVED"}]},
                             {"pendingSpans": [{"phase": "SUBJECT_FETCH"}]}, {"droppedSpans": 1},
                             {"counters": {"CLASSIFIED_POINT_COUNT": 10000}},
                             {"requests": [{"endpoint": "STATIC_INDEX", "completedCount": 1}]},
                             {"requests": [{"endpoint": "IMAGE", "completedCount": 6, "caches": {"NETWORK": 1, "DISK": 5}}]}):
            with self.subTest(change=trace_change):
                self.assertFalse(DRIVER.ready_interval_clean({"trace": dict(clean["trace"], **trace_change)}))

    def test_ready_span_requires_accepted_current_members_and_actual_duration(self):
        report = visual_report()
        event = {"phase": "READY_VIEWPORT_SELECTION", "outcome": "OBSERVED", "durationNanos": 350_000_000, "itemCount": 10000}
        self.assertTrue(DRIVER.ready_selection_observed(report, {"trace": {"stageEvents": [event]}}))
        for change in ({"outcome": "CANCELLED"}, {"durationNanos": None}, {"itemCount": 9999}):
            self.assertFalse(DRIVER.ready_selection_observed(report, {"trace": {"stageEvents": [dict(event, **change)]}}))

    def test_warm_reset_requires_repository_trace_and_native_copy_quiescence(self):
        report = {"listMode": True, "repositoryStateQuiescent": True, "repositoryQuiescent": True,
                  "traceQuiescent": True, "nativeVisual": {"sampleInFlight": False}}
        diagnostics = {"trace": {"enabled": True, "droppedSpans": 0, "pendingSpans": []}}
        self.assertTrue(DRIVER.warm_quiescent(report, diagnostics))
        for field in ("repositoryStateQuiescent", "repositoryQuiescent", "traceQuiescent"):
            self.assertFalse(DRIVER.warm_quiescent(dict(report, **{field: False}), diagnostics))
        self.assertFalse(DRIVER.warm_quiescent(dict(report, nativeVisual={"sampleInFlight": True}), diagnostics))
        self.assertFalse(DRIVER.warm_quiescent(report, {"trace": dict(diagnostics["trace"], pendingSpans=[{"phase": "IMAGE_LOAD"}])}))

    def test_adb_and_file_probe_use_the_remaining_attempt_watchdog(self):
        driver = object.__new__(DRIVER.Driver)
        driver.args = SimpleNamespace(adb="unused", serial="emulator-5584")
        driver.attempt_deadline = 11.0
        with mock.patch.object(DRIVER.time, "monotonic", return_value=10.0), \
                mock.patch.object(DRIVER.subprocess, "run", return_value=SimpleNamespace(returncode=0, stderr=b"")) as run:
            self.assertTrue(driver.exists("/data/local/tmp/synthetic"))
            self.assertEqual(1.0, run.call_args.kwargs["timeout"])
        with mock.patch.object(DRIVER.time, "monotonic", return_value=11.0), \
                mock.patch.object(DRIVER.subprocess, "run") as run:
            with self.assertRaises(DRIVER.Failure) as raised:
                driver.adb_run(["get-state"])
            self.assertEqual("timeout", raised.exception.category)
            run.assert_not_called()

    def test_failed_restore_keeps_owned_retry_sentinel_and_still_removes_owned_transport(self):
        driver = object.__new__(DRIVER.Driver)
        driver.prepared = driver.own_bootstrap = driver.own_reverse = driver.own_certificate = True
        driver.cert_hash = "a" * 64
        driver.identity = lambda **_kwargs: None
        driver.kill_background = lambda **_kwargs: None
        driver.control = mock.Mock(side_effect=DRIVER.Failure("storage", "restore_failed"))
        calls = []
        def adb(*arguments, **_kwargs):
            calls.append(arguments)
            if arguments == ("reverse", "--list"):
                return "emulator-5584 tcp:18443 tcp:18443\n"
            if arguments[:2] == ("shell", "sha256sum"):
                return driver.cert_hash + "  synthetic\n"
            return ""
        driver.adb = adb
        result = driver.cleanup()
        self.assertTrue(result["settingsRestoreRetryRequired"])
        self.assertTrue(result["bootstrapRetainedForRestoreRetry"])
        self.assertTrue(result["reverseRemoved"] and result["certificateRemoved"])
        self.assertTrue(driver.own_bootstrap)
        self.assertNotIn(("shell", "rm", "-f", DRIVER.BOOTSTRAP), calls)

    def test_normal_images_require_decode_or_cache_terminal_counts_and_marker_commit(self):
        trace = {"counters": {"IMAGE_REQUEST_COUNT": 2, "IMAGE_SUCCESS_COUNT": 2, "IMAGE_DECODE_COUNT": 2},
                 "events": [{"phase": "IMAGE_MARKERS_COMMITTED", "outcome": "OBSERVED", "itemCount": 1}], "pendingSpans": []}
        self.assertTrue(DRIVER.image_terminal({"trace": trace}, offline=False))
        self.assertFalse(DRIVER.image_terminal({"trace": trace}, offline=False, expected_committed=6))
        trace["pendingSpans"] = [{"phase": "IMAGE_LOAD"}]
        self.assertFalse(DRIVER.image_terminal({"trace": trace}, offline=False))
        trace["pendingSpans"] = []
        del trace["counters"]["IMAGE_DECODE_COUNT"]
        self.assertFalse(DRIVER.image_terminal({"trace": trace}, offline=False))
        trace["counters"]["IMAGE_DISK_HIT_COUNT"] = 2
        self.assertTrue(DRIVER.image_terminal({"trace": trace}, offline=False))

    def test_offline_errors_are_terminal_fallback_not_normal_image_success(self):
        diagnostics = {"trace": {"counters": {"IMAGE_REQUEST_COUNT": 2, "IMAGE_ERROR_COUNT": 2}, "pendingSpans": []}}
        self.assertTrue(DRIVER.image_terminal(diagnostics, offline=True))
        self.assertFalse(DRIVER.image_terminal(diagnostics, offline=False))

    def test_status_cannot_start_an_absent_target(self):
        driver = object.__new__(DRIVER.Driver)
        driver.pid = lambda: None
        driver.own_bootstrap = False
        driver.adb = lambda *_args, **_kwargs: self.fail("A provider call must not be issued")
        with self.assertRaises(DRIVER.Failure):
            driver.control("status")
        with self.assertRaises(DRIVER.Failure):
            driver.control("prepare", allow_bootstrap_start=True)

    def test_producer_record_matches_shared_analyzer_schema_and_retains_gaps(self):
        record = {"schemaVersion": 1,
                  "condition": {"provider": "google", "size": 1000, "dataKind": "synthetic", "startState": "cold_no_cache", "dataProfile": "normal", "imageProfile": "normal"},
                  "environmentHash": "a" * 64, "measurementHarnessHash": "b" * 64,
                  "apkSha256": "c" * 64, "sourceSha": "d" * 40, "attemptedIndex": 1,
                  "completed": False, "failureCategory": "timeout", "stageMillis": DRIVER.stage_values({}, SUMMARY.PHASES),
                  "diagnostics": {"harness": {"completionDefinition": "expected_controlled_workload_terminal_not_F6_acceptance"}},
                  "acceptanceGaps": DRIVER.GAPS}
        checked = SUMMARY.validate_record(record)
        self.assertEqual(DRIVER.GAPS, checked["acceptanceGaps"])
        self.assertFalse(checked["completed"])


if __name__ == "__main__":
    unittest.main()
