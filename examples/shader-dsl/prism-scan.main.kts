import lib.kasuga.shader.ShaderProgram
import lib.kasuga.shader.ShaderParameter
import lib.kasuga.shader.showcase.ShowcaseExport
import java.nio.file.Path

// Kotlin calls the same typed JVM DSL directly; no guest engine is needed.
val program = ShaderProgram.fullscreen("showcase:prism_scan") { s ->
    val time = s.uniformFloat("Time", 0f)
    val aspect = s.uniformFloat("Aspect", 1.7777778f)
    val split = s.uniformFloat("Split", 0f)
    val strength = s.exposeFloat(ShaderParameter.floatParameter(
        "Strength", "棱镜扫描强度", 0.8f, 0f, 1f))
    val uv = s.letVec2("uv", s.texCoord())
    val p = s.letVec2("p", uv.sub(s.vec2(0.5f, 0.5f)).mul(s.vec2(aspect, s.f32(1f))))
    val radius = s.letFloat("radius", p.length())
    val wave = s.letFloat("wave", radius.mul(24f).sub(time.mul(3f)).sin())
    val offset = s.letVec2("offset", p.mul(wave.mul(0.012f).mul(strength)))
    val sampler = s.sampler2D("SceneSampler")
    val original = s.letVec4("original", sampler.sample(uv))
    val scene = s.letVec4("scene", sampler.sample(uv.add(offset)))
    val phase = s.letFloat("phase", radius.mul(6f).sub(time.mul(0.8f)))
    val palette = s.letVec3("palette", s.vec3(
        phase.sin().mul(0.5f).add(0.5f),
        phase.add(2.1f).sin().mul(0.5f).add(0.5f),
        phase.add(4.2f).sin().mul(0.5f).add(0.5f)))
    val glow = s.localVec3("glow", s.vec3(0f, 0f, 0f))
    s.forRange("layer", s.i32(0), s.i32(3)) { layer ->
        val ring = s.letFloat("ring", radius.mul(22f).sub(time.mul(2.4f))
            .add(layer.toFloat().mul(2.094f)).sin().mul(8f).sub(7f).max(0f))
        glow.set(glow.get().add(palette.mul(ring.mul(0.55f))))
    }
    val graded = s.letVec3("graded", scene.rgb().mul(s.vec3(0.65f, 0.85f, 1.1f))
        .add(palette.mul(0.12f)).add(glow.get()))
    val color = s.letVec3("color", scene.rgb().mul(s.f32(1f).sub(strength))
        .add(graded.mul(strength)))
    s.ifThenElse(uv.x().lt(split),
        { s.fragmentColor(original) },
        { s.fragmentColor(s.vec4(color, original.a())) })
}
ShowcaseExport.write(program, Path.of(args[0], "kotlin"))
