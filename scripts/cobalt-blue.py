#!/usr/bin/env python3
"""Convert the Cobalt blue source track into the bundled positional Ogg asset."""

import argparse
import json
from pathlib import Path
import shutil
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT = ROOT / "modules/modelling/src/contentTesting/resources/assets/kasuga_demo/sounds/cobalt/instant_blue.ogg"
TRACK = ROOT / "modules/modelling/src/contentTesting/resources/assets/kasuga_demo/cobalt_blue/track.json"
RESOURCE_NAME = "kasuga_demo:sounds/cobalt/instant_blue.ogg"


def run(*command):
    print("+", " ".join(map(str, command)), flush=True)
    return subprocess.run(list(map(str, command)), cwd=ROOT, check=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)


def probe(ffprobe: Path, path: Path, *entries):
    result = run(ffprobe, "-v", "error", "-show_entries", ":".join(entries),
                 "-of", "json", path)
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError as failure:
        raise RuntimeError(f"ffprobe returned invalid JSON for {path}: {result.stdout!r}") from failure


def number(value, label):
    try:
        parsed = float(value)
    except (TypeError, ValueError) as failure:
        raise RuntimeError(f"{label} is not numeric: {value!r}") from failure
    if not parsed > 0:
        raise RuntimeError(f"{label} must be positive: {parsed}")
    return parsed


def source_metadata(ffprobe: Path, path: Path):
    data = probe(ffprobe, path, "stream=index,codec_type,duration", "format=duration")
    audio = next((stream for stream in data.get("streams", []) if stream.get("codec_type") == "audio"), None)
    if audio is None:
        raise RuntimeError(f"Source has no audio stream: {path}")
    duration = audio.get("duration") or data.get("format", {}).get("duration")
    return number(duration, "source duration")


def validate_output(ffprobe: Path, path: Path, expected_duration: float):
    if not path.is_file() or path.stat().st_size == 0:
        raise RuntimeError(f"Audio output was not created: {path}")
    data = probe(ffprobe, path, "stream=index,codec_type,codec_name,channels,duration", "format=format_name,duration")
    formats = set(str(data.get("format", {}).get("format_name", "")).split(","))
    if "ogg" not in formats:
        raise RuntimeError(f"Output is not an Ogg container: {formats}")
    streams = data.get("streams", [])
    if len(streams) != 1:
        raise RuntimeError(f"Expected one output stream, found {len(streams)}")
    stream = streams[0]
    if stream.get("codec_type") != "audio" or stream.get("codec_name") != "vorbis":
        raise RuntimeError(f"Output is not Ogg Vorbis audio: {stream}")
    if int(stream.get("channels", 0)) != 1:
        raise RuntimeError(f"Output must be mono, found {stream.get('channels')} channels")
    actual_duration = number(stream.get("duration") or data.get("format", {}).get("duration"),
                             "output duration")
    if abs(actual_duration - expected_duration) > 0.5:
        raise RuntimeError(f"Output duration changed unexpectedly: {actual_duration:.6f}s vs {expected_duration:.6f}s")
    return actual_duration


def update_track(duration: float):
    if not TRACK.is_file():
        raise RuntimeError(f"Cobalt blue track metadata is missing: {TRACK}")
    metadata = json.loads(TRACK.read_text(encoding="utf-8"))
    metadata["duration_seconds"] = round(duration, 6)
    metadata["audio_file"] = RESOURCE_NAME
    TRACK.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def find_ffmpeg():
    candidates = []
    selected = shutil.which("ffmpeg")
    if selected:
        candidates.append(Path(selected))
    for prefix in (Path("/opt/homebrew/opt"), Path("/usr/local/opt")):
        candidates.extend(sorted(prefix.glob("ffmpeg*/bin/ffmpeg")))
    seen = set()
    for candidate in candidates:
        candidate = candidate.resolve()
        if candidate in seen or not candidate.is_file():
            continue
        seen.add(candidate)
        result = subprocess.run([candidate, "-hide_banner", "-encoders"],
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        if result.returncode == 0 and " libvorbis " in result.stdout:
            sibling = candidate.with_name("ffprobe")
            ffprobe = sibling if sibling.is_file() else Path(shutil.which("ffprobe") or "")
            if ffprobe.is_file():
                return candidate, ffprobe
    raise RuntimeError("A libvorbis-enabled FFmpeg and ffprobe are required to create mono positional audio")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--music", type=Path, required=True, help="Source FLAC or other ffmpeg-readable audio file")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT,
                        help=f"Ogg output path (default: {DEFAULT_OUTPUT})")
    args = parser.parse_args()

    try:
        ffmpeg, ffprobe = find_ffmpeg()
    except RuntimeError as failure:
        parser.error(str(failure))
    source = args.music.expanduser().resolve()
    output = args.output.expanduser().resolve()
    if not source.is_file():
        parser.error(f"--music does not point to a file: {source}")
    if source == output:
        parser.error("--music and --output must be different files")

    expected_duration = source_metadata(ffprobe, source)
    output.parent.mkdir(parents=True, exist_ok=True)
    run(ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-i", source,
        "-map", "0:a:0", "-vn", "-ac", "1", "-c:a", "libvorbis", "-q:a", "5", "-f", "ogg", output)
    actual_duration = validate_output(ffprobe, output, expected_duration)
    update_track(expected_duration)
    print(f"Cobalt blue audio ready: {output} ({actual_duration:.6f}s, mono Ogg Vorbis)", flush=True)


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, subprocess.CalledProcessError) as failure:
        print(f"cobalt-blue.py: {failure}", file=sys.stderr)
        raise SystemExit(1)
