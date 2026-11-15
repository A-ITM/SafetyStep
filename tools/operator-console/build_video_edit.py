#!/usr/bin/env python3
"""Build local edit/SRT from successful rear/front recordings and actual telemetry."""
import argparse
import json
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("root", type=Path)
parser.add_argument("--rear-folder", default="final")
parser.add_argument("--front-folder", default="front-final")
parser.add_argument("--name", default="SafetyStep-0.5-rear-front")
args = parser.parse_args()
root = args.root.resolve()
segments, captions = [], []
cursor = 0.0


def srt_time(seconds):
    milliseconds = round(seconds * 1000)
    return f"{milliseconds // 3600000:02}:{milliseconds // 60000 % 60:02}:{milliseconds // 1000 % 60:02},{milliseconds % 1000:03}"


for folder, collision, raw, chapter in [
    (args.rear_folder, "REAR", "SafetyStep-0.5-raw.mov", "01  후방 충돌 · 트렁크 개방 보류"),
    (args.front_folder, "FRONT", "SafetyStep-0.5-front-raw.mov", "02  전방 충돌 · 조건 확인 후 자동 개방"),
]:
    source = root / folder
    metadata = json.loads((source / "recording-metadata.json").read_text())
    timeline = json.loads((source / "play-timeline.json").read_text())
    assert metadata["flow_exit_code"] == 0
    assert "app_touches=0" in (source / "flow.log").read_text()
    active = [x for x in timeline if x.get("collision") == collision and x.get("incidentId")]
    assert active
    if collision == "REAR":
        assert all(x["trunkAttempts"] == 0 for x in active)
    duration = round(metadata["flow_end_seconds"] - metadata["flow_start_seconds"] + 1.5, 3)
    segments.append({"path": str(source / raw), "start": metadata["flow_start_seconds"],
                     "duration": duration, "chapter": chapter})

    def first(predicate):
        # Allow the browser screenshot refresh to catch up to the observed state.
        return round(next(x["elapsed"] for x in active if predicate(x)) + 0.7, 3)

    hazard = first(lambda x: x["hazardLights"] is True)
    mobility = first(lambda x: x["screen"] == "MOBILITY")
    evacuation = first(lambda x: x["screen"] == "EVACUATION")
    door = first(lambda x: x["doorOpen"] is True)
    driver_out = first(lambda x: any(s["area"] == 1 and s["occupied"] is False for s in x["seats"]))
    passenger_out = first(lambda x: x["cabinPresence"] == "EMPTY")
    clear = first(lambda x: x["cabinClearConfirmed"] is True)
    clear = max(clear, passenger_out + 3)
    duration = max(duration, round(clear + 8, 3))
    segments[-1]["duration"] = duration
    impact = next(float(line.split()[0]) for line in (source / "flow.log").read_text().splitlines() if line.endswith(" impact")) + 0.3
    points = [
        (0, "사고 전에는 운전에 집중할 수 있도록 간결하게 표시합니다." if collision == "REAR" else "후방 개방 공간이 확인된 전방 충돌 상황입니다."),
        (impact, "후방 충돌 후 정차하면 자동 대응을 시작합니다." if collision == "REAR" else "전방 충돌 후에도 화면을 누르지 않고 대응을 시작합니다."),
        (hazard, "비상등을 켜고, 음성으로 탑승자의 응답을 확인합니다."),
        (mobility, "응답 여부와 스스로 움직일 수 있는지를 따로 확인합니다."),
    ]
    if collision == "REAR":
        points.append((evacuation, "후방 충돌에서는 트렁크를 열지 않고 안전한 이동을 안내합니다."))
        points.append((evacuation + 5, "비트박스 현황은 차량 상태에 따라 자동으로 갱신됩니다."))
    else:
        opened = first(lambda x: x["trunkOpen"] is True and x["trunkAttempts"] == 1)
        points.append((evacuation, "응답·정차·후방 공간 조건에 따라 트렁크를 자동으로 엽니다."))
        points.append((max(opened, evacuation + 4), "실제 열림을 확인한 뒤 트렁크 항목이 체크됩니다."))
        points.append((max(opened, evacuation + 4) + 4, "개방 버튼이나 다음 버튼을 누르지 않아도 진행됩니다."))
    points.extend([
        (door, "문 열림과 좌석 변화가 차량 화면과 앱에 함께 반영됩니다."),
        (driver_out, "운전자가 내려도 동승자가 남아 있으면 계속 표시합니다."),
        (passenger_out, "동승자까지 내리면 좌석별 이동 상태가 함께 갱신됩니다."),
        (clear, "좌석이 모두 비워지면 차량 복귀를 요구하지 않고 안내를 유지합니다."),
        (clear + 4, "안전한 곳에서 스마트폰으로 신고하도록 안내합니다. 도로 위험 정보는 유지합니다."),
    ])
    points.sort()
    for index, (start, text) in enumerate(points):
        end = points[index + 1][0] if index + 1 < len(points) else duration
        assert 0 <= start < end <= duration
        captions.append({"start": round(cursor + start, 3), "end": round(cursor + end, 3), "chapter": chapter, "text": text})
    cursor += duration

edit = {"segments": segments, "captions": captions, "output": str(root / (args.name + ".mp4"))}
(root / "video-edit.json").write_text(json.dumps(edit, ensure_ascii=False, indent=2))
(root / (args.name + ".srt")).write_text("\n\n".join(
    f"{i + 1}\n{srt_time(c['start'])} --> {srt_time(c['end'])}\n{c['chapter']}\n{c['text']}"
    for i, c in enumerate(captions)
) + "\n")
print(f"{cursor:.3f}s / {len(captions)} captions / {root / 'video-edit.json'}")
