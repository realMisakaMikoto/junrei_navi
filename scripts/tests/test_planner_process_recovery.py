"""Pure parser/safety checks only; these are not process-death acceptance."""

import importlib.util
from pathlib import Path
import unittest

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


if __name__ == "__main__":
    unittest.main()
