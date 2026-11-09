package lib.kasuga.uip.ldlib;

import com.lowdragmc.lowdraglib2.gui.texture.SDFRectTexture;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import lib.kasuga.rendering.models.mc.backend.ui.WorldUiFramebuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryStack;

import java.util.Arrays;

/** Real LDLib2 shader readback, including the ordinary-screen path outside the compatibility scope. */
final class LdlibSdfBlendSmokeTest {
    static void run() {
        var projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        var sorting = RenderSystem.getVertexSorting();
        var shader = RenderSystem.getShader();
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int[] blend = blend();
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST), blending = GL11.glIsEnabled(GL11.GL_BLEND);
        var modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix().translation(0, 0, WorldUiFramebuffer.GUI_VIEW_Z);
        RenderSystem.applyModelViewMatrix();
        try (var canvas = new WorldUiFramebuffer()) {
            RenderSystem.setProjectionMatrix(WorldUiFramebuffer.guiProjection(16, 16), VertexSorting.ORTHOGRAPHIC_Z);
            var mc = Minecraft.getInstance();
            var graphics = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
            canvas.render(16, 16, () -> {
                RenderSystem.defaultBlendFunc();
                // A normal 2D screen must preserve the existing opaque background too.
                draw(graphics, 0xff1e1f22);
                draw(graphics, 0x00000000);
                expectPixel(30, 31, 34, 255);
                check(Arrays.equals(blend(), new int[]{GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                        GL11.GL_ONE, GL11.GL_ZERO}), "Ordinary LDLib2 blend behavior changed");
            });
            canvas.render(16, 16, () -> {
                RenderSystem.defaultBlendFunc();
                int[] before = blend();
                {
                    draw(graphics, 0xff1e1f22);
                    draw(graphics, 0x00000000);
                    expectPixel(30, 31, 34, 255);
                    draw(graphics, 0x80ff0000);
                    // Premultiplied red overlays the opaque background exactly once.
                    expectPixel(143, 15, 17, 255);
                    check(Arrays.equals(before, blend()), "SDF draw leaked its blend factors");
                }
            });
            canvas.render(16, 16, () -> {
                {
                    SDFRectTexture.of(0xdd2c2c34).setRadius(6).draw(graphics, 0, 0, 0, 0, 16, 16, 0);
                    for (int x : new int[]{0, 15}) for (int y : new int[]{0, 15}) {
                        expectPixel(x, y, 0, 0, 0, 0);
                    }
                    expectPixel(38, 38, 45, 221);
                }
            });
            canvas.render(16, 16, () -> {
                {
                    draw(graphics, 0xff1e1f22);
                    SDFRectTexture.of(0xff71a4f4).setRadius(6).draw(graphics, 0, 0, 0, 0, 16, 16, 0);
                    for (int x : new int[]{0, 15}) for (int y : new int[]{0, 15}) {
                        expectPixel(x, y, 30, 31, 34, 255);
                    }
                    expectPixel(113, 164, 244, 255);
                }
            });
        } finally {
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(projection, sorting);
            RenderSystem.setShader(() -> shader);
            GlStateManager._glUseProgram(program);
            RenderSystem.blendFuncSeparate(blend[0], blend[1], blend[2], blend[3]);
            if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            if (blending) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
        }
    }

    private static void draw(GuiGraphics graphics, int color) {
        SDFRectTexture.of(color).draw(graphics, 0, 0, 0, 0, 16, 16, 0);
    }

    private static int[] blend() {
        return new int[]{GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),
                GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA)};
    }

    private static void expectPixel(int r, int g, int b, int a) {
        expectPixel(8, 8, r, g, b, a);
    }

    private static void expectPixel(int x, int y, int r, int g, int b, int a) {
        try (var stack = MemoryStack.stackPush()) {
            var pixel = stack.malloc(4);
            GL11.glReadPixels(x, y, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
            int[] expected = {r, g, b, a};
            for (int i = 0; i < 4; i++) check(Math.abs((pixel.get(i) & 255) - expected[i]) <= 1,
                    "SDF pixel (" + x + "," + y + ") channel " + i + ": expected " + expected[i] + ", got " + (pixel.get(i) & 255));
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
