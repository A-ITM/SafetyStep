#!/usr/bin/env python3
"""Generate explicit synthetic Korean STT fixtures with an installed macOS voice."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile
import wave

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--voice", default="Yuna")
parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parents[2] / "android/app/build/speech-validation/story")
args = parser.parse_args()
voices = subprocess.check_output(["/usr/bin/say", "-v", "?"], text=True)
if not any(line.startswith(args.voice + " ") and "ko_KR" in line for line in voices.splitlines()):
    parser.error("Choose an already installed Korean voice from: say -v '?'")
args.output.mkdir(parents=True, exist_ok=True)
manifest = {"source": "macOS synthetic voice", "voice": args.voice, "sample_rate": 16000, "channels": 1, "sample_width": 2, "pcm_tail_seconds": 1.5, "clips": {}}
for name, text in {"driver": "네, 들려요. 저는 운전자예요.", "mobility": "움직일 수 있어요."}.items():
    wav_path, pcm_path = args.output / f"{name}.wav", args.output / f"{name}.pcm"
    if wav_path.exists() or pcm_path.exists():
        parser.error(f"Output exists; choose a new output directory: {args.output}")
    with tempfile.TemporaryDirectory(prefix="safetystep-voice-") as tmp:
        aiff = Path(tmp) / "voice.aiff"
        subprocess.run(["/usr/bin/say", "-v", args.voice, "-o", str(aiff), text], check=True)
        subprocess.run(["/usr/bin/afconvert", "-f", "WAVE", "-d", "LEI16@16000", "-c", "1", str(aiff), str(wav_path)], check=True)
    with wave.open(str(wav_path), "rb") as wav:
        assert (wav.getframerate(), wav.getnchannels(), wav.getsampwidth()) == (16000, 1, 2)
        data = wav.readframes(wav.getnframes())
        assert data and any(data), "Empty synthetic audio"
        manifest["clips"][name] = {"text": text, "seconds": wav.getnframes() / 16000}
    pcm_path.write_bytes(data + b"\0" * 48000)
(args.output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
print(f"Created two synthetic 16 kHz mono PCM fixtures in {args.output}")
