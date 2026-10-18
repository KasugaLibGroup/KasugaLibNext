package lib.kasuga.rendering.models.mc.backend;

import lib.kasuga.rendering.models.uml.backend.gpu.GlslProgram;
import lib.kasuga.core.rendering.ShaderFunctionImport;
import java.io.StringReader;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small deterministic fixtures; all production shader bytes are recorded in the report. */
final class GlFixture implements AutoCloseable {
    static final Map<String, String> SHADER_HASHES = new LinkedHashMap<>();
    private final List<Integer> textures = new ArrayList<>(), framebuffers = new ArrayList<>(), programs = new ArrayList<>();
    private final int vao = GL30.glGenVertexArrays();

    GlFixture() {
        GL30.glBindVertexArray(vao);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(true);
        GL11.glColorMask(true, true, true, true);
        GL14.glBlendEquation(GL14.GL_FUNC_ADD);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    static String resource(String path) throws Exception {
        try (var stream = GlFixture.class.getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Missing fixture/production resource: " + path);
            byte[] bytes = stream.readAllBytes();
            SHADER_HASHES.put(path, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    static String mainFragment() throws Exception {
        String source = resource("/assets/kasuga_lib/shaders/core/ksglib_main.fsh");
        String fog = resource("/assets/minecraft/shaders/include/fog.glsl");
        fog = fog.replaceFirst("(?m)^#version[^\\n]*\\n", "");
        return source.replace("#moj_import <fog.glsl>", fog);
    }

    static String vertexSource(String path) throws Exception {
        String source = resource(path);
        for (String include : List.of("light", "fog")) {
            String imported = resource("/assets/minecraft/shaders/include/" + include + ".glsl")
                    .replaceFirst("(?m)^#version[^\\n]*\\n", "");
            source = source.replace("#moj_import <" + include + ".glsl>", imported);
        }
        String functions = ShaderFunctionImport.read(new StringReader(resource("/assets/kasuga_lib/shaders/ksg_skinning.transform.glsl")))
                .replaceFirst("(?m)^#version[^\\n]*\\n", "");
        return source.replace("#moj_import <kasuga_lib:ksg_skinning.transform.glsl>", functions);
    }

    int program(String fragment) {
        return program(GlslProgram.FULLSCREEN_VERTEX, fragment);
    }

    int program(String vertex, String fragment) {
        int program = GlslProgram.link(vertex, fragment);
        programs.add(program);
        return program;
    }

    int texture(int internal, int format, int width, int height, float[] pixels) {
        int texture = GL11.glGenTextures();
        textures.add(texture);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        if (pixels == null) {
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internal, width, height, 0, format, GL11.GL_FLOAT, 0L);
        } else {
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internal, width, height, 0, format, GL11.GL_FLOAT, pixels);
        }
        return texture;
    }

    int framebuffer(int color, int depth) {
        int framebuffer = GL30.glGenFramebuffers();
        framebuffers.add(framebuffer);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, color, 0);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depth, 0);
        GL11.glDrawBuffer(color == 0 ? GL11.GL_NONE : GL30.GL_COLOR_ATTACHMENT0);
        GL11.glReadBuffer(color == 0 ? GL11.GL_NONE : GL30.GL_COLOR_ATTACHMENT0);
        if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new AssertionError("Fixture framebuffer incomplete");
        }
        return framebuffer;
    }

    static void bindTexture(int unit, int texture) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
    }

    static void integer(int program, String name, int value) {
        GL20.glUniform1i(GL20.glGetUniformLocation(program, name), value);
    }

    static void scalar(int program, String name, float value) {
        GL20.glUniform1f(GL20.glGetUniformLocation(program, name), value);
    }

    static void draw() {
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
    }

    static float[] read(int width, int height, int format, int channels) {
        FloatBuffer buffer = MemoryUtil.memAllocFloat(Math.multiplyExact(width * height, channels));
        try {
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glReadPixels(0, 0, width, height, format, GL11.GL_FLOAT, buffer);
            float[] result = new float[buffer.remaining()];
            buffer.get(result);
            return result;
        } finally {
            MemoryUtil.memFree(buffer);
        }
    }

    static void expect(double actual, double expected, double tolerance, String description) {
        if (!Double.isFinite(actual) || Math.abs(actual - expected) > tolerance) {
            throw new AssertionError(description + ": actual=" + actual + ", expected=" + expected);
        }
    }

    static void noError(String label) {
        int error = GL11.glGetError();
        if (error != GL11.GL_NO_ERROR) throw new AssertionError(label + ": GL error 0x" + Integer.toHexString(error));
    }

    static void image(Path path, int width, int height, float[] rgba) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int offset = (y * width + x) * 4;
                int color = channel(rgba[offset + 3]) << 24 | channel(rgba[offset]) << 16
                        | channel(rgba[offset + 1]) << 8 | channel(rgba[offset + 2]);
                image.setRGB(x, height - y - 1, color);
            }
        }
        Files.createDirectories(path.toAbsolutePath().getParent());
        ImageIO.write(image, "png", path.toFile());
    }

    private static int channel(float value) {
        return Math.round(Math.clamp(value, 0f, 1f) * 255f);
    }

    @Override public void close() {
        GL20.glUseProgram(0);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GL30.glBindVertexArray(0);
        for (int program : programs) GL20.glDeleteProgram(program);
        for (int framebuffer : framebuffers) GL30.glDeleteFramebuffers(framebuffer);
        for (int texture : textures) GL11.glDeleteTextures(texture);
        GL30.glDeleteVertexArrays(vao);
    }
}
