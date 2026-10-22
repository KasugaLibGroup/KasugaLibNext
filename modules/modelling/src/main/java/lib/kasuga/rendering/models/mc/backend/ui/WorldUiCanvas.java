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
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryStack;

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
        try (var state = new DrawScope()) {
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
        try (var state = new DrawScope()) {
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

    /** Restore through Blaze3D so its state cache agrees with the actual GL state. */
    private static final class DrawScope implements AutoCloseable {
        private final Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        private final VertexSorting sorting = RenderSystem.getVertexSorting();
        private final ShaderInstance shader = RenderSystem.getShader();
        private final float[] color = RenderSystem.getShaderColor().clone();
        private final int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        private final boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST), blend = GL11.glIsEnabled(GL11.GL_BLEND);
        private final boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE), scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        private final boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        private final int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        private final int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        private final int srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        private final int equationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
        private final int equationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        private final int[] scissorBox = new int[4];
        private final boolean[] mask = new boolean[4];
        private final int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        private final int[] shaderTextures = new int[12], textures = new int[12];
        private final FramebufferScope framebuffer = new FramebufferScope((read, draw, viewport) -> {
            GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, read);
            GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER, draw);
            RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        });
        DrawScope() {
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox);
            try (var stack = MemoryStack.stackPush()) {
                var bytes = stack.malloc(4);
                GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, bytes);
                for (int i = 0; i < 4; i++) mask[i] = bytes.get(i) != 0;
            }
            for (int i = 0; i < textures.length; i++) {
                shaderTextures[i] = RenderSystem.getShaderTexture(i);
                GlStateManager._activeTexture(GL13.GL_TEXTURE0 + i);
                textures[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            }
            GlStateManager._activeTexture(activeTexture);
        }
        @Override public void close() {
            RenderSystem.setProjectionMatrix(projection, sorting);
            RenderSystem.setShader(() -> shader);
            GlStateManager._glUseProgram(program);
            RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
            if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
            if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
            if (scissor) RenderSystem.enableScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
            else RenderSystem.disableScissor();
            RenderSystem.depthMask(depthMask);
            RenderSystem.depthFunc(depthFunc);
            RenderSystem.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
            GL20.glBlendEquationSeparate(equationRgb, equationAlpha);
            RenderSystem.colorMask(mask[0], mask[1], mask[2], mask[3]);
            for (int i = 0; i < textures.length; i++) {
                RenderSystem.setShaderTexture(i, shaderTextures[i]);
                GlStateManager._activeTexture(GL13.GL_TEXTURE0 + i);
                GlStateManager._bindTexture(textures[i]);
            }
            GlStateManager._activeTexture(activeTexture);
            framebuffer.close();
        }
    }
}
