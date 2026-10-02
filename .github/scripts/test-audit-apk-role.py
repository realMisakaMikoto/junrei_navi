#!/usr/bin/env python3
"""Synthetic APK controls exercise the role CLI and actual manifest/DEX verifier."""

import copy
import hashlib
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile

sys.dont_write_bytecode = True
DIRECTORY = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("discovery_role_fixtures", DIRECTORY / "test-audit-discovery-build.py")
fixtures = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixtures)
SOURCE_SHA = "a" * 40
REGION = b"TEST_ONLY_APPROVED_REGION"
REGION_SHA = hashlib.sha256(REGION).hexdigest()
REGION_PATH = "assets/approved_regions/territory_regions_v1.json"
BACKEND = "https://api.anitabi.afunnypersonlol0.site"
LOCALHOST = "https://localhost:18443/fixture/"
BASH = shutil.which("bash")


@unittest.skipUnless(BASH and os.name != "nt", "Run with Linux Python in CI or WSL for the SDK shell inspector")
class ApkRoleAuditTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="anitabi-apk-role-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        binary = self.root / "bin"
        inspector = self.root / "sdk/cmdline-tools/latest/bin/apkanalyzer"
        binary.mkdir()
        inspector.parent.mkdir(parents=True)
        decoder = self.root / "decode-manifest.py"
        decoder.write_text("import sys, zipfile\nassert sys.argv[1:3] == ['manifest', 'print']\n"
            "with zipfile.ZipFile(sys.argv[3]) as apk:\n    sys.stdout.buffer.write(apk.read('AndroidManifest.xml'))\n", encoding="ascii")
        inspector.write_text('#!/usr/bin/env bash\nexec "$TEST_PYTHON" "$TEST_DECODER" "$@"\n', encoding="ascii")
        python = binary / "python3"
        python.write_text('#!/usr/bin/env bash\nexec "$TEST_PYTHON" "$@"\n', encoding="ascii")
        inspector.chmod(0o755)
        python.chmod(0o755)
        self.environment = os.environ.copy()
        self.environment.update(ANDROID_HOME=(self.root / "sdk").as_posix(),
            TEST_PYTHON=Path(sys.executable).as_posix(), TEST_DECODER=decoder.as_posix(),
            PYTHONDONTWRITEBYTECODE="1",
            PATH=binary.as_posix() + os.pathsep + self.environment["PATH"])

    def apk(self, mode="measurement", manifest=None, classes=None, marker=LOCALHOST,
            amap=True, region=True, extra=None):
        path = self.root / "synthetic.apk"
        document = fixtures.manifest(mode) if manifest is None else manifest
        if amap:
            ET.SubElement(document.find("application"), "meta-data",
                {fixtures.A + "name": "com.amap.api.v2.apikey", fixtures.A + "value": "TEST_ONLY"})
        definitions = fixtures.classes(mode) if classes is None else classes
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("AndroidManifest.xml", ET.tostring(document))
            archive.writestr("classes.dex", fixtures.dex_fixture(definitions))
            archive.writestr("assets/endpoint.txt", marker)
            if region:
                archive.writestr(REGION_PATH, REGION)
            for name, value in (extra or {}).items():
                archive.writestr(name, value)
        return path

    def audit(self, path, arguments, code=0):
        result = subprocess.run([BASH, (DIRECTORY / "audit-apk.sh").as_posix(), path.as_posix(), *arguments],
            env=self.environment, capture_output=True, timeout=30)
        self.assertEqual(code, result.returncode, result.stderr.decode("utf-8", errors="replace"))
        return result.stdout.decode("utf-8", errors="replace")

    def measurement(self, path, code=0):
        return self.audit(path, [REGION_SHA, "--discovery-measurement", SOURCE_SHA], code)

    def test_default_one_and_two_argument_ordinary_controls_remain_unchanged(self):
        self.audit(self.apk("ordinary", marker=BACKEND), [])
        self.audit(self.apk("ordinary", marker=BACKEND), [REGION_SHA])
        self.audit(self.apk("ordinary"), [], 1)
        self.audit(self.apk("ordinary"), [REGION_SHA], 1)

    def test_measurement_requires_actual_role_proof_and_fixed_localhost_endpoint(self):
        output = self.measurement(self.apk())
        self.assertIn("Discovery APK manifest and DEX audit passed (measurement)", output)
        self.measurement(self.apk(marker=BACKEND), 1)
        self.measurement(self.apk(marker="http://localhost:18443/fixture/"), 1)
        self.measurement(self.apk("ordinary"), 1)

    def test_invalid_mode_arity_and_source_or_region_digest_fail_closed(self):
        path = self.apk()
        for arguments in ([REGION_SHA, "--discovery-measurement"],
                [REGION_SHA, "--unknown", SOURCE_SHA], [REGION_SHA, "--discovery-measurement", "invalid"],
                ["", "--discovery-measurement", SOURCE_SHA], ["invalid", "--discovery-measurement", SOURCE_SHA]):
            with self.subTest(arguments=arguments):
                self.audit(path, arguments, 2)

    def test_disabled_debuggable_unprotected_or_missing_manifest_roles_are_rejected(self):
        mutations = []
        root = fixtures.manifest("measurement")
        root.find("application").set(fixtures.A + "debuggable", "true")
        mutations.append(root)
        root = fixtures.manifest("measurement")
        root.find("application/profileable").set(fixtures.A + "shell", "false")
        mutations.append(root)
        for index in (0, 1):
            root = fixtures.manifest("measurement")
            root.find("application").findall("provider")[index].attrib.pop(fixtures.A + "permission")
            mutations.append(root)
            root = fixtures.manifest("measurement")
            app = root.find("application")
            app.remove(app.findall("provider")[index])
            mutations.append(root)
        for root in mutations:
            self.measurement(self.apk(manifest=copy.deepcopy(root)), 1)

    def test_each_required_class_needs_a_dex_definition_even_when_its_string_exists(self):
        for name in (fixtures.audit.MEASUREMENT, fixtures.audit.MEASUREMENT_PROVIDER, fixtures.audit.DIAGNOSTICS_PROVIDER):
            with self.subTest(name=name):
                self.measurement(self.apk(classes=fixtures.classes("measurement") - {fixtures.descriptor(name)}), 1)

    def test_forbidden_material_and_key_files_stay_forbidden_in_both_roles(self):
        for mode in ("ordinary", "measurement"):
            for extra in ({"assets/forbidden.txt": "BEGIN PRIVATE KEY"}, {"assets/test-only.key": "TEST_ONLY"}):
                path = self.apk(mode, marker=BACKEND if mode == "ordinary" else LOCALHOST, extra=extra)
                if mode == "ordinary":
                    self.audit(path, [REGION_SHA], 1)
                else:
                    self.measurement(path, 1)

    def test_amap_and_exact_protected_region_remain_required_for_measurement(self):
        self.measurement(self.apk(amap=False), 1)
        self.measurement(self.apk(region=False), 1)
        self.audit(self.apk(), ["b" * 64, "--discovery-measurement", SOURCE_SHA], 1)


if __name__ == "__main__":
    unittest.main()
