package lib.kasuga.rendering.models.mc.backend;
import lib.kasuga.core.rendering.ShaderFunctionImport;
import java.io.StringReader;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ShaderFunctionImportTest {
    @Test void removesInterfaceAndEntryButPreservesIntegerDeclarationsAndFunctions() throws Exception {
        String source="""
                #version 150
                in vec3 Position;
                  uniform samplerBuffer Bones;
                out vec3 tf_Position;
                int boneOffset = 0;
                mat4 skin(int index) { return mat4(1); }
                  void main() { tf_Position = Position; }
                vec3 unreachable = vec3(0);
                """;
        String result=ShaderFunctionImport.read(new StringReader(source));
        assertTrue(result.contains("int boneOffset = 0;")); assertTrue(result.contains("mat4 skin("));
        assertFalse(result.contains("in vec3")); assertFalse(result.contains("uniform"));
        assertFalse(result.contains("void main")); assertFalse(result.contains("unreachable"));
    }
}
