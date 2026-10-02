"""Dedicated-AVD process death test. No instrumentation, reinstall, data clear, or paid calls.

Requires a separately built/installed ANITABI_DRAFT_RECOVERY_FIXTURE=true Debug APK.
The fixture provider never restores a ViewModel on behalf of the Android task.
Only the initial setup launches MainActivity; measurement restores its existing Recents card.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET

PACKAGE = "cn.anitabi.navigator"
SERIAL = "emulator-5584"
TARGETS = {
    "emulator-5584": (26, "anitabi-pr37-images-api26"),
    "emulator-5554": (37, "anitabi-redesign-api37"),
}
AUTHORITY = "content://cn.anitabi.navigator.planner-recovery"
PRIVATE = "files/planner-recovery-harness"
DRAFT = "files/planner-draft/current.json"
LABEL = "\u5de1\u793c\u624b\u5e33"
TRIPS = "\u884c\u7a0b"
PLAN = "\u89c4\u5212\u884c\u7a0b"
PLANNER_TITLE = "\u7f16\u6392\u4e00\u65e5\u8def\u7ebf"
RECOVERY_ACTION = "\u8fd4\u56de\u9009\u62e9\u5730\u70b9"
BACKUP_PATHS = [
    DRAFT, "files/planner-draft/current.tmp",
    "shared_prefs/anitabi_settings_v2.xml", "shared_prefs/anitabi_settings_v2.xml.bak",
    "shared_prefs/discovery_view.xml", "shared_prefs/discovery_view.xml.bak",
    "shared_prefs/active_navigation.xml", "shared_prefs/active_navigation.xml.bak",
]
CASES = ("restore", "missing", "corrupt", "unsupported", "cleared")


class HarnessFailure(RuntimeError):
    pass


def require(condition, code):
    if not condition:
        raise HarnessFailure(code)


def parse_report(output):
    marker = "report="
    require(marker in output, "provider_report_missing")
    value, _ = json.JSONDecoder().raw_decode(output.split(marker, 1)[1])
    require(value.get("schema") == 1, "provider_schema_invalid")
    return value


def parse_hierarchy(output):
    start = output.find("<hierarchy")
    end = output.rfind("</hierarchy>")
    require(start >= 0 and end >= start, "ui_dump_unavailable")
    return ET.fromstring(output[start:end + len("</hierarchy>")])


def bounds_center(bounds):
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
    require(match is not None, "ui_bounds_invalid")
    left, top, right, bottom = map(int, match.groups())
    require(right > left and bottom > top, "ui_bounds_empty")
    return (left + right) // 2, (top + bottom) // 2


def find_click(root, label):
    parents = {child: parent for parent in root.iter() for child in parent}
    for node in root.iter("node"):
        if node.get("text") != label and node.get("content-desc") != label:
            continue
        current = node
        while current is not None:
            if current.get("clickable") == "true" and current.get("enabled", "true") == "true":
                return bounds_center(current.get("bounds", ""))
            current = parents.get(current)
        if node.get("enabled", "true") == "true":
            return bounds_center(node.get("bounds", ""))
    return None


def task_present(output, task_id):
    pattern = re.compile(r"(?:#|taskId=)" + str(task_id) + r"\b")
    return any(PACKAGE in line and pattern.search(line) for line in output.splitlines())


class Driver:
    def __init__(self, adb, apk, output, serial=SERIAL):
        require(serial in TARGETS, "unapproved_target")
        self.adb_path = adb
        self.serial = serial
        self.api, self.avd = TARGETS[serial]
        self.apk_hash = hashlib.sha256(apk.read_bytes()).hexdigest()
        self.output = output
        self.backups = []
        self.backup_ready = False
        self.ui_dump_path = "/data/local/tmp/anitabi-recovery-" + uuid.uuid4().hex + ".xml"

    def adb(self, *args, data=None, allowed=(0,), timeout=25):
        result = subprocess.run([self.adb_path, "-s", self.serial, *args], input=data, capture_output=True, timeout=timeout)
        operation = args[0] if args else "unknown"
        if len(args) > 3 and args[:2] == ("shell", "run-as"):
            operation = "private_" + args[3]
        elif len(args) > 2 and args[0] == "shell":
            operation = args[1] + "_" + args[2]
        require(result.returncode in allowed, "adb_" + re.sub(r"[^a-z_-]", "", operation) + "_failed")
        return result.stdout.decode("utf-8", errors="replace")

    def run_as(self, *args, **kwargs):
        return self.adb("shell", "run-as", PACKAGE, *args, **kwargs)

    def write_private(self, path, value):
        require(path.startswith("files/") and ".." not in path, "private_path_invalid")
        self.run_as("sh", "-c", shlex.quote("cat > " + shlex.quote(path)), data=value.encode("ascii"))

    def exists_private(self, path):
        # API26 supplies test as a shell builtin rather than a standalone executable.
        result = subprocess.run([self.adb_path, "-s", self.serial, "shell", "run-as", PACKAGE,
            "sh", "-c", shlex.quote("test -f " + shlex.quote(path))], capture_output=True, timeout=10)
        require(result.returncode in (0, 1), "private_stat_failed")
        require(not result.stderr.strip(), "private_stat_transport_failed")
        return result.returncode == 0

    def identity(self):
        require(self.adb("get-state").strip() == "device", "device_unavailable")
        require(self.adb("shell", "getprop", "ro.kernel.qemu").strip() == "1", "not_emulator")
        require(self.adb("shell", "getprop", "ro.build.version.sdk").strip() == str(self.api), "wrong_api")
        require(self.adb("emu", "avd", "name").splitlines()[0].strip() == self.avd, "wrong_avd")
        require(self.adb("shell", "am", "get-current-user").strip() == "0", "wrong_android_user")
        paths = self.adb("shell", "pm", "path", PACKAGE).strip().splitlines()
        require(len(paths) == 1 and paths[0].startswith("package:/data/app/"), "unexpected_apk_layout")
        path = paths[0][len("package:"):]
        require(re.fullmatch(r"/data/app/[A-Za-z0-9_./=+~-]+/base\.apk", path) is not None, "apk_path_invalid")
        observed = self.adb("shell", "sha256sum", path).split()[0]
        require(observed == self.apk_hash, "installed_apk_hash_mismatch")
        package = self.adb("shell", "dumpsys", "package", PACKAGE)
        require("DEBUGGABLE" in package and "PlannerRecoveryProvider" in package, "fixture_debug_apk_required")
        self.run_as("id")

    def pid(self):
        value = self.adb("shell", "pidof", PACKAGE, allowed=(0, 1)).strip()
        require(not value or re.fullmatch(r"\d+", value), "unexpected_target_processes")
        return int(value) if value else None

    def wait(self, predicate, failure, seconds=25):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            value = predicate()
            if value:
                return value
            time.sleep(0.4)
        raise HarnessFailure(failure)

    def background_and_kill(self, verify_stopped=False):
        self.identity()
        old_pid = self.pid()
        if old_pid is None:
            return None
        self.adb("shell", "input", "keyevent", "KEYCODE_HOME")
        if verify_stopped:
            self.wait(lambda: self.report_if(lambda r: r["activityStopped"] and r["savedStateCallbacks"] > 0), "activity_not_saved_and_stopped")
        else:
            time.sleep(1)
        self.adb("shell", "am", "kill", "--user", "0", PACKAGE)
        self.wait(lambda: self.pid() is None, "background_process_did_not_exit", seconds=15)
        return old_pid

    def provider(self, method):
        return parse_report(self.adb("shell", "content", "call", "--uri", AUTHORITY, "--method", method))

    def report_if(self, predicate):
        report = self.provider("status")
        return report if predicate(report) else None

    def hierarchy(self):
        # API26 reports the destination without returning XML for /dev/tty or /proc/self/fd/1.
        # This dedicated synthetic AVD uses a unique temporary device file, removed immediately.
        # No hierarchy, titles or UI text are saved to host files or logs.
        try:
            self.adb("shell", "uiautomator", "dump", self.ui_dump_path)
            return parse_hierarchy(self.adb("shell", "cat", self.ui_dump_path))
        finally:
            self.adb("shell", "rm", "-f", self.ui_dump_path)

    def click(self, label):
        point = self.wait(lambda: find_click(self.hierarchy(), label), "ui_control_unavailable")
        self.adb("shell", "input", "tap", str(point[0]), str(point[1]))

    def has_text(self, text):
        return any(node.get("text") == text or node.get("content-desc") == text for node in self.hierarchy().iter("node"))

    def backup(self):
        self.identity()
        self.background_and_kill()
        require(not self.exists_private(PRIVATE + "/enabled") and not self.exists_private(PRIVATE + "/backup-ready"), "previous_fixture_requires_recovery")
        self.run_as("mkdir", "-p", PRIVATE + "/backup")
        for index, path in enumerate(BACKUP_PATHS):
            present = self.exists_private(path)
            self.backups.append((path, present))
            if present:
                self.run_as("cp", path, PRIVATE + "/backup/" + str(index))
        # Host manifest contains only fixed paths and presence, never stored draft/pref values.
        (self.output / "backup-manifest.json").write_text(json.dumps(self.backups, indent=2), encoding="ascii")
        self.write_private(PRIVATE + "/backup-ready", "1")
        self.backup_ready = True
        self.write_private(PRIVATE + "/enabled", "1")

    def restore_backup(self):
        if not self.backup_ready:
            return False
        self.identity()
        self.background_and_kill()
        for index, (path, present) in enumerate(self.backups):
            if present:
                self.run_as("mkdir", "-p", str(Path(path).parent).replace("\\", "/"))
                self.run_as("cp", PRIVATE + "/backup/" + str(index), path)
                original_hash = self.run_as("sha256sum", PRIVATE + "/backup/" + str(index)).split()[0]
                restored_hash = self.run_as("sha256sum", path).split()[0]
                require(original_hash == restored_hash, "restored_file_hash_mismatch")
            else:
                self.run_as("rm", "-f", path)
                require(not self.exists_private(path), "unexpected_restored_file")
        # Only the fixed fixture directory is removed, after all original files are restored.
        self.run_as("rm", "-rf", PRIVATE)
        self.backup_ready = False
        return True

    def run_case(self, case):
        require(case in CASES, "unknown_case")
        self.identity()
        self.background_and_kill()
        self.run_as("rm", "-f", PRIVATE + "/expected.json")
        # New task is permitted for setup only. There is no startActivity after the measured kill.
        self.adb("shell", "am", "start", "-W", "-f", "0x10008000", "-n", PACKAGE + "/.MainActivity")
        self.wait(lambda: self.report_if(lambda r: r["activityReady"]), "initial_activity_unavailable")
        self.provider("prepare")
        self.click(TRIPS)
        self.click(PLAN)
        self.wait(lambda: self.report_if(lambda r: r["plannerReady"] and r["plannerHasDraft"]), "planner_route_not_entered")
        before = self.provider("edit")
        require(before["plannerInputMatches"] and before["diskInputMatches"] and before["routeAttemptCount"] == 0, "initial_inputs_not_durable")
        if case == "cleared":
            cleared = self.provider("clear")
            require(not cleared["draftPresent"] and cleared["selectedCount"] == 0, "clear_not_durable")
        old_pid = self.background_and_kill(verify_stopped=True)
        require(old_pid == before["pid"], "initial_process_changed_early")
        require(task_present(self.adb("shell", "dumpsys", "activity", "recents"), before["taskId"]), "saved_task_missing")
        if case == "missing":
            self.run_as("rm", "-f", DRAFT)
        elif case == "corrupt":
            self.write_private(DRAFT, "{TEST_ONLY_CORRUPT")
        elif case == "unsupported":
            self.write_private(DRAFT, '{"schemaVersion":99,"revision":1,"draft":null}')

        # Only a Recents UI action may restart the process during the measured interval.
        self.adb("shell", "input", "keyevent", "KEYCODE_APP_SWITCH")
        self.click(LABEL)
        new_pid = self.wait(self.pid, "recent_task_did_not_restart_process")
        require(new_pid != old_pid, "pid_did_not_change")
        after = self.wait(lambda: self.report_if(lambda r: r["activityReady"] and not r["restoring"] and (r["plannerReady"] or r["recoveryError"])), "restored_activity_unavailable")
        require(after["pid"] == new_pid and after["taskId"] == before["taskId"] and after["activityRestored"], "framework_task_restore_not_proven")
        require(after["routeAttemptCount"] == 0 and not after["plannerHasPlan"], "passive_restore_requested_route")
        if case == "restore":
            require(all(after[key] for key in ("plannerReady", "draftIdMatches", "diskInputMatches", "plannerInputMatches", "selectionMatches")), "restored_input_mismatch")
            self.wait(lambda: self.has_text(PLANNER_TITLE), "restored_planner_ui_missing")
        else:
            expected_problem = {"missing": "MISSING", "corrupt": "CORRUPT", "unsupported": "UNSUPPORTED", "cleared": "NONE"}[case]
            require(after["recoveryError"] and not after["plannerReady"] and not after["draftPresent"] and after["selectedCount"] == 0, "unsafe_recovery_state")
            require(after["draftProblem"] == expected_problem, "wrong_recovery_reason")
            self.click(RECOVERY_ACTION)
            self.wait(lambda: not self.has_text(RECOVERY_ACTION), "recovery_return_action_failed")
        result = {
            "case": case, "passed": True, "oldPid": old_pid, "newPid": new_pid,
            "oldPidGone": True, "sameTask": True, "activitySavedBundleRestored": True,
            "restoredThroughRecents": True, "passiveRoutingAttempts": after["routeAttemptCount"],
            "inputChecks": {key: after[key] for key in ("draftIdMatches", "diskInputMatches", "plannerInputMatches", "selectionMatches", "recoveryError")},
        }
        (self.output / (case + ".json")).write_text(json.dumps(result, indent=2), encoding="ascii")
        print(json.dumps({"case": case, "passed": True}), flush=True)
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", choices=TARGETS, default=SERIAL)
    parser.add_argument("--app-apk", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--case", choices=CASES, action="append")
    args = parser.parse_args()
    workspace = Path(__file__).resolve().parents[1]
    output = args.output.resolve()
    require(output.is_relative_to(workspace / "build") and not output.exists(), "output_must_be_new_workspace_build_directory")
    require(args.app_apk.is_file(), "expected_apk_missing")
    output.mkdir(parents=True)
    driver = Driver(args.adb, args.app_apk, output, args.serial)
    report = {"schema": 1, "appSha256": driver.apk_hash, "api": driver.api, "target": "dedicated-emulator", "cases": []}
    try:
        driver.backup()
        for case in args.case or CASES:
            report["cases"].append(driver.run_case(case))
        report["passed"] = True
    except Exception as error:
        report["passed"] = False
        report["failureCategory"] = str(error) if isinstance(error, HarnessFailure) else type(error).__name__
    finally:
        try:
            report["originalFilesRestored"] = driver.restore_backup()
        except Exception:
            report["originalFilesRestored"] = False
            report["passed"] = False
            report["cleanupFailure"] = "restore_failed_keep_backup"
        (output / "summary.json").write_text(json.dumps(report, indent=2), encoding="ascii")
    print(json.dumps({key: report.get(key) for key in ("passed", "failureCategory", "originalFilesRestored")}), flush=True)
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
