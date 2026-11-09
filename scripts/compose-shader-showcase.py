#!/usr/bin/env python3
"""Compose source excerpts, genuine compiler artifacts and real GPU capture into a video."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import textwrap

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
W, H = 1920, 1080
BG, PANEL, BORDER = "#090f1c", "#111b2c", "#29394e"
WHITE, MUTED, CYAN, GREEN = "#e7eff9", "#96a9c2", "#74e9ff", "#88efb4"


def font_file(mono=False):
    candidates = ([os.environ.get("KASUGA_SHOWCASE_MONO", ""), "/System/Library/Fonts/SFNSMono.ttf",
                   "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"] if mono else
                  [os.environ.get("KASUGA_SHOWCASE_FONT", ""),
                   "/System/Library/Fonts/Supplemental/Arial Unicode.ttf",
                   "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"])
    return next((p for p in candidates if p and Path(p).is_file()), None)


def font(size, mono=False):
    path = font_file(mono)
    return ImageFont.truetype(path, size) if path else ImageFont.load_default(size=size)


def text(draw, xy, value, size=24, fill=WHITE, mono=False):
    draw.text(xy, value, font=font(size, mono), fill=fill)


def pill(draw, x, y, label, color=CYAN):
    f = font(20)
    width = int(draw.textlength(label, font=f)) + 32
    draw.rounded_rectangle((x, y, x + width, y + 36), radius=10, fill="#1b2b40", outline=BORDER)
    draw.text((x + 16, y + 4), label, font=f, fill=color)
    return x + width + 10


def syntax(draw, x, y, line, size=21):
    # Highlight only presentation text; the files/exports remain untouched.
    tokens = re.split(r'("[^"\n]*"|\b(?:const|val|def|return|void|float|vec[234]|uniform|for|if|else|int)\b|\b\d+(?:\.\d+)?f?\b)', line)
    f = font(size, True)
    comment = line.lstrip().startswith(("//", "#")) and not line.startswith("#version")
    for token in tokens:
        color = MUTED if comment else "#a4e8b1" if token.startswith('"') else "#f5c482" if re.fullmatch(r"\d+(?:\.\d+)?f?", token) else "#bcadff" if re.fullmatch(r"const|val|def|return|void|float|vec[234]|uniform|for|if|else|int", token) else WHITE
        # Use the CJK-capable font for non-ASCII literals.
        active = font(size) if any(ord(c) > 127 for c in token) else f
        draw.text((x, y), token, font=active, fill=color)
        x += draw.textlength(token, font=active)


def code_lines(path, first, last, width=70):
    source = path.read_text().splitlines()
    result = []
    for number in range(first, min(last, len(source)) + 1):
        line = source[number - 1]
        wrapped = textwrap.wrap(line, width=width, subsequent_indent="        ",
                                replace_whitespace=False, drop_whitespace=True) or [""]
        result += [(str(number) if i == 0 else "", part) for i, part in enumerate(wrapped)]
    return result


def code_card(heading, subtitle, filename, lines, badges):
    image = Image.new("RGBA", (W, H), BG)
    d = ImageDraw.Draw(image)
    text(d, (48, 28), "KASUGALIB NEXT  /  SHADER DSL", 24, CYAN)
    text(d, (48, 78), heading, 48)
    text(d, (50, 147), subtitle, 25, MUTED)
    d.rounded_rectangle((48, 212, 944, 932), radius=20, fill=PANEL, outline=BORDER, width=2)
    text(d, (76, 232), filename, 23, CYAN, True)
    text(d, (76, 268), "EXECUTED SOURCE / ACTUAL GENERATED FILE · EXCERPT", 15, MUTED)
    d.line((76, 302, 916, 302), fill=BORDER, width=1)
    spacing = min(24, 586 // max(1, len(lines)))
    size = min(20, spacing - 1)
    while size > 14 and any(d.textlength(line, font=font(size, True)) > 792 for _, line in lines):
        size -= 1
    for i, (number, line) in enumerate(lines):
        y = 318 + i * spacing
        text(d, (72, y), number.rjust(3), size - 2, "#657b96", True)
        syntax(d, 122, y, line, size)
    # A transparent aperture lets the genuine video show through.
    d.rectangle((976, 212, 1871, 715), fill=(0, 0, 0, 0))
    d.rounded_rectangle((976, 744, 1872, 932), radius=20, fill=PANEL, outline=BORDER, width=2)
    text(d, (1004, 766), "REAL MINECRAFT OUTPUT", 20, GREEN)
    text(d, (1004, 806), "Source → typed IR → GLSL 150 → GPU", 29)
    text(d, (1004, 853), "JS / Kotlin / Python: byte-identical IR + resources", 22, MUTED)
    text(d, (1004, 889), "Headless client · direct framebuffer capture", 20, MUTED)
    x = 48
    for badge in badges:
        x = pill(d, x, 958, badge)
    text(d, (48, 1020), "Full executable sources and compiler outputs are included with this demo.", 19, MUTED)
    return image


def intro_overlay():
    image = Image.new("RGBA", (W, H), (5, 11, 23, 170))
    d = ImageDraw.Draw(image)
    text(d, (96, 100), "KASUGALIB NEXT", 28, CYAN)
    text(d, (96, 290), "One effect. Three languages.", 76)
    text(d, (100, 402), "JavaScript  /  Kotlin  /  Python", 48, CYAN)
    text(d, (100, 504), "从脚本代码，到真实游戏画面", 40)
    text(d, (100, 580), "Prism Scan · animated refraction, color and GPU control flow", 28, MUTED)
    x = 100
    for label in ("TYPED IR", "GLSL 150 + JSON", "REAL GPU CAPTURE"):
        x = pill(d, x, 698, label)
    text(d, (100, 930), "Minecraft 1.21.1 / NeoForge  ·  No shader pack  ·  Silent video", 23, MUTED)
    return image


def game_overlay(label, detail, capture):
    image = Image.new("RGBA", (W, H))
    d = ImageDraw.Draw(image)
    d.rectangle((0, 0, W, 128), fill=(5, 11, 23, 225))
    text(d, (48, 22), "PRISM SCAN  /  LIVE SHADER", 32, CYAN)
    text(d, (48, 72), label, 27)
    text(d, (1300, 35), "Minecraft → framebuffer → FFmpeg", 22, MUTED)
    text(d, (1300, 74), f"{capture['renderer']}  ·  {capture['fps']} FPS export", 21, GREEN)
    d.rounded_rectangle((48, 872, 1872, 1012), radius=18, fill=(5, 11, 23, 220), outline=BORDER, width=2)
    text(d, (76, 893), detail, 30)
    text(d, (76, 944), "One GPU program · animated uniforms · preserved alpha · no per-frame script compilation", 24, MUTED)
    return image


def read_frame(pipe, count):
    data = bytearray()
    while len(data) < count:
        chunk = pipe.read(count - len(data))
        if not chunk:
            raise RuntimeError(f"Video decoder ended after {len(data)} of {count} frame bytes")
        data.extend(chunk)
    return bytes(data)


def compose(output):
    capture = json.loads((output / "capture-report.json").read_text())
    language = json.loads((output / "language-report.json").read_text())
    if not capture["passed"] or not language["passed"]:
        raise RuntimeError("Refusing to compose a failed capture or language comparison")
    fps, count = capture["fps"], capture["capturedFrames"]
    duration = count / fps
    source = ROOT / "examples/shader-dsl"
    generated = output / "javascript"
    ir = (generated / "program.ir.txt").read_text().splitlines()
    loop = next(i for i, line in enumerate(ir) if "ForRange {" in line)
    # Actual IR records, with only common indentation removed for readability.
    ir_lines = [(str(i + 1), line) for i, line in enumerate(ir[19:38], 19)]
    ir_lines += [("", ""), ("", "..."), *[(str(i + 1), line[6:]) for i, line in enumerate(ir[loop:loop + 7], loop)]]
    glsl = generated / "assets/showcase/shaders/core/prism_scan.fsh"
    stages = [
        intro_overlay(),
        code_card("01  JavaScript", "GraalJS 直接构造类型化 shader IR", "prism-scan.js",
                  code_lines(source / "prism-scan.js", 2, 24), ["GraalJS frontend", "Typed expressions", "GPU loop"]),
        code_card("02  Kotlin", "Kotlin/JVM 调用同一份 DSL，编译产物逐字节一致", "prism-scan.main.kts",
                  code_lines(source / "prism-scan.main.kts", 7, 28), ["Kotlin / JVM", "Same ShaderProgram", "Same backend"]),
        code_card("03  Python", "JPype 本地宿主桥接 · 此示例通过 JVM API 生成 shader", "prism-scan.py",
                  code_lines(source / "prism-scan.py", 28, 51), ["Python + JPype host", "Trusted local script", "Same typed IR"]),
        code_card("04  Typed intermediate representation", "真实编译产物：类型、uniform、动态控制流", "program.ir.txt",
                  ir_lines, ["FLOAT / VEC2 / VEC3", "ForRange / IfStatement", "Byte-identical × 3"]),
        code_card("05  Generated GLSL + Minecraft JSON", "同一后端生成 .vsh、.fsh、.json；客户端实际加载这一份代码", "prism_scan.fsh  ·  GLSL 150",
                  code_lines(glsl, 10, 31, 70), ["GLSL 150", "3 Minecraft resources", "No raw GLSL escape"]),
    ]
    # Show the genuine Minecraft metadata alongside the fragment source as well.
    metadata = stages[-1]
    md = ImageDraw.Draw(metadata)
    md.rounded_rectangle((976, 744, 1872, 932), radius=20, fill=PANEL, outline=BORDER, width=2)
    text(md, (1004, 758), "prism_scan.json  /  GENERATED RESOURCE", 18, CYAN, True)
    json_lines = glsl.with_suffix(".json").read_text().splitlines()
    for i, line in enumerate(json_lines[:7]):
        syntax(md, 1004, 791 + i * 18, line, 16)
    game_stages = [
        game_overlay("Baseline / 原始世界", "Strength = 0  ·  GPU pixels match the original world", capture),
        game_overlay("Exposed parameter / 参数渐变", "Strength 0 → 0.9  ·  color grading + radial refraction", capture),
        game_overlay("Animated effect / 动态效果", "Time = captured frame / FPS  ·  three GPU ring layers", capture),
        game_overlay("Before / after / 扫描对比", "Left: original world  |  Right: generated shader  ·  animated split", capture),
        game_overlay("Full effect / 完整效果", "JavaScript / Kotlin / Python → identical program → this GPU output", capture),
    ]
    previews = output / "previews"
    previews.mkdir(exist_ok=True)
    decoder = subprocess.Popen(["ffmpeg", "-v", "error", "-stream_loop", "1", "-i", str(output / "minecraft.mp4"),
                                "-vf", f"scale={W}:{H}", "-f", "rawvideo", "-pix_fmt", "rgb24", "pipe:1"],
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    log = (output / "ffmpeg-compose.log").open("wb")
    encoder = subprocess.Popen(["ffmpeg", "-y", "-v", "error", "-f", "rawvideo", "-pix_fmt", "rgb24",
                                "-s", f"{W}x{H}", "-r", str(fps), "-i", "pipe:0", "-an", "-c:v", "libx264",
                                "-preset", "fast", "-crf", "18", "-pix_fmt", "yuv420p", "-movflags", "+faststart",
                                str(output / "shader-dsl-showcase.mp4")], stdin=subprocess.PIPE, stderr=log)
    preview_at = {int(fps * duration * fraction): name for fraction, name in
                  [(0.06, "00-intro"), (0.2, "01-javascript"), (0.36, "02-kotlin"), (0.54, "03-python"),
                   (0.72, "04-ir"), (0.9, "05-glsl"), (1.05, "06-baseline"), (1.43, "07-comparison"), (1.77, "08-effect")]}
    try:
        for index in range(count * 2):
            base = Image.frombytes("RGB", (W, H), read_frame(decoder.stdout, W * H * 3))
            seconds = index / fps
            if index < count:
                fraction = index / count
                stage = 0 if fraction < 3/24 else 1 if fraction < 7/24 else 2 if fraction < 11/24 else 3 if fraction < 15/24 else 4 if fraction < 19/24 else 5
                if stage:
                    side = base.resize((896, 504), Image.Resampling.LANCZOS)
                    base.paste(side, (976, 212))
                base.paste(stages[stage], (0, 0), stages[stage])
            else:
                local = seconds - duration
                stage = 0 if local < 2 else 1 if local < 5 else 2 if local < 6 else 3 if local < 14 else 4
                overlay = game_stages[stage]
                base.paste(overlay, (0, 0), overlay)
            draw = ImageDraw.Draw(base)
            draw.rectangle((0, H - 7, int(W * (index + 1) / (count * 2)), H), fill=CYAN)
            if index in preview_at:
                base.save(previews / (preview_at[index] + ".png"))
                if preview_at[index] == "08-effect":
                    base.save(output / "poster.png")
            encoder.stdin.write(base.tobytes())
            if index % (fps * 4) == 0:
                print(f"Composing {seconds:.0f}/{duration * 2:.0f}s", flush=True)
        encoder.stdin.close()
        if encoder.wait(timeout=60) != 0:
            raise RuntimeError("Video composition failed; see ffmpeg-compose.log")
        decoder.stdout.close()
        if decoder.wait(timeout=30) != 0:
            raise RuntimeError(decoder.stderr.read().decode())
    finally:
        for process in (decoder, encoder):
            if process.poll() is None:
                process.kill()
                process.wait()
        log.close()
    video = output / "shader-dsl-showcase.mp4"
    probe = json.loads(subprocess.check_output(["ffprobe", "-v", "error", "-count_frames", "-show_streams", "-show_format", "-of", "json", str(video)]))
    stream = probe["streams"][0]
    if int(stream["nb_read_frames"]) != count * 2 or stream["codec_name"] != "h264" or stream["pix_fmt"] != "yuv420p":
        raise RuntimeError(f"Unexpected video: {stream}")
    subprocess.run(["ffmpeg", "-v", "error", "-xerror", "-i", str(video), "-f", "null", "-"], check=True)
    (output / "video-report.json").write_text(json.dumps({"passed": True, "file": video.name,
        "sha256": hashlib.sha256(video.read_bytes()).hexdigest(), "width": W, "height": H,
        "fps": fps, "frames": count * 2, "durationSeconds": duration * 2,
        "codec": stream["codec_name"], "pixelFormat": stream["pix_fmt"], "audio": "none",
        "fullDecodePassed": True}, indent=2) + "\n")
    thumbs = []
    for path in sorted(previews.glob("*.png")):
        thumb = Image.open(path).convert("RGB").resize((640, 360))
        thumbs.append(thumb)
    sheet = Image.new("RGB", (1920, 360 * ((len(thumbs) + 2) // 3)), BG)
    for i, thumb in enumerate(thumbs):
        sheet.paste(thumb, ((i % 3) * 640, (i // 3) * 360))
    sheet.save(output / "contact-sheet.png")
    print(video, flush=True)


if __name__ == "__main__":
    compose(Path(sys.argv[1]).resolve())
