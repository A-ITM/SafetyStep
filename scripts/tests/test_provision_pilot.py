import base64
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("provision_pilot", Path(__file__).parents[1] / "provision-pilot.py")
pilot = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pilot)


class PilotProvisionTest(unittest.TestCase):
    now = 1_800_000_000_000
    origin = "https://fixture.supabase.co"
    user = "1e4b421d-df7b-4b23-a885-c3f59904b370"
    key = "sb_publishable_fixture_only"

    def token(self, lifetime=86_400):
        claims = {"sub": self.user, "iss": self.origin + "/auth/v1", "role": "authenticated",
                  "aud": "authenticated", "iat": self.now // 1000, "exp": self.now // 1000 + lifetime}
        payload = base64.urlsafe_b64encode(json.dumps(claims).encode()).decode().rstrip("=")
        return "fixture_header." + payload + ".fixture_signature"

    def test_long_project_jwt_gets_local_cap_without_password_or_refresh_token(self):
        token = self.token()
        with patch.object(pilot, "json_request", side_effect=[{"access_token": token, "refresh_token": "not-stored"},
                                                             {"id": self.user}, {"owner": "A"}]) as request:
            document = pilot.provision_document(self.origin, self.key, "A", "fixture@example.invalid", "fixture-password", self.now)
        self.assertEqual(self.now + 3_600_000, document["expiresAt"])
        self.assertNotIn("password", document)
        self.assertNotIn("refresh_token", document)
        self.assertEqual(self.origin + "/functions/v1/accidents/session", request.call_args_list[-1].args[0])

    def test_short_jwt_expiry_is_preserved_and_wrong_owner_never_provisions(self):
        with patch.object(pilot, "json_request", side_effect=[{"access_token": self.token(300)}, {"id": self.user}, {"owner": "A"}]):
            document = pilot.provision_document(self.origin, self.key, "A", "fixture@example.invalid", "fixture-password", self.now)
        self.assertEqual(self.now + 300_000, document["expiresAt"])
        with patch.object(pilot, "json_request", side_effect=[{"access_token": self.token()}, {"id": self.user}, {"owner": "B"}]):
            with self.assertRaises(pilot.ProvisionError):
                pilot.provision_document(self.origin, self.key, "A", "fixture@example.invalid", "fixture-password", self.now)

    def test_adb_checks_cannot_consume_private_credentials_stdin(self):
        credentials = {"email": "fixture@example.invalid", "password": "fixture-password"}
        document = {"mode": "cloud", "owner": "A", "accessToken": self.token(), "expiresAt": self.now + 3_600_000}
        installed = []
        directory = "/data/user/10/io.github.aitm.pleosapp\n"

        def adb(command, **kwargs):
            if "input" not in kwargs:
                # A real adb subprocess inherits and may drain stdin unless it is explicitly detached.
                self.assertEqual(subprocess.DEVNULL, kwargs.get("stdin"))
            self.assertIn("--user", command) if "get-current-user" not in command else None
            if command[-1] == "get-current-user":
                return SimpleNamespace(returncode=0, stdout="10\n")
            if command[-1] == "pwd":
                return SimpleNamespace(returncode=0, stdout=directory)
            if "input" in kwargs:
                installed.append(kwargs["input"])
            if "stat" in command:
                return SimpleNamespace(returncode=0, stdout=str(len(installed[0])) + "\n")
            return SimpleNamespace(returncode=0, stdout=b"")

        argv = ["provision-pilot.py", "--serial", "fixture-device", "--owner", "A", "--url", self.origin,
                "--publishable-key", self.key, "--credentials-stdin"]
        output = io.StringIO()
        with patch.object(sys, "argv", argv), patch.object(sys, "stdin", io.StringIO(json.dumps(credentials))), \
                patch.object(pilot.subprocess, "run", side_effect=adb), \
                patch.object(pilot, "provision_document", return_value=document) as provision, contextlib.redirect_stdout(output):
            pilot.main()
        self.assertEqual(credentials["email"], provision.call_args.args[3])
        self.assertEqual(credentials["password"], provision.call_args.args[4])
        self.assertEqual(document, json.loads(installed[0]))
        self.assertNotIn(document["accessToken"], output.getvalue())
        self.assertNotIn(credentials["password"], output.getvalue())
        self.assertNotIn(credentials["email"], output.getvalue())


if __name__ == "__main__":
    unittest.main()
