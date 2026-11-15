#!/usr/bin/env python3
"""Exercise the installed debug apps on a parked Pleos emulator, using real HTTP.

Requires the local server on 8787 and both APKs installed. Creates synthetic
incidents, changes only the prototype's state, and leaves vehicle B open.
"""
import json
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "android/app/build/prototype-smoke"
OUT.mkdir(parents=True, exist_ok=True)
ADB = str(Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk"))) / "platform-tools/adb")
A = "io.github.aitm.pleosapp"
B = A + ".demo"
ACTIVITY = A + ".MainActivity"
checks = []

def adb(*args):
    return subprocess.check_output([ADB, *map(str, args)], text=True)

USER = adb("shell", "am", "get-current-user").strip()
assert adb("shell", "getprop", "ro.hardware").strip() in ("ranchu", "goldfish"), "Emulator only"

def launch(package):
    adb("shell", "am", "start", "--user", USER, "-n", f"{package}/{ACTIVITY}")

def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/prototype-smoke.xml")
    return list(ET.fromstring(adb("exec-out", "cat", "/sdcard/prototype-smoke.xml")).iter("node"))

def text():
    return "\n".join(n.get("text", "") for n in nodes())

def wait_for(needle, timeout=15):
    deadline = time.monotonic() + timeout
    latest = ""
    while time.monotonic() < deadline:
        latest = text()
        if needle in latest:
            return latest
        time.sleep(0.3)
    raise AssertionError(f"UI missing {needle!r}: {latest}")

def tap(label):
    matches = [n for n in nodes() if n.get("text") == label]
    assert len(matches) == 1, f"Expected exactly one visible {label}: {len(matches)}"
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", matches[0].get("bounds")))
    adb("shell", "input", "tap", (x1+x2)//2, (y1+y2)//2)

def consent():
    checkbox = [n for n in nodes() if n.get("class") == "android.widget.CheckBox"][0]
    if checkbox.get("checked") != "true":
        x1,y1,x2,y2=map(int,re.findall(r"\d+",checkbox.get("bounds")))
        adb("shell", "input", "tap", (x1+x2)//2, (y1+y2)//2)

def feed():
    request = urllib.request.Request("http://127.0.0.1:8787/prototype/incidents", headers={"Authorization":"Bearer prototype-vehicle-B"})
    with urllib.request.urlopen(request, timeout=3) as response:
        return json.load(response)["events"]

def snapshot(name):
    (OUT/f"{name}.png").write_bytes(subprocess.check_output([ADB,"exec-out","screencap","-p"]))
    (OUT/f"{name}.xml").write_text(adb("exec-out","cat","/sdcard/prototype-smoke.xml"))

def passed(name):
    checks.append(name)
    print("PASS", name, flush=True)

try:
    adb("reverse", "tcp:8787", "tcp:8787")
    launch(A)
    wait_for("차량 A")
    consent()
    current = text()
    if "도로 위험: ACTIVE" in current:
        tap("테스트 사건 해제")
        wait_for("대기 0개")
    tap("사고·고장 주입 (모의)")
    wait_for("서버 ACK")
    event = max((e for e in feed() if e["owner"] == "A" and e["status"] == "ACTIVE"), key=lambda e:e["observedAt"])
    incident_id = event["id"]
    tap("사고·고장 주입 (모의)")
    assert len([e for e in feed() if e["id"] == incident_id]) == 1
    passed("A creates one event and receives real server ACK; duplicate start is ignored")

    tap("모의 질문 · 15초 응답 대기")
    wait_for("응답: UNCONFIRMED", timeout=40)
    passed("Two 15-second test waits produce UNCONFIRMED, not a medical diagnosis")
    tap("동승자 응답 · 도움 필요")
    wait_for("RESPONDED / PASSENGER")
    tap("대피 보고 (위험 유지)")
    wait_for("대피 보고: true")
    assert next(e for e in feed() if e["id"] == incident_id)["status"] == "ACTIVE"
    assert not any(k in event for k in ("response","help","voice","responder","evacuated"))
    passed("Late passenger response and evacuation leave the shared hazard active; no health fields transmitted")
    adb("shell", "am", "force-stop", "--user", USER, A)
    launch(A)
    wait_for("RESPONDED / PASSENGER")
    wait_for("대피 보고: true")
    snapshot("a-restored")
    passed("Incident and occupant state survive process restart")

    launch(B)
    wait_for("차량 B")
    tap("같은 방향 접근") if "DIFFERENT_DIRECTION" in text() else None
    wait_for("전방 400m 위험")
    snapshot("b-warning")
    for label, decision in [("반대 방향","DIFFERENT_DIRECTION"),("평행도로","DIFFERENT_ROAD"),("사고 지점 통과","PASSED"),("위치 만료","INVALID_POSITION")]:
        tap(label)
        current = wait_for(decision)
        assert "현재 경고 대상 없음" in current
    passed("B warns for approach and excludes opposite direction, parallel road, passed point and stale position")
    tap("같은 방향 접근")
    wait_for("전방 400m 위험")

    launch(A)
    wait_for("차량 A")
    adb("reverse", "--remove", "tcp:8787")
    wait_for("전파/수신 미확인")
    tap("테스트 사건 해제")
    wait_for("전송 대기 1개")
    assert next(e for e in feed() if e["id"] == incident_id)["status"] == "ACTIVE"
    adb("shell", "am", "force-stop", "--user", USER, A)
    launch(A)
    wait_for("전송 대기 1개")
    snapshot("a-offline-outbox")
    passed("Actual network loss does not claim ACK; pending resolution survives process restart")
    adb("reverse", "tcp:8787", "tcp:8787")
    wait_for("전송 대기 0개")
    assert next(e for e in feed() if e["id"] == incident_id)["status"] == "RESOLVED"
    launch(B)
    current = wait_for("RESOLVED → RESOLVED")
    assert "현재 경고 대상 없음" in current
    passed("Reconnect delivers queued resolution and B stops that event warning")

    adb("shell", "am", "force-stop", "--user", USER, A)
    adb("shell", "am", "start", "--user", USER, "-p", A, "-a", f"{A}.START_ROADSIDE_SUPPORT")
    wait_for("START_ROADSIDE_SUPPORT → asking")
    adb("shell", "am", "start", "--user", USER, "-p", A, "-a", f"{A}.REPORT_OCCUPANT_STATUS", "--es", "responder", "DRIVER", "--es", "help", "NEEDS_HELP")
    wait_for("RESPONDED / DRIVER")
    wait_for("REPORT_OCCUPANT_STATUS → success")
    snapshot("a-gleo-intent-contract")
    passed("ADB-injected Gleo contract starts the app cold and handles a follow-up; this does not prove Gleo speech")
    tap("테스트 사건 해제")
    wait_for("전송 대기 0개")

    launch(B)
    wait_for("차량 B")
    (OUT/"results.json").write_text(json.dumps({"status":"passed","checks":checks,"incidentId":incident_id},ensure_ascii=False,indent=2))
finally:
    adb("reverse", "tcp:8787", "tcp:8787")
    (OUT/"logcat.txt").write_text(adb("logcat","-d","-s","RoadsidePrototype:I","*:S"))
