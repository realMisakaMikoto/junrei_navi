"""Compare authored Discovery attempt records; this utility does not collect performance evidence.

Each input directory contains one homogeneous group's attempt JSON files only. Outcomes and
failure records are retained. Percentiles use nearest rank, considering only observed stages
with an actual timing (including stages observed before a later failure in that attempt).
Missing/null counters stay unknown. knownSum adds measured values only; completeSum is null
unless every attempt measured that counter. Sums of per-run peaks are arithmetic sums, not
whole-session memory peaks. The original diagnostics files remain the trace evidence.
"""

import argparse
from collections import Counter
import json
import math
from pathlib import Path
import re
import sys


CONDITIONS = {
    "provider": {"google", "amap"},
    "size": {1000, 10000, 100000, 51828},
    "dataKind": {"synthetic"},
    "startState": {"cold_no_cache", "cold_valid_cache", "warm_return", "ready_viewport"},
    "dataProfile": {"normal", "delayed", "offline", "failure"},
    "imageProfile": {"normal", "delayed", "offline", "failure", "disabled"},
}
OUTCOMES = {"observed", "empty", "failed", "cancelled", "not_observed", "unsupported"}
FAILURES = {"timeout", "network", "data", "images", "sdk", "assertion", "storage", "crash", "harness", "cancelled", "unknown"}
ACCEPTANCE_GAPS = {"native_point_pixels", "visible_image_pixels", "geographic_basemap"}
PHASES = set("""
LAUNCH DISCOVERY_ENTER SHELL_DRAWN SDK_VIEW_CREATED SDK_READY BASEMAP_RENDER_OBSERVED
CACHE_READ INDEX_FETCH INDEX_PARSE REQUEST_QUEUE_WAIT PAGE_FETCH SUBJECT_FETCH CLASSIFY
CONVERT INDEX_BUILD FIRST_VIEWPORT_POINTS_DRAWN VIEWPORT_SELECTION_READY FIRST_VISIBLE_IMAGE
VISIBLE_IMAGES_SETTLED DETAILS_SYNC_COMPLETE CACHE_WRITE SNAPSHOT_LOCK_WAIT NEARBY_SORT
IMAGE_DECODE MARKER_UPDATE MAIN_THREAD_WORK SDK_NETWORK JSON_PARSE PAGE_PARSE SUBJECT_PARSE
APP_CONTAINER_INIT REGION_ASSET_READ FRAME_DURATION VIEWPORT_MARKERS_COMMITTED
IMAGE_MARKERS_COMMITTED SEARCH_INDEX_BUILD MAP_POINT_PREPARE IMAGE_LOAD IMAGE_FETCH
READY_VIEWPORT_SELECTION
""".split())
REQUIRED_PHASES = set("""
LAUNCH DISCOVERY_ENTER SHELL_DRAWN SDK_VIEW_CREATED SDK_READY BASEMAP_RENDER_OBSERVED CACHE_READ
INDEX_FETCH INDEX_PARSE CLASSIFY CONVERT INDEX_BUILD FIRST_VIEWPORT_POINTS_DRAWN
VIEWPORT_SELECTION_READY FIRST_VISIBLE_IMAGE VISIBLE_IMAGES_SETTLED DETAILS_SYNC_COMPLETE
READY_VIEWPORT_SELECTION
""".split())
COUNTERS = set("""
INDEX_REBUILD_COUNT DISTANCE_COMPUTATION_COUNT NEARBY_SORT_COUNT CACHE_WRITE_COUNT
CACHE_WRITE_BYTES MARKER_UPDATE_COUNT IMAGE_DECODE_COUNT IMAGE_REUSE_COUNT CANCELLED_WORK_COUNT
RESTARTED_WORK_COUNT SDK_VIEW_CREATE_COUNT SDK_VIEW_RELEASE_COUNT FRAME_COUNT
FRAME_TOTAL_DURATION_NANOS FIRST_DRAW_FRAME_COUNT FRAME_REPORT_DROPPED_COUNT
JAVA_HEAP_SAMPLE_COUNT JAVA_HEAP_PEAK_BYTES NATIVE_HEAP_SAMPLE_COUNT NATIVE_HEAP_PEAK_BYTES
PSS_SAMPLE_COUNT PSS_PEAK_BYTES CACHE_READ_BYTES CACHE_READ_HIT_COUNT FRAME_UNKNOWN_DELAY_NANOS
FRAME_LAYOUT_MEASURE_NANOS FRAME_DRAW_NANOS IMAGE_REQUEST_COUNT IMAGE_MEMORY_HIT_COUNT
IMAGE_DISK_HIT_COUNT CLASSIFIED_POINT_COUNT IMAGE_SUCCESS_COUNT IMAGE_ERROR_COUNT
IMAGE_CANCEL_COUNT MAIN_THREAD_SEGMENT_COUNT MAIN_THREAD_WORK_NANOS
""".split())
IDENTITY_FIELDS = ("condition", "environmentHash", "measurementHarnessHash", "apkSha256", "sourceSha")
REQUIRED_FIELDS = set(IDENTITY_FIELDS) | {"schemaVersion", "attemptedIndex", "completed", "failureCategory", "stageMillis"}
MINIMUM_SAMPLES = 20


class RecordError(ValueError):
    """A record cannot support a valid comparison."""


def require(condition, message):
    if not condition:
        raise RecordError(message)


def object_value(value, label):
    require(isinstance(value, dict), label + " must be an object")
    return value


def is_nonnegative_number(value):
    return type(value) in (int, float) and math.isfinite(value) and value >= 0


def validate_record(raw):
    record = object_value(raw, "record")
    require(REQUIRED_FIELDS <= record.keys(), "record is missing required fields")
    require(not (record.keys() - REQUIRED_FIELDS - {"diagnostics", "acceptanceGaps"}), "record contains unsupported fields")
    require(type(record["schemaVersion"]) is int and record["schemaVersion"] == 1, "unsupported schemaVersion")
    condition = object_value(record["condition"], "condition")
    require(condition.keys() == CONDITIONS.keys(), "condition keys do not match schema")
    for key, values in CONDITIONS.items():
        require(type(condition[key]) is (int if key == "size" else str) and condition[key] in values,
                "unsupported condition value for " + key)
    result = {"schemaVersion": 1, "condition": condition.copy()}
    for key, length in (("environmentHash", 64), ("measurementHarnessHash", 64), ("apkSha256", 64), ("sourceSha", 40)):
        value = record[key]
        require(isinstance(value, str) and re.fullmatch(r"[0-9a-fA-F]{" + str(length) + "}", value), "invalid " + key)
        result[key] = value.lower()
    index = record["attemptedIndex"]
    require(type(index) is int and index >= 0, "attemptedIndex must be a nonnegative integer")
    require(type(record["completed"]) is bool, "completed must be boolean")
    failure = record["failureCategory"]
    require(failure is None if record["completed"] else isinstance(failure, str) and failure in FAILURES,
            "completed and failureCategory disagree")
    result.update(attemptedIndex=index, completed=record["completed"], failureCategory=failure)
    gaps = record.get("acceptanceGaps", [])
    require(isinstance(gaps, list) and all(isinstance(gap, str) and gap in ACCEPTANCE_GAPS for gap in gaps),
            "acceptanceGaps contains an unsupported value")
    require(len(set(gaps)) == len(gaps), "acceptanceGaps contains duplicates")
    result["acceptanceGaps"] = gaps.copy()
    stages = object_value(record["stageMillis"], "stageMillis")
    require(not (stages.keys() - PHASES), "stageMillis contains an unsupported phase")
    result["stageMillis"] = {}
    for phase, stage in stages.items():
        stage = object_value(stage, "stage")
        require(stage.keys() == {"outcome", "millis"}, "stage fields do not match schema")
        require(isinstance(stage["outcome"], str) and stage["outcome"] in OUTCOMES, "unsupported stage outcome")
        millis = stage["millis"]
        require(millis is None or is_nonnegative_number(millis), "stage millis must be finite and nonnegative or null")
        require(stage["outcome"] not in {"not_observed", "unsupported"} or millis is None,
                "unobserved or unsupported stage cannot have a timing")
        result["stageMillis"][phase] = stage.copy()
    diagnostics = record.get("diagnostics")
    counters = {}
    if diagnostics is not None:
        diagnostics = object_value(diagnostics, "diagnostics")
        trace = object_value(diagnostics.get("trace", diagnostics), "diagnostic trace")
        counters = object_value(trace.get("counters", {}), "diagnostic counters")
        require(not (counters.keys() - COUNTERS), "diagnostics contains an unsupported counter")
        for value in counters.values():
            require(value is None or type(value) is int and value >= 0, "counter must be a nonnegative integer or null")
        if trace.get("enabled") is False:
            require(not any(value is not None for value in counters.values()), "disabled trace cannot supply measured counters")
    result["counters"] = counters.copy()
    return result


def load_group(directory):
    directory = Path(directory)
    require(directory.is_dir(), "input directory does not exist")
    files = sorted(directory.glob("*.json"))
    require(bool(files), "input directory has no attempt JSON files")
    records = []
    for ordinal, file in enumerate(files, 1):
        try:
            records.append(validate_record(json.loads(file.read_text(encoding="utf-8"))))
        except (UnicodeError, OSError, json.JSONDecodeError, RecordError) as error:
            detail = str(error) if isinstance(error, RecordError) else "unreadable or malformed JSON"
            raise RecordError("input record " + str(ordinal) + ": " + detail) from None
    reference = records[0]
    require(all(all(record[key] == reference[key] for key in IDENTITY_FIELDS) for record in records),
            "input directory mixes conditions, environments, harnesses or source/APK identities")
    require(len({record["attemptedIndex"] for record in records}) == len(records), "duplicate attemptedIndex")
    return sorted(records, key=lambda record: record["attemptedIndex"])


def statistics(values):
    ordered = sorted(values)
    return {
        "count": len(ordered),
        "p50": ordered[math.ceil(len(ordered) * 0.50) - 1] if ordered else None,
        "p95": ordered[math.ceil(len(ordered) * 0.95) - 1] if ordered else None,
        "max": ordered[-1] if ordered else None,
    }


def summarize_group(records, phases, counter_names):
    attempts = len(records)
    indices = [record["attemptedIndex"] for record in records]
    gaps = [[left + 1, right - 1] for left, right in zip(indices, indices[1:]) if right > left + 1]
    completed = sum(record["completed"] for record in records)
    result = {key: records[0][key] for key in IDENTITY_FIELDS}
    result.update(
        attemptedCount=attempts, completedCount=completed, failedCount=attempts - completed,
        completionRate=completed / attempts,
        failureCounts=dict(sorted(Counter(record["failureCategory"] for record in records if not record["completed"]).items())),
        acceptanceGapCounts=dict(sorted(Counter(gap for record in records for gap in record["acceptanceGaps"]).items())),
        indexGaps=gaps,
        atLeast20Attempts=attempts >= MINIMUM_SAMPLES,
        atLeast20CompletedAttempts=completed >= MINIMUM_SAMPLES,
        allRecordedAttemptsCompleted=completed == attempts,
    )
    result["stages"] = {}
    for phase in sorted(phases):
        entries = [record["stageMillis"][phase] for record in records if phase in record["stageMillis"]]
        values = [entry["millis"] for entry in entries if entry["outcome"] == "observed" and entry["millis"] is not None]
        outcomes = Counter(entry["outcome"] for entry in entries)
        result["stages"][phase] = {
            "millis": statistics(values),
            "outcomes": {outcome: outcomes[outcome] for outcome in sorted(OUTCOMES)},
            "missingEntryCount": attempts - len(entries),
            "observedWithoutTimingCount": outcomes["observed"] - len(values),
            "atLeast20ObservedTimings": len(values) >= MINIMUM_SAMPLES,
        }
    result["counters"] = {}
    for name in sorted(counter_names):
        values = [record["counters"][name] for record in records if record["counters"].get(name) is not None]
        result["counters"][name] = {
            "perRun": [{"attemptedIndex": record["attemptedIndex"], "value": record["counters"].get(name)} for record in records],
            "distribution": statistics(values), "missingCount": attempts - len(values),
            "knownSum": sum(values) if values else None,
            "completeSum": sum(values) if len(values) == attempts else None,
        }
    result["attempts"] = [{key: record[key] for key in ("attemptedIndex", "completed", "failureCategory", "acceptanceGaps", "stageMillis")} for record in records]
    return result


def compare_groups(baseline, candidate):
    require(bool(baseline) and bool(candidate), "both groups need attempt records")
    for key in ("condition", "environmentHash", "measurementHarnessHash"):
        require(baseline[0][key] == candidate[0][key], "B1/B2 mismatch: " + key)
    phases = REQUIRED_PHASES.union(*(record["stageMillis"] for record in baseline + candidate))
    counter_names = set().union(*(record["counters"] for record in baseline + candidate))
    b1 = summarize_group(baseline, phases, counter_names)
    b2 = summarize_group(candidate, phases, counter_names)
    changes = {}
    for phase in sorted(phases):
        before, after = b1["stages"][phase]["millis"], b2["stages"][phase]["millis"]
        changes[phase] = {
            "p95ImprovementPercent": (before["p95"] - after["p95"]) / before["p95"] * 100
                if before["p95"] is not None and before["p95"] > 0 and after["p95"] is not None else None,
            "bothHave20ObservedTimings": before["count"] >= 20 and after["count"] >= 20,
            "bothHave20CompletedAttemptsWithoutFailuresOrIndexGaps": all(
                group["atLeast20CompletedAttempts"] and group["failedCount"] == 0 and not group["indexGaps"] for group in (b1, b2)
            ),
        }
    return {"schemaVersion": 1, "percentileMethod": "nearest_rank", "minimumSamples": MINIMUM_SAMPLES,
            "baseline": b1, "candidate": b2, "stageChanges": changes}


def display(value):
    return "unknown" if value is None else format(value, ".3f").rstrip("0").rstrip(".") if isinstance(value, float) else str(value)


def markdown(report):
    b1, b2 = report["baseline"], report["candidate"]
    condition = b1["condition"]
    lines = ["# Discovery performance comparison", "",
        "**Completed attempts only mean that the controlled workload reached its terminal state. They are not F6 acceptance passes. This report declares no overall pass; missing required stages and unobserved native pixels/basemap proof remain open.**", "",
        "Condition: " + ", ".join(key + "=" + str(condition[key]) for key in CONDITIONS) + ".", "",
        "Environment hash: `" + b1["environmentHash"] + "`. Harness hash: `" + b1["measurementHarnessHash"] + "`.", "",
        "| Group | Attempts | Completed | Failed | Completion rate | Source SHA | APK SHA-256 |",
        "| --- | ---: | ---: | ---: | ---: | --- | --- |"]
    for label, group in (("B1", b1), ("B2", b2)):
        lines.append("| " + " | ".join((label, str(group["attemptedCount"]), str(group["completedCount"]),
            str(group["failedCount"]), display(group["completionRate"] * 100) + "%", group["sourceSha"], group["apkSha256"])) + " |")
    for label, group in (("B1", b1), ("B2", b2)):
        if group["failureCounts"]:
            lines.extend(["", label + " failure categories: " + ", ".join(key + "=" + str(value) for key, value in group["failureCounts"].items()) + "."])
        if group["indexGaps"]:
            lines.extend(["", label + " has missing index ranges: " + json.dumps(group["indexGaps"]) + "."])
        if group["acceptanceGapCounts"]:
            lines.extend(["", label + " declared acceptance gaps (attempt counts): " +
                ", ".join(key + "=" + str(value) for key, value in group["acceptanceGapCounts"].items()) + "."])
    lines.extend(["", "## Observed stage timings", "",
        "Nearest-rank p50/p95/max use only observed, non-null timings. A later failed attempt can still contribute an earlier observed stage. Failures remain in the attempt counts above. No missing timing is replaced with zero.", "",
        "| Stage | Group | Timed / attempts | p50 ms | p95 ms | Max ms | Missing entries | Observed without timing | Other outcomes |",
        "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |"])
    for phase in report["stageChanges"]:
        for label, group in (("B1", b1), ("B2", b2)):
            stage = group["stages"][phase]
            stats = stage["millis"]
            other = ", ".join(key + "=" + str(value) for key, value in stage["outcomes"].items() if key != "observed" and value) or "none"
            lines.append("| " + " | ".join((phase, label, str(stats["count"]) + " / " + str(group["attemptedCount"]),
                display(stats["p50"]), display(stats["p95"]), display(stats["max"]), str(stage["missingEntryCount"]),
                str(stage["observedWithoutTimingCount"]), other)) + " |")
    lines.extend(["", "## P95 changes", "",
        "Positive percentages indicate lower candidate p95. These are descriptive comparisons; limited coverage or failed attempts cannot establish acceptance.", "",
        "| Stage | P95 improvement | Both groups have 20 timed observations | Both groups have 20 completed attempts, no failures or index gaps |",
        "| --- | ---: | --- | --- |"])
    for phase, change in report["stageChanges"].items():
        value = change["p95ImprovementPercent"]
        lines.append("| " + " | ".join((phase, "unknown" if value is None else display(value) + "%",
            "yes" if change["bothHave20ObservedTimings"] else "no",
            "yes" if change["bothHave20CompletedAttemptsWithoutFailuresOrIndexGaps"] else "no")) + " |")
    lines.extend(["", "## Counter coverage", "",
        "Missing and null values are unknown; explicit zero is measured zero. Known sums cover measured runs only. Complete sums require every attempt. Per-run values are retained in JSON. Summing per-run memory peaks does not measure a session peak.", "",
        "| Counter | Group | Measured / attempts | Known sum | Complete sum | Per-run max |",
        "| --- | --- | ---: | ---: | ---: | ---: |"])
    for name in b1["counters"]:
        for label, group in (("B1", b1), ("B2", b2)):
            counter = group["counters"][name]
            lines.append("| " + " | ".join((name, label, str(counter["distribution"]["count"]) + " / " + str(group["attemptedCount"]),
                display(counter["knownSum"]), display(counter["completeSum"]), display(counter["distribution"]["max"]))) + " |")
    lines.extend(["", "This summary does not establish UI pixels, SDK rendering, physical-device behavior or acceptance completion. Read the condition, failures, timing coverage and original trace evidence together.", ""])
    return "\n".join(lines)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--b1", required=True, type=Path)
    parser.add_argument("--b2", required=True, type=Path)
    parser.add_argument("--json-output", required=True, type=Path)
    parser.add_argument("--markdown-output", required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        for output in (args.json_output, args.markdown_output):
            require(output.resolve().parent not in {args.b1.resolve(), args.b2.resolve()}, "outputs must stay outside input attempt directories")
        require(args.json_output.resolve() != args.markdown_output.resolve(), "output paths must differ")
        report = compare_groups(load_group(args.b1), load_group(args.b2))
        encoded = json.dumps(report, indent=2, sort_keys=True, allow_nan=False) + "\n"
        rendered = markdown(report)
        for path, contents in ((args.json_output, encoded), (args.markdown_output, rendered)):
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(contents, encoding="utf-8")
        print("Wrote comparison; B1 attempts=" + str(report["baseline"]["attemptedCount"]) +
              ", B2 attempts=" + str(report["candidate"]["attemptedCount"]))
        return 0
    except (RecordError, OSError) as error:
        print("Comparison refused: " + (str(error) if isinstance(error, RecordError) else "file access failed"), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
