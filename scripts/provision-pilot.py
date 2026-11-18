#!/usr/bin/env python3
"""Provision a short Supabase user session into a debug app's private no-backup directory."""

import argparse
import base64
import datetime
import getpass
import json
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

PACKAGES = {"A": "io.github.aitm.pleosapp", "B": "io.github.aitm.pleosapp.demo"}
FILE = "no_backup/safetystep-pilot.json"


class ProvisionError(Exception):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def json_request(url, key, *, body=None, token=None):
    headers = {"apikey": key, "Content-Type": "application/json", "Cache-Control": "no-store"}
    if token is not None:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(), headers=headers)
    try:
        with urllib.request.build_opener(NoRedirect).open(request, timeout=15) as response:
            raw = response.read(65_537)
            if len(raw) > 65_536:
                raise ProvisionError("Server response too large")
            result = json.loads(raw)
            if not isinstance(result, dict):
                raise ProvisionError("Server response must be a JSON object")
            return result
    except urllib.error.HTTPError as error:
        # Never print Auth bodies: they may contain credentials, identifiers or upstream request data.
        raise ProvisionError(f"Sign-in/session validation rejected (HTTP {error.code}); check user, key and allowlist") from None
    except (urllib.error.URLError, ValueError, TimeoutError):
        raise ProvisionError("Unable to validate the pilot user with the configured HTTPS project") from None


def provision_document(origin, key, owner, email, password, now=None):
    now = int(time.time() * 1000) if now is None else now
    response = json_request(origin + "/auth/v1/token?grant_type=password", key,
                            body={"email": email, "password": password})
    token = response.get("access_token")
    if not isinstance(token, str) or not re.fullmatch(r"[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+", token):
        raise ProvisionError("Auth did not return a user access JWT")
    try:
        payload = token.split(".")[1]
        claims = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
        user_id = str(uuid.UUID(claims["sub"]))
        exp = claims["exp"]
        iat = claims["iat"]
        audience = claims.get("aud")
        if (claims.get("iss") != origin + "/auth/v1" or claims.get("role") != "authenticated"
                or not (audience == "authenticated" or isinstance(audience, list) and "authenticated" in audience)
                or type(exp) is not int or type(iat) is not int or exp <= iat or iat * 1000 > now + 60_000):
            raise ValueError()
    except (KeyError, ValueError, TypeError):
        raise ProvisionError("Auth token is not a suitable authenticated user session") from None
    user = json_request(origin + "/auth/v1/user", key, token=token)
    if user.get("id") != user_id:
        raise ProvisionError("Auth user verification did not match the signed-in user")
    identity = json_request(origin + "/functions/v1/accidents/session", key, token=token)
    if identity != {"owner": owner}:
        raise ProvisionError("Authenticated pilot vehicle differs from requested app flavor; check A/B allowlist")
    expires_at = min(exp * 1000, now + 3_600_000)
    if expires_at - now < 30_000:
        raise ProvisionError("User session expires too soon; sign in again")
    # The refresh token and password are intentionally omitted from the device file.
    return {"mode": "cloud", "supabaseUrl": origin, "publishableKey": key, "owner": owner,
            "userId": user_id, "accessToken": token, "provisionedAt": now, "expiresAt": expires_at}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Explicit adb device serial")
    parser.add_argument("--user", type=int, help="Android user; defaults to am get-current-user (automotive commonly 10)")
    parser.add_argument("--owner", choices=PACKAGES, required=True, help="A=pleos, B=demo")
    parser.add_argument("--url", help="Public HTTPS Supabase project origin")
    parser.add_argument("--publishable-key", help="Public sb_publishable_ key (never a secret/service-role key)")
    parser.add_argument("--local", action="store_true", help="Explicitly remove cloud configuration and return to local mode")
    parser.add_argument("--credentials-stdin", action="store_true", help="Read email/password JSON from private stdin instead of prompting")
    args = parser.parse_args()
    package = PACKAGES[args.owner]
    adb = ["adb", "-s", args.serial]
    user = args.user
    if user is None:
        user = int(subprocess.run(adb + ["shell", "am", "get-current-user"], stdin=subprocess.DEVNULL, check=True,
                                  capture_output=True, text=True).stdout.strip())
    if user < 0:
        raise ProvisionError("Android user must be nonnegative")
    run_as = adb + ["shell", "run-as", package, "--user", str(user)]
    # Require run-as before asking for credentials; release or inaccessible apps fail without signing in.
    check = subprocess.run(run_as + ["pwd"], stdin=subprocess.DEVNULL, capture_output=True, text=True)
    if check.returncode:
        raise ProvisionError("Debug app/run-as unavailable on the selected device; install the matching debug flavor")
    data_directory = check.stdout.strip()
    if not (data_directory.endswith("/" + package) and (f"/user/{user}/" in data_directory or
            f"/user_de/{user}/" in data_directory or user == 0 and data_directory == "/data/data/" + package)):
        raise ProvisionError("run-as did not select the requested Android user's app directory")
    if args.local:
        subprocess.run(adb + ["shell", "am", "force-stop", "--user", str(user), package], stdin=subprocess.DEVNULL, check=True, capture_output=True)
        subprocess.run(run_as + ["rm", "-f", FILE], stdin=subprocess.DEVNULL, check=True, capture_output=True)
        print(f"LOCAL selected for vehicle {args.owner}; reopen the app. Cloud outbox remains separate.")
        return
    parsed = urllib.parse.urlsplit(args.url or "")
    if (parsed.scheme != "https" or not parsed.hostname or "." not in parsed.hostname or parsed.username
            or parsed.password or parsed.port is not None or parsed.path not in ("", "/") or parsed.query or parsed.fragment):
        raise ProvisionError("--url must be an HTTPS project origin without credentials, port, path or query")
    origin = "https://" + parsed.hostname.lower()
    if not re.fullmatch(r"sb_publishable_[A-Za-z0-9_-]{8,}", args.publishable_key or ""):
        raise ProvisionError("Use a public sb_publishable_ key; secret/service-role and operator credentials are rejected")
    if args.credentials_stdin:
        raw = sys.stdin.read(16_385)
        credentials = json.loads(raw)
        email, password = credentials.get("email"), credentials.get("password")
        if len(raw) > 16_384 or not isinstance(email, str) or not isinstance(password, str) or not email or not password:
            raise ProvisionError("Private stdin must contain bounded email/password JSON")
        raw = credentials = None
    else:
        email = input(f"Pilot {args.owner} Auth email: ").strip()
        password = getpass.getpass("Auth password (not stored): ")
    document = provision_document(origin, args.publishable_key, args.owner, email, password)
    password = None
    subprocess.run(adb + ["shell", "am", "force-stop", "--user", str(user), package], stdin=subprocess.DEVNULL, check=True, capture_output=True)
    # Credential data travels only over stdin. It never appears in adb arguments, stdout or a host file.
    command = "umask 077; mkdir -p no_backup; cat > no_backup/safetystep-pilot.json.tmp && mv no_backup/safetystep-pilot.json.tmp no_backup/safetystep-pilot.json"
    payload = json.dumps(document).encode()
    subprocess.run(run_as + ["sh", "-c", "'" + command + "'"], input=payload, check=True, capture_output=True)
    after = subprocess.run(run_as + ["pwd"], stdin=subprocess.DEVNULL, check=True, capture_output=True, text=True).stdout.strip()
    size = subprocess.run(run_as + ["stat", "-c", "%s", FILE], stdin=subprocess.DEVNULL, check=True, capture_output=True, text=True).stdout.strip()
    if after != data_directory or int(size) != len(payload):
        raise ProvisionError("Private-file verification failed for the selected Android user")
    expires = datetime.datetime.fromtimestamp(document["expiresAt"] / 1000, datetime.timezone.utc).isoformat()
    print(f"CLOUD user validated and provisioned for vehicle {args.owner}; local session expires {expires}. Reopen the app.")


if __name__ == "__main__":
    try:
        main()
    except (ProvisionError, subprocess.CalledProcessError, OSError, ValueError) as error:
        print(str(error) if isinstance(error, ProvisionError) else "Provisioning failed; check HTTPS settings, adb and the selected debug app.", file=sys.stderr)
        sys.exit(1)
