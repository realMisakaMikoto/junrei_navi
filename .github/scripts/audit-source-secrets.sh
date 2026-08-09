#!/usr/bin/env bash
set -euo pipefail

if git ls-files --error-unmatch app/google-services.json >/dev/null 2>&1; then
  echo "app/google-services.json must remain ignored and untracked" >&2
  exit 1
fi

secret_pattern='AIza[0-9A-Za-z_-]{35}|"private_key_id"[[:space:]]*:[[:space:]]*"[0-9a-f]{20,}"|"private_key"[[:space:]]*:[[:space:]]*"-----BEGIN|^-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|ANITABI_GOOGLE_SERVICES_JSON_BASE64[[:space:]]*='
amap_secret_pattern="(ANITABI_AMAP_(ANDROID_)?API_KEY|AMAP_(WEB_)?API_KEY|AMAP_(WEB_)?KEY|AMAP_SIGNATURE_SECRET|AMAP_SECURITY_JS_CODE)[[:space:]]*[:=][[:space:]]*['\"]?[0-9A-Za-z]{16,}"
release_secret_pattern="ANITABI_V025_REGION_DATA_(URL|BEARER_TOKEN)[[:space:]]*[:=][[:space:]]*['\"]?https?://[^$<{[:space:]]+|ANITABI_V025_REGION_DATA_BEARER_TOKEN[[:space:]]*[:=][[:space:]]*['\"]?[0-9A-Za-z_.-]{16,}"
if git grep -I -q -E "$secret_pattern|$amap_secret_pattern|$release_secret_pattern" -- . ':(exclude).github/scripts/audit-source-secrets.sh'; then
  echo "A tracked credential pattern was detected" >&2
  exit 1
fi

if git grep -I -q -E 'restapi\.amap\.com|/v[345]/direction/|/v3/assistant/coordinate/convert' -- app; then
  echo "Android source must not call AMap Web Service endpoints directly" >&2
  exit 1
fi

echo "Tracked-source credential audit passed"
