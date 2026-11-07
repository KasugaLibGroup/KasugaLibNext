// GraalJS frontend. Every expression below constructs typed shader IR.
Shader.fullscreen("showcase:prism_scan", s => {
    const time = s.uniformFloat("Time", 0);
    const aspect = s.uniformFloat("Aspect", 1.7777778);
    const split = s.uniformFloat("Split", 0);
    const strength = s.exposeFloat(ShaderParameter.floatParameter(
        "Strength", "棱镜扫描强度", 0.8, 0, 1));
    const uv = s.letVec2("uv", s.texCoord());
    const p = s.letVec2("p", uv.sub(s.vec2(0.5, 0.5)).mul(s.vec2(aspect, s.f32(1))));
    const radius = s.letFloat("radius", p.length());
    const wave = s.letFloat("wave", radius.mul(24).sub(time.mul(3)).sin());
    const offset = s.letVec2("offset", p.mul(wave.mul(0.012).mul(strength)));
    const sampler = s.sampler2D("SceneSampler");
    const original = s.letVec4("original", sampler.sample(uv));
    const scene = s.letVec4("scene", sampler.sample(uv.add(offset)));
    const phase = s.letFloat("phase", radius.mul(6).sub(time.mul(0.8)));
    const palette = s.letVec3("palette", s.vec3(
        phase.sin().mul(0.5).add(0.5),
        phase.add(2.1).sin().mul(0.5).add(0.5),
        phase.add(4.2).sin().mul(0.5).add(0.5)));
    const glow = s.localVec3("glow", s.vec3(0, 0, 0));
    // This is a dynamic GPU loop, not a JavaScript loop unrolled at build time.
    s.forRange("layer", s.i32(0), s.i32(3), layer => {
        const ring = s.letFloat("ring", radius.mul(22).sub(time.mul(2.4))
            .add(layer.toFloat().mul(2.094)).sin().mul(8).sub(7).max(0));
        glow.set(glow.get().add(palette.mul(ring.mul(0.55))));
    });
    const graded = s.letVec3("graded", scene.rgb().mul(s.vec3(0.65, 0.85, 1.1))
        .add(palette.mul(0.12)).add(glow.get()));
    const color = s.letVec3("color", scene.rgb().mul(s.f32(1).sub(strength))
        .add(graded.mul(strength)));
    s.ifThenElse(uv.x().lt(split),
        () => s.fragmentColor(original),
        () => s.fragmentColor(s.vec4(color, original.a())));
});
