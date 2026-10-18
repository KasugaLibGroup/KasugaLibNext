package lib.kasuga.rendering.models.uml.backend.gpu.program;

import lib.kasuga.rendering.models.uml.backend.gpu.GpuContext;
import lib.kasuga.rendering.models.uml.backend.gpu.GpuObject;
import lombok.Getter;
import org.lwjgl.opengl.GL20;

public class GlslProgram implements GpuObject<GlslProgram> {

    @Getter
    private final int shaderType;

    @Getter
    private final int programId;

    @Getter
    private final GpuContext<GlslProgram> context;

    private boolean closed = false;

    public GlslProgram(int shaderType, String sourceCode) {
        this.shaderType = shaderType;
        programId = compileShader(shaderType, sourceCode);
        context = new GlslProgramContext();
    }

    public static int compileShader(int shaderType, String source) {
        int shaderId = GL20.glCreateShader(shaderType);
        GL20.glShaderSource(shaderId, source);
        GL20.glCompileShader(shaderId);
        int compileStatus = GL20.glGetShaderi(shaderId, GL20.GL_COMPILE_STATUS);
        if (compileStatus == GL20.GL_FALSE) {
            String infoLog = GL20.glGetShaderInfoLog(shaderId);
            throw new RuntimeException("Failed to compile shader: " + infoLog);
        }
        return shaderId;
    }

    @Override
    public void bind(int index) {
        checkOpen();
        if (!context.isEmpty()) return;
        context.enter(this);
    }

    @Override
    public void unbind() {
        checkOpen();
        context.exit(this);
    }

    @Override
    public void close() throws Exception {
        if (closed) return;
        if (!context.isEmpty()) unbind();
        GL20.glDeleteShader(programId);
        closed = true;
    }

    public void checkOpen() {
        if (closed) {
            throw new IllegalStateException("Cannot operate on a closed GLSL Program.");
        }

    }
}
