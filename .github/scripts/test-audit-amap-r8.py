#!/usr/bin/env python3
"""Mutation tests for the release-only AMap JNI regression guard."""

import importlib.util
from pathlib import Path
import struct
import sys
import tempfile
import unittest
import zipfile

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("amap_audit", Path(__file__).with_name("audit-amap-r8.py"))
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


def mapping_and_seeds():
    mapping, seeds = [], []
    for name, signatures in audit.CONTRACT.items():
        mapping.append(f"{name} -> {name}:")
        seeds.append(name)
        for signature in signatures:
            original = f"1:2:{signature}:3:4" if "(" in signature else signature
            mapping.append(f"    {original} -> {audit.member_name(signature)}")
            seeds.append(audit.seed_name(name, signature))
    return "\n".join(mapping), "\n".join(seeds)


def dex_fixture(define_classes):
    # Minimal class-table fixture: all names are referenced, but definitions
    # are independent. Mere DEX string presence must not pass the APK audit.
    names = ["L" + name.replace(".", "/") + ";" for name in audit.CONTRACT]
    size = len(names)
    strings_offset, types_offset, classes_offset = 112, 112 + size * 4, 112 + size * 8
    data = bytearray(classes_offset + (size * 32 if define_classes else 0))
    data[:8] = b"dex\n035\0"
    struct.pack_into("<IIII", data, 56, size, strings_offset, size, types_offset)
    struct.pack_into("<II", data, 96, size if define_classes else 0, classes_offset)
    for index, name in enumerate(names):
        struct.pack_into("<I", data, strings_offset + index * 4, len(data))
        struct.pack_into("<I", data, types_offset + index * 4, index)
        if define_classes:
            struct.pack_into("<I", data, classes_offset + index * 32, index)
        data.extend(bytes([len(name)]) + name.encode("ascii") + b"\0")
    return data


class AmapR8AuditTest(unittest.TestCase):
    def check(self, mapping, seeds, usage=""):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "mapping.txt").write_text(mapping, encoding="utf-8")
            (root / "seeds.txt").write_text(seeds, encoding="utf-8")
            (root / "usage.txt").write_text(usage, encoding="utf-8")
            audit.audit(root / "mapping.txt", root / "seeds.txt")

    def test_preserved_classes_methods_fields_and_line_ranges_pass(self):
        self.check(*mapping_and_seeds())

    def test_removed_or_renamed_class_fails_even_when_seed_exists(self):
        mapping, seeds = mapping_and_seeds()
        for name in audit.CONTRACT:
            for target in ("a", "R8$$REMOVED$$CLASS$$1"):
                with self.subTest(name=name, target=target), self.assertRaises(audit.AuditError):
                    self.check(mapping.replace(f"{name} -> {name}:", f"{name} -> {target}:"), seeds)

    def test_removed_or_renamed_native_callbacks_and_fields_fail(self):
        mapping, seeds = mapping_and_seeds()
        for member in ("getClassLoader", "getGlyphMetrics", "bitmapBuffer"):
            with self.subTest(member=member), self.assertRaises(audit.AuditError):
                self.check(mapping.replace(f" -> {member}", " -> renamed"), seeds)
        with self.assertRaises(audit.AuditError):
            self.check("\n".join(line for line in mapping.splitlines() if "getClassLoader" not in line), seeds)

    def test_mapping_without_explicit_callback_keep_seed_fails(self):
        mapping, seeds = mapping_and_seeds()
        with self.assertRaises(audit.AuditError):
            self.check(mapping, "\n".join(line for line in seeds.splitlines() if "getClassLoader" not in line))

    def test_omitted_identity_field_mapping_requires_seed_and_no_removed_field(self):
        mapping, seeds = mapping_and_seeds()
        mapping = "\n".join(line for line in mapping.splitlines() if "bitmapBuffer" not in line)
        self.check(mapping, seeds)
        with self.assertRaises(audit.AuditError):
            self.check(mapping, seeds, "com.autonavi.base.ae.gmap.glyph.GlyphRaster:\n    public byte[] bitmapBuffer\n")
        with self.assertRaises(audit.AuditError):
            self.check(mapping, "\n".join(line for line in seeds.splitlines() if "bitmapBuffer" not in line))

    def test_apk_requires_class_definitions_not_string_references(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "fixture.apk"
            for definitions in (False, True):
                with zipfile.ZipFile(path, "w") as archive:
                    archive.writestr("classes.dex", dex_fixture(definitions))
                if definitions:
                    audit.audit_apk(path)
                else:
                    with self.assertRaises(audit.AuditError):
                        audit.audit_apk(path)

    def test_missing_outputs_and_invalid_dex_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(audit.AuditError):
                audit.audit(Path(directory) / "missing", Path(directory) / "missing-seeds")
        with self.assertRaises(audit.AuditError):
            audit.dex_classes(b"not-a-dex")


if __name__ == "__main__":
    unittest.main()
