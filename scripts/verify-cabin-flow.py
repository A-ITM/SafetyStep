#!/usr/bin/env python3
"""Bounded, local-only cabin/voice integration QA on an explicitly selected Pleos AVD.

Requires the updated debug APKs and a loopback prototype server. Vehicle inputs are
emulator-only. Host microphone input is forced to zero before launch; no audio is
recorded, copied, or injected. Screenshots contain the actual Android app pane.
"""
import argparse
import importlib.util
import json
import pathlib
import re
import signal
import subprocess
import time


spec = importlib.util.spec_from_file_location(
    "legacy_qa", pathlib.Path(__file__).with_name("verify-prototype-v06.py"))
qa = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qa)

VOICE_ACTIVE = {"PREPARING", "SPEAKING", "LISTENING"}


class CabinHarness(qa.Harness):
    def __enter__(self):
        self.adb("emu", "avd", "hostmicoff")
        self.save("microphone", {
            "command": "adb emu avd hostmicoff",
            "help": self.adb("emu", "help", "avd", "hostmicoff").decode(),
            "hostAudioCaptured": False,
            "audioArtifactsCreated": False,
        })
        return super().__enter__()

    def seats(self, driver, passenger):
        for area, occupied in [("driver", driver), ("passenger", passenger),
                               ("rear_left", False), ("rear_right", False)]:
            self.console("/vehicle/seat", kind="occupied", area=area,
                         value=str(occupied).lower())
        return self.wait(self.A, lambda s:
                         any(x["area"] == 1 and x["occupied"] == driver for x in s["seats"]) and
                         any(x["area"] == 4 and x["occupied"] == passenger for x in s["seats"]))

    def session_metrics(self, incident):
        return [row for row in self.metrics(self.A)
                if row.get("supportSessionId") == incident or row.get("incidentId") == incident]

    def count(self, incident, name):
        return sum(row.get("metric") == name for row in self.session_metrics(incident))

    def wait_metric(self, incident, name, timeout=35):
        until = time.monotonic() + timeout
        while time.monotonic() < until:
            rows = [row for row in self.session_metrics(incident) if row.get("metric") == name]
            if rows:
                return rows
            self.state(self.A)
            time.sleep(.25)
        raise AssertionError({"missingMetric": name, "incident": incident,
                              "state": self.state(self.A), "metrics": self.session_metrics(incident)})

    def stable(self, predicate, seconds=4):
        until = time.monotonic() + seconds
        while time.monotonic() < until:
            state = self.state(self.A)
            assert predicate(state), state
            time.sleep(.3)
        return state

    def capture(self, name):
        self.snapshot(name, self.A)
        # Preserve the entire original framebuffer. Also provide an app-only crop
        # when the system window dump exposes its current, verified frame.
        windows = self.adb("shell", "dumpsys", "window", "windows").decode()
        self.save(name + "-window", {"windows": windows})
        blocks = re.split(r"\n\s+Window #\d+", windows)
        for block in blocks:
            if self.A + "/io.github.aitm.pleosapp.MainActivity" not in block:
                continue
            frame = re.search(r"mFrame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]", block)
            if frame is None:
                frame = re.search(r"Frames:.*?frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]", block)
            if frame:
                left, top, right, bottom = map(int, frame.groups())
                if right > left and bottom > top:
                    image = self.out / (name + ".png")
                    pane = self.out / (name + "-pane.png")
                    subprocess.run(["sips", "--cropToHeightWidth", str(bottom - top), str(right - left),
                                    "--cropOffset", str(top), str(left), str(image), "--out", str(pane)],
                                   check=True, capture_output=True, timeout=10)
                    self.save(name + "-pane", {"bounds": [left, top, right, bottom],
                                               "source": image.name, "image": pane.name})
                    break
        print("Captured", name, flush=True)

    def registered_accident(self, incident):
        stored = json.loads(self.args.server_data.read_text())
        event = next(row["event"] for row in stored["records"] if row["event"]["id"] == incident)
        assert event["status"] == "ACTIVE" and event["version"] == 1, event
        assert not {"help", "response", "seats", "voiceText"}.intersection(event), event
        return event

    def received_by_b(self, incident, name):
        # Reuse the actual Android API fix without restamping its receipt/fix time.
        location = self.state(self.A)["geoLocation"]
        assert location is not None
        snapshot = self.http("http://127.0.0.1:8787/prototype/accidents/nearby", "POST",
                             {"requestId": "cabin-" + incident, "location": location}, "B")
        event = next(row for row in snapshot["events"] if row["id"] == incident)
        assert event["owner"] == "A" and event["status"] == "ACTIVE" and event["version"] == 1
        self.save(name, snapshot)
        return event

    def start(self, *, voice=True):
        self.op(self.A, "reset")
        self.wait(self.A, lambda s: s["incidentId"] is None)
        self.console("/vehicle/controls", speed=0, accelerator=0, brake=0)
        self.console("/vehicle/gear", gear="Park")
        self.console("/vehicle/body", door_fl=0)
        self.seats(True, True)
        self.adb("emu", "geo", "fix", "127.0946", "37.4127")
        self.op(self.A, "geo_live")
        self.wait(self.A, lambda s: s.get("geoLocation") is not None and
                  s["geoLocation"]["origin"] == "GPS", timeout=30)
        self.op(self.A, "scenario", collision="REAR", voice="on" if voice else "off")
        state = self.wait(self.A, lambda s: s.get("incidentId") is not None and
                          s["eventType"] == "ACCIDENT" and s["supportQualified"] and
                          s["accidentSharing"] == "REGISTERED", timeout=30)
        return state["incidentId"]

    def quiet(self):
        return self.wait(self.A, lambda s: s["cabinClearConfirmed"] and
                         s["voice"] == "IDLE" and s["voiceInput"] == "NONE", timeout=15)

    def verify_ui(self):
        classes = [
            "io.github.aitm.pleosapp.prototype.SafetyStepUiTest",
            "io.github.aitm.pleosapp.ui.SafetyStepPresentationTest",
            "io.github.aitm.pleosapp.ui.AccidentLocationPresentationTest",
        ]
        results = []
        for flavor, package in [("demo", self.B), ("pleos", self.A)]:
            total = 0
            for test_class in classes:
                # Separate instrumentation processes keep an earlier Activity's
                # lifecycle/Compose idling resources from leaking into another class.
                self.adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
                self.adb("shell", "wm", "dismiss-keyguard")
                time.sleep(1)
                started = time.monotonic()
                command = [str(self.args.adb), "-s", self.args.serial, "shell",
                           "am", "instrument", "-w", "--user", str(self.args.user),
                           "-e", "class", test_class,
                           package + ".test/androidx.test.runner.AndroidJUnitRunner"]
                completed = subprocess.run(command, capture_output=True, text=True, timeout=120)
                log = completed.stdout + completed.stderr
                name = "ui-" + flavor + "-" + test_class.rsplit(".", 1)[-1]
                (self.out / (name + ".log")).write_text(log)
                match = re.search(r"OK \((\d+) tests?\)", log)
                assert completed.returncode == 0 and match is not None and "FAILURES!!!" not in log, log
                total += int(match.group(1))
                result = {"flavor": flavor, "tests": int(match.group(1)), "passed": True,
                          "seconds": round(time.monotonic() - started, 2), "class": test_class,
                          "command": command}
                results.append(result)
                self.save("ui-result", results)
            print("PASS", flavor, total, "UI tests", flush=True)

    def verify_flow(self):
        self.adb("shell", "am", "force-stop", "--user", self.args.user, self.B)
        self.launch(self.A)
        self.op(self.A, "online", value=True)
        self.op(self.A, "consent", value=True)
        self.op(self.A, "receiver_enabled", value=False)
        self.op(self.A, "theme", value="DARK")
        for permission in ["ACCESS_COARSE_LOCATION", "ACCESS_FINE_LOCATION"]:
            self.adb("shell", "pm", "grant", "--user", self.args.user, self.A,
                     "android.permission." + permission)
        self.adb("shell", "am", "start", "--user", self.args.user, "-n",
                 "ai.umos.maps.android.navigation.app/.MainActivity")
        time.sleep(.7)
        self.adb("shell", "input", "swipe", 2546, 720, 1706, 720, 2000)
        time.sleep(.7)
        telecom_before = self.adb("shell", "dumpsys", "telecom").decode()
        self.save("telecom-before", {"dump": telecom_before})

        incident = self.start()
        initial = self.wait_metric(incident, "support_voice_complete")
        listening = self.wait(self.A, lambda s: s["voice"] in {"LISTENING", "UNAVAILABLE"}, timeout=25)
        assert listening["voice"] == "LISTENING", listening
        assert self.count(incident, "exception_listen_start") >= 1
        self.capture("01-exit-guidance-then-listen")
        self.seats(False, True)
        # A fresh driver-empty reading must not interrupt an underway listen.
        current = self.state(self.A)
        if current["voice"] == "LISTENING":
            assert self.count(incident, "passenger_reminder_start") == 0
        self.capture("02-passenger-remains-listening")
        reminder = self.wait_metric(incident, "passenger_reminder_start", timeout=30)
        self.wait_metric(incident, "passenger_reminder_complete", timeout=30)
        assert self.count(incident, "exception_listen_interrupted") == 0
        ended_listening = [row for row in self.session_metrics(incident)
                          if row.get("metric") in {"exception_no_speech", "exception_listen_failed"}]
        assert ended_listening and ended_listening[0]["elapsedMs"] <= reminder[0]["elapsedMs"]
        assert self.count(incident, "passenger_reminder_start") == 1
        assert self.count(incident, "passenger_reminder_complete") == 1
        self.capture("03-passenger-reminder-completed")
        self.stable(lambda s: s["incidentId"] == incident, seconds=3)
        assert self.count(incident, "passenger_reminder_start") == 1

        self.seats(False, False)
        self.quiet()
        self.capture("04-empty-quiet")
        self.stable(lambda s: s["cabinClearConfirmed"] and s["voice"] == "IDLE" and
                    s["voiceInput"] == "NONE", seconds=5)
        event = self.registered_accident(incident)
        assert self.received_by_b(incident, "nearby-after-quiet") == event
        before_restart = self.session_metrics(incident)
        self.save("metrics-before-relaunch", before_restart)
        self.adb("shell", "am", "force-stop", "--user", self.args.user, self.A)
        self.launch(self.A)
        self.quiet()
        self.stable(lambda s: s["incidentId"] == incident and s["cabinClearConfirmed"] and
                    s["voice"] == "IDLE" and s["voiceInput"] == "NONE", seconds=4)
        assert self.registered_accident(incident) == event
        assert self.received_by_b(incident, "nearby-after-relaunch") == event
        assert self.session_metrics(incident) == before_restart
        self.save("metrics-after-relaunch", self.session_metrics(incident))
        self.capture("05-quiet-after-relaunch")
        # Fresh positive occupancy is the evidence required to leave quiet mode.
        self.seats(True, True)
        self.wait(self.A, lambda s: s["incidentId"] == incident and not s["cabinClearConfirmed"])
        assert self.count(incident, "passenger_reminder_start") == 1
        self.capture("06-fresh-return-leaves-quiet")

        help_incident = self.start(voice=False)
        self.op(self.A, "transcript", text="움직이기 어려워요")
        self.wait(self.A, lambda s: s.get("help") == "NEEDS_HELP")
        self.seats(False, False)
        self.quiet()
        self.stable(lambda s: s["help"] == "NEEDS_HELP" and s["cabinClearConfirmed"] and
                    s["voice"] == "IDLE" and s["voiceInput"] == "NONE", seconds=3)
        assert self.count(help_incident, "passenger_reminder_start") == 0
        self.registered_accident(help_incident)
        self.capture("07-help-record-empty-passive")

        # A reminder that is preparing or speaking must stop when no passenger remains.
        cancelled_incident = self.start()
        self.wait_metric(cancelled_incident, "support_voice_start")
        self.seats(False, True)
        self.wait_metric(cancelled_incident, "passenger_reminder_start", timeout=30)
        self.seats(False, False)
        self.quiet()
        assert self.count(cancelled_incident, "passenger_reminder_complete") == 0
        assert self.count(cancelled_incident, "passenger_reminder_interrupted") == 1
        self.capture("08-empty-cancels-reminder")
        self.registered_accident(cancelled_incident)

        telecom_after = self.adb("shell", "dumpsys", "telecom").decode()
        self.save("telecom-after", {"dump": telecom_after})
        calls = re.search(r"\n  mCalls:[ \t]*\n(.*?)  mCallAudioManager:", telecom_after, re.S)
        assert calls is not None and not calls.group(1).strip(), "Unexpected Telecom call"
        result = {"passed": True, "cabinChecksPassed": True,
                  "listeningVerified": True,
                  "eventId": incident, "helpEventId": help_incident,
                  "cancelledReminderEventId": cancelled_incident,
                  "initialVoiceComplete": initial, "firstListening": listening,
                  "passengerReminderStart": reminder, "registeredAccident": event,
                  "hostMicrophone": "disabled: avd hostmicoff", "audioCaptured": False,
                  "unknownSeatScope": "JVM policy test; console cannot supply unknown Boolean",
                  "scope": "Actual Pleos TTS/STT callbacks on one AVD; silent input only; no real vehicle, person, call or emergency dispatch."}
        self.save("result", result)
        for name, session in [("main", incident), ("help", help_incident), ("cancelled", cancelled_incident)]:
            self.save("metrics-" + name, self.session_metrics(session))
        print("PASS cabin flow", incident, flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--user", type=int, default=10)
    parser.add_argument("--adb", type=pathlib.Path,
                        default=pathlib.Path.home() / "Library/Android/sdk/platform-tools/adb")
    parser.add_argument("--output", type=pathlib.Path, default=pathlib.Path("output/cabin-flow-qa/screens"))
    parser.add_argument("--server-data", type=pathlib.Path, default=pathlib.Path("output/cabin-flow-qa/accidents.json"))
    parser.add_argument("--timeout", type=int, default=240)
    parser.add_argument("--ui-only", action="store_true",
                        help="Run the three relevant instrumentation classes in both installed flavors")
    args = parser.parse_args()
    if not 60 <= args.timeout <= 600:
        parser.error("--timeout must be between 60 and 600 seconds")
    signal.signal(signal.SIGALRM, lambda *_: (_ for _ in ()).throw(TimeoutError("Cabin QA deadline exceeded")))
    signal.alarm(args.timeout)
    try:
        with CabinHarness(args) as harness:
            try:
                if args.ui_only:
                    harness.verify_ui()
                else:
                    harness.verify_flow()
            except BaseException as error:
                harness.save("failure", {"type": type(error).__name__, "message": str(error)})
                raise
    finally:
        signal.alarm(0)


if __name__ == "__main__":
    main()
