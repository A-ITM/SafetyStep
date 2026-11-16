#!/usr/bin/env python3
"""Exercise the frozen TEST contract over actual loopback HTTP, without adb.

Start a fresh local server first. --base-url permits an isolated port; every run
uses unique IDs and leaves its small synthetic dataset in that server process.
Clock-advanced retry and 200-record capacity cases live in the Deno unit suite.
"""
import argparse
import json
from pathlib import Path
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


ROOT = Path(__file__).resolve().parents[1]
FIXTURE = json.loads((ROOT / "docs/contracts/fixtures/prototype-v06.json").read_text())
TTL_MS = 180_000


def now_ms():
    return time.time_ns() // 1_000_000


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8787")
    parser.add_argument("--output", type=Path, help="Optional local JSON results file")
    args = parser.parse_args()
    address = urllib.parse.urlsplit(args.base_url)
    if (address.scheme != "http" or address.hostname != "127.0.0.1"
            or address.username or address.password or address.path not in ("", "/")
            or address.query or address.fragment):
        parser.error("--base-url must be http://127.0.0.1[:port]")
    base_url = args.base_url.rstrip("/")
    run_id = "smoke-" + uuid.uuid4().hex[:12]
    checks = []
    request_count = 0

    def http(method, path, body=None, identity="A", status=200, code=None):
        nonlocal request_count
        headers = {"Content-Type": "application/json"}
        if identity is not None:
            headers["Authorization"] = ("Bearer prototype-test-admin" if identity == "ADMIN"
                                        else f"Bearer prototype-vehicle-{identity}")
        payload = None if body is None else json.dumps(body, allow_nan=False).encode()
        request = urllib.request.Request(base_url + path, data=payload, headers=headers, method=method)
        request_count += 1
        try:
            response = urllib.request.urlopen(request, timeout=3)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            assert response.status == status, f"{method} {path}: expected {status}, got {response.status}"
            assert response.headers.get("Cache-Control") == "no-store"
            assert response.headers.get_content_type() == "application/json"
            result = json.load(response)
        if code:
            assert set(result) == {"error"}
            assert set(result["error"]) == {"code", "message"}
            assert result["error"]["code"] == code and isinstance(result["error"]["message"], str)
        return result

    def passed(message):
        checks.append(message)
        print("PASS", message, flush=True)

    def create(suffix, **changes):
        observed = now_ms() - 5000
        return {**FIXTURE["create"], "id": f"{run_id}-{suffix}",
                "observedAt": observed, "expiresAt": observed + TTL_MS, **changes}

    def query(**changes):
        fields = {**FIXTURE["query"], "requestId": f"{run_id}-poll", "observedAt": now_ms(), **changes}
        return "/prototype/incidents?" + urllib.parse.urlencode({key: value for key, value in fields.items() if value is not None})

    def snapshot(**changes):
        path = query(**changes)
        result = http("GET", path, identity="B")
        fields = dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(path).query))
        assert set(result) == set(FIXTURE["snapshot"])
        assert result["requestId"] == fields["requestId"]
        assert result["epoch"] == epoch
        assert result["scopeKey"] == f"v1:{fields['road']}:{fields['direction']}"
        assert result["scopeVersion"] == 1 and result["complete"] is True
        assert result["sourceHealth"] == "TEST_ONLY" and isinstance(result["serverNow"], int)
        for event in result["events"]:
            assert set(event) == set(FIXTURE["create"])
            assert event["simulation"] is True
            assert event["road"] == fields["road"] and event["direction"] == fields["direction"]
            assert 0 < event["meters"] - float(fields["meters"]) <= 1000
        return result

    def ack(path, command, event, identity="A", duplicate=False, status=200):
        result = http("POST", path, command, identity=identity, status=status)
        assert result == {"accepted": True, "duplicate": duplicate, "event": event}
        return result

    health = http("GET", "/health", identity=None)
    assert health["status"] == "ok" and health["mode"] == "local-simulation"
    epoch = health["epoch"]
    assert isinstance(epoch, str) and epoch
    collection = "/prototype/incidents"
    stop = create("stop")
    ack(collection, stop, stop, status=201)
    ack(collection, stop, stop, duplicate=True)
    http("POST", collection, {**stop, "meters": 999}, status=409, code="VERSION_CONFLICT")
    http("POST", collection, {**stop, "eventType": "ACCIDENT"}, status=409, code="VERSION_CONFLICT")
    http("POST", collection, {**stop, "owner": "B"}, identity="B", status=403, code="FORBIDDEN")
    passed("Actual HTTP create, exact retry and immutable owner/type/position")

    boundary = create("boundary", meters=1600)
    far = create("far", meters=1600.1)
    at_point = create("at", meters=600)
    west = create("west", direction="west")
    for event in (boundary, far, at_point, west):
        ack(collection, event, event, status=201)
    own_ids = {event["id"] for event in (stop, boundary, far, at_point, west)}
    received = snapshot()
    assert {event["id"] for event in received["events"]} & own_ids == {stop["id"], boundary["id"]}
    assert snapshot(road="parallel-highway")["events"] == []
    assert {event["id"] for event in snapshot(direction="west")["events"]} & own_ids == {west["id"]}
    assert stop["id"] not in {event["id"] for event in snapshot(meters=1000)["events"]}
    assert stop["id"] in {event["id"] for event in snapshot(speedMps=None)["events"]}
    assert stop["id"] in {event["id"] for event in snapshot(speedMps=0)["events"]}
    passed("Complete scoped HTTP snapshots filter direction, parallel road, passed and 1000m boundary; unknown speed works")

    for path, status, code in ((collection, 400, "INVALID_REQUEST"),
                               (query() + "&road=test-highway", 400, "INVALID_REQUEST"),
                               (query(extra=1), 400, "INVALID_REQUEST"),
                               (query(meters="NaN"), 422, "INVALID_POSITION"),
                               (query(speedMps=""), 422, "INVALID_POSITION"),
                               (query(observedAt=now_ms() - 16000), 422, "INVALID_POSITION")):
        http("GET", path, identity="B", status=status, code=code)
    http("GET", query(), identity=None, status=401, code="TEST_IDENTITY_REQUIRED")
    http("GET", query(), identity="ADMIN", status=403, code="FORBIDDEN")
    http("DELETE", collection, status=405, code="METHOD_NOT_ALLOWED")
    http("POST", collection, {**create("invalid"), "help": "NEEDS_HELP"}, status=400, code="INVALID_REQUEST")
    http("POST", collection, create("array-type", eventType=["STOPPED_VEHICLE"]), status=400, code="INVALID_REQUEST")
    passed("Actual HTTP rejects malformed queries/payloads, missing identity and wrong methods with typed errors")

    observed = now_ms()
    retract = {**FIXTURE["retract"], "evidence": {**FIXTURE["retract"]["evidence"],
                                                "startedAt": observed - 3000, "observedAt": observed}}
    retract_path = f"{collection}/{stop['id']}/retract"
    http("POST", retract_path, retract, identity="B", status=403, code="FORBIDDEN")
    http("POST", retract_path, {**retract, "evidence": {**retract["evidence"], "speedMps": 1}}, status=400, code="INVALID_REQUEST")
    terminal = {**stop, "version": 2, "status": "RETRACTED"}
    ack(retract_path, retract, terminal)
    ack(retract_path, retract, terminal, duplicate=True)
    http("POST", retract_path, {**retract, "expectedVersion": 2}, status=409, code="VERSION_CONFLICT")
    http("POST", collection, terminal, status=409, code="VERSION_CONFLICT")
    result = snapshot()
    assert next(event for event in result["events"] if event["id"] == stop["id"]) == terminal
    assert next(event for event in result["events"] if event["id"] == boundary["id"])["status"] == "ACTIVE"
    assert stop["id"] not in {event["id"] for event in snapshot(meters=1000)["events"]}
    passed("Owner-only evidence retraction is idempotent, scoped and preserves unrelated hazards")

    accident = create("accident", eventType="ACCIDENT", source="MANUAL_TEST")
    ack(collection, accident, accident, status=201)
    resolve_path = f"/prototype/admin/incidents/{accident['id']}/resolve"
    http("POST", resolve_path, FIXTURE["resolve"], status=403, code="FORBIDDEN")
    http("POST", f"{collection}/{accident['id']}/retract", retract, status=403, code="FORBIDDEN")
    resolved = {**accident, "version": 2, "status": "RESOLVED"}
    ack(resolve_path, FIXTURE["resolve"], resolved, identity="ADMIN")
    ack(resolve_path, FIXTURE["resolve"], resolved, identity="ADMIN", duplicate=True)
    assert next(event for event in snapshot()["events"] if event["id"] == accident["id"]) == resolved
    http("POST", f"{collection}/{run_id}-missing/retract", retract, status=404, code="NOT_FOUND")
    passed("Only controlled TEST admin resolves accidents; exact ACK retry remains idempotent")

    observed = now_ms() - TTL_MS + 500
    expiring = create("ttl", observedAt=observed, expiresAt=observed + TTL_MS)
    ack(collection, expiring, expiring, status=201)
    time.sleep(max(0, expiring["expiresAt"] - now_ms() + 20) / 1000)
    ack(collection, expiring, expiring, duplicate=True)
    stale = snapshot()
    stale_event = next(event for event in stale["events"] if event["id"] == expiring["id"])
    assert stale_event == expiring and stale["serverNow"] >= stale_event["expiresAt"]
    http("POST", collection, {**expiring, "id": run_id + "-expired-new"}, status=410, code="EXPIRED_EVENT")
    passed("Real clock TTL keeps ACTIVE data stale, permits ACK retry and rejects a new expired event")

    summary = {"status": "passed", "mode": "HTTP_ONLY_TEST", "contractVersion": FIXTURE["contractVersion"],
               "runId": run_id, "baseUrl": base_url, "requestCount": request_count, "checks": checks}
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
