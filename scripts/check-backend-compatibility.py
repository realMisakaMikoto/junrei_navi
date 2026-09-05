#!/usr/bin/env python3
"""Check public bootstrap metadata before building an internal-test APK."""

import argparse
import datetime
import json
import os
from pathlib import Path
import re
import sys
import urllib.error
import urllib.parse
import urllib.request


class CompatibilityError(Exception):
    """A diagnostic that contains no URL, response body, or region data."""


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise CompatibilityError("Backend redirects are not allowed; configure the final HTTPS base URL.")


def validated_base_url(raw):
    try:
        url = urllib.parse.urlsplit(raw)
        path_parts = urllib.parse.unquote(url.path).split("/")
        valid = (
            raw.startswith("https://") and url.scheme == "https" and url.hostname
            and url.username is None and url.password is None and url.port in (None, 443)
            and not url.query and not url.fragment and "?" not in raw and "#" not in raw
            and not any(character.isspace() or ord(character) < 32 for character in raw)
            and "\\" not in raw and not any(part in (".", "..") for part in path_parts)
        )
    except ValueError:
        valid = False
    if not valid:
        raise CompatibilityError("Configure a normalized HTTPS test backend URL without credentials, query, or fragment.")
    return raw.rstrip("/")


def stable_version(value):
    if not isinstance(value, str) or len(value) > 64 or not re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", value):
        raise CompatibilityError("App and backend minimum versions must be stable semantic versions.")
    parts = tuple(map(int, value.split(".")))
    if any(part > 9007199254740991 for part in parts):
        raise CompatibilityError("App and backend minimum versions must be stable semantic versions.")
    return parts


def candidate_versions(region_asset, app_gradle):
    try:
        asset = json.loads(Path(region_asset).read_text(encoding="utf-8"))
        region_version = asset.get("regionDataVersion") if isinstance(asset, dict) else None
        app_versions = re.findall(r'^\s*versionName\s*=\s*"([^"\r\n]+)"\s*$', Path(app_gradle).read_text(encoding="utf-8"), re.MULTILINE)
    except (OSError, ValueError):
        raise CompatibilityError("Cannot read candidate region data or app version; verify the build inputs.") from None
    if not isinstance(region_version, str) or not region_version.strip() or len(app_versions) != 1:
        raise CompatibilityError("Candidate region data or app version is invalid; verify the build inputs.")
    stable_version(app_versions[0])
    return region_version, app_versions[0]


def fetch_metadata(base_url, endpoint, opener):
    request = urllib.request.Request(base_url + endpoint, headers={"Accept": "application/json"})
    try:
        with opener.open(request, timeout=10) as response:
            if response.status != 200:
                raise CompatibilityError("Backend bootstrap is unavailable; deploy and enable the compatible v2 backend first.")
            if response.headers.get_content_type() != "application/json":
                raise CompatibilityError("Backend bootstrap did not return JSON; check the reverse proxy and v2 deployment.")
            payload = response.read(16385)
            if len(payload) > 16384:
                raise CompatibilityError("Backend bootstrap metadata exceeds the allowed size.")
            data = json.loads(payload)
    except urllib.error.HTTPError as error:
        if error.code == 404:
            raise CompatibilityError("Backend v2 endpoint is missing; deploy v2 before building this APK.") from None
        raise CompatibilityError("Backend bootstrap is unavailable; deploy and enable the compatible v2 backend first.") from None
    except (urllib.error.URLError, OSError, ValueError):
        raise CompatibilityError("Cannot read backend bootstrap metadata; check HTTPS connectivity and the v2 deployment.") from None
    if not isinstance(data, dict):
        raise CompatibilityError("Backend bootstrap schema is invalid; deploy a compatible v2 backend.")
    return data


def validate_policy(policy, region_version, app_version):
    if policy.get("apiVersion") != "2":
        raise CompatibilityError("Backend API version is incompatible; deploy v2 before building this APK.")
    if policy.get("regionDataVersion") != region_version:
        raise CompatibilityError("Backend region-data version differs from the APK; deploy the matching region asset.")
    if stable_version(app_version) < stable_version(policy.get("minimumAppVersion")):
        raise CompatibilityError("This app version is older than the backend minimum; update the candidate app version.")
    sunset = policy.get("v1SunsetAt")
    try:
        if not isinstance(sunset, str) or not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{3})?Z", sunset):
            raise ValueError()
        datetime.datetime.fromisoformat(sunset.replace("Z", "+00:00"))
    except ValueError:
        raise CompatibilityError("Backend policy schema is invalid; configure a valid v1 sunset UTC instant.") from None
    providers = policy.get("providers")
    if not isinstance(providers, dict) or any(providers.get(provider) != "enabled" for provider in ("google", "amap")):
        raise CompatibilityError("Both route providers must be enabled; restore backend provider readiness before building.")


def check_backend(base_url, region_version, app_version):
    base_url = validated_base_url(base_url)
    opener = urllib.request.build_opener(NoRedirect())
    validate_policy(fetch_metadata(base_url, "/v2/policy", opener), region_version, app_version)
    health = fetch_metadata(base_url, "/v2/health", opener)
    if any(health.get(key) != "ok" for key in ("database", "regionData", "google", "amap")):
        raise CompatibilityError("Backend v2 is not ready; restore database, region data, and both providers before building.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--region-asset", required=True)
    parser.add_argument("--app-gradle", required=True)
    args = parser.parse_args()
    try:
        region_version, app_version = candidate_versions(args.region_asset, args.app_gradle)
        check_backend(os.environ.get("TEST_BACKEND_BASE_URL", ""), region_version, app_version)
    except CompatibilityError as error:
        print(f"Backend compatibility check failed: {error}", file=sys.stderr)
        return 1
    print("Backend v2 bootstrap compatibility passed; live route acceptance and the formal release gate remain separate.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
