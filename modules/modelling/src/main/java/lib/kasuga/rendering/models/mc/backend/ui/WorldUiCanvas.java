package lib.kasuga.rendering.models.mc.backend.ui;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import lib.kasuga.rendering.output.FrameTexture;
import lib.kasuga.rendering.output.gl.FramebufferScope;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

import java.util.ArrayDeque;
import java.util.Deque;

/** Keeps native widget/font depth and clipping in GUI space, then composites a single world plane. */
final class WorldUiCanvas implements AutoCloseable {
    private boolean rendering;
    private boolean closed;
    private final WorldUiFramebuffer target = new WorldUiFramebuffer((read, draw, viewport) -> {
        GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, read);
        GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER, draw);
        RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
    });

    void render(GuiGraphics worldGui, Renderable renderable, int left, int top, int width, int height,
                int mouseX, int mouseY, float partialTick) {
        if (closed) return;
        if (rendering) throw new IllegalStateException("Recursive world UI paint");
        rendering = true;
        try { renderContent(worldGui, renderable, left, top, width, height, mouseX, mouseY, partialTick); }
        finally {
            rendering = false;
            if (closed) target.close();
        }
    }

    private void renderContent(GuiGraphics worldGui, Renderable renderable, int left, int top, int width, int height,
                               int mouseX, int mouseY, float partialTick) {
        // No queued host vertices may be flushed into the panel's framebuffer.
        worldGui.flush();
        Minecraft mc = Minecraft.getInstance();
        double scale = Math.max(1, mc.getWindow().getGuiScale());
        int pixelWidth = Math.toIntExact((long) Math.ceil(width * scale));
        int pixelHeight = Math.toIntExact((long) Math.ceil(height * scale));
        FrameTexture frame;
        try (var state = new GuiRenderScope()) {
            frame = target.render(pixelWidth, pixelHeight, () -> {
                RenderSystem.setProjectionMatrix(WorldUiFramebuffer.guiProjection(width, height), VertexSorting.ORTHOGRAPHIC_Z);
                var modelView = RenderSystem.getModelViewStack();
                modelView.pushMatrix().translation(0, 0, WorldUiFramebuffer.GUI_VIEW_Z);
                RenderSystem.applyModelViewMatrix();
                RenderSystem.setShaderColor(1, 1, 1, 1);
                RenderSystem.disableScissor();
                RenderSystem.disableCull();
                RenderSystem.enableDepthTest();
                RenderSystem.depthFunc(GL11.GL_LEQUAL);
                RenderSystem.depthMask(true);
                RenderSystem.colorMask(true, true, true, true);
                RenderSystem.enableBlend();
                RenderSystem.blendEquation(GL14.GL_FUNC_ADD);
                RenderSystem.defaultBlendFunc();
                GuiGraphics gui = new CanvasGraphics(mc, left, top, width, height, pixelWidth, pixelHeight);
                gui.pose().translate(-left, -top, 0);
                try {
                    renderable.render(gui, mouseX, mouseY, partialTick);
                } finally {
                    try { gui.flush(); }
                    finally {
                        modelView.popMatrix();
                        RenderSystem.applyModelViewMatrix();
                    }
                }
            });
        }
        if (closed) return; // A custom render callback may remove its own panel.
        try (var state = new GuiRenderScope()) {
            RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
            RenderSystem.setShaderTexture(0, frame.textureId());
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.enableBlend();
            RenderSystem.blendEquation(GL14.GL_FUNC_ADD);
            // GUI drawing over transparent black stores premultiplied RGB.
            RenderSystem.blendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            RenderSystem.disableCull();
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.depthMask(false);
            var buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            Matrix4f pose = worldGui.pose().last().pose();
            // Framebuffer textures have their origin at the bottom left.
            buffer.addVertex(pose, left, top, 0).setUv(0, 1).setColor(-1);
            buffer.addVertex(pose, left, top + height, 0).setUv(0, 0).setColor(-1);
            buffer.addVertex(pose, left + width, top + height, 0).setUv(1, 0).setColor(-1);
            buffer.addVertex(pose, left + width, top, 0).setUv(1, 1).setColor(-1);
            BufferUploader.drawWithShader(buffer.buildOrThrow());
        }
    }

    @Override public void close() {
        closed = true;
        if (!rendering) target.close();
    }

    /** GuiGraphics normally converts scissor coordinates using the main window's height. */
    private static final class CanvasGraphics extends GuiGraphics {
        private final int left, top, width, height, pixelWidth, pixelHeight;
        private final Deque<ScreenRectangle> scissors = new ArrayDeque<>();
        CanvasGraphics(Minecraft mc, int left, int top, int width, int height, int pixelWidth, int pixelHeight) {
            super(mc, mc.renderBuffers().bufferSource());
            this.left = left; this.top = top; this.width = width; this.height = height;
            this.pixelWidth = pixelWidth; this.pixelHeight = pixelHeight;
        }
        @Override public int guiWidth() { return width; }
        @Override public int guiHeight() { return height; }
        @Override public void enableScissor(int minX, int minY, int maxX, int maxY) {
            ScreenRectangle rectangle = new ScreenRectangle(minX, minY, Math.max(0, maxX - minX), Math.max(0, maxY - minY));
            if (!scissors.isEmpty()) {
                rectangle = rectangle.intersection(scissors.peek());
                if (rectangle == null) rectangle = new ScreenRectangle(0, 0, 0, 0);
            }
            scissors.push(rectangle);
            applyScissor();
        }
        @Override public void disableScissor() {
            if (scissors.isEmpty()) throw new IllegalStateException("Unbalanced world UI scissor");
            scissors.pop();
            applyScissor();
        }
        @Override public boolean containsPointInScissor(int x, int y) {
            if (scissors.isEmpty()) return true;
            ScreenRectangle rectangle = scissors.peek();
            return x >= rectangle.left() && x < rectangle.right() && y >= rectangle.top() && y < rectangle.bottom();
        }
        private void applyScissor() {
            flush();
            if (scissors.isEmpty()) { RenderSystem.disableScissor(); return; }
            ScreenRectangle r = scissors.peek();
            double sx = (double) pixelWidth / width, sy = (double) pixelHeight / height;
            int x = (int) Math.floor((r.left() - left) * sx);
            int y = (int) Math.floor(pixelHeight - (r.bottom() - top) * sy);
            int right = (int) Math.ceil((r.right() - left) * sx);
            int upper = (int) Math.ceil(pixelHeight - (r.top() - top) * sy);
            RenderSystem.enableScissor(x, y, Math.max(0, right - x), Math.max(0, upper - y));
        }
    }

}
