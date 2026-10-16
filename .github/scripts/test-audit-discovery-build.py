#!/usr/bin/env python3
"""Synthetic manifest/DEX mutation controls, not proof of a built APK's properties."""

import copy
import importlib.util
from pathlib import Path
import struct
import sys
import unittest
import xml.etree.ElementTree as ET


sys.dont_write_bytecode = True


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


audit = load("discovery_build_audit", "audit-discovery-build.py")
dex_reader = load("discovery_build_dex_reader", "audit-amap-r8.py")
A = audit.ANDROID
RECOVERY_APPLICATION = audit.PACKAGE + ".recovery.PlannerRecoveryApplication"
RECOVERY_PROVIDER = audit.PACKAGE + ".recovery.PlannerRecoveryProvider"
RECOVERY_AUTHORITY = audit.PACKAGE + ".planner-recovery"


def descriptor(name):
    return "L" + name.replace(".", "/") + ";"


def manifest(mode="ordinary", explicit_disabled=False):
    root = ET.Element("manifest", {"package": audit.PACKAGE})
    application = {"ordinary": ".AnitabiApplication", "measurement": ".measurement.DiscoveryMeasurementApplication",
        "planner-recovery": ".recovery.PlannerRecoveryApplication"}[mode]
    app = ET.SubElement(root, "application", {A + "name": application})
    if mode == "planner-recovery":
        app.set(A + "debuggable", "true")
        ET.SubElement(app, "provider", {A + "name": RECOVERY_PROVIDER,
            A + "authorities": RECOVERY_AUTHORITY, A + "enabled": "true",
            A + "exported": "true", A + "permission": "android.permission.DUMP"})
    if mode == "measurement" or explicit_disabled:
        enabled = "true" if mode == "measurement" else "false"
        ET.SubElement(app, "profileable", {A + "enabled": enabled, A + "shell": enabled})
        ET.SubElement(app, "provider", {A + "name": ".diagnostics.DiscoveryDiagnosticsProvider",
            A + "authorities": audit.PACKAGE + ".discovery-diagnostics", A + "enabled": enabled,
            A + "exported": "true", A + "permission": "android.permission.DUMP"})
    if mode == "measurement":
        ET.SubElement(app, "provider", {A + "name": ".measurement.DiscoveryMeasurementProvider",
            A + "authorities": audit.PACKAGE + ".discovery-measurement", A + "enabled": "true",
            A + "exported": "true", A + "permission": "android.permission.DUMP"})
    return root


def classes(mode="ordinary"):
    names = [audit.APPLICATION]
    if mode == "measurement":
        names += [audit.MEASUREMENT, audit.MEASUREMENT_PROVIDER, audit.DIAGNOSTICS_PROVIDER]
    elif mode == "planner-recovery":
        names += [RECOVERY_APPLICATION, RECOVERY_PROVIDER]
    return {descriptor(name) for name in names}


def dex_fixture(defined):
    # Names exist in string/type tables for every mutation; only the class definition table differs.
    names = sorted(classes("measurement") | classes("planner-recovery"))
    count = len(names)
    strings_offset, types_offset, definitions_offset = 112, 112 + count * 4, 112 + count * 8
    definitions = [index for index, name in enumerate(names) if name in defined]
    data = bytearray(definitions_offset + len(definitions) * 32)
    data[:8] = b"dex\n035\0"
    struct.pack_into("<IIII", data, 56, count, strings_offset, count, types_offset)
    struct.pack_into("<II", data, 96, len(definitions), definitions_offset)
    for index, name in enumerate(names):
        struct.pack_into("<I", data, strings_offset + index * 4, len(data))
        struct.pack_into("<I", data, types_offset + index * 4, index)
        data.extend(bytes([len(name)]) + name.encode("ascii") + b"\0")
    for index, type_index in enumerate(definitions):
        struct.pack_into("<I", data, definitions_offset + index * 32, type_index)
    return bytes(data)


class DiscoveryBuildAuditTest(unittest.TestCase):
    def check(self, root, mode="ordinary", definitions=None):
        return audit.audit_manifest(ET.tostring(root), classes(mode) if definitions is None else definitions, mode)

    def rejected(self, root, mode="ordinary", definitions=None, code=None):
        with self.assertRaises(audit.AuditError) as failure:
            self.check(root, mode, definitions)
        if code is not None:
            self.assertEqual(code, str(failure.exception))

    def test_ordinary_defaults_and_explicit_disabled_controls_pass(self):
        for explicit in (False, True):
            with self.subTest(explicit=explicit):
                self.assertEqual({"nonDebuggable": True, "profilingEnabled": False,
                    "measurementFixture": False, "manifestAndDexVerified": True}, self.check(manifest(explicit_disabled=explicit)))

    def test_proper_measurement_manifest_and_actual_definitions_pass(self):
        definitions = dex_reader.dex_classes(dex_fixture(classes("measurement")))
        report = self.check(manifest("measurement"), "measurement", definitions)
        self.assertEqual({"nonDebuggable": True, "profilingEnabled": True,
            "measurementFixture": True, "manifestAndDexVerified": True}, report)

    def test_proper_recovery_manifest_and_actual_definitions_pass(self):
        definitions = dex_reader.dex_classes(dex_fixture(classes("planner-recovery")))
        self.assertEqual(classes("planner-recovery"), definitions)
        for explicit in (False, True):
            with self.subTest(explicit=explicit):
                report = self.check(manifest("planner-recovery", explicit), "planner-recovery", definitions)
                self.assertEqual({"nonDebuggable": False, "profilingEnabled": False,
                    "measurementFixture": False, "manifestAndDexVerified": True,
                    "plannerRecoveryFixture": True}, report)

    def test_relative_simple_and_fully_qualified_ordinary_names_resolve(self):
        for name in (".AnitabiApplication", "AnitabiApplication", audit.APPLICATION):
            root = manifest()
            root.find("application").set(A + "name", name)
            self.assertFalse(self.check(root)["measurementFixture"])

    def test_wrong_package_missing_or_duplicate_application_fail(self):
        for mode in ("ordinary", "measurement", "planner-recovery"):
            root = manifest(mode)
            root.set("package", "example.invalid")
            self.rejected(root, mode)
            root = manifest(mode)
            root.remove(root.find("application"))
            self.rejected(root, mode)
            root = manifest(mode)
            root.append(copy.deepcopy(root.find("application")))
            self.rejected(root, mode)

    def test_debuggable_release_fails_in_both_modes(self):
        for mode in ("ordinary", "measurement"):
            root = manifest(mode)
            root.find("application").set(A + "debuggable", "true")
            self.rejected(root, mode)

    def test_wrong_or_missing_application_class_fails(self):
        for mode in ("ordinary", "measurement", "planner-recovery"):
            for name in (".WrongApplication", None):
                root = manifest(mode)
                app = root.find("application")
                if name is None:
                    app.attrib.pop(A + "name")
                else:
                    app.set(A + "name", name)
                self.rejected(root, mode)

    def test_recovery_must_be_explicitly_debuggable_and_use_the_recovery_application(self):
        for value in (None, "false", "@bool/unresolved"):
            root = manifest("planner-recovery")
            app = root.find("application")
            if value is None:
                app.attrib.pop(A + "debuggable")
            else:
                app.set(A + "debuggable", value)
            self.rejected(root, "planner-recovery", code="recovery_must_be_debuggable")
        for name in (audit.APPLICATION, audit.MEASUREMENT):
            root = manifest("planner-recovery")
            root.find("application").set(A + "name", name)
            self.rejected(root, "planner-recovery", code="recovery_application_missing")

    def test_recovery_requires_only_one_approved_recovery_provider(self):
        for mutation in ("absent", "duplicate", "other-recovery", "authority-collision"):
            root = manifest("planner-recovery")
            app = root.find("application")
            provider = app.find("provider")
            if mutation == "absent":
                app.remove(provider)
            else:
                extra = copy.deepcopy(provider)
                if mutation == "duplicate":
                    extra.set(A + "name", ".recovery.PlannerRecoveryProvider")
                elif mutation == "other-recovery":
                    extra.set(A + "name", ".recovery.OtherProvider")
                    extra.set(A + "authorities", audit.PACKAGE + ".other-recovery")
                else:
                    extra.set(A + "name", ".OtherProvider")
                    extra.set(A + "authorities", "example.invalid;" + RECOVERY_AUTHORITY)
                app.append(extra)
            self.rejected(root, "planner-recovery", code="duplicate_provider" if mutation == "duplicate" else "recovery_provider_count")

    def test_recovery_provider_identity_and_access_controls_are_exact(self):
        for key, values in (("authorities", (None, "", "example.invalid", RECOVERY_AUTHORITY + ";example.invalid")),
                ("permission", (None, "", "android.permission.INTERNET")),
                ("enabled", (None, "false", "@bool/unresolved")),
                ("exported", (None, "false", "@bool/unresolved"))):
            for value in values:
                with self.subTest(key=key, value=value):
                    root = manifest("planner-recovery")
                    provider = root.find("application/provider")
                    if value is None:
                        provider.attrib.pop(A + key)
                    else:
                        provider.set(A + key, value)
                    code = {"authorities": "recovery_authority", "permission": "recovery_provider_permission",
                        "enabled": "recovery_provider_disabled", "exported": "recovery_provider_export"}[key]
                    self.rejected(root, "planner-recovery", code=code)

    def test_recovery_requires_actual_base_application_and_fixture_definitions(self):
        for name in (audit.APPLICATION, RECOVERY_APPLICATION, RECOVERY_PROVIDER):
            definitions = dex_reader.dex_classes(dex_fixture(classes("planner-recovery") - {descriptor(name)}))
            self.assertNotIn(descriptor(name), definitions)
            with self.assertRaisesRegex(audit.AuditError, "^recovery_dex_definition_missing$"):
                self.check(manifest("planner-recovery"), "planner-recovery", definitions)
        self.rejected(manifest("planner-recovery"), "planner-recovery", dex_reader.dex_classes(dex_fixture(set())),
            "recovery_dex_definition_missing")

    def test_recovery_rejects_profiling_and_enabled_diagnostics(self):
        for key in ("enabled", "shell"):
            root = manifest("planner-recovery", explicit_disabled=True)
            root.find("application/profileable").set(A + key, "true")
            self.rejected(root, "planner-recovery", code="recovery_profiling_enabled")
        root = manifest("planner-recovery", explicit_disabled=True)
        app = root.find("application")
        app.append(copy.deepcopy(app.find("profileable")))
        self.rejected(root, "planner-recovery", code="profileable_count")
        for enabled in (None, "true", "@bool/unresolved"):
            root = manifest("planner-recovery", explicit_disabled=True)
            provider = root.find("application").findall("provider")[1]
            if enabled is None:
                provider.attrib.pop(A + "enabled")
            else:
                provider.set(A + "enabled", enabled)
            self.rejected(root, "planner-recovery", code="recovery_diagnostics_enabled")
        for name, authority in ((audit.PACKAGE + ".diagnostics.OtherProvider", "example.invalid"),
                (audit.PACKAGE + ".OtherProvider", "example.invalid;" + audit.PACKAGE + ".discovery-diagnostics")):
            root = manifest("planner-recovery")
            ET.SubElement(root.find("application"), "provider", {A + "name": name, A + "authorities": authority})
            self.rejected(root, "planner-recovery", code="recovery_diagnostics_enabled")

    def test_recovery_rejects_measurement_providers_and_classes(self):
        for name, authority in ((audit.MEASUREMENT_PROVIDER, audit.PACKAGE + ".discovery-measurement"),
                (audit.PACKAGE + ".measurement.OtherProvider", "example.invalid"),
                (audit.PACKAGE + ".OtherProvider", "example.invalid;" + audit.PACKAGE + ".discovery-measurement")):
            root = manifest("planner-recovery")
            ET.SubElement(root.find("application"), "provider", {A + "name": name,
                A + "authorities": authority, A + "enabled": "false"})
            self.rejected(root, "planner-recovery", code="measurement_provider_in_recovery_fixture")
        for name in (audit.MEASUREMENT, audit.MEASUREMENT_PROVIDER, audit.PACKAGE + ".measurement.UnreferencedFixture"):
            self.rejected(manifest("planner-recovery"), "planner-recovery",
                classes("planner-recovery") | {descriptor(name)}, "measurement_class_in_recovery_fixture")

    def test_unknown_mode_cannot_fall_through_to_ordinary_checks(self):
        for mode in ("", "planner_recovery", "unknown"):
            with self.assertRaisesRegex(audit.AuditError, "^unexpected_mode$"):
                self.check(manifest(), mode, classes())

    def test_each_measurement_provider_is_required_and_must_have_dump_permission(self):
        for index in (0, 1):
            root = manifest("measurement")
            app = root.find("application")
            app.remove(app.findall("provider")[index])
            self.rejected(root, "measurement")
            for permission in (None, "", "android.permission.INTERNET"):
                root = manifest("measurement")
                provider = root.find("application").findall("provider")[index]
                if permission is None:
                    provider.attrib.pop(A + "permission")
                else:
                    provider.set(A + "permission", permission)
                self.rejected(root, "measurement")

    def test_disabled_private_or_wrong_authority_measurement_provider_fails(self):
        for index in (0, 1):
            for key, value in (("enabled", "false"), ("exported", "false"), ("authorities", "example.invalid")):
                root = manifest("measurement")
                root.find("application").findall("provider")[index].set(A + key, value)
                self.rejected(root, "measurement")

    def test_measurement_requires_enabled_shell_profile_and_single_profile_element(self):
        for remove in (False, True):
            root = manifest("measurement")
            app = root.find("application")
            profile = app.find("profileable")
            if remove:
                app.remove(profile)
            else:
                app.append(copy.deepcopy(profile))
            self.rejected(root, "measurement")
        for key in ("enabled", "shell"):
            root = manifest("measurement")
            root.find("application/profileable").set(A + key, "false")
            self.rejected(root, "measurement")
        root = manifest("measurement")
        root.find("application/profileable").attrib.pop(A + "shell")
        self.rejected(root, "measurement")

    def test_ordinary_rejects_profile_enable_shell_enable_and_enabled_diagnostics(self):
        for key in ("enabled", "shell"):
            root = manifest(explicit_disabled=True)
            root.find("application/profileable").set(A + key, "true")
            self.rejected(root)
        for enabled in (None, "true"):
            root = manifest(explicit_disabled=True)
            provider = root.find("application/provider")
            if enabled is None:
                provider.attrib.pop(A + "enabled")
            else:
                provider.set(A + "enabled", enabled)
            self.rejected(root)

    def test_fixture_provider_or_dex_class_in_ordinary_artifact_fails(self):
        root = manifest()
        measurement_provider = manifest("measurement").find("application").findall("provider")[1]
        root.find("application").append(copy.deepcopy(measurement_provider))
        self.rejected(root)
        for name in (audit.MEASUREMENT, audit.MEASUREMENT_PROVIDER, audit.PACKAGE + ".measurement.UnreferencedFixture"):
            self.rejected(manifest(), definitions=classes() | {descriptor(name)})

    def test_recovery_provider_is_forbidden_in_both_release_modes(self):
        for mode in ("ordinary", "measurement"):
            root = manifest(mode)
            ET.SubElement(root.find("application"), "provider", {A + "name": ".recovery.PlannerRecoveryProvider"})
            self.rejected(root, mode)

    def test_measurement_class_strings_cannot_replace_any_missing_definition(self):
        for name in (audit.MEASUREMENT, audit.MEASUREMENT_PROVIDER, audit.DIAGNOSTICS_PROVIDER):
            definitions = dex_reader.dex_classes(dex_fixture(classes("measurement") - {descriptor(name)}))
            self.assertNotIn(descriptor(name), definitions)
            self.rejected(manifest("measurement"), "measurement", definitions)
        self.rejected(manifest("measurement"), "measurement", dex_reader.dex_classes(dex_fixture(set())))

    def test_ordinary_application_requires_a_dex_definition_not_just_a_manifest_reference(self):
        self.rejected(manifest(), definitions=dex_reader.dex_classes(dex_fixture(set())))

    def test_duplicate_normalized_provider_cannot_hide_an_unprotected_provider(self):
        root = manifest("measurement")
        app = root.find("application")
        protected = app.findall("provider")[0]
        unprotected = copy.deepcopy(protected)
        unprotected.set(A + "name", audit.DIAGNOSTICS_PROVIDER)
        unprotected.set(A + "authorities", audit.PACKAGE + ".unguarded-diagnostics")
        unprotected.attrib.pop(A + "permission")
        app.insert(0, unprotected)
        self.rejected(root, "measurement")

    def test_unresolved_profile_boolean_cannot_be_treated_as_disabled(self):
        for mode in ("ordinary", "planner-recovery"):
            for key in ("enabled", "shell"):
                root = manifest(mode, explicit_disabled=True)
                root.find("application/profileable").set(A + key, "@bool/unresolved")
                self.rejected(root, mode)

    def test_failure_codes_do_not_expose_manifest_metadata(self):
        root = manifest("measurement")
        app = root.find("application")
        ET.SubElement(app, "meta-data", {A + "name": "synthetic.key", A + "value": "TEST_ONLY_PRIVATE"})
        app.set(A + "debuggable", "true")
        with self.assertRaises(audit.AuditError) as failure:
            self.check(root, "measurement")
        self.assertNotIn("TEST_ONLY_PRIVATE", str(failure.exception))
        self.assertEqual("release_must_not_be_debuggable", str(failure.exception))


if __name__ == "__main__":
    unittest.main()
