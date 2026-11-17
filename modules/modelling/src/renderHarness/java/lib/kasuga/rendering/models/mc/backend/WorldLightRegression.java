package lib.kasuga.rendering.models.mc.backend;

import lib.kasuga.rendering.lighting.LightBuffer;
import lib.kasuga.rendering.lighting.LightSource;
import lib.kasuga.rendering.models.uml.backend.gpu.buf.tbo.TBOBuffer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;

import static lib.kasuga.rendering.models.mc.backend.GlFixture.*;

/** Production resource shaders and buffer layout, tested with actual color/depth textures. */
final class WorldLightRegression {
    private static final int WIDTH = 192;
    private static final int HEIGHT = 128;
    private static final String ROOT = "/assets/kasuga_lib/shaders/core/lighting/world_lights";

    private WorldLightRegression() {}

    static void run(Path output) throws Exception {
        int vertices = GL15.glGenBuffers();
        try (GlFixture fixture = new GlFixture();
             TBOBuffer lights = new TBOBuffer(() -> 8, LightBuffer.BYTES_PER_LIGHT * 4L, GL15.GL_DYNAMIC_DRAW)) {
            int program = fixture.program(resource(ROOT + ".vsh"), resource(ROOT + ".fsh"));
            GL20.glUseProgram(program);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertices);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, new float[]{0, 0, 0, 2, 0, 0, 0, 2, 0}, GL15.GL_STATIC_DRAW);
            int position = GL20.glGetAttribLocation(program, "Position");
            GL20.glEnableVertexAttribArray(position);
            GL20.glVertexAttribPointer(position, 3, GL11.GL_FLOAT, false, 0, 0L);
            Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(60), (float) WIDTH / HEIGHT, .1f, 100);
            GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program, "InverseViewProjection"), false,
                    new Matrix4f(projection).invert().get(new float[16]));
            float[] color = new float[WIDTH * HEIGHT * 4];
            Arrays.fill(color, .12f);
            for (int pixel = 0; pixel < WIDTH * HEIGHT; pixel++) color[pixel * 4 + 3] = .75f;
            int scene = fixture.texture(GL30.GL_RGBA32F, GL11.GL_RGBA, WIDTH, HEIGHT, color);
            int result = fixture.texture(GL30.GL_RGBA32F, GL11.GL_RGBA, WIDTH, HEIGHT, null);
            int target = fixture.framebuffer(result, 0);
            int depth = depth(fixture, projection, 6);
            bindTexture(0, scene);
            integer(program, "SceneSampler", 0);
            integer(program, "DepthSampler", 1);
            integer(program, "LightSampler", 8);
            float[] baseline = draw(program, lights, target, depth);
            expect(center(baseline, 0), .12, 1e-5, "No-light scene color");
            expect(center(baseline, 3), .75, 1e-6, "Scene alpha");
            LightSource point = LightSource.point(0, 0, -4).temperature(2700).intensity(16).range(8).build();
            float[] warm = draw(program, lights, target, depth, point);
            require(center(warm, 0) > center(warm, 2) + .04, "Warm point light has no warm tint");
            require(center(warm, 0) > warm[(HEIGHT / 2 * WIDTH + 8) * 4], "Point falloff is missing");
            image(output.resolve("world-light-point-warm.png"), WIDTH, HEIGHT, warm);
            float[] cool = draw(program, lights, target, depth, point.toBuilder().temperature(12000).build());
            require(center(cool, 2) > center(cool, 0), "Cool point light has no cool tint");
            image(output.resolve("world-light-point-cool.png"), WIDTH, HEIGHT, cool);
            float[] stronger = draw(program, lights, target, depth, point.toBuilder().intensity(32).build());
            require(center(stronger, 0) > center(warm, 0), "Intensity has no effect");
            image(output.resolve("world-light-point-strong.png"), WIDTH, HEIGHT, stronger);
            identical(baseline, draw(program, lights, target, depth, point.toBuilder().intensity(0).build()), "Disabled light");
            identical(baseline, draw(program, lights, target, depth, point.toBuilder().range(1).build()), "Range cutoff");
            LightSource panel = LightSource.area(0, 0, -4, 3, 1).temperature(4500).intensity(12).range(8).build();
            identical(baseline, draw(program, lights, target, depth, panel), "Back of one-sided panel");
            float[] area = draw(program, lights, target, depth, panel.toBuilder().rotation(180, 0).build());
            require(center(area, 0) > .2, "Front of area light is dark");
            identical(area, draw(program, lights, target, depth, panel.toBuilder().twoSided(true).build()), "Two-sided panel");
            image(output.resolve("world-light-area.png"), WIDTH, HEIGHT, area);
            LightSource volume = LightSource.volumetric(0, 0, -4).range(2).intensity(60).temperature(7500).medium(.5f, .25f).build();
            float[] fog = draw(program, lights, target, depth, volume);
            require(center(fog, 2) > .16, "Volumetric scattering is missing");
            image(output.resolve("world-light-volume.png"), WIDTH, HEIGHT, fog);
            identical(baseline, draw(program, lights, target, depth(fixture, projection, 1), volume), "Foreground depth did not occlude volume");
            identical(baseline, draw(program, lights, target, depth, volume.toBuilder().position(0, 0, -10).build()), "Volume behind wall");
            float[] skyFog = draw(program, lights, target, depth(fixture, projection, 100), volume);
            require(center(skyFog, 2) > .16, "Sky rays lost volume");
            float[] combined = draw(program, lights, target, depth, point, panel.toBuilder().rotation(180, 0).build(), volume);
            image(output.resolve("world-light-combined.png"), WIDTH, HEIGHT, combined);
            float[] reversed = draw(program, lights, target, depth, volume, panel.toBuilder().rotation(180, 0).build(), point);
            identical(combined, reversed, "Light insertion order changed output");
            identical(baseline, draw(program, lights, target, depth), "Light removal did not restore scene");
            noError("world lights");
        } finally {
            GL15.glDeleteBuffers(vertices);
        }
    }

    private static int depth(GlFixture fixture, Matrix4f projection, float distance) {
        float value = projection.transformProject(new Vector3f(0, 0, -distance)).z * .5f + .5f;
        float[] pixels = new float[WIDTH * HEIGHT];
        Arrays.fill(pixels, value);
        return fixture.texture(GL30.GL_DEPTH_COMPONENT32F, GL11.GL_DEPTH_COMPONENT, WIDTH, HEIGHT, pixels);
    }

    private static float[] draw(int program, TBOBuffer buffer, int target, int depth, LightSource... sources) {
        ByteBuffer data = MemoryUtil.memAlloc(Math.max(1, sources.length) * LightBuffer.BYTES_PER_LIGHT);
        try {
            for (LightSource source : sources) LightBuffer.write(data, source, 0, 0, 0);
            data.flip();
            if (data.hasRemaining()) buffer.updateAll(data);
        } finally {
            MemoryUtil.memFree(data);
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, target);
        GL11.glViewport(0, 0, WIDTH, HEIGHT);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glColorMask(true, true, true, true);
        bindTexture(1, depth);
        integer(program, "LightCount", sources.length);
        buffer.bind(8);
        try { GlFixture.draw(); }
        finally { buffer.unbind(); }
        float[] pixels = read(WIDTH, HEIGHT, GL11.GL_RGBA, 4);
        for (float value : pixels) require(Float.isFinite(value), "Non-finite lighting output");
        return pixels;
    }

    private static float center(float[] pixels, int channel) {
        return pixels[(HEIGHT / 2 * WIDTH + WIDTH / 2) * 4 + channel];
    }

    private static void identical(float[] expected, float[] actual, String label) {
        for (int index = 0; index < expected.length; index++) expect(actual[index], expected[index], 1e-5, label);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
