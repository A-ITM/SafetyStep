#!/usr/bin/env python3
"""Record the visible dedicated browser window, then drive each verified story.

Keep the selected Chrome window visible for the capture. A hidden Chrome window
can stop painting even though the Android scenario continues successfully.
"""
import argparse
import json
from pathlib import Path
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--window-id", type=int, required=True)
parser.add_argument("--seconds", type=int, default=75)
args = parser.parse_args()
repo = Path(__file__).resolve().parents[2]
assert args.seconds >= 70

for collision, folder, filename in [
    ("REAR", "rear-final-visible", "SafetyStep-0.5-raw.mov"),
    ("FRONT", "front-final-visible", "SafetyStep-0.5-front-raw.mov"),
]:
    out = args.output.resolve() / folder
    out.mkdir(parents=True, exist_ok=True)
    raw = out / filename
    assert not raw.exists(), raw
    flow = ["python3", "tools/operator-console/story_flow.py"]
    options = ["--collision", collision, "--output", str(out)]
    with (out / "prepare.log").open("w") as log:
        subprocess.run(flow + ["prepare"] + options, cwd=repo, stdout=log,
                       stderr=subprocess.STDOUT, check=True)
    started = time.monotonic()
    with (out / "capture.log").open("w") as log:
        recording = subprocess.Popen([
            "/usr/sbin/screencapture", "-v", "-V", str(args.seconds), "-o",
            "-l", str(args.window_id), str(raw),
        ], stdout=log, stderr=subprocess.STDOUT)
        time.sleep(1.5)
        offset = time.monotonic() - started
        with (out / "flow.log").open("w") as flow_log:
            result = subprocess.run(flow + ["play"] + options, cwd=repo,
                                    stdout=flow_log, stderr=subprocess.STDOUT)
        ended = time.monotonic() - started
        recording.wait()
    metadata = {
        "flow_exit_code": result.returncode,
        "capture_exit_code": recording.returncode,
        "flow_start_seconds": offset,
        "flow_end_seconds": ended,
        "capture_window_id": args.window_id,
    }
    (out / "recording-metadata.json").write_text(json.dumps(metadata, indent=2))
    assert result.returncode == recording.returncode == 0, metadata
    assert ended < args.seconds - 2, "Capture is too short for this flow"
    print(collision, "recorded", round(ended, 2), "seconds; visual QA required", flush=True)
