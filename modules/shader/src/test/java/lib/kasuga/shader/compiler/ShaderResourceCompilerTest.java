package lib.kasuga.shader.compiler;

import lib.kasuga.shader.ShaderProgram;
import lib.kasuga.shader.ShaderProgramProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaderResourceCompilerTest {

    @Test
    void writesProviderProgramsAsJarResources(@TempDir Path output) throws Exception {
        var result = ShaderResourceCompiler.compileProviders(List.of(new TestProvider()), output);

        assertEquals(1, result.programCount());
        assertEquals(3, result.resourceCount());
        assertTrue(Files.readString(output.resolve(
                "assets/compiler_test/shaders/core/solid.fsh"
        )).contains("fragColor = vec4(1.0, 0.0, 0.0, 1.0);"));
    }

    @Test
    void rejectsDuplicateProgramResources(@TempDir Path output) {
        ShaderProgram program = TestProvider.program();
        assertThrows(IllegalArgumentException.class,
                () -> ShaderResourceCompiler.compile(List.of(program, program), output));
    }

    @Test
    void commandLineCompilerRemovesStaleGeneratedResources(@TempDir Path output) throws Exception {
        Path stale = output.resolve("assets/compiler_test/shaders/core/removed.fsh");
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "stale");
        Path testClasses = Path.of(TestProvider.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());

        ShaderCompilerMain.main(new String[]{output.toString(), testClasses.toString()});

        assertTrue(Files.notExists(stale));
        assertTrue(Files.isRegularFile(output.resolve(
                "assets/compiler_test/shaders/core/solid.fsh"
        )));
    }

    public static final class TestProvider implements ShaderProgramProvider {
        @Override
        public List<ShaderProgram> shaderPrograms() {
            return List.of(program());
        }

        static ShaderProgram program() {
            return ShaderProgram.fullscreen("compiler_test:solid", shader ->
                    shader.fragmentColor(shader.vec4(1, 0, 0, 1))
            );
        }
    }
}
