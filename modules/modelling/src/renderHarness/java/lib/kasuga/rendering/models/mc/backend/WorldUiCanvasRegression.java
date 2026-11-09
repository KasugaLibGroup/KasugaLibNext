package lib.kasuga.rendering.models.mc.backend;

import lib.kasuga.rendering.models.mc.backend.ui.WorldUiFramebuffer;
import lib.kasuga.rendering.output.FrameTexture;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import static lib.kasuga.rendering.models.mc.backend.GlFixture.*;

/** Actual GPU readback for GUI depth, transparent canvas contents, and target ownership. */
final class WorldUiCanvasRegression {
    static void run() throws Exception {
        try (var gl = new GlFixture(); var canvas = new WorldUiFramebuffer()) {
            int hostColor = gl.texture(GL11.GL_RGBA8, GL11.GL_RGBA, 8, 8, null);
            int hostDepth = gl.texture(GL14.GL_DEPTH_COMPONENT24, GL11.GL_DEPTH_COMPONENT, 8, 8, null);
            int host = gl.framebuffer(hostColor, hostDepth);
            GL11.glViewport(0, 0, 8, 8);
            int program = gl.program("""
                    #version 150
                    uniform mat4 Projection;
                    uniform float BaseZ;
                    uniform float LayerZ;
                    void main() {
                        vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);
                        gl_Position=Projection*vec4(p*2-1,BaseZ+LayerZ,1);
                    }
                    """, """
                    #version 150
                    out vec4 fragColor;
                    void main(){fragColor=vec4(1);}
                    """);
            // Reproduce the old path: a font's 0.03 GUI-Z delta disappears in perspective depth.
            float[] oldDepths = layers(program, new Matrix4f().perspective(1, 1, .05f, 128), -2.5f, .1f / 20000);
            float[] newDepths = new float[2];
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, host);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
            GL11.glViewport(2, 3, 5, 6);
            FrameTexture frame = canvas.render(8, 8, () -> {
                float[] clear = read(8, 8, GL11.GL_RGBA, 4);
                for (float channel : clear) expect(channel, 0, 0, "transparent canvas clear");
                float[] depths = layers(program, WorldUiFramebuffer.guiProjection(2, 2)
                        .translate(1, 1, 0), WorldUiFramebuffer.GUI_VIEW_Z, 1);
                System.arraycopy(depths, 0, newDepths, 0, 2);
            });
            // The assertion failed on the old path before introducing the canvas.
            if (!(newDepths[1] < newDepths[0])) throw new AssertionError(
                    "GUI text and shadow collapse to the same GPU depth: " + newDepths[1] + " / " + newDepths[0]);
            if (newDepths[0] - newDepths[1] < 1e-6f) throw new AssertionError("Insufficient GUI depth separation");
            System.out.println("World UI depth separation: legacy=" + (oldDepths[0] - oldDepths[1])
                    + ", canvas=" + (newDepths[0] - newDepths[1]));
            restored(host, 0);
            verifyGuiDepthBounds(canvas, program, host);
            verifyAlpha(gl, canvas, frame, host);
            verifyLifecycle(canvas, host);
            noError("world UI canvas");
        }
    }

    private static void verifyGuiDepthBounds(WorldUiFramebuffer canvas, int program, int host) {
        for (float guiZ : new float[]{-10000, 0, 10000, 10000 + .03f}) {
            FrameTexture frame = canvas.render(8, 8, () -> {
                GL11.glDisable(GL11.GL_BLEND);
                GL11.glEnable(GL11.GL_DEPTH_TEST);
                GL11.glDepthMask(true);
                GL11.glDepthFunc(GL11.GL_LEQUAL);
                GL20.glUseProgram(program);
                Matrix4f projection = WorldUiFramebuffer.guiProjection(2, 2).translate(1, 1, 0);
                GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program, "Projection"), false, projection.get(new float[16]));
                scalar(program, "BaseZ", WorldUiFramebuffer.GUI_VIEW_Z);
                scalar(program, "LayerZ", guiZ);
                draw();
                float[] pixel = read(8, 8, GL11.GL_RGBA, 4);
                expect(pixel[36 * 4 + 3], 1, 0, "GUI Z boundary remains visible: " + guiZ);
            });
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, frame.framebufferId());
            expect(read(8, 8, GL11.GL_RGBA, 4)[36 * 4], 1, 0, "GUI Z color: " + guiZ);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, host);
        }
    }

    private static float[] layers(int program, Matrix4f projection, float baseZ, float scale) {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glDepthMask(true);
        GL11.glColorMask(true, true, true, true);
        GL11.glClearDepth(1);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL20.glUseProgram(program);
        GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program, "Projection"), false, projection.get(new float[16]));
        scalar(program, "BaseZ", baseZ);
        scalar(program, "LayerZ", 0);
        draw();
        float background = read(8, 8, GL11.GL_DEPTH_COMPONENT, 1)[36];
        scalar(program, "LayerZ", .03f * scale);
        draw();
        float foreground = read(8, 8, GL11.GL_DEPTH_COMPONENT, 1)[36];
        return new float[]{background, foreground};
    }

    private static void verifyAlpha(GlFixture gl, WorldUiFramebuffer canvas, FrameTexture old, int host) {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, old.framebufferId());
        int oldDepth = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, host);
        int solid = gl.program("""
                #version 150
                out vec4 fragColor;
                void main(){fragColor=vec4(1,0,0,0.5);}
                """);
        FrameTexture frame = canvas.render(2, 2, () -> {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL20.glUseProgram(solid);
            draw();
        });
        if (GL11.glIsTexture(old.textureId()) || GL30.glIsFramebuffer(old.framebufferId()))
            throw new AssertionError("Resizing retained the old canvas attachments");
        // Query immediately, before another allocation can legitimately reuse a retired GLuint.
        if (GL30.glIsRenderbuffer(oldDepth))
            throw new AssertionError("Resizing retained the old canvas depth attachment");
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, frame.framebufferId());
        float[] pixels = read(2, 2, GL11.GL_RGBA, 4);
        for (int i = 0; i < 4; i++) {
            expect(pixels[i * 4], .5, 1d / 255, "premultiplied canvas RGB");
            expect(pixels[i * 4 + 3], .5, 1d / 255, "preserved canvas alpha");
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, host);
        GL11.glViewport(0, 0, 8, 8);
        GL11.glClearColor(.2f, .4f, .8f, 1);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        int composite = gl.program("""
                #version 150
                out vec2 uv;
                void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2-1,0,1);uv=p;}
                """, """
                #version 150
                uniform sampler2D Canvas;
                in vec2 uv;
                out vec4 fragColor;
                void main(){fragColor=texture(Canvas,uv);}
                """);
        GL20.glUseProgram(composite);
        integer(composite, "Canvas", 0);
        bindTexture(0, frame.textureId());
        GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        draw();
        pixels = read(8, 8, GL11.GL_RGBA, 4);
        expect(pixels[0], .6, 2d / 255, "single alpha multiplication: red");
        expect(pixels[1], .2, 2d / 255, "single alpha multiplication: green");
        expect(pixels[2], .4, 2d / 255, "single alpha multiplication: blue");
        expect(pixels[3], 1, 1d / 255, "opaque destination stays opaque");
    }

    private static void verifyLifecycle(WorldUiFramebuffer canvas, int host) {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, host);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
        GL11.glViewport(2, 3, 5, 6);
        try {
            canvas.render(0, 4, () -> {});
            throw new AssertionError("Zero-size canvas accepted");
        } catch (IllegalArgumentException expected) { restored(host, 0); }
        try {
            canvas.render(4, 4, () -> { throw new IllegalStateException("intentional paint failure"); });
            throw new AssertionError("Paint failure swallowed");
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().equals("intentional paint failure")) throw expected;
            restored(host, 0);
        }
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(0, 0, 0, 0);
        GL11.glColorMask(false, false, false, false);
        GL11.glDepthMask(false);
        FrameTexture frame = canvas.render(4, 4, () -> {
            if (!GL11.glIsEnabled(GL11.GL_SCISSOR_TEST) || GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK))
                throw new AssertionError("Canvas clear leaked write-mask/scissor state");
            for (float channel : read(4, 4, GL11.GL_RGBA, 4)) expect(channel, 0, 0, "masked clear still transparent");
        });
        restored(host, 0);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, frame.framebufferId());
        int depth = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, host);
        canvas.close();
        canvas.close();
        if (GL11.glIsTexture(frame.textureId()) || GL30.glIsFramebuffer(frame.framebufferId()) || GL30.glIsRenderbuffer(depth))
            throw new AssertionError("Closed canvas retains GPU objects");
        try {
            canvas.render(4, 4, () -> {});
            throw new AssertionError("Closed canvas reused");
        } catch (IllegalStateException expected) { /* terminal ownership */ }
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glColorMask(true, true, true, true);
        GL11.glDepthMask(true);
    }

    private static void restored(int read, int draw) {
        expect(GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING), read, 0, "canvas restores read FBO");
        expect(GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING), draw, 0, "canvas restores draw FBO");
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        expect(viewport[0], 2, 0, "canvas restores viewport origin");
        expect(viewport[3], 6, 0, "canvas restores viewport height");
    }
}
