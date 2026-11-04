package lib.kasuga.shader.graal;

import lib.kasuga.shader.ShaderParameter;
import lib.kasuga.shader.ShaderParameterType;
import lib.kasuga.shader.ShaderProgram;
import lib.kasuga.shader.ShaderStructField;
import lib.kasuga.shader.ShaderType;
import lib.kasuga.shader.FloatExpr;
import lib.kasuga.shader.Vec3Expr;
import lib.kasuga.shader.backend.MinecraftGlsl150Backend;
import lib.kasuga.shader.compiler.ShaderResourceCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.graalvm.polyglot.PolyglotException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class GraalShaderCompilerTest {
    private static final String SOLID = """
            Shader.fullscreen("example:solid", shader => {
                shader.fragmentColor(shader.vec4(1, 0.5, 0.25, 1));
            });
            """;

    private final GraalShaderCompiler compiler = new GraalShaderCompiler();

    @Test
    void fullscreenMatchesJavaIncludingExposedParametersAndAllGeneratedResources(@TempDir Path output)
            throws Exception {
        var source = Objects.requireNonNull(getClass().getResource("/shaders/screen-tint.js"));
        ShaderProgram scripted = compiler.compile(Path.of(source.toURI()));
        ShaderParameter tint = ShaderParameter.builder("Tint", "画面色调", ShaderParameterType.COLOR_RGB)
                .range(0, 1).defaultValues(1, 0.5, 0.25).build();
        ShaderProgram java = ShaderProgram.fullscreen("example:script_tint", shader -> {
            var color = shader.exposeVec3(tint);
            var sample = shader.sampler2D("SceneSampler").sample(shader.texCoord());
            shader.fragmentColor(shader.vec4(sample.rgb().mul(color), sample.a()));
        });

        assertSameProgram(java, scripted);
        assertEquals(List.of(tint), scripted.parameterSchema().parameters());
        var resources = ShaderResourceCompiler.compile(List.of(scripted), output);
        assertEquals(1, resources.programCount());
        assertEquals(3, resources.resourceCount());
        for (var entry : MinecraftGlsl150Backend.generate(java).resources().entrySet()) {
            assertEquals(entry.getValue(), Files.readString(output.resolve(entry.getKey())));
        }
    }

    @Test
    void graphicsUsesBothStageCallbacksAndMatrixOverloads() {
        ShaderProgram scripted = compiler.compile("particle.js", """
                Shader.graphics("example:particle", vertex => {
                    const position = vertex.inputVec3("Position");
                    vertex.outputVec2("uv").set(vertex.inputVec2("UV0"));
                    const projection = vertex.uniformMat4("ProjMat");
                    vertex.position(projection.transform(vertex.vec4(position, vertex.f32(1))));
                }, fragment => {
                    const uv = fragment.inputVec2("uv");
                    fragment.fragmentColor(fragment.sampler2D("Sampler0").sample(uv));
                });
                """);
        ShaderProgram java = ShaderProgram.graphics("example:particle", vertex -> {
            var position = vertex.inputVec3("Position");
            vertex.outputVec2("uv").set(vertex.inputVec2("UV0"));
            var projection = vertex.uniformMat4("ProjMat");
            vertex.position(projection.transform(vertex.vec4(position, vertex.f32(1))));
        }, fragment -> {
            var uv = fragment.inputVec2("uv");
            fragment.fragmentColor(fragment.sampler2D("Sampler0").sample(uv));
        });
        assertSameProgram(java, scripted);
    }

    @Test
    void structsVarargsAndNestedControlFlowKeepTypedIr() {
        ShaderProgram scripted = compiler.compile("advanced.js", """
                const materialBuilder = ShaderStructType.builder("Material");
                const tint = materialBuilder.vec3Field("tint");
                const roughness = materialBuilder.floatField("roughness");
                const materialType = materialBuilder.build();
                Shader.fullscreen("example:advanced", shader => {
                    shader.declareStruct(materialType);
                    const transform = shader.uniformMat2("UvTransform", 1, 0, 0, 1);
                    const samples = shader.uniformFloatArray("Samples", 4, 0.5);
                    const material = shader.localStruct("material", shader.structValue(
                        materialType, shader.vec3(1, 0.5, 0.25), shader.f32(0.75)));
                    const sum = shader.localFloat("sum", shader.f32(0));
                    shader.forRange("index", shader.i32(0), shader.i32(4), index => {
                        shader.ifThenElse(index.lt(shader.i32(2)),
                            () => sum.set(sum.get().add(samples.get(index))),
                            () => shader.continueLoop());
                    });
                    shader.whileLoop(sum.get().lt(shader.f32(1)), () => {
                        sum.set(sum.get().add(0.25));
                        shader.breakLoop();
                    });
                    shader.doWhile(shader.bool(false), () => sum.set(sum.get().mul(0.5)));
                    shader.switchOn(shader.i32(0), cases => cases
                        .caseOf(0, () => {
                            shader.setStructField(material, roughness, sum.get());
                            shader.breakSwitch();
                        }).defaultCase(() => shader.setStructField(
                            material, tint, shader.vec3(0, 0, 0))));
                    shader.letVec2("uv", transform.transform(shader.texCoord()));
                    shader.fragmentColor(shader.vec4(material.get().field(tint), material.get().field(roughness)));
                });
                """);
        // Struct fields have nominal owner identities. Verify the declaration, then use the
        // same type keys in the independent Java definition to compare the complete typed IR.
        var materialType = scripted.fragmentModule().structs().getFirst();
        assertEquals("Material", materialType.name());
        assertEquals(List.of("tint", "roughness"), materialType.fields().stream()
                .map(ShaderStructField::name).toList());
        assertEquals(List.of(ShaderType.VEC3, ShaderType.FLOAT), materialType.fields().stream()
                .map(ShaderStructField::type).toList());
        @SuppressWarnings("unchecked")
        var tint = (ShaderStructField<Vec3Expr>) materialType.fields().get(0);
        @SuppressWarnings("unchecked")
        var roughness = (ShaderStructField<FloatExpr>) materialType.fields().get(1);
        ShaderProgram java = ShaderProgram.fullscreen("example:advanced", shader -> {
            shader.declareStruct(materialType);
            var transform = shader.uniformMat2("UvTransform", 1, 0, 0, 1);
            var samples = shader.uniformFloatArray("Samples", 4, 0.5f);
            var material = shader.localStruct("material", shader.structValue(
                    materialType, shader.vec3(1, 0.5f, 0.25f), shader.f32(0.75f)));
            var sum = shader.localFloat("sum", shader.f32(0));
            shader.forRange("index", shader.i32(0), shader.i32(4), index ->
                    shader.ifThenElse(index.lt(shader.i32(2)),
                            () -> sum.set(sum.get().add(samples.get(index))), shader::continueLoop));
            shader.whileLoop(sum.get().lt(shader.f32(1)), () -> {
                sum.set(sum.get().add(0.25f));
                shader.breakLoop();
            });
            shader.doWhile(shader.bool(false), () -> sum.set(sum.get().mul(0.5f)));
            shader.switchOn(shader.i32(0), cases -> cases.caseOf(0, () -> {
                shader.setStructField(material, roughness, sum.get());
                shader.breakSwitch();
            }).defaultCase(() -> shader.setStructField(material, tint, shader.vec3(0, 0, 0))));
            shader.letVec2("uv", transform.transform(shader.texCoord()));
            shader.fragmentColor(shader.vec4(material.get().field(tint), material.get().field(roughness)));
        });
        assertSameProgram(java, scripted);
    }

    @Test
    void javascriptControlFlowRunsAtConstructionTime() {
        ShaderProgram scripted = compiler.compile("construction.js", """
                Shader.fullscreen("example:construction", shader => {
                    let value = shader.f32(0);
                    for (let i = 0; i < 3; i++) value = value.add(0.25);
                    if (true) shader.fragmentColor(shader.vec4(value, value, value, shader.f32(1)));
                });
                """);
        ShaderProgram java = ShaderProgram.fullscreen("example:construction", shader -> {
            var value = shader.f32(0).add(0.25f).add(0.25f).add(0.25f);
            shader.fragmentColor(shader.vec4(value, value, value, shader.f32(1)));
        });
        assertSameProgram(java, scripted);
    }

    @Test
    void decimalNumbersUseJavaFloatRoundingForDslFloatArguments() {
        ShaderProgram scripted = compiler.compile("decimals.js", """
                const exposure = ShaderParameter.floatParameter("Exposure", "exposure", 1.35, 0, 4);
                Shader.fullscreen("example:decimals", shader => {
                    const gain = shader.uniformFloat("Gain", 0.1);
                    const tint = shader.uniformVec3("Tint", 0.2, 0.3, 0.4);
                    shader.exposeFloat(exposure);
                    shader.letVec2("uv", shader.mat2(0.1, 0.2, 0.3, 0.4).transform(shader.texCoord()));
                    const color = shader.vec3(0.1, 0.2, 0.3).mul(tint).mul(gain.add(0.1));
                    shader.fragmentColor(shader.vec4(color, shader.f32(Math.sin(1))));
                });
                """);
        var exposure = ShaderParameter.floatParameter("Exposure", "exposure", 1.35f, 0, 4);
        ShaderProgram java = ShaderProgram.fullscreen("example:decimals", shader -> {
            var gain = shader.uniformFloat("Gain", 0.1f);
            var tint = shader.uniformVec3("Tint", 0.2f, 0.3f, 0.4f);
            shader.exposeFloat(exposure);
            shader.letVec2("uv", shader.mat2(0.1f, 0.2f, 0.3f, 0.4f).transform(shader.texCoord()));
            var color = shader.vec3(0.1f, 0.2f, 0.3f).mul(tint).mul(gain.add(0.1f));
            shader.fragmentColor(shader.vec4(color, shader.f32((float) Math.sin(1))));
        });
        assertSameProgram(java, scripted);
    }

    @ParameterizedTest
    @ValueSource(strings = {"undefined", "null", "42", "({})", "[]", "() => 1"})
    void rejectsNonProgramResults(String source) {
        var error = assertThrows(ShaderScriptException.class, () -> compiler.compile("return.js", source));
        assertEquals("return.js", error.sourceName());
        assertTrue(error.getMessage().contains("ShaderProgram"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Java.type('java.lang.System')",
            "Java.type('java.lang.Runtime')",
            "Java.type('java.nio.file.Files')",
            "Java.type('lib.kasuga.shader.compiler.ShaderResourceCompiler')",
            "Shader.fullscreen('example:bad', shader => shader.getClass())",
            "Shader.class.getClassLoader()",
            "load('/etc/passwd')",
            "require('fs')"
    })
    void rejectsHostCapabilitiesOutsideDsl(String source) {
        var error = assertThrows(ShaderScriptException.class, () -> compiler.compile("access.js", source));
        assertEquals("access.js", error.sourceName());
        assertNotNull(error.getCause());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Shader.fullscreen('example:bad', s => s.fragmentColor(s.vec4(NaN, 0, 0, 1)))",
            "Shader.fullscreen('example:bad', s => s.fragmentColor(s.vec4(Infinity, 0, 0, 1)))",
            "Shader.fullscreen('example:bad', s => s.i32(1.5))",
            "Shader.fullscreen('INVALID', s => s.fragmentColor(s.vec4(1, 1, 1, 1)))",
            "Shader.fullscreen('example:bad', s => {})",
            "Shader.fullscreen('example:bad', s => s.exposeFloat(ShaderParameter.intParameter('Count', 'count', 1, 0, 4)))",
            "Shader.graphics('example:bad', v => v.position(v.vec4(0,0,0,1)), f => f.fragmentColor(f.vec4(f.inputVec3('missing'), f.f32(1))))"
    })
    void retainsNumericTypeAndStageValidation(String source) {
        var error = assertThrows(ShaderScriptException.class, () -> compiler.compile("invalid.js", source));
        assertEquals("invalid.js", error.sourceName());
        assertNotNull(error.getCause());
        assertFalse(error.getMessage().contains("Unknown identifier"), error::getMessage);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "// header\nthrow new Error('broken shader');",
            "// header\nconst invalid = ;",
            "// header\nShader.fullscreen('example:bad', s => s.f32(NaN));"
    })
    void errorsRetainSourceNameLineColumnAndCause(String source) {
        var error = assertThrows(ShaderScriptException.class, () -> compiler.compile("错误.js", source));
        assertEquals("错误.js", error.sourceName());
        assertEquals(2, error.line());
        assertTrue(error.column() > 0);
        assertTrue(error.getMessage().contains("错误.js:2:"));
        assertNotNull(error.getCause());
    }

    @Test
    void isolatedContextsSurviveErrorsAndReturnDetachedPrograms() {
        String source = "const value = 1;\n" + SOLID;
        ShaderProgram first = compiler.compile("first.js", source);
        assertThrows(ShaderScriptException.class, () -> compiler.compile("broken.js", "throw Error('bad');"));
        ShaderProgram second = compiler.compile("second.js", source);
        assertSameProgram(first, second);
        assertFalse(MinecraftGlsl150Backend.generate(first).fragmentSource().isBlank());
    }

    @Test
    @Timeout(15)
    void terminatesInfiniteScriptsAndCanCompileAfterwards() {
        var limited = new GraalShaderCompiler(1_000);
        var error = assertThrows(ShaderScriptException.class,
                () -> limited.compile("loop.js", "while (true) {}"));
        assertEquals("loop.js", error.sourceName());
        assertTrue(assertInstanceOf(PolyglotException.class, error.getCause()).isResourceExhausted());
        assertEquals("example:solid", limited.compile("after.js", SOLID).id());
    }

    @Test
    void rejectsInvalidStatementLimits() {
        assertThrows(IllegalArgumentException.class, () -> new GraalShaderCompiler(0));
        assertThrows(IllegalArgumentException.class, () -> new GraalShaderCompiler(-1));
    }

    @Test
    void missingFilesKeepIoFailure(@TempDir Path directory) {
        assertThrows(IOException.class, () -> compiler.compile(directory.resolve("missing.js")));
    }

    @Test
    void fileSourcesUseUtf8AndKeepTheirPathInDiagnostics(@TempDir Path directory) throws IOException {
        Path source = directory.resolve("着色器.js");
        Files.writeString(source, "// 中文\n" + SOLID, StandardCharsets.UTF_8);
        assertEquals("example:solid", compiler.compile(source).id());
        Files.writeString(source, "// 中文\nthrow new Error('错误');", StandardCharsets.UTF_8);
        var error = assertThrows(ShaderScriptException.class, () -> compiler.compile(source));
        assertEquals(source.toString(), error.sourceName());
        assertEquals(2, error.line());
    }

    @Test
    void oneCompilerCanBeUsedByIndependentThreads() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> compiler.compile("a.js", "const a = 1;\n" + SOLID));
            var second = executor.submit(() -> compiler.compile("b.js", "const a = 2;\n" + SOLID));
            assertSameProgram(first.get(), second.get());
        }
    }

    private static void assertSameProgram(ShaderProgram expected, ShaderProgram actual) {
        assertEquals(expected, actual);
        assertEquals(MinecraftGlsl150Backend.generate(expected).resources(),
                MinecraftGlsl150Backend.generate(actual).resources());
    }
}
