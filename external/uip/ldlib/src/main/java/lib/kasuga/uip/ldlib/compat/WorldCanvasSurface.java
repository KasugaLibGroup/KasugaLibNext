package lib.kasuga.uip.ldlib.compat;

import com.lowdragmc.lowdraglib2.gui.ui.rendering.UISurface;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Adapts the currently bound Kasuga canvas to LDLib2's public off-screen rendering contract. */
public record WorldCanvasSurface(RenderTarget target, double guiScale) implements UISurface {
    public static WorldCanvasSurface capture(GuiGraphics graphics) {
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        return new WorldCanvasSurface(new BorrowedTarget(viewport[2], viewport[3]),
                (double) viewport[2] / graphics.guiWidth());
    }

    @Override public int screenWidth() { return Minecraft.getInstance().getWindow().getScreenWidth(); }
    @Override public int screenHeight() { return Minecraft.getInstance().getWindow().getScreenHeight(); }
    @Override public long windowHandle() { return Minecraft.getInstance().getWindow().getWindow(); }

    /** No allocation or ownership transfer: WorldUiCanvas alone owns and retires these GL objects. */
    private static final class BorrowedTarget extends RenderTarget {
        BorrowedTarget(int width, int height) {
            super(false);
            this.width = this.viewWidth = width;
            this.height = this.viewHeight = height;
            this.frameBufferId = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            this.colorTextureId = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER,
                    GL30.GL_COLOR_ATTACHMENT0, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        }
        @Override public void destroyBuffers() { /* borrowed */ }
    }
}
