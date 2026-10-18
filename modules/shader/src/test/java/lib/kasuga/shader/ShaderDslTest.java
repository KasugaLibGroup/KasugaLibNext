package lib.kasuga.shader;

import lib.kasuga.shader.backend.MinecraftGlsl150Backend;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaderDslTest {

    @Test
    void generatesFullscreenLensShaderAndMinecraftMetadata() throws IOException {
        ShaderProgram lens = createLensProgram();
        var bundle = MinecraftGlsl150Backend.generate(lens);

        assertEquals(resource("golden/lens.fsh"), bundle.fragmentSource());
        assertEquals(resource("golden/lens.json"), bundle.programJson());
        assertEquals(3, bundle.resources().size());
        assertTrue(bundle.resources().containsKey("assets/example/shaders/core/lens.vsh"));
        assertTrue(bundle.resources().containsKey("assets/example/shaders/core/lens.fsh"));
        assertTrue(bundle.resources().containsKey("assets/example/shaders/core/lens.json"));
    }

    @Test
    void recordsShaderControlFlowInsteadOfExecutingItAsJavaControlFlow() {
        ShaderProgram loop = ShaderProgram.fullscreen("example:loop", shader -> {
            IntExpr count = shader.uniformInt("Count", 0);
            FloatArrayExpr values = shader.uniformFloatArray("Values", 8, 0);
            ShaderVariable<FloatExpr> sum = shader.localFloat("sum", shader.f32(0));

            shader.forRange("index", shader.i32(0), count, index -> {
                shader.ifThen(index.gte(shader.i32(8)), shader::breakLoop);
                sum.set(sum.get().add(values.get(index)));
            });

            shader.fragmentColor(shader.vec4(sum.get(), sum.get(), sum.get(), shader.f32(1)));
        });

        String source = MinecraftGlsl150Backend.generate(loop).fragmentSource();
        assertTrue(source.contains("for (int index = 0; index < Count; ++index)"));
        assertTrue(source.contains("if ((index >= 8))"));
        assertTrue(source.contains("sum = (sum + Values[index]);"));
    }

    @Test
    void generatesLinkedVertexAndFragmentStagesForGraphicsPrograms() {
        ShaderProgram particle = ShaderProgram.graphics(
                "example:particle",
                vertex -> {
                    Vec3Expr position = vertex.inputVec3("Position");
                    Vec2Expr uv = vertex.inputVec2("UV0");
                    Vec4Expr color = vertex.inputVec4("Color");
                    Mat4Expr modelView = vertex.uniformMat4("ModelViewMat");
                    Mat4Expr projection = vertex.uniformMat4("ProjMat");
                    vertex.outputVec2("particleUv", uv);
                    vertex.outputVec4("particleColor", color);
                    vertex.position(projection.transform(modelView.transform(
                            vertex.vec4(position, vertex.f32(1))
                    )));
                },
                fragment -> {
                    Vec2Expr uv = fragment.inputVec2("particleUv");
                    Vec4Expr color = fragment.inputVec4("particleColor");
                    FloatExpr radial = fragment.f32(1).sub(
                            uv.sub(fragment.vec2(0.5f, 0.5f)).length().mul(2).min(1)
                    );
                    fragment.fragmentColor(fragment.vec4(
                            color.rgb().mul(radial), color.a().mul(radial)
                    ));
                }
        );

        var bundle = MinecraftGlsl150Backend.generate(particle);
        assertEquals(ShaderProgram.Kind.GRAPHICS, particle.kind());
        assertTrue(bundle.vertexSource().contains("in vec3 Position;"));
        assertTrue(bundle.vertexSource().contains("uniform mat4 ModelViewMat;"));
        assertTrue(bundle.vertexSource().contains("out vec2 particleUv;"));
        assertTrue(bundle.vertexSource().contains("gl_Position ="));
        assertTrue(bundle.fragmentSource().contains("in vec4 particleColor;"));
        assertTrue(bundle.programJson().contains("\"type\": \"matrix4x4\""));
        assertEquals(1, occurrences(bundle.programJson(), "\"name\": \"ModelViewMat\""));
    }

    @Test
    void shaderExplicitlyRegistersUserAdjustableParameters() {
        ShaderParameter strength = ShaderParameter.floatParameter(
                "Strength", "Controls lens distortion", 0.25f, 0.0f, 2.0f
        );
        ShaderParameter enabled = ShaderParameter.booleanParameter(
                "Enabled", "Enables the lens", true
        );

        ShaderProgram program = ShaderProgram.fullscreen("example:parameters", shader -> {
            FloatExpr value = shader.exposeFloat(strength);
            shader.exposeBool(enabled);
            shader.fragmentColor(shader.vec4(value, value, value, shader.f32(1.0f)));
        });

        assertEquals(List.of(strength, enabled), program.parameterSchema().parameters());
        assertEquals("Controls lens distortion", strength.description());
        assertEquals(ShaderParameterType.FLOAT, strength.type());
        assertEquals(new ShaderParameterRange(0.0, 2.0), strength.range());
        assertEquals(List.of(0.25f), strength.defaultValues());

        String source = MinecraftGlsl150Backend.generate(program).fragmentSource();
        assertTrue(source.contains("uniform float Strength;"));
        assertTrue(source.contains("uniform bool Enabled;"));
    }

    @Test
    void exposedParameterContractRejectsInvalidDefaultsAndTypes() {
        assertThrows(IllegalArgumentException.class, () -> ShaderParameter.floatParameter(
                "Strength", "Outside range", 3.0f, 0.0f, 2.0f
        ));
        assertThrows(IllegalStateException.class, () -> ShaderParameter.builder(
                "Strength", "Missing range", ShaderParameterType.FLOAT
        ).defaultValues(1.0f).build());
        assertThrows(IllegalArgumentException.class, () -> ShaderParameter.builder(
                        "Iterations", "Fractional integer range", ShaderParameterType.INTEGER
                )
                .range(0.5, 8.5)
                .defaultValues(4)
                .build());
        assertThrows(IllegalArgumentException.class, () -> ShaderParameter.builder(
                        "Iterations", "Integer range overflow", ShaderParameterType.INTEGER
                )
                .range(Integer.MIN_VALUE - 1.0, Integer.MAX_VALUE)
                .defaultValues(4)
                .build());
        assertThrows(IllegalArgumentException.class, () -> ShaderParameter.builder(
                        "Strength", "Float range overflow", ShaderParameterType.FLOAT
                )
                .range(0.0, Double.MAX_VALUE)
                .defaultValues(1.0f)
                .build());

        ShaderParameter integer = ShaderParameter.intParameter(
                "Iterations", "Number of iterations", 4, 1, 16
        );
        assertThrows(IllegalArgumentException.class, () -> ShaderProgram.fullscreen(
                "example:wrong_parameter_type", shader -> shader.exposeFloat(integer)
        ));
    }

    @Test
    void rejectsInvalidProgramsDuringJavaConstruction() {
        assertThrows(IllegalArgumentException.class, () -> ShaderProgram.fullscreen("invalid", shader ->
                shader.fragmentColor(shader.vec4(0, 0, 0, 1))));
        assertThrows(IllegalArgumentException.class, () -> ShaderProgram.fullscreen("example:duplicate", shader -> {
            shader.uniformFloat("Strength", 1);
            shader.uniformFloat("Strength", 2);
            shader.fragmentColor(shader.vec4(0, 0, 0, 1));
        }));
        assertThrows(IllegalStateException.class, () -> ShaderProgram.fullscreen(
                "example:no_output", shader -> shader.uniformFloat("Strength", 1)
        ));
        assertThrows(IllegalStateException.class, () -> ShaderProgram.graphics(
                "example:no_vertex_position",
                vertex -> vertex.inputVec3("Position"),
                fragment -> fragment.fragmentColor(fragment.vec4(1, 1, 1, 1))
        ));
        assertThrows(IllegalArgumentException.class, () -> ShaderProgram.graphics(
                "example:stage_mismatch",
                vertex -> {
                    vertex.outputVec2("value", vertex.vec2(0, 0));
                    vertex.position(vertex.vec4(0, 0, 0, 1));
                },
                fragment -> {
                    fragment.inputVec3("value");
                    fragment.fragmentColor(fragment.vec4(1, 1, 1, 1));
                }
        ));
    }

    private static ShaderProgram createLensProgram() {
        return ShaderProgram.fullscreen("example:lens", shader -> {
            Sampler2DExpr scene = shader.sampler2D("SceneSampler");
            Vec2Expr center = shader.uniformVec2("Center", 0.5f, 0.5f);
            FloatExpr strength = shader.uniformFloat("Strength", 0.1f);

            ShaderVariable<Vec2Expr> delta = shader.localVec2(
                    "delta", shader.texCoord().sub(center)
            );
            ShaderVariable<FloatExpr> distance = shader.localFloat(
                    "distanceToCenter", delta.get().length()
            );
            ShaderVariable<Vec2Expr> warped = shader.localVec2(
                    "warped",
                    shader.texCoord().add(delta.get().normalize().mul(
                            strength.div(distance.get().max(0.001f))
                    ))
            );
            shader.fragmentColor(scene.sample(warped.get()));
        });
    }

    private static String resource(String path) throws IOException {
        try (var stream = ShaderDslTest.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Missing test resource: " + path);
            // Golden files are stored with LF endings; normalise CRLF checkouts (Windows CI) before comparing.
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    private static int occurrences(String value, String pattern) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(pattern, offset)) >= 0) {
            count++;
            offset += pattern.length();
        }
        return count;
    }
}
