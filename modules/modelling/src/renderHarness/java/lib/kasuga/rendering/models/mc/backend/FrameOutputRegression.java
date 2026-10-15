package lib.kasuga.rendering.models.mc.backend;

import lib.kasuga.rendering.output.FrameBlitter;
import lib.kasuga.rendering.output.FrameOutputMode;
import lib.kasuga.rendering.output.FrameOutputRouter;
import lib.kasuga.rendering.output.FrameOutputTarget;
import lib.kasuga.rendering.output.OutputFrame;
import java.util.ArrayList;
import lib.kasuga.rendering.output.FrameTexture;
import lib.kasuga.rendering.output.gl.OutputFramebuffer;
import lib.kasuga.rendering.output.gl.FramebufferScope;
import lib.kasuga.rendering.output.gl.RgbaReadback;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import static lib.kasuga.rendering.models.mc.backend.GlFixture.*;

/** Exercises the production FBO with Minecraft's final-blit fragment shader. */
final class FrameOutputRegression {
    static void run() throws Exception {
        try (var gl = new GlFixture(); var output = new OutputFramebuffer()) {
            int source = gl.texture(GL11.GL_RGBA8, GL11.GL_RGBA, 2, 2,
                    new float[]{1,0,0,.1f, 0,1,0,.2f, 0,0,1,.3f, 1,1,0,.4f});
            int sourceFbo = gl.framebuffer(source, 0);
            int screenTexture = gl.texture(GL11.GL_RGBA8, GL11.GL_RGBA, 2, 2, null);
            int screenFbo = gl.framebuffer(screenTexture, 0);
            int program = gl.program("""
                    #version 150
                    out vec2 texCoord;
                    void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);
                        gl_Position=vec4(p*2-1,1,1);texCoord=p;}
                    """, resource("/assets/minecraft/shaders/core/blit_screen.fsh"));
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, sourceFbo);
            float[] expected = read(2, 2, GL11.GL_RGBA, 4);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, screenFbo);
            GL11.glViewport(3, 4, 5, 6);
            FrameBlitter finalBlit = (width, height) -> {
                GL11.glViewport(0, 0, width, height);
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glDisable(GL11.GL_BLEND);
                GL11.glColorMask(true, true, true, false);
                GL20.glUseProgram(program);
                integer(program, "DiffuseSampler", 0);
                bindTexture(0, source);
                draw();
                GL20.glUseProgram(0);
                GL11.glColorMask(true, true, true, true);
            };
            FrameTexture frame = output.capture(finalBlit, 2, 2);
            expect(GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING), sourceFbo, 0, "read binding restored");
            expect(GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING), screenFbo, 0, "draw binding restored");
            int[] viewport = new int[4]; GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            expect(viewport[0], 3, 0, "viewport x restored");
            expect(viewport[3], 6, 0, "viewport height restored");
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, frame.framebufferId());
            float[] captured = read(2, 2, GL11.GL_RGBA, 4);
            for (int pixel = 0; pixel < 4; pixel++) {
                for (int channel = 0; channel < 3; channel++) {
                    expect(captured[pixel * 4 + channel], expected[pixel * 4 + channel], 0, "final RGB/orientation identity");
                }
                expect(captured[pixel * 4 + 3], 1, 0, "opaque screen alpha");
            }
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, sourceFbo);
            // Sodium's optimized screen path copies RGBA instead of using the RGB-only shader draw.
            FrameTexture copied = output.capture((w, h) -> GL30.glBlitFramebuffer(0, 0, 2, 2, 0, 0, w, h,
                    GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST), 2, 2);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, copied.framebufferId());
            float[] copyPixels = read(2, 2, GL11.GL_RGBA, 4);
            for (int pixel = 0; pixel < 4; pixel++) {
                for (int channel = 0; channel < 3; channel++)
                    expect(copyPixels[pixel * 4 + channel], expected[pixel * 4 + channel], 0, "native blit RGB identity");
                expect(copyPixels[pixel * 4 + 3], 1, 0, "native blit opaque alpha");
            }
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, sourceFbo);
            int packBuffer = GL15.glGenBuffers();
            try {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packBuffer);
                GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, 128, GL15.GL_STREAM_READ);
                GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 8);
                GL11.glPixelStorei(GL12.GL_PACK_ROW_LENGTH, 7);
                GL11.glPixelStorei(GL12.GL_PACK_SKIP_ROWS, 1);
                GL11.glPixelStorei(GL12.GL_PACK_SKIP_PIXELS, 2);
                byte[] bytes = RgbaReadback.copy(frame);
                for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) for (int c = 0; c < 4; c++)
                    expect(Byte.toUnsignedInt(bytes[(y * 2 + x) * 4 + c]),
                            Math.round(captured[((1 - y) * 2 + x) * 4 + c] * 255), 0, "top-left CPU RGBA");
                expect(GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING), packBuffer, 0, "PBO restored");
                expect(GL11.glGetInteger(GL12.GL_PACK_ROW_LENGTH), 7, 0, "pack row length restored");
                expect(GL11.glGetInteger(GL12.GL_PACK_SKIP_ROWS), 1, 0, "pack skip rows restored");
                expect(GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING), sourceFbo, 0, "readback binding restored");
            } finally {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
                GL15.glDeleteBuffers(packBuffer);
                GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
                GL11.glPixelStorei(GL12.GL_PACK_ROW_LENGTH, 0);
                GL11.glPixelStorei(GL12.GL_PACK_SKIP_ROWS, 0);
                GL11.glPixelStorei(GL12.GL_PACK_SKIP_PIXELS, 0);
            }
            verifySharedFanout(finalBlit, captured);
            FrameTexture resized = output.capture(finalBlit, 4, 2);
            if (GL30.glIsFramebuffer(frame.framebufferId()) || GL11.glIsTexture(frame.textureId())) {
                throw new AssertionError("Retired resize allocation remains live");
            }
            expect(resized.width(), 4, 0, "output resize width");
            try {
                output.capture((w, h) -> {
                    GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
                    GL11.glViewport(0, 0, 1, 1);
                    throw new IllegalStateException("intentional blit failure");
                }, 4, 2);
                throw new AssertionError("Blit failure was swallowed");
            } catch (IllegalStateException expectedFailure) {
                expect(GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING), sourceFbo, 0, "failure read restoration");
                expect(GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING), screenFbo, 0, "failure draw restoration");
            }
            output.close(); output.close();
            if (GL30.glIsFramebuffer(resized.framebufferId()) || GL11.glIsTexture(resized.textureId())) {
                throw new AssertionError("Output resources leaked after close");
            }
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            noError("completed-frame output");
        }
    }

    private static void verifySharedFanout(FrameBlitter blitter, float[] expected) throws Exception {
        int[] allocations = {0}, captures = {0}, closes = {0};
        var received = new ArrayList<OutputFrame<FrameTexture>>();
        try (var router = new FrameOutputRouter<FrameTexture>(() -> {}, () -> {
            allocations[0]++;
            var framebuffer = new OutputFramebuffer();
            return new FrameOutputTarget<FrameTexture>() {
                public FrameTexture capture(FrameBlitter draw, int width, int height) {
                    captures[0]++;
                    return framebuffer.capture(draw, width, height);
                }
                public void close() { closes[0]++; framebuffer.close(); }
            };
        })) {
            var first = router.register("shared", FrameOutputMode.MIRROR, received::add);
            var second = router.register("shared", FrameOutputMode.OFFSCREEN_ONLY, received::add);
            router.register("shared", FrameOutputMode.MIRROR, frame -> {
                throw new IllegalStateException("intentional failed consumer of shared GPU storage");
            });
            var delivery = router.publish("shared", 2, 2, blitter);
            expect(delivery.delivered(), 2, 0, "shared frame deliveries");
            expect(delivery.failures().size(), 1, 0, "shared consumer failure isolated");
            expect(allocations[0], 1, 0, "one FBO allocation for three consumers");
            expect(captures[0], 1, 0, "one final blit for three consumers");
            if (received.get(0) != received.get(1)) throw new AssertionError("Frames were not shared by identity");
            FrameTexture texture = received.getFirst().resource();
            try (var scope = new FramebufferScope(FramebufferScope.OPENGL)) {
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, texture.framebufferId());
                float[] pixels = read(2, 2, GL11.GL_RGBA, 4);
                for (int i = 0; i < pixels.length; i++) expect(pixels[i], expected[i], 0, "shared frame RGB/alpha identity");
            }
            first.close();
            expect(closes[0], 0, 0, "closing one subscriber preserves shared FBO");
            if (!GL11.glIsTexture(texture.textureId())) throw new AssertionError("Shared texture retired too early");
            router.publish("shared", 4, 2, blitter);
            expect(captures[0], 2, 0, "one capture after consumer failure and resize");
            if (GL11.glIsTexture(texture.textureId())) throw new AssertionError("Shared resize retained old texture");
            FrameTexture resized = second.latest().orElseThrow().resource();
            second.close();
            expect(closes[0], 1, 0, "last subscriber closes shared FBO once");
            if (GL11.glIsTexture(resized.textureId()) || GL30.glIsFramebuffer(resized.framebufferId()))
                throw new AssertionError("Shared frame leaked after last subscriber closed");
        }
        expect(closes[0], 1, 0, "router shutdown does not close shared FBO twice");
    }

}
