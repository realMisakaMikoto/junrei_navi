"""Inspect actual APK manifest and DEX definitions without emitting manifest metadata or keys."""

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

ANDROID = '{http://schemas.android.com/apk/res/android}'
PACKAGE = 'cn.anitabi.navigator'
APPLICATION = PACKAGE + '.AnitabiApplication'
MEASUREMENT = PACKAGE + '.measurement.DiscoveryMeasurementApplication'
MEASUREMENT_PROVIDER = PACKAGE + '.measurement.DiscoveryMeasurementProvider'
DIAGNOSTICS_PROVIDER = PACKAGE + '.diagnostics.DiscoveryDiagnosticsProvider'


class AuditError(Exception):
    pass


def require(condition, code):
    if not condition:
        raise AuditError(code)


def class_name(value):
    if value.startswith('.'):
        return PACKAGE + value
    return value if '.' in value else PACKAGE + '.' + value


def audit_manifest(document, classes, mode):
    root = ET.fromstring(document)
    require(root.get('package') == PACKAGE, 'unexpected_package')
    applications = root.findall('application')
    require(len(applications) == 1, 'application_count')
    app = applications[0]
    require(app.get(ANDROID+'debuggable', 'false') == 'false', 'release_must_not_be_debuggable')
    provider_nodes = app.findall('provider')
    providers = {class_name(p.get(ANDROID+'name', '')): p for p in provider_nodes}
    require(len(providers) == len(provider_nodes), 'duplicate_provider')
    require(not any('.recovery.' in name for name in providers), 'recovery_fixture_in_release')
    profiles = app.findall('profileable')
    require(len(profiles) <= 1, 'profileable_count')
    for profile in profiles:
        require(profile.get(ANDROID+'enabled', 'true') in ('true', 'false') and
                profile.get(ANDROID+'shell', 'false') in ('true', 'false'), 'unresolved_profileable_state')
    profile_enabled = bool(profiles) and profiles[0].get(ANDROID+'enabled', 'true') == 'true'
    profile_shell = bool(profiles) and profiles[0].get(ANDROID+'shell', 'false') == 'true'
    actual_application = class_name(app.get(ANDROID+'name', ''))
    diagnostics = providers.get(DIAGNOSTICS_PROVIDER)
    if mode == 'measurement':
        require(actual_application == MEASUREMENT, 'measurement_application_missing')
        require(profile_enabled and profile_shell, 'measurement_profiling_disabled')
        for name, authority in [(MEASUREMENT_PROVIDER, 'discovery-measurement'), (DIAGNOSTICS_PROVIDER, 'discovery-diagnostics')]:
            provider = providers.get(name)
            require(provider is not None, 'measurement_provider_missing')
            require(provider.get(ANDROID+'authorities') == PACKAGE+'.'+authority, 'measurement_authority')
            require(provider.get(ANDROID+'enabled', 'true') == 'true', 'measurement_provider_disabled')
            require(provider.get(ANDROID+'exported') == 'true', 'measurement_provider_export')
            require(provider.get(ANDROID+'permission') == 'android.permission.DUMP', 'measurement_provider_permission')
        for name in (MEASUREMENT, MEASUREMENT_PROVIDER, DIAGNOSTICS_PROVIDER):
            require('L'+name.replace('.', '/')+';' in classes, 'measurement_dex_definition_missing')
    else:
        require(actual_application == APPLICATION, 'ordinary_application_replaced')
        require('L'+APPLICATION.replace('.', '/')+';' in classes, 'ordinary_application_dex_missing')
        require(not profile_enabled and not profile_shell, 'ordinary_profiling_enabled')
        require(MEASUREMENT_PROVIDER not in providers, 'measurement_provider_in_ordinary_release')
        require(not any(name.startswith('Lcn/anitabi/navigator/measurement/') for name in classes), 'measurement_class_in_ordinary_release')
        require(diagnostics is None or diagnostics.get(ANDROID+'enabled', 'true') == 'false', 'ordinary_diagnostics_enabled')
    return {'nonDebuggable':True, 'profilingEnabled':profile_enabled and profile_shell,
            'measurementFixture':mode == 'measurement', 'manifestAndDexVerified':True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--apkanalyzer', required=True)
    parser.add_argument('--mode', choices=['ordinary', 'measurement'], required=True)
    parser.add_argument('--source-sha', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        require(re.fullmatch('[a-f0-9]{40}', args.source_sha) is not None, 'invalid_source_sha')
        result = subprocess.run([args.apkanalyzer, 'manifest', 'print', str(args.apk)], capture_output=True, timeout=120)
        require(result.returncode == 0, 'manifest_inspection_failed')
        specification = importlib.util.spec_from_file_location('amap_dex_reader', Path(__file__).with_name('audit-amap-r8.py'))
        reader = importlib.util.module_from_spec(specification)
        specification.loader.exec_module(reader)
        with zipfile.ZipFile(args.apk) as apk:
            dex_names = [name for name in apk.namelist() if re.fullmatch(r'classes\d*\.dex', name)]
            require(bool(dex_names), 'dex_missing')
            classes = set().union(*(reader.dex_classes(apk.read(name)) for name in dex_names))
        report = audit_manifest(result.stdout, classes, args.mode)
        report.update(sourceSha=args.source_sha, sha256=hashlib.sha256(args.apk.read_bytes()).hexdigest())
        args.output.write_text(json.dumps(report, indent=2)+'\n', encoding='ascii')
    except AuditError as failure:
        print('Discovery APK audit failed: '+str(failure), file=sys.stderr)
        return 1
    except Exception:
        print('Discovery APK audit failed: inspection_unavailable', file=sys.stderr)
        return 1
    print('Discovery APK manifest and DEX audit passed ('+args.mode+')')
    return 0


if __name__ == '__main__':
    sys.exit(main())
