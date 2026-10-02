"""Pure parser/safety checks only; these are not process-death acceptance."""

import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "verify-planner-process-recovery.py"
SPEC = importlib.util.spec_from_file_location("planner_process_recovery", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class PlannerRecoveryDriverParsingTest(unittest.TestCase):
    def test_provider_report_requires_its_scalar_schema(self):
        result = MODULE.parse_report('Result: Bundle[{report={"schema":1,"pid":123,"plannerReady":true}}]')
        self.assertTrue(result["plannerReady"])
        with self.assertRaises(MODULE.HarnessFailure):
            MODULE.parse_report('Result: Bundle[{report={"schema":2}}]')
        with self.assertRaises(MODULE.HarnessFailure):
            MODULE.parse_report("Provider unavailable")

    def test_ui_selection_uses_clickable_parent_not_nonclickable_label(self):
        root = MODULE.parse_hierarchy('''Noise<hierarchy><node clickable="true" enabled="true" bounds="[10,20][110,120]">
            <node text="TEST_ONLY" clickable="false" bounds="[20,30][40,50]"/>
            </node></hierarchy>UI hierarchy dumped''')
        self.assertEqual((60, 70), MODULE.find_click(root, "TEST_ONLY"))
        self.assertIsNone(MODULE.find_click(root, "ABSENT"))

    def test_recents_membership_requires_same_task_and_package(self):
        text = "Recent #0: TaskRecord{abc #18 A=cn.anitabi.navigator U=0 StackId=1 sz=1}"
        self.assertTrue(MODULE.task_present(text, 18))
        self.assertFalse(MODULE.task_present(text, 1))
        self.assertFalse(MODULE.task_present(text.replace(MODULE.PACKAGE, "test.other"), 18))

    def test_invalid_hierarchy_and_empty_bounds_fail_instead_of_tapping_guess(self):
        with self.assertRaises(MODULE.HarnessFailure):
            MODULE.parse_hierarchy("ERROR: no idle state")
        with self.assertRaises(MODULE.HarnessFailure):
            MODULE.bounds_center("[0,0][0,0]")
        with self.assertRaises(MODULE.HarnessFailure):
            MODULE.bounds_center("unexpected")


class PlannerRecoveryTargetTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="anitabi-recovery-control-")
        self.addCleanup(temporary.cleanup)
        self.output = Path(temporary.name)
        self.apk = self.output / "fixture.apk"
        self.apk.write_bytes(b"TEST_ONLY_APK")

    def driver(self, serial=MODULE.SERIAL):
        return MODULE.Driver("TEST_ONLY_ADB", self.apk, self.output, serial)

    def identity_transport(self, driver, changes=None):
        path = "/data/app/~~TEST_ONLY/TEST_ONLY/base.apk"
        responses = {
            ("get-state",): "device",
            ("shell", "getprop", "ro.kernel.qemu"): "1",
            ("shell", "getprop", "ro.build.version.sdk"): str(driver.api),
            ("emu", "avd", "name"): driver.avd + "\nOK",
            ("shell", "am", "get-current-user"): "0",
            ("shell", "pm", "path", MODULE.PACKAGE): "package:" + path,
            ("shell", "sha256sum", path): driver.apk_hash + "  " + path,
            ("shell", "dumpsys", "package", MODULE.PACKAGE): "DEBUGGABLE PlannerRecoveryProvider",
            ("shell", "run-as", MODULE.PACKAGE, "id"): "TEST_ONLY_UID",
        }
        responses.update(changes or {})
        driver.adb = lambda *args, **kwargs: responses[args]

    def test_only_approved_target_pairs_are_available_and_default_is_unchanged(self):
        self.assertEqual({"emulator-5584": (26, "anitabi-pr37-images-api26"),
            "emulator-5554": (37, "anitabi-redesign-api37")}, MODULE.TARGETS)
        self.assertEqual(("emulator-5584", 26, "anitabi-pr37-images-api26"),
            (self.driver().serial, self.driver().api, self.driver().avd))
        with self.assertRaisesRegex(MODULE.HarnessFailure, "unapproved_target"):
            self.driver("TEST_ONLY_PHYSICAL")

    def test_both_approved_identities_require_matching_installed_fixture_hash(self):
        for serial in MODULE.TARGETS:
            with self.subTest(serial=serial):
                driver = self.driver(serial)
                self.identity_transport(driver)
                driver.identity()

    def test_api37_rejects_wrong_api_avd_user_emulator_hash_or_fixture(self):
        path = "/data/app/~~TEST_ONLY/TEST_ONLY/base.apk"
        controls = [
            (("shell", "getprop", "ro.build.version.sdk"), "26", "wrong_api"),
            (("emu", "avd", "name"), "anitabi-pr37-images-api26", "wrong_avd"),
            (("shell", "am", "get-current-user"), "10", "wrong_android_user"),
            (("shell", "getprop", "ro.kernel.qemu"), "0", "not_emulator"),
            (("shell", "sha256sum", path), "0" * 64 + "  " + path, "installed_apk_hash_mismatch"),
            (("shell", "dumpsys", "package", MODULE.PACKAGE), "PlannerRecoveryProvider", "fixture_debug_apk_required"),
            (("shell", "dumpsys", "package", MODULE.PACKAGE), "DEBUGGABLE", "fixture_debug_apk_required"),
        ]
        for command, response, code in controls:
            with self.subTest(code=code):
                driver = self.driver("emulator-5554")
                self.identity_transport(driver, {command: response})
                with self.assertRaisesRegex(MODULE.HarnessFailure, code):
                    driver.identity()

    def test_every_adb_entry_point_uses_the_selected_target(self):
        driver = self.driver("emulator-5554")
        with patch.object(MODULE.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, b"", b"")) as run:
            driver.adb("get-state")
            driver.exists_private("files/TEST_ONLY")
        self.assertEqual(2, run.call_count)
        for call in run.call_args_list:
            self.assertEqual(["TEST_ONLY_ADB", "-s", "emulator-5554"], call.args[0][:3])

    def test_restore_still_requires_zero_route_attempts_and_a_consumed_saved_bundle(self):
        for changed_key, changed_value, expected_code in (
            (None, None, None),
            ("routeAttemptCount", 1, "passive_restore_requested_route"),
            ("activityRestored", False, "framework_task_restore_not_proven"),
            ("taskId", 19, "framework_task_restore_not_proven"),
        ):
            with self.subTest(changed_key=changed_key):
                driver = self.driver("emulator-5554")
                before = {"activityReady": True, "plannerReady": True, "plannerHasDraft": True,
                    "plannerInputMatches": True, "diskInputMatches": True, "routeAttemptCount": 0,
                    "pid": 100, "taskId": 18}
                after = dict(before, pid=200, activityRestored=True, plannerHasPlan=False,
                    restoring=False, draftIdMatches=True, selectionMatches=True, recoveryError=False)
                if changed_key:
                    after[changed_key] = changed_value
                restored = False

                def adb(*args, **kwargs):
                    nonlocal restored
                    if args == ("shell", "input", "keyevent", "KEYCODE_APP_SWITCH"):
                        restored = True
                    return "Recent #0: TaskRecord{abc #18 A=cn.anitabi.navigator U=0}"

                def wait(predicate, code, **kwargs):
                    value = predicate()
                    MODULE.require(value, code)
                    return value

                driver.adb = adb
                driver.identity = lambda: None
                driver.background_and_kill = lambda **kwargs: 100
                driver.run_as = lambda *args, **kwargs: None
                driver.provider = lambda method: after if restored else before
                driver.wait = wait
                driver.click = lambda label: None
                driver.pid = lambda: 200
                driver.has_text = lambda text: True
                if expected_code:
                    with self.assertRaisesRegex(MODULE.HarnessFailure, expected_code):
                        driver.run_case("restore")
                else:
                    with patch.object(MODULE, "print", create=True):
                        self.assertTrue(driver.run_case("restore")["activitySavedBundleRestored"])


if __name__ == "__main__":
    unittest.main()
