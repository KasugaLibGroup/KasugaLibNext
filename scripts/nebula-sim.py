#!/usr/bin/env python3
"""Run the Nebula Sim demo in an isolated copy of a local Minecraft world."""
import argparse
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--world", type=Path, default=ROOT / "modules/modelling/run/client/saves/New World")
    parser.add_argument("--output", type=Path, default=ROOT / "build/nebula-sim")
    parser.add_argument("--test", action="store_true", help="Run the hidden real-client smoke and exit")
    parser.add_argument("--record-seconds", type=int, default=0, help="Also capture up to 30 seconds in test mode")
    args = parser.parse_args()
    if not 0 <= args.record_seconds <= 30:
        parser.error("--record-seconds must be in 0..30")
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
    options = {"version": "3955", "onboardAccessibility": "false", "fullscreen": "false",
               "enableVsync": "false", "overrideWidth": "640", "overrideHeight": "360",
               "renderDistance": "4", "simulationDistance": "5", "guiScale": "2", "maxFps": "30",
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
                    f"-PkasugaNebulaRecordFrames={args.record_seconds * 30}", "-PkasugaHeadlessFps=30"]
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
            if abs(elapsed - frames / 30) > 0.02:
                raise SystemExit(f"Capture clock advanced incorrectly: {elapsed} seconds for {frames} frames")
            report["fixedStepCapture"] = True
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
