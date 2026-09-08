#!/usr/bin/env python3
"""Check the AMap 11.2.000 JNI contract in minified Release R8 outputs.

Native FindClass/GetMethodID/GetFieldID lookups are invisible to ordinary JVM
tests and debug builds. Check their Java names AND explicitly kept members;
retaining just native method declarations does not protect native callbacks.
"""

import argparse
from pathlib import Path
import re
import struct
import sys
import zipfile


# Bounded to JNI entry points and callback types in the pinned combined SDK.
# Review these names against the SDK JAR/native libraries when upgrading it.
CONTRACT = {
    "com.autonavi.base.amap.mapcore.ClassTools": (
        "java.lang.ClassLoader getClassLoader()",
    ),
    "com.autonavi.base.ae.gmap.glyph.GlyphLoader": (
        "com.autonavi.base.ae.gmap.glyph.GlyphMetrics getGlyphMetrics(byte[])",
        "com.autonavi.base.ae.gmap.glyph.GlyphRaster getGlyphRaster(byte[])",
        "com.autonavi.base.ae.gmap.glyph.FontMetrics getFontMetrics(byte[])",
    ),
    "com.autonavi.base.ae.gmap.glyph.GlyphMetrics": (
        "boolean bSuccess", "int nWidth", "int nHeight", "float fLeft", "float fTop", "float fAdvance",
    ),
    "com.autonavi.base.ae.gmap.glyph.GlyphRaster": (
        "boolean bSuccess", "byte[] bitmapBuffer", "int bitmapSize", "int bitmapWidth",
        "int bitmapHeight", "int bitmapPixelMode",
    ),
    "com.autonavi.base.ae.gmap.glyph.FontMetrics": (
        "boolean bSuccess", "float fAscent", "float fDescent", "float fLeading", "float fHeight",
    ),
    "com.amap.api.maps.model.BitmapDescriptor": (),
    "com.autonavi.base.ae.gmap.bean.NativeTextGenerate": (
        "com.autonavi.base.ae.gmap.bean.NativeTextGenerate getInstance()",
        "com.amap.api.maps.model.BitmapDescriptor getIconBitmap(java.lang.String)",
        "byte[] getMapStyleFileData(java.lang.String)",
    ),
}


class AuditError(Exception):
    pass


def member_name(signature):
    return signature.split(" ", 1)[1].split("(", 1)[0]


def seed_name(class_name, signature):
    if signature.startswith("void <init>("):
        signature = class_name.rsplit(".", 1)[1] + signature[len("void <init>"):]
    return class_name + ": " + signature


def audit(mapping_file, seeds_file):
    usage_file = seeds_file.with_name("usage.txt")
    for required in (mapping_file, seeds_file):
        if not required.is_file() or required.stat().st_size == 0:
            raise AuditError(f"Missing release R8 output: {required}")
    if not usage_file.is_file():
        raise AuditError(f"Missing release R8 output: {usage_file}")

    retained_classes = set()
    retained_members = {name: set() for name in CONTRACT}
    mapped_members = {name: set() for name in CONTRACT}
    current = None
    with mapping_file.open(encoding="utf-8") as lines:
        for raw in lines:
            line = raw.rstrip()
            if not line or line.lstrip().startswith("#"):
                continue
            if not line[0].isspace():
                original, separator, renamed = line.partition(" -> ")
                current = original if separator and original in CONTRACT else None
                if current and renamed == current + ":":
                    retained_classes.add(current)
                continue
            if current is None or " -> " not in line:
                continue
            signature, renamed = line.strip().rsplit(" -> ", 1)
            # R8 method ranges may surround the original Java signature.
            signature = re.sub(r"^\d+:\d+:", "", signature)
            signature = re.sub(r":\d+(?::\d+)?$", "", signature)
            if signature in CONTRACT[current]:
                mapped_members[current].add(signature)
                if renamed == member_name(signature):
                    retained_members[current].add(signature)

    required_seeds = set(CONTRACT)
    for class_name, signatures in CONTRACT.items():
        required_seeds.update(seed_name(class_name, signature) for signature in signatures)
    with seeds_file.open(encoding="utf-8") as lines:
        actual_seeds = {line.strip() for line in lines if line.strip() in required_seeds}

    removed_members = set()
    current = None
    with usage_file.open(encoding="utf-8") as lines:
        for raw in lines:
            line = raw.rstrip()
            if not line:
                continue
            if not line[0].isspace():
                current = line.rstrip(":") if line.rstrip(":") in CONTRACT else None
            elif current:
                signature = re.sub(r"^(?:(?:public|private|protected|static|final|volatile|transient) )+", "", line.strip())
                if signature in CONTRACT[current]:
                    removed_members.add((current, signature))

    failures = []
    for class_name, signatures in CONTRACT.items():
        if class_name not in retained_classes:
            failures.append(f"class absent or renamed: {class_name}")
        for signature in signatures:
            # R8 omits identity field mappings. A field must still be an
            # explicit keep seed, absent from usage, and never mapped away.
            must_have_mapping = "(" in signature or signature in mapped_members[class_name]
            if must_have_mapping and signature not in retained_members[class_name]:
                failures.append(f"member absent or renamed: {class_name}: {signature}")
            if (class_name, signature) in removed_members:
                failures.append(f"member removed: {class_name}: {signature}")
    failures.extend(f"not an R8 seed: {seed}" for seed in sorted(required_seeds - actual_seeds))
    if failures:
        raise AuditError("AMap JNI R8 contract audit failed:\n" + "\n".join(failures))


def dex_classes(data):
    """Return defined classes, not mere string/type references to absent classes."""
    if len(data) < 112 or not data.startswith(b"dex\n"):
        raise AuditError("APK contains invalid DEX data")

    def u32(offset):
        return struct.unpack_from("<I", data, offset)[0]

    string_count, strings_offset, type_count, types_offset = struct.unpack_from("<IIII", data, 56)
    class_count, classes_offset = struct.unpack_from("<II", data, 96)
    strings = []
    for index in range(string_count):
        offset = u32(strings_offset + index * 4)
        while data[offset] & 128:
            offset += 1
        offset += 1
        # JNI class descriptors use ASCII, which is identical in DEX MUTF-8.
        strings.append(data[offset:data.index(0, offset)].decode("utf-8", errors="replace"))
    types = [strings[u32(types_offset + index * 4)] for index in range(type_count)]
    return {types[u32(classes_offset + index * 32)] for index in range(class_count)}


def audit_apk(apk_file):
    try:
        with zipfile.ZipFile(apk_file) as apk:
            defined = set()
            for name in apk.namelist():
                if re.fullmatch(r"classes\d*\.dex", name):
                    defined.update(dex_classes(apk.read(name)))
    except (zipfile.BadZipFile, IndexError, ValueError, struct.error) as error:
        raise AuditError("APK DEX class audit could not be completed") from error
    missing = [name for name in CONTRACT if "L" + name.replace(".", "/") + ";" not in defined]
    if missing:
        raise AuditError("AMap JNI classes absent from APK:\n" + "\n".join(missing))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mapping_dir", nargs="?", type=Path,
                        default=Path("app/build/outputs/mapping/release"))
    parser.add_argument("mapping_file", nargs="?", type=Path)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--apk-only", action="store_true")
    args = parser.parse_args()
    try:
        if args.apk_only and args.apk is None:
            raise AuditError("--apk-only requires --apk")
        if not args.apk_only:
            audit(args.mapping_file or args.mapping_dir / "mapping.txt", args.mapping_dir / "seeds.txt")
        if args.apk:
            audit_apk(args.apk)
    except (AuditError, OSError) as error:
        print(error, file=sys.stderr)
        return 1
    if args.apk_only:
        print(f"AMap JNI APK class audit passed ({len(CONTRACT)} classes)")
    else:
        count = sum(len(members) for members in CONTRACT.values())
        print(f"AMap JNI R8 contract audit passed ({len(CONTRACT)} classes, {count} members)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
