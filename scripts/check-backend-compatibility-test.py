#!/usr/bin/env python3
"""Offline regressions for the internal APK backend compatibility check."""

from email.message import Message
import importlib.util
import io
import json
from pathlib import Path
import unittest
from unittest.mock import patch
import urllib.error

spec = importlib.util.spec_from_file_location(
    "compatibility", Path(__file__).with_name("check-backend-compatibility.py")
)
compat = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compat)

POLICY = {
    "apiVersion": "2", "regionDataVersion": "test-regions-1", "minimumAppVersion": "0.2.5",
    "v1SunsetAt": "2026-09-19T00:00:00.000Z",
    "providers": {"google": "enabled", "amap": "enabled"},
}
HEALTH = dict.fromkeys(("database", "regionData", "google", "amap"), "ok")


class Response(io.BytesIO):
    def __init__(self, payload, content_type="application/json"):
        super().__init__(json.dumps(payload).encode())
        self.status = 200
        self.headers = Message()
        self.headers["Content-Type"] = content_type


class CompatibilityTest(unittest.TestCase):
    def test_ready_backend_uses_only_unauthenticated_bootstrap_endpoints(self):
        with patch.object(compat.urllib.request, "build_opener") as factory:
            factory.return_value.open.side_effect = [Response(POLICY), Response(HEALTH)]
            compat.check_backend("https://test.invalid/internal", "test-regions-1", "0.2.5")
            requests = [call.args[0] for call in factory.return_value.open.call_args_list]
        self.assertEqual([r.full_url for r in requests], [
            "https://test.invalid/internal/v2/policy", "https://test.invalid/internal/v2/health",
        ])
        self.assertTrue(all(r.get_method() == "GET" and not r.has_header("Authorization") for r in requests))

    def test_missing_v2_blocks_before_route_calls_without_leaking_body(self):
        with patch.object(compat.urllib.request, "build_opener") as factory:
            factory.return_value.open.side_effect = urllib.error.HTTPError(
                "https://test.invalid/private", 404, "sensitive-response", {}, None,
            )
            with self.assertRaises(compat.CompatibilityError) as raised:
                compat.check_backend("https://test.invalid", "test-regions-1", "0.2.5")
            self.assertEqual(factory.return_value.open.call_count, 1)
        self.assertIn("v2 endpoint is missing", str(raised.exception))
        self.assertNotIn("sensitive-response", str(raised.exception))
        self.assertNotIn("test.invalid", str(raised.exception))

    def test_incompatible_or_disabled_policy_is_rejected(self):
        mutations = [
            {"apiVersion": "1"}, {"regionDataVersion": "other"},
            {"minimumAppVersion": "0.2.6"}, {"minimumAppVersion": "0.2.5-rc.1"},
            {"providers": {"google": "disabled", "amap": "enabled"}},
            {"providers": {"google": "enabled", "amap": "disabled"}},
            {"v1SunsetAt": "invalid"},
        ]
        for mutation in mutations:
            with self.subTest(mutation=mutation), self.assertRaises(compat.CompatibilityError):
                compat.validate_policy(POLICY | mutation, "test-regions-1", "0.2.5")

    def test_unhealthy_backend_is_rejected_even_with_enabled_policy(self):
        for key in HEALTH:
            with self.subTest(key=key), patch.object(compat.urllib.request, "build_opener") as factory:
                factory.return_value.open.side_effect = [Response(POLICY), Response(HEALTH | {key: "unavailable"})]
                with self.assertRaises(compat.CompatibilityError):
                    compat.check_backend("https://test.invalid", "test-regions-1", "0.2.5")

    def test_redirects_insecure_urls_html_and_oversize_metadata_are_rejected(self):
        with self.assertRaises(compat.CompatibilityError):
            compat.NoRedirect().redirect_request(None, None, 302, "", {}, "https://elsewhere.invalid")
        for url in ("http://test.invalid", "https://user:secret@test.invalid", "https://test.invalid/../v2", "https://test.invalid?token=secret"):
            with self.subTest(url=url), self.assertRaises(compat.CompatibilityError):
                compat.validated_base_url(url)
        for response in (Response(POLICY, "text/html"), Response({"padding": "x" * 16384})):
            with patch.object(compat.urllib.request, "build_opener") as factory:
                factory.return_value.open.return_value = response
                with self.assertRaises(compat.CompatibilityError):
                    compat.check_backend("https://test.invalid", "test-regions-1", "0.2.5")


if __name__ == "__main__":
    unittest.main()
