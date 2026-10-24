package lib.kasuga.rendering.models.mc.backend.ui;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import lib.kasuga.rendering.output.gl.FramebufferScope;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.system.MemoryStack;

/** Restore through Blaze3D so its state cache agrees with the actual GL state. */
public final class GuiRenderScope implements AutoCloseable {
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
    private final int[] shaderTextures = new int[12], textures = new int[12], bufferTextures = new int[12];
    private final int vertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
    private final int arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
    private final FramebufferScope framebuffer = new FramebufferScope((read, draw, viewport) -> {
        GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, read);
        GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER, draw);
        RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
    });
    public GuiRenderScope() {
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
            bufferTextures[i] = GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER);
        }
        GlStateManager._activeTexture(activeTexture);
    }
    @Override public void close() {
        // Clear the shader's program cache before restoring a program outside ShaderInstance.apply().
        var currentShader = RenderSystem.getShader();
        if (currentShader != null) currentShader.clear();
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
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, bufferTextures[i]);
        }
        GlStateManager._activeTexture(activeTexture);
        com.mojang.blaze3d.vertex.BufferUploader.reset();
        GlStateManager._glBindVertexArray(vertexArray);
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer);
        framebuffer.close();
    }
}
