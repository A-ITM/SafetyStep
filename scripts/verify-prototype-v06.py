#!/usr/bin/env python3
"""Foreground TEST integration checks on an explicitly selected Pleos emulator.

Requires installed debug APKs and the local 8787 server. Artifacts stay local.
The existing Virtual Console HTTP API supplies emulator-only vehicle signals.
"""
import argparse
import json
import pathlib
import shlex
import subprocess
import threading
import time
import urllib.parse
import urllib.request
import uuid


class Harness:
    A = "io.github.aitm.pleosapp"
    B = A + ".demo"

    def __init__(self, args):
        self.args = args
        self.out = args.output.resolve()
        self.out.mkdir(parents=True, exist_ok=True)
        self.run_id = "qa-" + uuid.uuid4().hex[:12]
        self.timeline = []
        self.stop = threading.Event()
        self.thread = None
        if not args.serial.startswith("emulator-"):
            raise ValueError("An explicit emulator serial is required")
        if self.adb("shell", "getprop", "ro.hardware").strip() not in (b"ranchu", b"goldfish"):
            raise ValueError("Physical vehicle/phone targets are forbidden")
        self.adb("forward", "tcp:8088", "tcp:8088")
        self.adb("reverse", "tcp:8787", "tcp:8787")
        self.http("http://127.0.0.1:8787/health")
        self.save("environment", {
            "runId": self.run_id, "serial": args.serial, "user": args.user,
            "bootId": self.adb("shell", "cat", "/proc/sys/kernel/random/boot_id").decode().strip(),
            "hostUtcMs": int(time.time() * 1000),
            "scope": "foreground TEST on one AVD boot; not real GPS or production Auth",
        })

    def adb(self, *parts):
        # adb shell reparses its arguments remotely; preserve spaces in injected speech.
        arguments = list(map(str, parts))
        if arguments and arguments[0] == "shell":
            arguments = ["shell", shlex.join(arguments[1:])]
        return subprocess.check_output(
            [str(self.args.adb), "-s", self.args.serial, *arguments],
            timeout=25,
        )

    @staticmethod
    def http(url, method="GET", data=None, owner=None):
        headers = {}
        if owner:
            headers["Authorization"] = "Bearer prototype-vehicle-" + owner
        if data is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(
            url, method=method, headers=headers,
            data=None if data is None else json.dumps(data).encode(),
        )
        with urllib.request.urlopen(request, timeout=8) as response:
            return json.load(response)

    def console(self, path, **values):
        return self.http("http://127.0.0.1:8088" + path + "?" + urllib.parse.urlencode(values), "PUT")

    def heartbeat(self, closing=False):
        suffix = "&closing=1" if closing else ""
        return self.http("http://127.0.0.1:8088/browser/session?client=" + self.run_id + suffix, "POST")

    def __enter__(self):
        def keep_alive():
            while not self.stop.wait(.7):
                try:
                    self.heartbeat()
                except OSError:
                    pass
        self.heartbeat()
        self.thread = threading.Thread(target=keep_alive, daemon=True)
        self.thread.start()
        time.sleep(1)
        self.console("/vehicle/controls", speed=0, accelerator=0, brake=0)
        self.console("/vehicle/gear", gear="Park")
        return self

    def __exit__(self, *_):
        try:
            self.console("/vehicle/controls", speed=0, accelerator=0, brake=0)
            self.console("/vehicle/gear", gear="Park")
        finally:
            self.stop.set()
            if self.thread:
                self.thread.join(timeout=2)
            try:
                self.heartbeat(closing=True)
            finally:
                self.save("timeline", self.timeline)

    def save(self, name, value):
        (self.out / (name + ".json")).write_text(json.dumps(value, ensure_ascii=False, indent=2))

    def op(self, package, command, **values):
        parts = ["shell", "am", "broadcast", "--user", self.args.user,
                 "-a", package + ".OPERATOR", "-p", package, "--es", "command", command]
        for key, value in values.items():
            if value is not None:
                parts += ["--es", key, str(value).lower() if isinstance(value, bool) else str(value)]
        return self.adb(*parts)

    def state(self, package):
        result = json.loads(self.adb("exec-out", "run-as", package, "--user", self.args.user,
                                     "cat", "files/operator-state.json"))
        result.pop("voiceText", None)
        self.timeline.append({"runId": self.run_id, "hostUtcMs": int(time.time() * 1000),
                              "package": package, "state": result})
        return result

    def wait(self, package, predicate, timeout=20):
        until = time.monotonic() + timeout
        latest = None
        while time.monotonic() < until:
            latest = self.state(package)
            if predicate(latest):
                return latest
            time.sleep(.25)
        raise AssertionError(latest)

    def launch(self, package):
        try:
            before = self.state(package).get("heartbeat", 0)
        except (subprocess.CalledProcessError, json.JSONDecodeError):
            before = 0
        self.adb("shell", "am", "start", "--user", self.args.user, "-n", package + "/io.github.aitm.pleosapp.MainActivity")
        return self.wait(package, lambda s: s.get("visible") is True and s.get("heartbeat", 0) > before)

    def position(self, package, meters, speed, *, road="test-highway", kind="MAINLINE", reset=False):
        self.op(package, "position", road=road, direction="east", roadKind=kind,
                meters=meters, speedMps=speed, observedAt=int(time.time() * 1000), reset=reset,
                runId=self.run_id)

    def feed(self, package, meters, speed, seconds, **kwargs):
        until = time.monotonic() + seconds
        while time.monotonic() < until:
            self.position(package, meters, speed, **kwargs)
            time.sleep(.5)

    def snapshot(self, name, package):
        self.save(name, self.state(package))
        (self.out / (name + ".png")).write_bytes(self.adb("exec-out", "screencap", "-p"))

    def query(self, meters=600, owner="B"):
        params = {"requestId": "poll-" + uuid.uuid4().hex, "road": "test-highway",
                  "direction": "east", "meters": meters, "observedAt": int(time.time() * 1000)}
        return self.http("http://127.0.0.1:8787/prototype/incidents?" + urllib.parse.urlencode(params), owner=owner)

    def metrics(self, package):
        raw = self.adb("exec-out", "run-as", package, "--user", self.args.user,
                       "cat", "files/prototype-metrics.jsonl").decode()
        return [json.loads(line) for line in raw.splitlines() if line.strip()]

    def sharing(self):
        self.launch(self.A)
        self.op(self.A, "reset")
        self.wait(self.A, lambda s: s.get("incidentId") is None)
        self.op(self.A, "consent", value=True)
        self.wait(self.A, lambda s: s.get("consent") is True)
        self.console("/vehicle/lights", feature="hazard", value="false")
        self.console("/vehicle/gear", gear="Drive")
        self.position(self.A, 1000, 0, reset=True)
        self.feed(self.A, 1000, 0, 7)
        source = self.wait(self.A, lambda s: s.get("stopReportId") and s.get("pending") == 0)
        event_id = source["stopReportId"]
        assert source.get("incidentId") is None, "Candidate sharing must not require a support session"
        event = next(e for e in self.query()["events"] if e["id"] == event_id)
        assert event["eventType"] == "STOPPED_VEHICLE" and event["status"] == "ACTIVE"
        assert not {"help", "response", "seats", "voiceText"}.intersection(event)
        self.snapshot("01-unanswered-stop", self.A)
        self.launch(self.B)
        self.op(self.B, "reset")
        self.op(self.B, "consent", value=False)
        self.op(self.B, "receiver_enabled", value=True)
        self.position(self.B, 600, 25, reset=True)
        received = self.wait(self.B, lambda s: event_id in s.get("warningIds", []))
        self.snapshot("02-same-event-received", self.B)
        self.launch(self.A)
        self.feed(self.A, 1010, 3, 4.5)
        self.wait(self.A, lambda s: s.get("stopReportStatus") == "RETRACTED")
        terminal = next(e for e in self.query()["events"] if e["id"] == event_id)
        assert terminal["status"] == "RETRACTED" and terminal["version"] == 2
        self.launch(self.B)
        self.position(self.B, 600, 25)
        self.wait(self.B, lambda s: s.get("receiverStatus") == "ACTIVE" and s.get("snapshotConfirmed") is True
                  and any(e.get("id") == event_id and e.get("version") == 2 and e.get("status") == "RETRACTED"
                          for e in s.get("receivedEvents", []))
                  and event_id not in s.get("warningIds", []))
        self.snapshot("03-retracted-warning", self.B)
        self.save("sharing-result", {"runId": self.run_id, "event": event, "received": received,
                                     "terminal": terminal, "passed": True})
        print("PASS unanswered stopped vehicle -> same event received -> departure retraction", flush=True)

    def receiver_voice(self):
        self.launch(self.A)
        self.op(self.A, "reset")
        self.op(self.A, "consent", value=False)
        self.op(self.A, "receiver_enabled", value=True)
        event_id = "voice-" + uuid.uuid4().hex
        observed = int(time.time() * 1000)
        event = {"id": event_id, "owner": "B", "version": 1, "eventType": "ACCIDENT",
                 "status": "ACTIVE", "source": "OPERATOR_EVENT", "simulation": True,
                 "observedAt": observed, "expiresAt": observed + 180_000,
                 "road": "test-highway", "direction": "east", "meters": 1000}
        self.http("http://127.0.0.1:8787/prototype/incidents", "POST", event, "B")
        self.position(self.A, 400, 25, reset=True)
        self.console("/vehicle/gear", gear="Drive")
        started = time.monotonic()
        crossed = None
        restricted_seen = False
        warning_seen = False
        restricted_warning_seen = False
        captured_warning = False
        while True:
            elapsed = time.monotonic() - started
            meters = 400 + elapsed * 25
            self.console("/vehicle/controls", speed=90, accelerator=0, brake=0)
            self.position(self.A, meters, 25)
            state = self.state(self.A)
            restricted_seen |= state.get("restricted") is True
            warning_seen |= event_id in state.get("warningIds", [])
            restricted_warning_seen |= state.get("restricted") is True and event_id in state.get("warningIds", [])
            if restricted_warning_seen and not captured_warning:
                self.snapshot("04-warning-during-driving", self.A)
                captured_warning = True
            if crossed is None and meters >= 1000:
                crossed = {"hostUtcMs": int(time.time() * 1000), "meters": meters}
            if meters > 1050:
                break
            time.sleep(.4)
        self.console("/vehicle/controls", speed=0, accelerator=0, brake=0)
        self.console("/vehicle/gear", gear="Park")
        records = [m for m in self.metrics(self.A) if m.get("incidentId") == event_id]
        completed = [m for m in records if m.get("metric") == "warning_voice_complete"]
        crossing = [m for m in records if m.get("metric") == "trajectory_crossing"]
        self.save("receiver-voice-result", {"runId": self.run_id, "event": event, "metrics": records,
                                           "hostCrossing": crossed, "restrictedSeen": restricted_seen,
                                           "warningSeen": warning_seen, "restrictedWarningSeen": restricted_warning_seen})
        self.snapshot("04-after-actual-crossing", self.A)
        assert restricted_warning_seen, "Warning and actual driving restriction must coexist in one sample"
        assert len(completed) == 1, "Need one actual Pleos TTS completion, not a request or demo success"
        assert crossing, "Actual progressing TEST trajectory must record crossing"
        y_ms = crossing[0]["elapsedMs"] - completed[0]["elapsedMs"]
        assert y_ms > 0, "Voice must finish before the incident position"
        print(f"PASS actual Pleos warning during driving; measured voice lead {y_ms / 1000:.3f}s (target 10s)", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", type=pathlib.Path, default=pathlib.Path.home() / "Library/Android/sdk/platform-tools/adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--user", default="10")
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--phase", choices=["sharing", "receiver-voice", "all"], default="sharing")
    args = parser.parse_args()
    with Harness(args) as harness:
        if args.phase in ("sharing", "all"):
            harness.sharing()
        if args.phase in ("receiver-voice", "all"):
            harness.receiver_voice()


if __name__ == "__main__":
    main()
