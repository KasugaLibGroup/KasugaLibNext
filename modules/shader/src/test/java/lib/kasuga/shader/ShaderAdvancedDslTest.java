package lib.kasuga.shader;

import lib.kasuga.shader.backend.MinecraftGlsl150Backend;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaderAdvancedDslTest {

    @Test
    void emitsMatricesStructsWhileLoopsAndSwitches() {
        ShaderStructType.Builder materialBuilder = ShaderStructType.builder("Material");
        ShaderStructField<Vec3Expr> tint = materialBuilder.vec3Field("tint");
        ShaderStructField<FloatExpr> roughness = materialBuilder.floatField("roughness");
        ShaderStructType materialType = materialBuilder.build();

        ShaderProgram program = ShaderProgram.fullscreen("advanced:typed", shader -> {
            shader.declareStruct(materialType);
            Mat2Expr uvTransform = shader.uniformMat2("UvTransform");
            Mat3Expr normalTransform = shader.uniformMat3("NormalTransform");
            IntExpr count = shader.uniformInt("Count", 4);
            IntExpr mode = shader.uniformInt("Mode", 0);
            ShaderVariable<IntExpr> index = shader.localInt("index", shader.i32(0));
            ShaderVariable<StructExpr> material = shader.localStruct(
                    "material",
                    shader.structValue(materialType, shader.vec3(1, 0.5f, 0.25f), shader.f32(0.8f))
            );

            Vec2Expr transformedUv = shader.letVec2(
                    "transformedUv", uvTransform.transform(shader.texCoord())
            );
            shader.letVec3("transformedNormal", normalTransform.transform(shader.vec3(0, 0, 1)));

            shader.whileLoop(index.get().lt(count), () -> {
                index.set(index.get().add(1));
                shader.ifThen(index.get().gte(shader.i32(4)), shader::breakLoop);
            });
            shader.doWhile(index.get().gt(shader.i32(0)), () ->
                    index.set(index.get().sub(shader.i32(1)))
            );
            shader.switchOn(mode, cases -> cases
                    .caseOf(0, () -> {
                        shader.setStructField(material, roughness, shader.f32(0.25f));
                        shader.breakSwitch();
                    })
                    .caseOf(1, () -> shader.setStructField(
                            material, tint, shader.vec3(0.25f, 0.5f, 1.0f)
                    ))
                    .defaultCase(() -> shader.setStructField(
                            material, roughness, transformedUv.x()
                    ))
            );

            shader.fragmentColor(shader.vec4(
                    material.get().field(tint), material.get().field(roughness)
            ));
        });

        var bundle = MinecraftGlsl150Backend.generate(program);
        String source = bundle.fragmentSource();
        assertTrue(source.contains("struct Material {\n    vec3 tint;\n    float roughness;\n};"));
        assertTrue(source.contains("uniform mat2 UvTransform;"));
        assertTrue(source.contains("uniform mat3 NormalTransform;"));
        assertTrue(source.contains("Material material = Material("));
        assertTrue(source.contains("(material).roughness = 0.25;"));
        assertTrue(source.contains("while ((index < Count)) {"));
        assertTrue(source.contains("do {"));
        assertTrue(source.contains("} while ((index > 0));"));
        assertTrue(source.contains("switch (Mode) {"));
        assertTrue(source.contains("case 0:"));
        assertTrue(source.contains("case 1:"));
        assertTrue(source.contains("default:"));
        assertTrue(source.contains("(material).roughness = 0.25;\n            break;"));
        assertTrue(bundle.programJson().contains("\"type\": \"matrix2x2\""));
        assertTrue(bundle.programJson().contains("\"type\": \"matrix3x3\""));
    }

    @Test
    void validatesStructAndSwitchContracts() {
        ShaderStructType.Builder firstBuilder = ShaderStructType.builder("First");
        ShaderStructField<FloatExpr> firstValue = firstBuilder.floatField("value");
        assertThrows(IllegalArgumentException.class, () -> firstBuilder.floatField("value"));
        ShaderStructType first = firstBuilder.build();

        ShaderStructType.Builder secondBuilder = ShaderStructType.builder("Second");
        ShaderStructField<FloatExpr> secondValue = secondBuilder.floatField("value");
        ShaderStructType second = secondBuilder.build();

        assertThrows(IllegalArgumentException.class, () -> ShaderProgram.fullscreen(
                "advanced:undeclared_struct",
                shader -> shader.structValue(first, shader.f32(1))
        ));
        assertThrows(IllegalArgumentException.class, () -> ShaderProgram.fullscreen(
                "advanced:wrong_struct_value",
                shader -> {
                    shader.declareStruct(first);
                    shader.structValue(first, shader.i32(1));
                }
        ));
        assertThrows(IllegalArgumentException.class, () -> ShaderProgram.fullscreen(
                "advanced:wrong_field",
                shader -> {
                    shader.declareStruct(first);
                    StructExpr value = shader.structValue(first, shader.f32(1));
                    value.field(secondValue);
                }
        ));
        assertThrows(IllegalStateException.class, () -> ShaderProgram.fullscreen(
                "advanced:break_outside_switch",
                shader -> shader.breakSwitch()
        ));
        assertThrows(IllegalArgumentException.class, () -> ShaderProgram.fullscreen(
                "advanced:duplicate_case",
                shader -> shader.switchOn(shader.i32(0), cases -> cases
                        .caseOf(1, () -> {})
                        .caseOf(1, () -> {}))
        ));
        assertThrows(IllegalStateException.class, () -> ShaderProgram.fullscreen(
                "advanced:empty_switch",
                shader -> shader.switchOn(shader.i32(0), cases -> {})
        ));
        assertThrows(IllegalStateException.class, () -> ShaderProgram.fullscreen(
                "advanced:wrong_nested_break",
                shader -> shader.whileLoop(shader.bool(true), () ->
                        shader.switchOn(shader.i32(0), cases ->
                                cases.caseOf(0, shader::breakLoop)))
        ));
        assertThrows(IllegalArgumentException.class, () -> ShaderProgram.fullscreen(
                "advanced:wrong_matrix_size",
                shader -> shader.mat2(1.0f, 0.0f, 0.0f)
        ));
        assertThrows(IllegalArgumentException.class, () -> new ShaderIr.VariableDeclaration(
                ShaderType.FLOAT,
                "wrongInitializer",
                new ShaderIr.Literal(ShaderType.INT, "1")
        ));

        assertTrue(first.contains(firstValue));
        assertTrue(second.contains(secondValue));
    }

    @Test
    @DelicateShaderApi
    void emitsRawEscapeHatchesVerbatim() {
        ShaderProgram program = ShaderProgram.fullscreen("advanced:raw", shader -> {
            shader.rawPreamble("#extension GL_ARB_gpu_shader5 : enable");
            shader.rawDeclaration("float saturate(float value) { return clamp(value, 0.0, 1.0); }");
            FloatExpr edge = shader.rawFloat("dFdx(texCoord.x)");
            shader.rawStatement("if (edge < 0.0) discard;");
            shader.fragmentColor(shader.vec4(edge, edge, edge, shader.f32(1)));
        });

        String source = MinecraftGlsl150Backend.generate(program).fragmentSource();
        assertTrue(source.startsWith("#version 150\n#extension GL_ARB_gpu_shader5 : enable\n"));
        assertTrue(source.contains("float saturate(float value) { return clamp(value, 0.0, 1.0); }"));
        assertTrue(source.contains("if (edge < 0.0) discard;"));
        assertTrue(source.contains("(dFdx(texCoord.x))"));
        assertThrows(IllegalArgumentException.class, () -> new ShaderIr.RawExpression(
                ShaderType.FLOAT, " "
        ));
    }
}
