"""Trusted local Python host via JPype, not a built-in/sandboxed Python engine."""
import os
import sys
from pathlib import Path

import jpype
from jpype import JClass, JFloat, JProxy

output = Path(sys.argv[1]).resolve()
jpype.startJVM(classpath=(output / "jvm-classpath.txt").read_text().split(os.pathsep))
Shader = JClass("lib.kasuga.shader.ShaderProgram")
Parameter = JClass("lib.kasuga.shader.ShaderParameter")
Export = JClass("lib.kasuga.shader.showcase.ShowcaseExport")
JavaPath = JClass("java.nio.file.Path")


def consumer(fn):
    return JProxy("java.util.function.Consumer", dict(accept=fn))


def runnable(fn):
    return JProxy("java.lang.Runnable", dict(run=fn))


def define(s):
    f = JFloat
    time = s.uniformFloat("Time", f(0))
    aspect = s.uniformFloat("Aspect", f(1.7777778))
    split = s.uniformFloat("Split", f(0))
    strength = s.exposeFloat(Parameter.floatParameter(
        "Strength", "棱镜扫描强度", f(0.8), f(0), f(1)))
    uv = s.letVec2("uv", s.texCoord())
    p = s.letVec2("p", uv.sub(s.vec2(f(0.5), f(0.5))).mul(s.vec2(aspect, s.f32(f(1)))))
    radius = s.letFloat("radius", p.length())
    wave = s.letFloat("wave", radius.mul(f(24)).sub(time.mul(f(3))).sin())
    offset = s.letVec2("offset", p.mul(wave.mul(f(0.012)).mul(strength)))
    sampler = s.sampler2D("SceneSampler")
    original = s.letVec4("original", sampler.sample(uv))
    scene = s.letVec4("scene", sampler.sample(uv.add(offset)))
    phase = s.letFloat("phase", radius.mul(f(6)).sub(time.mul(f(0.8))))
    palette = s.letVec3("palette", s.vec3(
        phase.sin().mul(f(0.5)).add(f(0.5)),
        phase.add(f(2.1)).sin().mul(f(0.5)).add(f(0.5)),
        phase.add(f(4.2)).sin().mul(f(0.5)).add(f(0.5))))
    glow = s.localVec3("glow", s.vec3(f(0), f(0), f(0)))

    def layer_body(layer):
        ring = s.letFloat("ring", radius.mul(f(22)).sub(time.mul(f(2.4)))
            .add(layer.toFloat().mul(f(2.094))).sin().mul(f(8)).sub(f(7)).max(f(0)))
        glow.set(glow.get().add(palette.mul(ring.mul(f(0.55)))))

    s.forRange("layer", s.i32(0), s.i32(3), consumer(layer_body))
    graded = s.letVec3("graded", scene.rgb().mul(s.vec3(f(0.65), f(0.85), f(1.1)))
        .add(palette.mul(f(0.12))).add(glow.get()))
    color = s.letVec3("color", scene.rgb().mul(s.f32(f(1)).sub(strength))
        .add(graded.mul(strength)))
    s.ifThenElse(uv.x().lt(split),
        runnable(lambda: s.fragmentColor(original)),
        runnable(lambda: s.fragmentColor(s.vec4(color, original.a()))))


try:
    program = Shader.fullscreen("showcase:prism_scan", consumer(define))
    Export.write(program, JavaPath.of(str(output / "python")))
finally:
    jpype.shutdownJVM()
