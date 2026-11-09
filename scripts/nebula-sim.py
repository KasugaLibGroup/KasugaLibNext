#!/usr/bin/env python3
"""Run the Nebula Sim demo in an isolated copy of a local Minecraft world."""
import argparse
from pathlib import Path
import platform
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--world", type=Path, default=ROOT / "modules/modelling/run/client/saves/New World")
    parser.add_argument("--output", type=Path, default=ROOT / "build/nebula-sim")
    parser.add_argument("--test", action="store_true", help="Run the hidden real-client smoke and exit")
    parser.add_argument("--record-seconds", type=int, default=0, help="Also capture up to 30 seconds in test mode")
    default_window_scale = 2 if platform.system() == "Darwin" else 1
    parser.add_argument("--width", type=int, default=1280, help="Physical capture width (default: 1280)")
    parser.add_argument("--height", type=int, default=720, help="Physical capture height (default: 720)")
    parser.add_argument("--fps", type=int, default=30, help="Capture frame rate (default: 30)")
    parser.add_argument("--window-scale", type=int, default=default_window_scale,
                        help=f"Headless framebuffer scale (default: {default_window_scale})")
    parser.add_argument("--timeout-seconds", type=int, default=600,
                        help="Real-client smoke timeout (default: 600)")
    parser.add_argument("--allow-slow-render", action="store_true",
                        help="Do not fail the smoke run when a high-resolution render exceeds the GPU budget")
    args = parser.parse_args()
    if not 0 <= args.record_seconds <= 30:
        parser.error("--record-seconds must be in 0..30")
    if args.width <= 0 or args.height <= 0:
        parser.error("--width and --height must be positive")
    if not 1 <= args.fps <= 120:
        parser.error("--fps must be in 1..120")
    if args.window_scale <= 0:
        parser.error("--window-scale must be positive")
    if args.timeout_seconds < 60:
        parser.error("--timeout-seconds must be at least 60")
    if args.record_seconds and not args.test:
        parser.error("--record-seconds requires --test")
    if args.record_seconds and not shutil.which("ffmpeg"):
        parser.error("ffmpeg must be on PATH to record")
    if not (args.world / "level.dat").is_file():
        parser.error("--world must point to an existing test world's folder")
    try:
        import nbtlib
    except ImportError:
        parser.error("Install nbtlib==2.0.4 in your Python environment")

    output = args.output.resolve()
    client = output / "client"
    destination = client / "saves/nebula-sim"
    source = args.world.resolve()
    if source == destination or source in destination.parents or destination in source.parents:
        parser.error("Source world and isolated output must be separate directories")
    if not destination.exists():
        shutil.copytree(source, destination, ignore=shutil.ignore_patterns("session.lock"))
    level = nbtlib.load(destination / "level.dat")
    original_player = level["Data"].get("Player")
    original_state = None if original_player is None else {
        key: original_player[key].unpack() for key in ("Dimension", "Pos", "Rotation", "playerGameType", "abilities")
    }
    level["Data"]["LevelName"] = nbtlib.String("Nebula Sim (isolated copy)")
    level.save()
    logical_width = max(1, (args.width + args.window_scale - 1) // args.window_scale)
    logical_height = max(1, (args.height + args.window_scale - 1) // args.window_scale)
    options = {"version": "3955", "onboardAccessibility": "false", "fullscreen": "false",
               "enableVsync": "false", "overrideWidth": str(logical_width), "overrideHeight": str(logical_height),
               "renderDistance": "4", "simulationDistance": "5", "guiScale": "2",
               "maxFps": str(args.fps) if args.test else "260",
               "graphicsMode": "1", "soundCategory_master": "0.0", "pauseOnLostFocus": "false",
               "tutorialStep": "none", "showSubtitles": "false", "bobView": "false"}
    client.mkdir(parents=True, exist_ok=True)
    (client / "options.txt").write_text("".join(f"{k}:{v}\n" for k, v in options.items()))
    task = "runClientHeadless" if args.test else "runClient"
    command = [str(ROOT / "gradlew"), f":modules:modelling:{task}", "--console=plain",
               f"-PkasugaClientDirectory={client}", "-PkasugaQuickPlayWorld=nebula-sim",
               "-PkasugaNebulaAuto=true"]
    if args.test:
        command += ["-PkasugaTestNebula=true", f"-PkasugaNebulaOutput={output}",
                    f"-PkasugaNebulaRecordFrames={args.record_seconds * args.fps}",
                    f"-PkasugaNebulaCaptureFps={args.fps}", f"-PkasugaHeadlessFps={args.fps}",
                    f"-PkasugaNebulaEnforceGpuBudget={str(not args.allow_slow_render).lower()}",
                    f"-PkasugaNebulaTimeoutSeconds={args.timeout_seconds}"]
    subprocess.run(command, cwd=ROOT, check=True)
    if args.test:
        import json
        report = json.loads((output / "report.json").read_text())
        if not report.get("passed"):
            raise SystemExit(f"Nebula smoke failed: {report.get('failure')}")
        frames = int(report.get("videoFrames", 0))
        if frames:
            # The report spans the first recorded frame through the following close-frame boundary.
            elapsed = float(report["recordSimulatedSeconds"])
            expected = frames / args.fps
            if abs(elapsed - expected) > max(0.02, 1 / args.fps):
                raise SystemExit(f"Capture clock advanced incorrectly: {elapsed} seconds for {frames} frames at {args.fps} FPS")
            report["fixedStepCapture"] = True
            report["captureFps"] = args.fps
            report["captureWidth"] = int(report.get("nebula", {}).get("width", args.width))
            report["captureHeight"] = int(report.get("nebula", {}).get("height", args.height))
            report["requestedWidth"] = args.width
            report["requestedHeight"] = args.height
            report["windowScale"] = args.window_scale
        saved_player = nbtlib.load(destination / "level.dat")["Data"].get("Player")
        if original_state is not None:
            saved_state = {key: saved_player[key].unpack() for key in original_state}
            if saved_state != original_state:
                raise SystemExit(f"Player state did not restore: {original_state} -> {saved_state}")
            report["savedPlayerRestored"] = True
        (output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
        print(f"Nebula smoke passed; artifacts: {output}")


if __name__ == "__main__":
    main()
