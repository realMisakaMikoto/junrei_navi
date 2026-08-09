#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 || ! -f "$1" || ! -f "$2" ]]; then
  echo "Usage: audit-apk-signature.sh <apk> <apksigner>" >&2
  exit 2
fi

apk="$1"
apksigner="$2"
expected_sha256="9679c83769368c7150f629d9cba3c0e5d633fa7f1043ce251fdba6c7c64fb00a"
report="$(mktemp)"
trap 'rm -f "$report"' EXIT

"$apksigner" verify --verbose --print-certs "$apk" > "$report"
cat "$report"

if ! grep -Fqx 'Number of signers: 1' "$report"; then
  echo "Release APK must have exactly one signer" >&2
  exit 1
fi

actual_sha256="$(
  sed -n -E 's/^(Signer #[0-9]+|V[0-9.]+ Signer): certificate SHA-256 digest: //p' "$report" |
    tr 'A-F' 'a-f' |
    LC_ALL=C sort -u
)"
if [[ "$(printf '%s\n' "$actual_sha256" | grep -c .)" -ne 1 ]]; then
  echo "Release APK certificate digest is missing or ambiguous" >&2
  exit 1
fi
if [[ "$actual_sha256" != "$expected_sha256" ]]; then
  echo "Release APK signer does not match the fixed public certificate" >&2
  exit 1
fi
if ! grep -Eq '^(Signer #[0-9]+ certificate public key algorithm|V[0-9.]+ Signer: key algorithm): RSA$' "$report"; then
  echo "Release APK signer must use RSA" >&2
  exit 1
fi
if ! grep -Eq '^(Signer #[0-9]+ certificate key size \(bits\)|V[0-9.]+ Signer: key size \(bits\)): 4096$' "$report"; then
  echo "Release APK signer must use a 4096-bit key" >&2
  exit 1
fi

echo "Release APK fixed-signature audit passed"
