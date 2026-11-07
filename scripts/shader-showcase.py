#!/usr/bin/env python3
"""Reproduce the real three-language compiler + Minecraft + MP4 demonstration."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def run(*command, env=None):
    print("+", " ".join(map(str, command)), flush=True)
    subprocess.run(list(map(str, command)), cwd=ROOT, env=env, check=True)


def compare_exports(output):
    reference = output / "javascript"
    expected = {"program.ir.txt", "assets/showcase/shaders/core/prism_scan.vsh",
                "assets/showcase/shaders/core/prism_scan.fsh", "assets/showcase/shaders/core/prism_scan.json"}
    manifest = {}
    for language in ("javascript", "kotlin", "python"):
        directory = output / language
        actual = {str(p.relative_to(directory)) for p in directory.rglob("*") if p.is_file()}
        if actual != expected:
            raise RuntimeError(f"Unexpected {language} outputs: {actual ^ expected}")
        for name in sorted(expected):
            data = (directory / name).read_bytes()
            if data != (reference / name).read_bytes():
                raise RuntimeError(f"{language} output differs: {name}")
            manifest[name] = {"sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)}
    result = {"passed": True, "languages": ["javascript", "kotlin", "python"],
              "comparison": "byte-identical typed IR and all three Minecraft resources",
              "pythonHost": "trusted local JPype; not a built-in Python engine", "files": manifest}
    (output / "language-report.json").write_text(json.dumps(result, indent=2) + "\n")
    return result


def prepare_world(source, output):
    import nbtlib

    source = source.resolve()
    if not (source / "level.dat").is_file():
        raise ValueError(f"Expected an existing Minecraft world: {source}")
    client = output / "client"
    destination = client / "saves" / "showcase-world"
    if source == destination or source in destination.parents:
        raise ValueError("The source world must be outside the isolated client directory")
    if not destination.exists():
        shutil.copytree(source, destination, ignore=shutil.ignore_patterns("session.lock"))
    level = nbtlib.load(destination / "level.dat")
    data = level["Data"]
    data["LevelName"] = nbtlib.String("Shader DSL Showcase (isolated copy)")
    data["DayTime"] = nbtlib.Long(6000)
    data["raining"] = nbtlib.Byte(0)
    data["thundering"] = nbtlib.Byte(0)
    data["clearWeatherTime"] = nbtlib.Int(1000000)
    data["GameRules"]["doDaylightCycle"] = nbtlib.String("false")
    data["GameRules"]["doWeatherCycle"] = nbtlib.String("false")
    player = data.get("Player")
    if player is not None:
        player["Pos"][1] = nbtlib.Double(max(84, float(player["Pos"][1])))
        if "abilities" in player:
            player["abilities"]["flying"] = nbtlib.Byte(1)
            player["abilities"]["mayfly"] = nbtlib.Byte(1)
        player["Motion"] = nbtlib.List[nbtlib.Double]([0, 0, 0])
    level.save()
    # These are logical window pixels; a Retina framebuffer may be twice as large.
    options = {"version": "3955", "onboardAccessibility": "false", "fullscreen": "false",
               "enableVsync": "false", "overrideWidth": "640", "overrideHeight": "360",
               "renderDistance": "8", "simulationDistance": "5", "guiScale": "2",
               "maxFps": "120", "gamma": "0.6", "graphicsMode": "1", "ao": "true",
               "soundCategory_master": "0.0", "pauseOnLostFocus": "false",
               "tutorialStep": "none", "showSubtitles": "false"}
    (client / "options.txt").write_text("".join(f"{k}:{v}\n" for k, v in options.items()))
    return client


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--world", type=Path, help="Existing test world; copied before use")
    parser.add_argument("--output", type=Path, default=ROOT / "build/shader-showcase")
    parser.add_argument("--java-home", type=Path, help="JDK 21+ matching the Python process architecture")
    parser.add_argument("--fps", type=int, default=30)
    parser.add_argument("--seconds", type=int, default=24)
    parser.add_argument("--skip-compile", action="store_true")
    parser.add_argument("--skip-capture", action="store_true")
    parser.add_argument("--skip-compose", action="store_true")
    args = parser.parse_args()
    if not 1 <= args.fps <= 120 or not 16 <= args.seconds <= 120:
        parser.error("Use 1..120 FPS and 16..120 seconds")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    for tool in ("ffmpeg", "ffprobe"):
        if not shutil.which(tool):
            parser.error(f"Install {tool} and add it to PATH")
    env = os.environ.copy()
    if args.java_home:
        env["JAVA_HOME"] = str(args.java_home.resolve())
        env["PATH"] = str(args.java_home.resolve() / "bin") + os.pathsep + env.get("PATH", "")
    if not args.skip_compile:
        if not shutil.which("kotlinc"):
            parser.error("Install kotlinc to execute the Kotlin example")
        run("./gradlew", ":modules:shader-graal:exportShowcase", f"-PkasugaShowcaseOutput={output}", "--console=plain", env=env)
        classpath = (output / "jvm-classpath.txt").read_text()
        run("kotlinc", "-script", ROOT / "examples/shader-dsl/prism-scan.main.kts", "-classpath", classpath, "--", output, env=env)
        run(sys.executable, ROOT / "examples/shader-dsl/prism-scan.py", output, env=env)
    compare_exports(output)
    if not args.skip_capture:
        if args.world is None:
            parser.error("--world is required for capture; the original world is preserved")
        client = prepare_world(args.world, output)
        (output / "capture-report.json").unlink(missing_ok=True)
        run(ROOT / "scripts/run-headless-client.sh", "-PkasugaShaderShowcase=true",
            f"-PkasugaClientDirectory={client}", "-PkasugaQuickPlayWorld=showcase-world",
            f"-PkasugaShowcaseOutput={output}", f"-PkasugaShowcaseFrames={args.seconds * args.fps}",
            f"-PkasugaShowcaseFps={args.fps}", "-PkasugaHeadlessFps=120",
            f"-PkasugaShowcaseFfmpeg={shutil.which('ffmpeg')}", "--console=plain", env=env)
    report = json.loads((output / "capture-report.json").read_text())
    if not report["passed"]:
        raise RuntimeError(f"Minecraft capture failed: {report}")
    for path in (output / "client-generated").rglob("*"):
        if path.is_file() and path.read_bytes() != (output / "javascript" / path.relative_to(output / "client-generated")).read_bytes():
            raise RuntimeError(f"Client-generated shader differs from offline export: {path}")
    if not args.skip_compose:
        run(sys.executable, ROOT / "scripts/compose-shader-showcase.py", output)
    print(f"Showcase complete: {output}", flush=True)


if __name__ == "__main__":
    main()
