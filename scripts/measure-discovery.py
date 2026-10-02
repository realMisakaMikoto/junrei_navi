"""Collect one homogeneous controlled Discovery workload group (20 attempts, one precondition).

Root must build/sign/install the measurement APK and set the declared ART compiler filter first.
This driver does not install, compile, grant permissions, change connectivity or clear user data.
Completion means the expected app workload became terminal; it is NOT native-pixel/F6 acceptance.
Native-copy timings are provisional until the matcher is separately qualified. Geographic
basemap proof remains unobserved; SDK callbacks and optional screenshots cannot establish it.
"""

import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import platform
import re
import shlex
import struct
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET

PACKAGE = "cn.anitabi.navigator"
CONTROL = "content://cn.anitabi.navigator.discovery-measurement"
DIAGNOSTICS = "content://cn.anitabi.navigator.discovery-diagnostics"
EXTERNAL = "/sdcard/Android/data/" + PACKAGE + "/files/discovery-measurement"
BOOTSTRAP = EXTERNAL + "/bootstrap"
CERTIFICATE = EXTERNAL + "/fixture-cert.pem"
TARGETS = {
    "emulator-5554": ("anitabi-redesign-api37", 37),
    "emulator-5580": ("anitabi-redesign-api26", 26),
    "emulator-5584": ("anitabi-pr37-images-api26", 26),
}
SHOW_LIST = "\u663e\u793a\u5730\u70b9\u5217\u8868"
SHOW_MAP = "\u663e\u793a\u5730\u56fe"
GAPS = ["native_point_pixels", "visible_image_pixels", "geographic_basemap"]
PIXEL_PHASES = {"FIRST_VIEWPORT_POINTS_DRAWN", "FIRST_VISIBLE_IMAGE", "BASEMAP_RENDER_OBSERVED"}
DURATION_ONLY_PHASES = {"READY_VIEWPORT_SELECTION"}
READY_DATA_PHASES = {"CACHE_READ", "CACHE_WRITE", "INDEX_FETCH", "INDEX_PARSE", "PAGE_FETCH", "PAGE_PARSE",
                     "SUBJECT_FETCH", "SUBJECT_PARSE", "JSON_PARSE", "CLASSIFY", "MAP_POINT_PREPARE"}
ATTEMPTS = 20
WATCHDOG_SECONDS = 120
POLL_SECONDS = 0.5
HARNESS_FILES = [
    "scripts/measure-discovery.py",
    "scripts/summarize-discovery-performance.py",
    "app/build.gradle.kts", "build.gradle.kts", "gradle/wrapper/gradle-wrapper.properties",
    "scripts/discovery-measurement-server.init.gradle",
    "app/src/test/java/cn/anitabi/navigator/data/discovery/ControlledDiscoveryMeasurementServer.kt",
    "app/src/discoveryMeasurement/java/cn/anitabi/navigator/measurement/DiscoveryMeasurementApplication.kt",
    "app/src/discoveryMeasurement/java/cn/anitabi/navigator/measurement/DiscoveryMeasurementProvider.kt",
    "app/src/discoveryMeasurement/java/cn/anitabi/navigator/measurement/MeasurementVisualProbe.kt",
    "app/src/discoveryMeasurement/AndroidManifest.xml",
    "app/src/main/java/cn/anitabi/navigator/diagnostics/DiscoveryDiagnostics.kt",
    "app/src/main/java/cn/anitabi/navigator/diagnostics/DiscoveryDiagnosticsProvider.kt",
    "app/src/main/java/cn/anitabi/navigator/diagnostics/DiscoveryVisualFrame.kt",
    "app/src/main/java/cn/anitabi/navigator/data/discovery/DiscoveryLoadTrace.kt",
    "app/src/main/java/cn/anitabi/navigator/data/discovery/DiscoveryTraceSections.kt",
]


class Failure(RuntimeError):
    def __init__(self, category, code, fatal=False):
        super().__init__(code)
        self.category, self.code, self.fatal = category, code, fatal


def require(value, code, category="harness", fatal=False):
    if not value:
        raise Failure(category, code, fatal)


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("ascii")).hexdigest()


def file_hash(path):
    result = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def write_json(path, value):
    with Path(path).open("x", encoding="ascii") as stream:
        json.dump(value, stream, ensure_ascii=True, indent=2)


def parse_report(output):
    require("report=" in output, "control_report_missing")
    result, _ = json.JSONDecoder().raw_decode(output.split("report=", 1)[1])
    require(result.get("schema") == 1, "control_schema_invalid")
    return result


def parse_manifest_tree(output):
    root = {"name": "root", "attrs": {}, "children": []}
    stack = [(-1, root)]
    for line in output.splitlines():
        match = re.match(r"^(\s*)([EA]):\s+([^\s(=]+)(.*)$", line)
        if not match:
            continue
        indent, kind, name, tail = match.groups()
        level = len(indent)
        if kind == "E":
            while stack[-1][0] >= level:
                stack.pop()
            node = {"name": name, "attrs": {}, "children": []}
            stack[-1][1]["children"].append(node)
            stack.append((level, node))
        else:
            value = tail.split("=", 1)[-1].strip()
            if value.startswith('"'):
                value = value.split('"', 2)[1]
            elif "0xffffffff" in value or value == "true":
                value = True
            elif "0x0" in value or value == "false":
                value = False
            stack[-1][1]["attrs"][name] = value
    return root


def inspect_manifest(output):
    tree = parse_manifest_tree(output)
    manifest = next((n for n in tree["children"] if n["name"] == "manifest"), None)
    require(manifest and manifest["attrs"].get("package") == PACKAGE, "apk_package_invalid", fatal=True)
    app = next((n for n in manifest["children"] if n["name"] == "application"), None)
    require(app is not None, "apk_application_missing", fatal=True)
    require(app["attrs"].get("android:debuggable", False) is False, "debuggable_apk_rejected", fatal=True)
    require(app["attrs"].get("android:name") in (".measurement.DiscoveryMeasurementApplication", PACKAGE + ".measurement.DiscoveryMeasurementApplication"), "measurement_application_missing", fatal=True)
    profile = next((n for n in app["children"] if n["name"] == "profileable"), None)
    require(profile and profile["attrs"].get("android:shell") is True and profile["attrs"].get("android:enabled", True) is True, "profileable_manifest_required", fatal=True)
    for suffix in ("discovery-measurement", "discovery-diagnostics"):
        provider = next((n for n in app["children"] if n["name"] == "provider" and n["attrs"].get("android:authorities") == PACKAGE + "." + suffix), None)
        require(provider and provider["attrs"].get("android:enabled", True) is True and
                provider["attrs"].get("android:permission") == "android.permission.DUMP", "guarded_provider_required", fatal=True)
    return {"debuggable": False, "profileableManifest": True, "measurementApplication": True, "guardedProviders": True}


def art_filters(output, apk_path):
    # Read only the installed base APK's block; do not export device paths or other packages.
    lines = output.splitlines()
    filters = set()
    selected = False
    next_line_is_path = False
    for line in lines:
        if "path:" in line:
            selected = apk_path in line
            next_line_is_path = not line.split("path:", 1)[1].strip()
        elif next_line_is_path:
            selected = line.strip() == apk_path
            next_line_is_path = False
        if selected:
            filters.update(re.findall(r"\bstatus=([a-z-]+)\b", line))
    return sorted(filters)


def permission_denied(package_dump, permission):
    values = re.findall(re.escape(permission) + r":\s*granted=(true|false)\b", package_dump)
    require(bool(values), "location_permission_unobserved", fatal=True)
    return all(value == "false" for value in values)


def stage_values(diagnostics, allowed_phases):
    trace = diagnostics.get("trace", {})
    result = {phase: {"outcome": "not_observed", "millis": None} for phase in allowed_phases}
    # Ring loss means the first remaining event cannot safely be relabeled as the first stage.
    if "stageEvents" in trace:
        events_by_phase = trace["stageEvents"]
    elif trace.get("droppedEvents", 0) or trace.get("droppedSpans", 0):
        trace = diagnostics.get("harness", {}).get("earlyTrace", {})
        if not trace or trace.get("droppedEvents", 0) or trace.get("droppedSpans", 0):
            events_by_phase = []
        else:
            events_by_phase = trace.get("events", [])
    else:
        events_by_phase = trace.get("events", [])
    for phase in allowed_phases - PIXEL_PHASES:
        events = [e for e in events_by_phase if e.get("phase") == phase]
        chosen = next((e for e in events if e.get("outcome") == "OBSERVED"), events[0] if events else None)
        if chosen is None:
            continue
        outcome = chosen["outcome"].lower()
        nanos = chosen.get("durationNanos")
        if nanos is None and outcome == "observed" and phase not in DURATION_ONLY_PHASES:
            nanos = chosen.get("offsetNanos")
        result[phase] = {"outcome": outcome, "millis": None if nanos is None or outcome in ("not_observed", "unsupported") else nanos / 1_000_000}
    report = diagnostics.get("harness", {}).get("providerStatus", {})
    visual = report.get("nativeVisual", {})
    if points_ready(report) and visual.get("captureMethod") in {"SURFACE_VIEW_PIXEL_COPY", "WINDOW_TEXTURE_PIXEL_COPY"} and \
            visual.get("timingDefinition") == "first_verified_copy_upper_bound_including_sampling_cost":
        for phase, field in (("FIRST_VIEWPORT_POINTS_DRAWN", "firstViewportPointsDrawnOffsetNanos"),
                             ("FIRST_VISIBLE_IMAGE", "firstVisibleImageOffsetNanos")):
            nanos = visual.get(field)
            eligible = points_ready(report) if phase == "FIRST_VIEWPORT_POINTS_DRAWN" else photos_ready(report)
            if phase in allowed_phases and eligible and type(nanos) is int and nanos >= 0:
                result[phase] = {"outcome": "observed", "millis": nanos / 1_000_000}
    return result


def image_terminal(diagnostics, offline, expected_committed=None):
    trace = diagnostics.get("trace", {})
    counters = trace.get("counters", {})
    requested = counters.get("IMAGE_REQUEST_COUNT", 0)
    succeeded = counters.get("IMAGE_SUCCESS_COUNT", 0)
    errors = counters.get("IMAGE_ERROR_COUNT", 0)
    cancelled = counters.get("IMAGE_CANCEL_COUNT", 0)
    decoded_or_cached = counters.get("IMAGE_DECODE_COUNT", 0) > 0 or (
        counters.get("IMAGE_MEMORY_HIT_COUNT", 0) + counters.get("IMAGE_DISK_HIT_COUNT", 0) > 0)
    pending = any(s.get("phase") in ("IMAGE_LOAD", "IMAGE_FETCH", "IMAGE_DECODE") for s in trace.get("pendingSpans", []))
    committed = any(e.get("phase") == "IMAGE_MARKERS_COMMITTED" and e.get("outcome") == "OBSERVED" and
                    (e.get("itemCount") == expected_committed if expected_committed is not None else e.get("itemCount", 0) > 0)
                    for e in trace.get("events", []))
    terminal = requested > 0 and requested == succeeded + errors + cancelled and not pending
    # Offline may legitimately display cached images, or settle as a preserved-point fallback.
    if offline:
        return terminal and (errors > 0 or succeeded > 0 and decoded_or_cached and committed)
    return terminal and succeeded > 0 and decoded_or_cached and errors == 0 and committed


def points_ready(report):
    visual = report.get("nativeVisual", {})
    return report.get("viewportSnapshotValid") is True and report.get("viewportEligible") is True and \
        visual.get("outcome") == "OBSERVED" and visual.get("allCurrentMarkerPixelsMatch") is True and \
        visual.get("currentMarkerCount", 0) > 0 and visual.get("eligibleMarkerCount") == visual.get("currentMarkerCount") and \
        visual.get("matchedMarkerCount") == visual.get("currentMarkerCount") and \
        visual.get("currentMemberCount", 0) > 0 and \
        visual.get("currentMemberCount") == visual.get("matchedMemberCount") == report.get("viewportMemberCount")


def photos_ready(report):
    visual = report.get("nativeVisual", {})
    return points_ready(report) and report.get("expectedPhotoCount") == 6 and report.get("visiblePhotoCount") == 6 and \
        all(visual.get(field) == 6 for field in ("committedPhotoCount", "eligiblePhotoCount", "matchedPhotoCount"))


def warm_quiescent(report, diagnostics):
    trace = diagnostics.get("trace", {})
    return report.get("listMode") is True and all(report.get(field) is True for field in (
        "repositoryStateQuiescent", "repositoryQuiescent", "traceQuiescent")) and \
        trace.get("enabled") is True and trace.get("droppedSpans") == 0 and trace.get("pendingSpans") == [] and \
        report.get("nativeVisual", {}).get("sampleInFlight") is False


def ready_interval_clean(diagnostics):
    trace = diagnostics.get("trace", {})
    if trace.get("enabled") is not True or trace.get("droppedSpans", 0) != 0 or trace.get("listenerFailureCount", 0) != 0:
        return False
    events = trace.get("stageEvents", []) + trace.get("events", []) + trace.get("pendingSpans", [])
    if any(event.get("phase") in READY_DATA_PHASES for event in events):
        return False
    for request in trace.get("requests", []):
        count = request.get("completedCount")
        if request.get("endpoint") in {"STATIC_INDEX", "STATIC_PAGE", "SUBJECT_DETAILS"} and count not in (None, 0):
            return False
        if request.get("endpoint") == "IMAGE" and any(request.get("caches", {}).get(cache, 0) for cache in ("NETWORK", "UNKNOWN")):
            return False
    return not any(trace.get("counters", {}).get(counter, 0) for counter in (
        "CACHE_READ_BYTES", "CACHE_READ_HIT_COUNT", "CACHE_WRITE_COUNT", "CACHE_WRITE_BYTES", "CLASSIFIED_POINT_COUNT"))


def ready_selection_observed(report, diagnostics):
    trace = diagnostics.get("trace", {})
    entries = trace.get("stageEvents", trace.get("events", []))
    return any(event.get("phase") == "READY_VIEWPORT_SELECTION" and event.get("outcome") == "OBSERVED" and
               type(event.get("durationNanos")) is int and event["durationNanos"] >= 0 and
               event.get("itemCount") == report.get("viewportMemberCount") and report.get("viewportMemberCount", 0) > 0
               for event in entries)


def validate_ready_condition(args):
    if args.start_state == "ready_viewport":
        require(args.size == 10000 and args.data_profile == "normal" and args.image_profile == "normal",
                "ready_viewport_requires_10k_normal_data_and_images", fatal=True)


def hierarchy_click(output, label):
    begin, end = output.find("<hierarchy"), output.rfind("</hierarchy>")
    require(begin >= 0 and end >= begin, "hierarchy_unavailable")
    root = ET.fromstring(output[begin:end + len("</hierarchy>")])
    parents = {child: parent for parent in root.iter() for child in parent}
    for node in root.iter("node"):
        if label not in (node.get("text"), node.get("content-desc")):
            continue
        current = node
        while current is not None:
            if current.get("enabled", "true") == "false":
                break
            if current.get("clickable") == "true":
                match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", current.get("bounds", ""))
                require(match is not None, "click_bounds_invalid")
                x1, y1, x2, y2 = map(int, match.groups())
                require(x2 > x1 and y2 > y1, "click_bounds_empty")
                return (x1 + x2) // 2, (y1 + y2) // 2
            current = parents.get(current)
    return None


class Driver:
    def __init__(self, args, analyzer):
        self.args, self.analyzer = args, analyzer
        self.workspace = Path(__file__).resolve().parents[1]
        self.apk_hash = file_hash(args.apk)
        self.condition = {"provider": args.provider, "size": args.size, "dataKind": "synthetic",
                          "startState": args.start_state, "dataProfile": args.data_profile, "imageProfile": args.image_profile}
        self.harness_files = {path: file_hash(self.workspace / path) for path in HARNESS_FILES}
        self.harness_hash = digest(self.harness_files)
        self.environment = None
        self.environment_hash = None
        self.api = TARGETS[args.serial][1]
        self.ui_path = "/data/local/tmp/anitabi-measurement-" + uuid.uuid4().hex + ".xml"
        self.own_reverse = self.own_certificate = self.own_bootstrap = self.prepared = False
        self.cleanup_evidence = {"settingsRestored": False}
        self.last_diagnostics, self.last_status = {}, {}
        self.early_trace = None
        self.attempt_deadline = None
        self.warm_pid = None
        self.control_metadata = json.loads(args.server_ready.read_text(encoding="utf-8"))
        ready = self.control_metadata
        require(ready.get("schema") == 1 and ready.get("port") == 18443 and ready.get("controlledHttps") is True and ready.get("privateKeyPersisted") is False and ready.get("upstreamRequestsSupported") is False, "fixture_server_identity_invalid", fatal=True)
        require(ready.get("certificateFile") == "certificate.pem", "fixture_certificate_name_invalid", fatal=True)
        self.certificate = args.server_ready.parent / "certificate.pem"
        require(self.certificate.is_file() and 0 < self.certificate.stat().st_size <= 16_384, "fixture_certificate_missing", fatal=True)
        self.cert_hash = file_hash(self.certificate)
        self.dataset = next((d for d in ready["datasets"] if d["provider"].lower() == args.provider and d["size"] == args.size), None)
        require(self.dataset is not None, "fixture_dataset_missing", fatal=True)

    def adb_run(self, arguments, timeout=30, data=None):
        deadline = getattr(self, "attempt_deadline", None)
        if deadline is not None:
            remaining = deadline - time.monotonic()
            require(remaining > 0, "attempt_watchdog_expired", "timeout")
            timeout = min(timeout, remaining)
        try:
            return subprocess.run([self.args.adb, "-s", self.args.serial, *arguments], input=data, capture_output=True, timeout=timeout)
        except subprocess.TimeoutExpired:
            raise Failure("timeout", "adb_operation_timeout") from None

    def adb(self, *arguments, allowed=(0,), binary=False, timeout=30, data=None):
        result = self.adb_run(arguments, timeout, data)
        require(result.returncode in allowed, "adb_operation_failed")
        return result.stdout if binary else result.stdout.decode("utf-8", errors="replace")

    def pid(self):
        text = self.adb("shell", "pidof", PACKAGE, allowed=(0, 1)).strip()
        require(not text or re.fullmatch(r"\d+", text), "ambiguous_process_identity", fatal=True)
        return int(text) if text else None

    def identity(self, require_conditions=True):
        avd, api = TARGETS[self.args.serial]
        require(self.adb("get-state").strip() == "device", "device_unavailable", fatal=True)
        require(self.adb("shell", "getprop", "ro.kernel.qemu").strip() == "1", "physical_device_rejected", fatal=True)
        require(self.adb("emu", "avd", "name").splitlines()[0].strip() == avd, "avd_identity_mismatch", fatal=True)
        require(self.adb("shell", "getprop", "ro.build.version.sdk").strip() == str(api), "api_identity_mismatch", fatal=True)
        require(self.adb("shell", "am", "get-current-user").strip() == "0", "android_user_mismatch", fatal=True)
        paths = self.adb("shell", "pm", "path", PACKAGE).strip().splitlines()
        require(len(paths) == 1 and paths[0].startswith("package:/data/app/"), "installed_apk_path_invalid", fatal=True)
        path = paths[0].removeprefix("package:")
        require(re.fullmatch(r"/data/app/[A-Za-z0-9_./=+~-]+/base\.apk", path), "installed_apk_path_invalid", fatal=True)
        require(self.adb("shell", "sha256sum", path).split()[0] == self.apk_hash, "installed_apk_changed", fatal=True)
        package = self.adb("shell", "dumpsys", "package", PACKAGE)
        require("DEBUGGABLE" not in package, "debuggable_target_rejected", fatal=True)
        if require_conditions:
            fine = permission_denied(package, "android.permission.ACCESS_FINE_LOCATION")
            coarse = permission_denied(package, "android.permission.ACCESS_COARSE_LOCATION")
            require(fine and coarse, "location_permissions_must_already_be_denied", fatal=True)
        abi = re.search(r"primaryCpuAbi=([a-zA-Z0-9_-]+)", package)
        require(abi is not None, "installed_abi_unobserved", fatal=True)
        if require_conditions and self.args.provider == "amap":
            require(api == 37 and abi.group(1) == "arm64-v8a", "amap_native_environment_unavailable", fatal=True)
        return path, abi.group(1)

    def environment_now(self):
        apk_path, abi = self.identity()
        dexopt = self.adb("shell", "pm", "art", "dump", PACKAGE) if self.api >= 34 else self.adb("shell", "dumpsys", "package", "dexopt")
        filters = art_filters(dexopt, apk_path)
        require(filters and filters == [self.args.compilation_mode], "art_compilation_mode_unverified", fatal=True)
        props = {key: self.adb("shell", "getprop", key).strip() for key in (
            "ro.build.fingerprint", "ro.product.cpu.abi", "ro.hardware", "ro.hardware.egl", "ro.opengles.version")}
        dimensions = self.adb("shell", "wm", "size").strip()
        density = self.adb("shell", "wm", "density").strip()
        font = self.adb("shell", "settings", "get", "system", "font_scale").strip()
        rotation = self.adb("shell", "settings", "get", "system", "user_rotation").strip()
        auto_rotate = self.adb("shell", "settings", "get", "system", "accelerometer_rotation").strip()
        gms = self.adb("shell", "dumpsys", "package", "com.google.android.gms")
        gms_version = re.search(r"versionCode=(\d+)", gms)
        display = self.adb("shell", "dumpsys", "display")
        refresh = re.search(r"mBaseDisplayInfo=.*?([0-9]+(?:\.[0-9]+)?) fps", display)
        surface = self.adb("shell", "dumpsys", "SurfaceFlinger")
        renderer = next((line.strip() for line in surface.splitlines() if line.strip().startswith("GLES:")), None)
        tls = {key: self.control_metadata.get(key) for key in ("fixtureVersion", "certificatePublicKeyAlgorithm", "certificateSignatureAlgorithm", "dataDelayMillis", "imageThrottleBytes", "imageThrottlePeriodMillis")}
        require(all(value is not None for value in tls.values()), "fixture_tls_policy_unrecorded", fatal=True)
        return {
            "api": self.api, "avd": TARGETS[self.args.serial][0], "installedAbi": abi, "properties": props,
            "displaySize": dimensions, "displayDensity": density, "fontScale": font,
            "rotation": rotation, "autoRotate": auto_rotate, "appAppearance": "LIGHT", "imagesEnabled": True,
            "locationMode": "denied", "fineLocationDenied": True, "coarseLocationDenied": True,
            "compilationMode": self.args.compilation_mode, "observedCompilerFilters": filters,
            "gmsVersionCode": gms_version.group(1) if gms_version else None,
            "displayRefreshRateHz": float(refresh.group(1)) if refresh else None, "glesRenderer": renderer,
            "sdkTileCache": "unknown_preserved", "filesystemPageCache": "not_reset",
            "datasetSha256": self.dataset["datasetSha256"], "tlsPolicy": tls,
            "windowPolicy": "production_portrait_layout_zoom15", "buildMode": "optimized_non_debuggable_release",
            "hostSystem": platform.system(), "hostMachine": platform.machine(),
            "pythonVersion": list(sys.version_info[:3]),
            "pollIntervalMillis": int(POLL_SECONDS * 1000), "watchdogMillis": WATCHDOG_SECONDS * 1000,
        }

    def check_environment(self):
        current = self.environment_now()
        require(current == self.environment, "measurement_environment_changed", fatal=True)
        require({p: file_hash(self.workspace / p) for p in HARNESS_FILES} == self.harness_files, "measurement_harness_changed", fatal=True)

    def exists(self, path):
        result = self.adb_run(["shell", "sh", "-c", shlex.quote("test -f " + shlex.quote(path))], timeout=15)
        require(result.returncode in (0, 1) and not result.stderr.strip(), "fixture_file_stat_failed")
        return result.returncode == 0

    def bootstrap(self, require_conditions=True):
        self.identity(require_conditions)
        require(not self.exists(BOOTSTRAP), "unowned_bootstrap_sentinel_exists", fatal=True)
        self.adb("shell", "mkdir", "-p", EXTERNAL)
        self.adb("shell", "touch", BOOTSTRAP)
        self.own_bootstrap = True

    def unbootstrap(self, require_conditions=True):
        require(self.own_bootstrap, "bootstrap_not_owned", fatal=True)
        self.identity(require_conditions)
        self.adb("shell", "rm", "-f", BOOTSTRAP)
        self.own_bootstrap = False

    def transport(self):
        self.identity()
        existing = self.adb("reverse", "--list").splitlines()
        occupied = [line.split() for line in existing if "tcp:18443" in line.split()[1:2]]
        require(not occupied or all(parts[-2:] == ["tcp:18443", "tcp:18443"] for parts in occupied), "fixture_reverse_conflict", fatal=True)
        if not occupied:
            self.adb("reverse", "--no-rebind", "tcp:18443", "tcp:18443")
            self.own_reverse = True
        self.adb("shell", "mkdir", "-p", EXTERNAL)
        if self.exists(CERTIFICATE):
            require(self.adb("shell", "sha256sum", CERTIFICATE).split()[0] == self.cert_hash, "unowned_fixture_certificate_exists", fatal=True)
        else:
            self.adb("push", str(self.certificate), CERTIFICATE)
            self.own_certificate = True
            require(self.adb("shell", "sha256sum", CERTIFICATE).split()[0] == self.cert_hash, "fixture_certificate_copy_failed")

    def wait(self, predicate, code, seconds=30):
        end = time.monotonic() + seconds
        if getattr(self, "attempt_deadline", None) is not None:
            end = min(end, self.attempt_deadline)
        while time.monotonic() < end:
            value = predicate()
            if value:
                return value
            time.sleep(min(POLL_SECONDS, max(0, end - time.monotonic())))
        raise Failure("timeout", code)

    def kill_background(self, require_conditions=True):
        self.identity(require_conditions)
        old = self.pid()
        if old is not None:
            self.adb("shell", "input", "keyevent", "KEYCODE_HOME")
            remaining = self.attempt_deadline - time.monotonic() if getattr(self, "attempt_deadline", None) is not None else 1
            require(remaining > 0, "attempt_watchdog_expired", "timeout")
            time.sleep(min(1, remaining))
            self.adb("shell", "am", "kill", "--user", "0", PACKAGE)
            self.wait(lambda: self.pid() is None, "background_process_still_alive", 20)
        return old

    def control(self, method, extras=None, allow_bootstrap_start=False):
        require(self.pid() is not None or allow_bootstrap_start and self.own_bootstrap, "provider_would_start_unmeasured_process", fatal=True)
        arguments = ["shell", "content", "call", "--uri", CONTROL, "--method", method]
        for key, value in (extras or {}).items():
            arguments += ["--extra", key + (":i:" if type(value) is int else ":s:") + str(value)]
        report = parse_report(self.adb(*arguments, timeout=40))
        self.last_status = report
        return report

    def diagnostics(self):
        require(self.pid() is not None, "diagnostics_would_start_process", fatal=True)
        result = json.loads(self.adb("exec-out", "content", "read", "--uri", DIAGNOSTICS + "/snapshot", timeout=40))
        require(result.get("trace", {}).get("enabled") is True, "profiling_trace_disabled")
        self.last_diagnostics = result
        return result

    def configure(self, data, images, clear):
        self.kill_background()
        self.bootstrap()
        values = {"provider": self.args.provider.upper(), "size": self.args.size,
                  "data": data.upper(), "images": "THROTTLED" if images == "delayed" else images.upper(),
                  "discoveryCache": "EMPTY" if clear else "KEEP", "imageCache": "EMPTY" if clear else "KEEP"}
        self.prepared = True  # A failed partial prepare may still have created its exact-settings backup.
        report = self.control("prepare", values, allow_bootstrap_start=True)
        require(report["configured"] and report["datasetSha256"] == self.dataset["datasetSha256"], "prepared_dataset_mismatch")
        self.unbootstrap()
        old = self.kill_background()
        require(self.pid() is None, "cold_process_absence_unproven", fatal=True)
        return old

    def launch(self):
        require(self.pid() is None and not self.exists(BOOTSTRAP), "cold_launch_precondition_failed", fatal=True)
        start = time.monotonic_ns()
        self.adb("shell", "am", "start", "-W", "-f", "0x10008000", "-n", PACKAGE + "/.MainActivity", timeout=40)
        new_pid = self.wait(self.pid, "launch_process_missing")
        return start, new_pid

    def click(self, label):
        def point():
            try:
                self.adb("shell", "uiautomator", "dump", self.ui_path)
                return hierarchy_click(self.adb("shell", "cat", self.ui_path), label)
            finally:
                self.adb("shell", "rm", "-f", self.ui_path)
        center = self.wait(point, "ui_control_unavailable")
        self.adb("shell", "input", "tap", str(center[0]), str(center[1]))

    def wait_workload(self, data, images, expected_pid, ready_interval=False):
        deadline = time.monotonic() + WATCHDOG_SECONDS
        if getattr(self, "attempt_deadline", None) is not None:
            deadline = min(deadline, self.attempt_deadline)
        captured_index = False
        while time.monotonic() < deadline:
            current_pid = self.pid()
            require(current_pid == expected_pid, "measured_process_exited_or_changed", "crash")
            report = self.control("status")
            require(report["passiveRouteAttempts"] == 0, "unexpected_routing_attempt", fatal=True)
            require(report.get("datasetSha256") == self.dataset["datasetSha256"], "runtime_dataset_mismatch", fatal=True)
            if report["loadedPointCount"] == self.args.size and not captured_index:
                self.early_trace = self.diagnostics().get("trace")
                captured_index = True
            if data == "offline" and report["dataError"] == "NETWORK" and report["loadedPointCount"] == 0:
                return report, self.diagnostics(), "offline_empty_fallback"
            data_ready = report["loadedPointCount"] == self.args.size and report["preparedPointCount"] == self.args.size
            require(report["dataError"] in ("NONE", "NETWORK"), "invalid_fixture_data", "data")
            require(data == "offline" or report["dataError"] != "NETWORK", "unexpected_data_transport_failure", "network")
            if data_ready and report["dataComplete"] and report["viewportEligible"] and report["providerMatches"] and not report["listMode"]:
                diagnostics = self.diagnostics()
                if ready_interval:
                    require(ready_interval_clean(diagnostics), "ready_viewport_app_data_or_network_changed", "assertion")
                require(images == "offline" or diagnostics.get("trace", {}).get("counters", {}).get("IMAGE_ERROR_COUNT", 0) == 0,
                        "unexpected_image_failure", "images")
                points_committed = any(e.get("phase") == "VIEWPORT_MARKERS_COMMITTED" and e.get("outcome") == "OBSERVED" and
                    e.get("itemCount", 0) > 0 for e in diagnostics.get("trace", {}).get("events", []))
                strict_photos = images != "offline"
                if strict_photos:
                    require(report["expectedPhotoCount"] == 6, "photo_fixture_invalid", "assertion")
                if points_committed and (not strict_photos or photos_ready(report)) and \
                        image_terminal(diagnostics, images == "offline", expected_committed=6 if strict_photos else None) and \
                        (not ready_interval or ready_selection_observed(report, diagnostics)):
                    return report, diagnostics, "controlled_workload_terminal"
            time.sleep(min(POLL_SECONDS, max(0, deadline - time.monotonic())))
        raise Failure("timeout", "controlled_workload_timeout")

    def visual_capture(self):
        # A PNG capture alone is not an image/marker/geographic matcher. Never persist its pixels.
        result = {"outcome": "not_observed", "millis": None, "reason": "capture_metadata_only"}
        if not self.args.capture_after:
            return result
        started = time.monotonic_ns()
        png = self.adb("exec-out", "screencap", "-p", binary=True)
        require(png.startswith(b"\x89PNG\r\n\x1a\n") and len(png) >= 24, "screenshot_capture_failed")
        width, height = struct.unpack(">II", png[16:24])
        result.update(captureMillis=(time.monotonic_ns() - started) / 1_000_000, width=width, height=height,
                      captureOnly=True, screenshotPersisted=False)
        return result

    def run_attempt(self, index, precondition=False):
        self.last_status, self.last_diagnostics = {}, {}
        self.early_trace = None
        evidence = {"completionDefinition": "expected_controlled_workload_terminal_not_F6_acceptance",
                    "nativePixelMatcherQualification": "not_performed",
                    "nativePixelTimingDefinition": "provisional_first_verified_copy_upper_bound_including_sampling_cost"}
        failure = None
        completed = False
        data = "normal" if precondition else self.args.data_profile
        images = "normal" if precondition else self.args.image_profile
        self.attempt_deadline = time.monotonic() + WATCHDOG_SECONDS
        try:
            if self.environment is not None:
                self.check_environment()
            ready_interval = self.args.start_state == "ready_viewport" and not precondition
            if self.args.start_state in {"warm_return", "ready_viewport"} and not precondition:
                require(self.warm_pid is not None and self.pid() == self.warm_pid, "warm_process_changed", "crash")
                self.click(SHOW_LIST)
                def quiescent_list():
                    status = self.control("status")
                    return status if warm_quiescent(status, self.diagnostics()) else None
                evidence["warmResetBarrier"] = self.wait(quiescent_list, "list_repository_or_trace_not_quiescent")
                if ready_interval:
                    barrier = evidence["warmResetBarrier"]
                    require(barrier.get("loadedPointCount") == barrier.get("preparedPointCount") == 10000 and
                            barrier.get("dataComplete") is True and barrier.get("providerMatches") is True,
                            "ready_viewport_prepared_data_changed", "assertion")
                self.adb("shell", "content", "call", "--uri", DIAGNOSTICS, "--method", "reset")
                started, new_pid = time.monotonic_ns(), self.warm_pid
                self.click(SHOW_MAP)
                evidence.update(warmSamePid=True, pid=new_pid, actualListToMap=True)
                if ready_interval:
                    evidence["readyViewportInterval"] = "paired_accepted_selection_span_after_frozen_10k_six_photo_setup"
            else:
                old = self.configure(data, images, clear=precondition or self.args.start_state == "cold_no_cache")
                started, new_pid = self.launch()
                require(old is None or old != new_pid, "cold_pid_not_changed", "crash")
                evidence.update(coldProcessAbsentBeforeLaunch=True, previousPid=old, pid=new_pid)
            report, diagnostics, terminal = self.wait_workload(data, images, new_pid, ready_interval=ready_interval)
            if not precondition and self.args.start_state.startswith("cold_"):
                hits = diagnostics.get("trace", {}).get("counters", {}).get("CACHE_READ_HIT_COUNT", 0)
                require((hits == 0) == (self.args.start_state == "cold_no_cache"), "discovery_cache_precondition_mismatch", "storage")
            evidence.update(terminalReason=terminal, hostToTerminalUpperBoundMillis=(time.monotonic_ns() - started) / 1_000_000,
                            providerStatus=report)
            completed = True
            try:
                evidence["visualObservation"] = self.visual_capture()
            except Exception:
                evidence["visualObservation"] = {"outcome": "not_observed", "millis": None, "reason": "optional_capture_unavailable"}
        except KeyboardInterrupt:
            failure = Failure("cancelled", "operator_cancelled", True)
        except Exception as error:
            failure = error if isinstance(error, Failure) else Failure("harness", type(error).__name__)
            evidence["failureCode"] = failure.code
            # Never ask a provider to restart an absent/crashed process just to obtain a report.
            try:
                if self.pid() is not None:
                    self.diagnostics()
            except Exception:
                pass
        finally:
            self.attempt_deadline = None
        diagnostics = dict(self.last_diagnostics)
        evidence.setdefault("providerStatus", self.last_status)
        evidence.setdefault("visualObservation", {"outcome": "not_observed", "millis": None})
        if self.early_trace is not None:
            evidence["earlyTrace"] = self.early_trace
        callbacks = [e for e in diagnostics.get("trace", {}).get("events", []) if e.get("phase") == "BASEMAP_RENDER_OBSERVED"]
        evidence["sdkBasemapCallback"] = next((e for e in callbacks if e.get("outcome") == "OBSERVED"), {"outcome": "NOT_OBSERVED", "durationNanos": None})
        diagnostics["harness"] = evidence
        record = {
            "schemaVersion": 1, "condition": self.condition, "environmentHash": self.environment_hash or "0" * 64,
            "measurementHarnessHash": self.harness_hash, "apkSha256": self.apk_hash, "sourceSha": self.args.source_sha,
            "attemptedIndex": index, "completed": completed, "failureCategory": None if completed else failure.category,
            "stageMillis": stage_values(diagnostics, self.analyzer.PHASES), "diagnostics": diagnostics, "acceptanceGaps": GAPS,
        }
        try:
            self.analyzer.validate_record(record)
        except self.analyzer.RecordError:
            # Preserve the attempted run and its raw scalar evidence even when the schema drifts.
            failure = Failure("harness", "diagnostic_schema_mismatch", True)
            record.update(completed=False, failureCategory="harness", stageMillis={})
            record["diagnostics"] = {"harness": {"failureCode": failure.code, "unvalidatedDiagnostics": diagnostics}}
            self.analyzer.validate_record(record)
        return record, failure

    def warm_setup(self):
        self.configure(self.args.data_profile, self.args.image_profile, clear=False)
        _, self.warm_pid = self.launch()
        status, diagnostics, reason = self.wait_workload(self.args.data_profile, self.args.image_profile, self.warm_pid)
        if self.args.start_state == "ready_viewport":
            def ready_map():
                report = self.control("status")
                observed = self.diagnostics()
                require(self.pid() == self.warm_pid, "warm_setup_process_changed", "crash")
                settled = all(report.get(field) is True for field in (
                    "activityResumed", "windowFocused", "repositoryStateQuiescent", "repositoryQuiescent", "traceQuiescent"))
                return (report, observed) if settled and report.get("nativeVisual", {}).get("sampleInFlight") is False and \
                    report.get("loadedPointCount") == report.get("preparedPointCount") == 10000 and report.get("dataComplete") is True and \
                    photos_ready(report) and ready_selection_observed(report, observed) else None
            status, diagnostics = self.wait(ready_map, "ready_viewport_setup_not_frozen")
        write_json(self.args.output / "warm-setup.json", {"excludedFromSamples": True, "purpose": "documented_initial_map_open_before_warm_returns",
                    "terminalReason": reason, "status": status, "diagnostics": diagnostics})

    def cleanup(self):
        # Scenario drift must stop measurement, but must not prevent safe restoration on the same APK/device.
        result = {"settingsRestored": not self.prepared, "reverseRemoved": False, "certificateRemoved": False, "bootstrapRemoved": False}
        self.cleanup_evidence = result
        self.identity(require_conditions=False)
        failures = []
        def attempt(step, action):
            try:
                action()
            except Exception as error:
                failures.append({"step": step, "failureCode": error.code if isinstance(error, Failure) else type(error).__name__})
        def restore_settings():
            self.kill_background(require_conditions=False)
            if not self.own_bootstrap:
                self.bootstrap(require_conditions=False)
            report = self.control("teardown", allow_bootstrap_start=True)
            require(report["settingsRestored"] and not report["configured"], "settings_restoration_unverified", "storage")
            result["settingsRestored"] = True
            result["passiveRouteAttempts"] = report["passiveRouteAttempts"]
            self.prepared = False
        def remove_bootstrap():
            self.unbootstrap(require_conditions=False)
            result["bootstrapRemoved"] = True
        def remove_reverse():
            self.identity(require_conditions=False)
            maps = [line.split()[-2:] for line in self.adb("reverse", "--list").splitlines()]
            require(["tcp:18443", "tcp:18443"] in maps, "owned_reverse_changed", fatal=True)
            self.adb("reverse", "--remove", "tcp:18443")
            self.own_reverse = False
            result["reverseRemoved"] = True
        def remove_certificate():
            self.identity(require_conditions=False)
            require(self.adb("shell", "sha256sum", CERTIFICATE).split()[0] == self.cert_hash, "owned_certificate_changed", fatal=True)
            self.adb("shell", "rm", "-f", CERTIFICATE)
            self.own_certificate = False
            result["certificateRemoved"] = True
        if self.prepared:
            attempt("settingsRestore", restore_settings)
            attempt("processStop", lambda: self.kill_background(require_conditions=False))
        # A failed restore keeps the owned bootstrap sentinel and the provider's backup for retry.
        result["settingsRestoreRetryRequired"] = not result["settingsRestored"]
        result["bootstrapRetainedForRestoreRetry"] = self.own_bootstrap and not result["settingsRestored"]
        if self.own_bootstrap and result["settingsRestored"]:
            attempt("bootstrapRemove", remove_bootstrap)
        if self.own_reverse:
            attempt("reverseRemove", remove_reverse)
        if self.own_certificate:
            attempt("certificateRemove", remove_certificate)
        if failures:
            result["failures"] = failures
            result["failureCode"] = failures[0]["failureCode"]
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--aapt2", required=True, type=Path)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--server-ready", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--serial", required=True, choices=TARGETS)
    parser.add_argument("--provider", required=True, choices=("google", "amap"))
    parser.add_argument("--size", required=True, type=int, choices=(1000, 10000, 100000, 51828))
    parser.add_argument("--start-state", required=True, choices=("cold_no_cache", "cold_valid_cache", "warm_return", "ready_viewport"))
    parser.add_argument("--data-profile", choices=("normal", "delayed", "offline"), default="normal")
    parser.add_argument("--image-profile", choices=("normal", "delayed", "offline"), default="normal")
    parser.add_argument("--compilation-mode", required=True, choices=("speed", "speed-profile", "verify", "quicken", "space", "space-profile", "interpret-only"))
    parser.add_argument("--checkpoint", required=True, choices=("B1", "B2"))
    parser.add_argument("--precondition-only", action="store_true")
    parser.add_argument("--capture-after", action="store_true")
    args = parser.parse_args()
    validate_ready_condition(args)
    workspace = Path(__file__).resolve().parents[1]
    args.output = args.output.resolve()
    require(args.output.is_relative_to(workspace / "build") and not args.output.exists(), "output_must_be_new_build_directory")
    require(re.fullmatch(r"[0-9a-f]{40}", args.source_sha), "source_sha_invalid")
    require(args.apk.is_file() and args.aapt2.is_file(), "apk_or_manifest_tool_missing")
    args.output.mkdir(parents=True)
    (args.output / "attempts").mkdir()
    module = importlib.util.spec_from_file_location("discovery_summary", workspace / "scripts/summarize-discovery-performance.py")
    analyzer = importlib.util.module_from_spec(module)
    module.loader.exec_module(analyzer)
    driver = Driver(args, analyzer)
    summary = {"checkpoint": args.checkpoint, "completionDefinition": "expected_controlled_workload_terminal_not_F6_acceptance", "acceptanceGaps": GAPS,
               "plannedAttempts": ATTEMPTS, "attempted": 0, "completed": 0, "preconditionExcluded": True}
    try:
        manifest_output = subprocess.run([str(args.aapt2), "dump", "xmltree", "--file", "AndroidManifest.xml", str(args.apk)], capture_output=True, timeout=30)
        require(manifest_output.returncode == 0, "manifest_inspection_failed", fatal=True)
        apk_manifest = inspect_manifest(manifest_output.stdout.decode("utf-8", errors="replace"))
        driver.identity()
        driver.environment = driver.environment_now()
        driver.environment_hash = digest(driver.environment)
        driver.transport()
        precondition, failure = driver.run_attempt(0, precondition=True)
        write_json(args.output / "precondition.json", {"excludedFromSamples": True, "record": precondition})
        require(precondition["completed"], "precondition_failed", fatal=True)
        driver.check_environment()
        manifest = {"schemaVersion": 1, "createdUtc": datetime.now(timezone.utc).isoformat(), "checkpoint": args.checkpoint,
                    "condition": driver.condition, "environment": driver.environment, "environmentHash": driver.environment_hash,
                    "measurementHarnessHash": driver.harness_hash, "harnessFiles": driver.harness_files,
                    "apkSha256": driver.apk_hash, "sourceSha": args.source_sha, "sourceBinding": "caller_verified_protected_build",
                    "apkManifest": apk_manifest, "certificateSha256": driver.cert_hash, "serverReadySha256": file_hash(args.server_ready),
                    "plannedAttempts": ATTEMPTS, "stageTiming": "first observed span duration; READY_VIEWPORT_SELECTION is duration-only; milestones use trace-origin offsets; native-copy timings are provisional until matcher qualification",
                    "pixelCapture": "after_trace_snapshot_metadata_only" if args.capture_after else "not_requested"}
        write_json(args.output / "manifest.json", manifest)
        if not args.precondition_only:
            if args.start_state in {"warm_return", "ready_viewport"}:
                driver.warm_setup()
            for index in range(1, ATTEMPTS + 1):
                record, failure = driver.run_attempt(index)
                write_json(args.output / "attempts" / (f"{index:03d}.json"), record)
                summary["attempted"] += 1
                summary["completed"] += int(record["completed"])
                print(json.dumps({"attempt": index, "completed": record["completed"], "failureCategory": record["failureCategory"]}), flush=True)
                if failure and failure.fatal:
                    summary["failureCategory"] = failure.category
                    summary["failureCode"] = failure.code
                    summary["fatalFailure"] = True
                    break
    except BaseException as error:
        summary["failureCategory"] = error.category if isinstance(error, Failure) else "cancelled" if isinstance(error, KeyboardInterrupt) else "harness"
        summary["failureCode"] = error.code if isinstance(error, Failure) else type(error).__name__
    finally:
        try:
            summary["cleanup"] = driver.cleanup()
        except Exception as error:
            summary["cleanup"] = dict(driver.cleanup_evidence, failureCode=error.code if isinstance(error, Failure) else type(error).__name__)
        summary["groupComplete"] = summary["attempted"] == ATTEMPTS and "failureCategory" not in summary
        summary["allWorkloadsCompleted"] = summary["completed"] == ATTEMPTS
        summary["F6Accepted"] = False
        write_json(args.output / "summary.json", summary)
    return 0 if summary["cleanup"].get("settingsRestored") and "failureCode" not in summary["cleanup"] and \
        (args.precondition_only and "failureCategory" not in summary or summary["groupComplete"] and summary["allWorkloadsCompleted"]) else 1


if __name__ == "__main__":
    sys.exit(main())
