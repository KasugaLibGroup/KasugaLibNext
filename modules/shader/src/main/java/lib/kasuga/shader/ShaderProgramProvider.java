package lib.kasuga.shader;

import java.util.Collection;

/** Implement in the dedicated shader source set; the Gradle task discovers providers automatically. */
@FunctionalInterface
public interface ShaderProgramProvider {
    Collection<ShaderProgram> shaderPrograms();
}
