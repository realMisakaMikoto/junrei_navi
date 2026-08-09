#!/usr/bin/env bash
set -euo pipefail

if [[ ($# -ne 1 && $# -ne 2) || ! -f "$1" ]]; then
  echo "Usage: audit-apk.sh <apk> [expected-region-data-sha256]" >&2
  exit 2
fi

apk="$1"
expected_region_sha256="${2:-}"
audit_dir="$(mktemp -d)"
trap 'rm -rf "$audit_dir"' EXIT

if [[ -n "$expected_region_sha256" && ! "$expected_region_sha256" =~ ^[0-9a-f]{64}$ ]]; then
  echo "Expected region-data SHA-256 must be 64 lowercase hexadecimal characters" >&2
  exit 2
fi

if command -v unzip >/dev/null 2>&1; then
  unzip -qq -o "$apk" -d "$audit_dir"
elif command -v python3 >/dev/null 2>&1; then
  python3 -m zipfile -e "$apk" "$audit_dir"
else
  echo "APK audit requires unzip or Python 3" >&2
  exit 2
fi

forbidden_pattern='api\.openrouteservice\.org|api\.heigit\.org|api\.transitous\.org|tiles\.openfreemap\.org|org\.maplibre|organicmaps|"type":"service_account"|"private_key":|ANITABI_STORE_PASSWORD|ANITABI_KEY_PASSWORD|GOOGLE_SERVICE_ACCOUNT_JSON|AMAP_WEB_API_KEY|AMAP_WEB_KEY|AMAP_SIGNATURE_SECRET|AMAP_SECURITY_JS_CODE|BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY'
if LC_ALL=C grep -a -E -r -q -- "$forbidden_pattern" "$audit_dir"; then
  echo "Forbidden legacy endpoint, SDK, server credential, or private-key marker found in APK" >&2
  LC_ALL=C grep -a -E -r -l -- "$forbidden_pattern" "$audit_dir" | sed -n '1,20p' >&2
  exit 1
fi

required_pattern='api\.anitabi\.afunnypersonlol0\.site'
if ! LC_ALL=C grep -a -E -r -q -- "$required_pattern" "$audit_dir"; then
  echo "The fixed Anitabi HTTPS backend endpoint is missing from the APK" >&2
  exit 1
fi

if ! LC_ALL=C grep -a -F -r -q -- 'com.amap.api.v2.apikey' "$audit_dir"; then
  echo "The AMap Android manifest integration is missing from the APK" >&2
  exit 1
fi

if find "$audit_dir" -type f \( -iname '*.jks' -o -iname '*.keystore' -o -iname '*.p12' -o -iname '*.pem' -o -iname '*.key' \) -print -quit | grep -q .; then
  echo "A key or keystore file was packaged in the APK" >&2
  exit 1
fi

if [[ -n "$expected_region_sha256" ]]; then
  region_asset="$audit_dir/assets/approved_regions/territory_regions_v1.json"
  if [[ ! -f "$region_asset" ]]; then
    echo "The approved production region asset is missing from the APK" >&2
    exit 1
  fi
  actual_region_sha256="$(sha256sum "$region_asset" | cut -d ' ' -f 1)"
  if [[ "$actual_region_sha256" != "$expected_region_sha256" ]]; then
    echo "The packaged region asset does not match the protected SHA-256" >&2
    exit 1
  fi
fi

echo "APK content audit passed"
