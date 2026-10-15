package lib.kasuga.rendering.effect.builtin.blackhole;

import lib.kasuga.shader.FloatArrayExpr;
import lib.kasuga.shader.FloatExpr;
import lib.kasuga.shader.FragmentShaderBuilder;
import lib.kasuga.shader.IntExpr;
import lib.kasuga.shader.Sampler2DExpr;
import lib.kasuga.shader.ShaderProgram;
import lib.kasuga.shader.ShaderParameter;
import lib.kasuga.shader.ShaderVariable;
import lib.kasuga.shader.Vec2Expr;
import lib.kasuga.shader.Vec3Expr;
import lib.kasuga.shader.Vec4Expr;

/** The built-in black-hole fragment shader, authored entirely through the imperative Java DSL :)*/
public final class BlackHoleShaderProvider {
    public static final String ID = "kasuga_lib:black_hole";
    public static final ShaderParameter LENSING_SCALE = ShaderParameter.floatParameter(
            "LensingScale",
            "Global multiplier for gravitational lensing distortion",
            1.0f, 0.0f, 3.0f
    );
    public static final ShaderParameter DISK_BRIGHTNESS = ShaderParameter.floatParameter(
            "DiskBrightness",
            "Global brightness multiplier for accretion disks",
            1.0f, 0.0f, 4.0f
    );
    public static final ShaderParameter CHROMATIC_SCALE = ShaderParameter.floatParameter(
            "ChromaticScale",
            "Global multiplier for chromatic aberration",
            1.0f, 0.0f, 3.0f
    );
    private static final int MAX_HOLES = 8;
    private static final int HOLE_STRIDE = 16;

    private BlackHoleShaderProvider() {
    }

    public static ShaderProgram program() {
        return ShaderProgram.fullscreen(ID, BlackHoleShaderProvider::fragment);
    }

    private static void fragment(FragmentShaderBuilder shader) {
        Sampler2DExpr sceneSampler = shader.sampler2D("SceneSampler");
        Sampler2DExpr depthSampler = shader.sampler2D("DepthSampler");
        IntExpr holeCount = shader.uniformInt("HoleCount", 0);
        FloatArrayExpr holeData = shader.uniformFloatArray(
                "HoleData", MAX_HOLES * HOLE_STRIDE, 0.0f
        );
        Vec2Expr screenSize = shader.uniformVec2("ScreenSize", 1.0f, 1.0f);
        FloatExpr time = shader.uniformFloat("Time", 0.0f);
        FloatExpr lensingScale = shader.exposeFloat(LENSING_SCALE);
        FloatExpr diskBrightness = shader.exposeFloat(DISK_BRIGHTNESS);
        FloatExpr chromaticScale = shader.exposeFloat(CHROMATIC_SCALE);

        FloatExpr aspect = shader.letFloat("aspect", screenSize.x().div(screenSize.y().max(1.0f)));
        FloatExpr sceneDepth = shader.letFloat(
                "sceneDepth", depthSampler.sample(shader.texCoord()).r()
        );
        ShaderVariable<Vec2Expr> warpedUv = shader.localVec2("warpedUv", shader.texCoord());
        ShaderVariable<Vec2Expr> chromaticOffset = shader.localVec2(
                "chromaticOffset", shader.vec2(0.0f, 0.0f)
        );
        ShaderVariable<Vec3Expr> accretionGlow = shader.localVec3(
                "accretionGlow", shader.vec3(0.0f, 0.0f, 0.0f)
        );
        ShaderVariable<FloatExpr> darkness = shader.localFloat("darkness", shader.f32(0.0f));

        shader.forRange("hole", shader.i32(0), shader.i32(MAX_HOLES), hole -> {
            shader.ifThen(hole.gte(holeCount), shader::breakLoop);

            Vec2Expr center = shader.letVec2("center", shader.vec2(
                    value(holeData, hole, 0), value(holeData, hole, 1)
            ));
            FloatExpr coreRadius = shader.letFloat("coreRadius", value(holeData, hole, 2));
            FloatExpr holeDepth = shader.letFloat("holeDepth", value(holeData, hole, 3));
            FloatExpr influenceRadius = shader.letFloat(
                    "influenceRadius", coreRadius.mul(value(holeData, hole, 4))
            );
            FloatExpr distortionStrength = shader.letFloat(
                    "distortionStrength", value(holeData, hole, 5).mul(lensingScale)
            );
            FloatExpr ringRadius = shader.letFloat(
                    "ringRadius", coreRadius.mul(value(holeData, hole, 6))
            );
            FloatExpr ringWidth = shader.letFloat(
                    "ringWidth", coreRadius.mul(value(holeData, hole, 7))
            );
            FloatExpr glowStrength = shader.letFloat(
                    "glowStrength", value(holeData, hole, 8).mul(diskBrightness)
            );
            FloatExpr chromaticStrength = shader.letFloat(
                    "chromaticStrength", value(holeData, hole, 9).mul(chromaticScale)
            );
            FloatExpr rotationSpeed = shader.letFloat(
                    "rotationSpeed", value(holeData, hole, 10)
            );
            FloatExpr useDepth = shader.letFloat("useDepth", value(holeData, hole, 11));
            Vec3Expr glowColor = shader.letVec3("glowColor", shader.vec3(
                    value(holeData, hole, 12),
                    value(holeData, hole, 13),
                    value(holeData, hole, 14)
            ));
            FloatExpr diskTilt = shader.letFloat(
                    "diskTilt", value(holeData, hole, 15).max(0.08f)
            );

            FloatExpr depthVisible = shader.letFloat(
                    "depthVisible", sceneDepth.step(holeDepth.sub(0.0005f))
            );
            FloatExpr visibility = shader.letFloat("visibility", shader.f32(1.0f).mix(
                    depthVisible,
                    useDepth.clamp(shader.f32(0.0f), shader.f32(1.0f))
            ));
            shader.ifThen(visibility.lte(shader.f32(0.0f)), shader::continueLoop);

            Vec2Expr metricDelta = shader.letVec2("metricDelta", shader.vec2(
                    shader.texCoord().x().sub(center.x()).mul(aspect),
                    shader.texCoord().y().sub(center.y())
            ));
            FloatExpr distanceToCenter = shader.letFloat(
                    "distanceToCenter", metricDelta.length()
            );
            shader.ifThen(distanceToCenter.gte(influenceRadius), shader::continueLoop);

            Vec2Expr metricDirection = shader.letVec2(
                    "metricDirection", metricDelta.div(distanceToCenter.max(0.000001f))
            );
            Vec2Expr uvDirection = shader.letVec2("uvDirection", shader.vec2(
                    metricDirection.x().div(aspect), metricDirection.y()
            ));
            FloatExpr field = shader.letFloat("field", shader.f32(1.0f).sub(
                    distanceToCenter.smoothstep(coreRadius, influenceRadius)
            ));
            FloatExpr inverseDistance = shader.letFloat("inverseDistance", coreRadius.div(
                    distanceToCenter.max(coreRadius.mul(0.55f))
            ));
            FloatExpr deflection = shader.letFloat(
                    "deflection", coreRadius.mul(distortionStrength)
                            .mul(field).mul(field).mul(inverseDistance)
            );
            warpedUv.set(warpedUv.get().add(uvDirection.mul(deflection).mul(visibility)));
            chromaticOffset.set(chromaticOffset.get().add(
                    uvDirection.mul(coreRadius).mul(chromaticStrength).mul(field).mul(visibility)
            ));

            Vec2Expr diskDelta = shader.letVec2("diskDelta", shader.vec2(
                    metricDelta.x(), metricDelta.y().div(diskTilt)
            ));
            FloatExpr diskDistance = shader.letFloat("diskDistance", diskDelta.length());
            FloatExpr ringDistance = shader.letFloat(
                    "ringDistance", diskDistance.sub(ringRadius).abs()
            );
            FloatExpr ring = shader.letFloat("ring", shader.f32(1.0f).sub(
                    ringDistance.smoothstep(shader.f32(0.0f), ringWidth.max(0.000001f))
            ));
            FloatExpr angle = shader.letFloat("angle", diskDelta.y().atan2(diskDelta.x()));
            FloatExpr radial = shader.letFloat(
                    "radial", diskDistance.div(coreRadius.max(0.000001f))
            );
            FloatExpr turbulence = shader.letFloat("turbulence", shader.f32(0.72f).add(shader.f32(0.28f).mul(
                    angle.mul(17.0f)
                            .sub(time.mul(rotationSpeed).mul(7.0f))
                            .add(radial.mul(11.0f))
                            .sin()
            )));
            FloatExpr diskSide = shader.letFloat("diskSide", diskDelta.y()
                    .div(ringRadius.max(0.000001f)).mul(0.5f).add(0.5f)
                    .clamp(shader.f32(0.0f), shader.f32(1.0f)));
            FloatExpr approach = shader.letFloat("approach", diskDelta.x()
                    .div(ringRadius.max(0.000001f)).mul(0.5f).add(0.5f)
                    .clamp(shader.f32(0.0f), shader.f32(1.0f)));
            FloatExpr beaming = shader.letFloat(
                    "beaming", shader.f32(0.55f).add(approach.mul(approach).mul(1.15f))
            );
            FloatExpr frontLighting = shader.letFloat(
                    "frontLighting", shader.f32(0.65f).add(
                            shader.f32(1.0f).sub(diskSide).mul(0.55f)
                    )
            );
            FloatExpr coreOcclusion = shader.letFloat("coreOcclusion", distanceToCenter.smoothstep(
                    coreRadius.mul(0.9f), coreRadius.mul(1.08f)
            ));
            FloatExpr diskVisibility = shader.letFloat("diskVisibility", coreOcclusion.max(
                    shader.f32(1.0f).sub(diskSide).mul(0.82f)
            ));
            FloatExpr innerHeat = shader.letFloat("innerHeat", shader.f32(1.0f).sub(
                    diskDistance.smoothstep(coreRadius.mul(1.05f), ringRadius.add(ringWidth))
            ));
            Vec3Expr hotColor = shader.letVec3("hotColor", shader.vec3(1.0f, 0.62f, 0.22f));
            Vec3Expr diskColor = shader.letVec3("diskColor",
                    glowColor.mul(shader.f32(1.0f).sub(innerHeat.mul(0.55f)))
                            .add(hotColor.mul(innerHeat.mul(0.55f)))
            );
            FloatExpr outerGlow = shader.letFloat("outerGlow", diskDistance.sub(ringRadius).abs()
                    .negate().div(coreRadius.mul(0.72f).max(0.000001f))
                    .exp().mul(0.12f));
            FloatExpr photonRing = shader.letFloat("photonRing", shader.f32(1.0f).sub(
                    distanceToCenter.sub(coreRadius.mul(1.08f)).abs().smoothstep(
                            shader.f32(0.0f), coreRadius.mul(0.055f).max(0.000001f)
                    )
            ));
            FloatExpr upperSide = shader.letFloat("upperSide", metricDelta.y().smoothstep(
                    coreRadius.mul(-0.18f), coreRadius.mul(0.58f)
            ));
            FloatExpr lensedBack = shader.letFloat("lensedBack", shader.f32(1.0f).sub(
                    distanceToCenter.sub(coreRadius.mul(1.34f)).abs().smoothstep(
                            shader.f32(0.0f), coreRadius.mul(0.13f).max(0.000001f)
                    )
            ).mul(upperSide).mul(shader.f32(1.0f).sub(diskTilt).mul(0.9f)));
            accretionGlow.set(accretionGlow.get().add(
                    diskColor.mul(ring.mul(turbulence).mul(beaming)
                                    .mul(frontLighting).mul(diskVisibility).add(outerGlow))
                            .add(hotColor.mul(photonRing.mul(0.42f).add(lensedBack.mul(0.32f))))
                            .mul(glowStrength).mul(visibility)
            ));

            FloatExpr core = shader.letFloat("core", shader.f32(1.0f).sub(distanceToCenter.smoothstep(
                    coreRadius.mul(0.88f), coreRadius.mul(1.04f)
            )));
            darkness.set(darkness.get().max(core.mul(visibility)));
        });

        Vec2Expr minimumUv = shader.vec2(0.0f, 0.0f);
        Vec2Expr maximumUv = shader.vec2(1.0f, 1.0f);
        warpedUv.set(warpedUv.get().clamp(minimumUv, maximumUv));
        Vec2Expr redUv = shader.letVec2(
                "redUv", warpedUv.get().add(chromaticOffset.get()).clamp(minimumUv, maximumUv)
        );
        Vec2Expr blueUv = shader.letVec2(
                "blueUv", warpedUv.get().sub(chromaticOffset.get()).clamp(minimumUv, maximumUv)
        );
        ShaderVariable<Vec4Expr> base = shader.localVec4(
                "base", sceneSampler.sample(warpedUv.get())
        );
        Vec3Expr lensedColor = shader.letVec3("lensedColor", shader.vec3(
                sceneSampler.sample(redUv).r(),
                base.get().g(),
                sceneSampler.sample(blueUv).b()
        ));
        Vec3Expr rawGlow = shader.letVec3(
                "rawGlow", accretionGlow.get().max(shader.vec3(0.0f, 0.0f, 0.0f))
        );
        Vec3Expr mappedGlow = shader.letVec3("mappedGlow", shader.vec3(
                rawGlow.x().div(rawGlow.x().add(1.0f)),
                rawGlow.y().div(rawGlow.y().add(1.0f)),
                rawGlow.z().div(rawGlow.z().add(1.0f))
        ));
        Vec3Expr color = shader.letVec3(
                "color", lensedColor.mul(shader.f32(1.0f).sub(darkness.get()))
                        .add(mappedGlow)
        );
        shader.fragmentColor(shader.vec4(
                color.max(shader.vec3(0.0f, 0.0f, 0.0f)),
                base.get().a()
        ));
    }

    private static FloatExpr value(FloatArrayExpr data, IntExpr hole, int field) {
        return data.get(hole.mul(HOLE_STRIDE).add(field));
    }
}
