"""Statistics/schema checks using synthetic numbers only; not Discovery performance evidence."""

import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "summarize-discovery-performance.py"
SPEC = importlib.util.spec_from_file_location("discovery_performance_summary", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def record(index=1, millis=10, *, completed=True):
    return {
        "schemaVersion": 1,
        "condition": {"provider": "google", "size": 10000, "dataKind": "synthetic",
                      "startState": "cold_no_cache", "dataProfile": "normal", "imageProfile": "normal"},
        "environmentHash": "a" * 64, "measurementHarnessHash": "b" * 64,
        "apkSha256": "c" * 64, "sourceSha": "d" * 40,
        "attemptedIndex": index, "completed": completed, "failureCategory": None if completed else "timeout",
        "stageMillis": {"INDEX_FETCH": {"outcome": "observed", "millis": millis}},
    }


def group(*records):
    return [MODULE.validate_record(value) for value in records]


class DiscoveryPerformanceSummaryTest(unittest.TestCase):
    def test_nearest_rank_uses_tenth_and_nineteenth_of_twenty(self):
        values = list(range(1, 21))
        self.assertEqual({"count": 20, "p50": 10, "p95": 19, "max": 20}, MODULE.statistics(values[::-1]))
        self.assertEqual({"count": 0, "p50": None, "p95": None, "max": None}, MODULE.statistics([]))

    def test_twenty_attempts_with_timeout_never_become_twenty_completed_attempts(self):
        attempts = group(*(record(index, index, completed=index != 20) for index in range(1, 21)))
        report = MODULE.compare_groups(attempts, attempts)
        baseline = report["baseline"]
        self.assertEqual((20, 19, 1), (baseline["attemptedCount"], baseline["completedCount"], baseline["failedCount"]))
        self.assertEqual({"timeout": 1}, baseline["failureCounts"])
        self.assertTrue(baseline["atLeast20Attempts"])
        self.assertFalse(baseline["atLeast20CompletedAttempts"])
        self.assertEqual(20, baseline["stages"]["INDEX_FETCH"]["millis"]["count"])
        self.assertFalse(report["stageChanges"]["INDEX_FETCH"]["bothHave20CompletedAttemptsWithoutFailuresOrIndexGaps"])
        self.assertEqual("timeout", baseline["attempts"][-1]["failureCategory"])

    def test_null_missing_and_failed_stages_are_not_zero_or_successful_samples(self):
        observed = record(1, 0)
        untimed = record(2, None)
        absent = record(3)
        absent["stageMillis"] = {}
        failed = record(4, completed=False)
        failed["stageMillis"]["INDEX_FETCH"] = {"outcome": "failed", "millis": 9999}
        unavailable = record(5)
        unavailable["stageMillis"]["INDEX_FETCH"] = {"outcome": "not_observed", "millis": None}
        values = group(observed, untimed, absent, failed, unavailable)
        stage = MODULE.compare_groups(values, values)["baseline"]["stages"]["INDEX_FETCH"]
        self.assertEqual({"count": 1, "p50": 0, "p95": 0, "max": 0}, stage["millis"])
        self.assertEqual(1, stage["missingEntryCount"])
        self.assertEqual(1, stage["observedWithoutTimingCount"])
        self.assertEqual(1, stage["outcomes"]["failed"])
        self.assertEqual(1, stage["outcomes"]["not_observed"])

    def test_required_phase_missing_from_both_groups_remains_explicit(self):
        values = group(record())
        stage = MODULE.compare_groups(values, values)["baseline"]["stages"]["BASEMAP_RENDER_OBSERVED"]
        self.assertEqual(1, stage["missingEntryCount"])
        self.assertIsNone(stage["millis"]["p95"])

    def test_ready_viewport_duration_and_uncalibrated_pixel_gaps_survive_comparison(self):
        raw = record()
        raw["condition"]["startState"] = "ready_viewport"
        raw["stageMillis"] = {"READY_VIEWPORT_SELECTION": {"outcome": "observed", "millis": 350},
                              "FIRST_VISIBLE_IMAGE": {"outcome": "observed", "millis": 500}}
        raw["acceptanceGaps"] = ["native_point_pixels", "visible_image_pixels", "geographic_basemap"]
        values = group(raw)
        report = MODULE.compare_groups(values, values)
        self.assertEqual(350, report["baseline"]["stages"]["READY_VIEWPORT_SELECTION"]["millis"]["p95"])
        self.assertEqual(500, report["baseline"]["stages"]["FIRST_VISIBLE_IMAGE"]["millis"]["p95"])
        self.assertEqual({gap: 1 for gap in raw["acceptanceGaps"]}, report["baseline"]["acceptanceGapCounts"])
        self.assertFalse(report["stageChanges"]["READY_VIEWPORT_SELECTION"]["bothHave20ObservedTimings"])

    def test_completed_controlled_runs_retain_acceptance_gaps_without_an_overall_pass(self):
        raw = record()
        raw["acceptanceGaps"] = ["native_point_pixels", "visible_image_pixels", "geographic_basemap"]
        values = group(raw)
        report = MODULE.compare_groups(values, values)
        self.assertEqual(1, report["baseline"]["completedCount"])
        self.assertEqual({gap: 1 for gap in raw["acceptanceGaps"]}, report["baseline"]["acceptanceGapCounts"])
        self.assertEqual(raw["acceptanceGaps"], report["baseline"]["attempts"][0]["acceptanceGaps"])
        text = MODULE.markdown(report)
        self.assertIn("They are not F6 acceptance passes", text)
        self.assertIn("geographic_basemap=1", text)
        for gaps in (["unsupported_gap"], ["geographic_basemap", "geographic_basemap"], "geographic_basemap"):
            raw["acceptanceGaps"] = gaps
            with self.assertRaises(MODULE.RecordError):
                MODULE.validate_record(raw)

    def test_counters_preserve_exact_integers_zero_missing_and_null_per_run(self):
        first, second, third = record(1), record(2), record(3)
        first["diagnostics"] = {"trace": {"enabled": True, "counters": {"CACHE_WRITE_COUNT": 0, "CACHE_WRITE_BYTES": 2**60}}}
        second["diagnostics"] = {"counters": {"CACHE_WRITE_COUNT": None, "CACHE_WRITE_BYTES": 3}}
        third["diagnostics"] = {"trace": {"counters": {"CACHE_WRITE_BYTES": 7}}}
        values = group(first, second, third)
        counters = MODULE.compare_groups(values, values)["baseline"]["counters"]
        partial = counters["CACHE_WRITE_COUNT"]
        self.assertEqual(0, partial["knownSum"])
        self.assertIsNone(partial["completeSum"])
        self.assertEqual(2, partial["missingCount"])
        self.assertEqual([0, None, None], [item["value"] for item in partial["perRun"]])
        self.assertEqual(2**60 + 10, counters["CACHE_WRITE_BYTES"]["completeSum"])

    def test_all_unknown_counter_has_no_sum(self):
        raw = record()
        raw["diagnostics"] = {"counters": {"CACHE_WRITE_COUNT": None}}
        values = group(raw)
        counter = MODULE.compare_groups(values, values)["baseline"]["counters"]["CACHE_WRITE_COUNT"]
        self.assertIsNone(counter["knownSum"])
        self.assertIsNone(counter["completeSum"])

    def test_pair_refuses_condition_environment_and_harness_mismatch(self):
        baseline = group(record())
        for key in ("condition", "environmentHash", "measurementHarnessHash"):
            changed = record()
            if key == "condition":
                changed[key]["provider"] = "amap"
            else:
                changed[key] = "e" * 64
            with self.subTest(key=key), self.assertRaises(MODULE.RecordError):
                MODULE.compare_groups(baseline, group(changed))

    def test_different_candidate_source_and_apk_are_expected(self):
        changed = record()
        changed.update(sourceSha="e" * 40, apkSha256="f" * 64)
        report = MODULE.compare_groups(group(record()), group(changed))
        self.assertNotEqual(report["baseline"]["sourceSha"], report["candidate"]["sourceSha"])

    def test_invalid_types_nonfinite_values_and_impossible_states_fail_closed(self):
        variants = []
        for millis in (-1, float("nan"), float("inf"), True, "10"):
            value = record(millis=millis)
            variants.append(value)
        value = record()
        value["condition"]["size"] = "10k"
        variants.append(value)
        value = record()
        value["condition"]["dataKind"] = "actual"
        variants.append(value)
        value = record()
        value["failureCategory"] = "timeout"
        variants.append(value)
        value = record()
        value["stageMillis"]["INDEX_FETCH"] = {"outcome": "unsupported", "millis": 0}
        variants.append(value)
        value = record()
        value["diagnostics"] = {"trace": {"enabled": False, "counters": {"CACHE_WRITE_COUNT": 1}}}
        variants.append(value)
        for value in variants:
            with self.subTest(value=value), self.assertRaises(MODULE.RecordError):
                MODULE.validate_record(value)

    def test_directory_preserves_failures_and_refuses_duplicates_mixed_identity_or_invalid_file(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary)
            self.write(path / "one.json", record(1))
            self.write(path / "two.json", record(2, completed=False))
            self.assertEqual(2, len(MODULE.load_group(path)))
            for invalid in (record(1), dict(record(2), apkSha256="f" * 64), {"bad": True}):
                self.write(path / "two.json", invalid)
                with self.assertRaises(MODULE.RecordError):
                    MODULE.load_group(path)

    def test_empty_group_rejected_and_index_gaps_are_retained(self):
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaises(MODULE.RecordError):
                MODULE.load_group(temporary)
        values = group(record(1), record(3))
        self.assertEqual([[2, 2]], MODULE.compare_groups(values, values)["baseline"]["indexGaps"])

    def test_comparison_never_divides_by_zero_or_invents_missing_improvement(self):
        report = MODULE.compare_groups(group(record(millis=0)), group(record(millis=0)))
        self.assertIsNone(report["stageChanges"]["INDEX_FETCH"]["p95ImprovementPercent"])
        report = MODULE.compare_groups(group(record(millis=100)), group(record(millis=70)))
        self.assertEqual(30, report["stageChanges"]["INDEX_FETCH"]["p95ImprovementPercent"])

    def test_cli_writes_json_and_markdown_and_refusal_does_not_write_outputs(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            before, after = root / "b1", root / "b2"
            before.mkdir()
            after.mkdir()
            self.write(before / "one.json", record())
            self.write(after / "one.json", record(completed=False))
            output, document = root / "summary.json", root / "summary.md"
            arguments = ["--b1", str(before), "--b2", str(after), "--json-output", str(output), "--markdown-output", str(document)]
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, MODULE.main(arguments))
            report = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(1, report["candidate"]["failedCount"])
            text = document.read_text(encoding="utf-8")
            self.assertIn("B2 failure categories: timeout=1", text)
            self.assertIn("unknown", text)
            self.assertIn("No missing timing is replaced with zero", text)
            changed = record()
            changed["environmentHash"] = "f" * 64
            self.write(after / "one.json", changed)
            previous = output.read_bytes()
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(2, MODULE.main(arguments))
            self.assertEqual(previous, output.read_bytes())

    def test_cli_refuses_output_in_attempt_directory(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            before, after = root / "b1", root / "b2"
            before.mkdir()
            after.mkdir()
            self.write(before / "one.json", record())
            self.write(after / "one.json", record())
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(2, MODULE.main(["--b1", str(before), "--b2", str(after),
                    "--json-output", str(before / "summary.json"), "--markdown-output", str(root / "summary.md")]))
            self.assertFalse((before / "summary.json").exists())

    @staticmethod
    def write(path, value):
        path.write_text(json.dumps(value), encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
