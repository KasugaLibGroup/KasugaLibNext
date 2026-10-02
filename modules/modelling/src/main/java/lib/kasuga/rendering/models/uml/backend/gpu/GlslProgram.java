package lib.kasuga.rendering.models.uml.backend.gpu;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.util.Map;

/** Raw OpenGL program construction shared by the game adapters and standalone tests. */
public final class GlslProgram {
    public static final String FULLSCREEN_VERTEX = """
            #version 150
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    private GlslProgram() {}

    public static int link(String vertex, String fragment) {
        return link(vertex, fragment, Map.of(), new String[0]);
    }

    public static int link(String vertex, String fragment, Map<String, Integer> attributes, String[] feedback) {
        int vs = 0, fs = 0, program = 0;
        try {
            vs = compile(GL20.GL_VERTEX_SHADER, vertex);
            if (fragment != null) fs = compile(GL20.GL_FRAGMENT_SHADER, fragment);
            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, vs);
            if (fs != 0) GL20.glAttachShader(program, fs);
            for (var attribute : attributes.entrySet()) {
                GL20.glBindAttribLocation(program, attribute.getValue(), attribute.getKey());
            }
            if (feedback.length > 0) GL30.glTransformFeedbackVaryings(program, feedback, GL30.GL_INTERLEAVED_ATTRIBS);
            GL20.glLinkProgram(program);
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                throw new IllegalStateException("GLSL link failed: " + GL20.glGetProgramInfoLog(program));
            }
            int result = program;
            program = 0;
            return result;
        } finally {
            if (vs != 0) GL20.glDeleteShader(vs);
            if (fs != 0) GL20.glDeleteShader(fs);
            if (program != 0) GL20.glDeleteProgram(program);
        }
    }

    public static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("GLSL compile failed: " + log);
        }
        return shader;
    }
}
