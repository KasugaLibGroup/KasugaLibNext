package lib.kasuga.rendering.output.mc;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import lib.kasuga.rendering.output.gl.FramebufferScope;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import com.mojang.blaze3d.shaders.FogShape;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Restores the state that native world rendering and final blits overwrite. */
final class WorldRenderScope implements AutoCloseable {
    private final FramebufferScope framebuffer = new FramebufferScope(MinecraftFrameOutputs.restorer());
    private final Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
    private final VertexSorting sorting = RenderSystem.getVertexSorting();
    private final boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
    private final boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
    private final boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
    private final boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
    private final boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
    private final int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
    private final int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
    private final int dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
    private final int srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
    private final int dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
    private final int scissorX, scissorY, scissorWidth, scissorHeight;
    private final boolean maskRed, maskGreen, maskBlue, maskAlpha;
    private final float shaderRed, shaderGreen, shaderBlue, shaderAlpha;
    private final WorldViewFog fog = WorldViewFog.capture();
    private final float fogStart = RenderSystem.getShaderFogStart(), fogEnd = RenderSystem.getShaderFogEnd();
    private final float fogRed, fogGreen, fogBlue, fogAlpha;
    private final FogShape fogShape = RenderSystem.getShaderFogShape();
    private final float clearRed, clearGreen, clearBlue, clearAlpha;

    WorldRenderScope() {
        var shaderColor = RenderSystem.getShaderColor();
        shaderRed = shaderColor[0]; shaderGreen = shaderColor[1]; shaderBlue = shaderColor[2]; shaderAlpha = shaderColor[3];
        var fogColor = RenderSystem.getShaderFogColor();
        fogRed = fogColor[0]; fogGreen = fogColor[1]; fogBlue = fogColor[2]; fogAlpha = fogColor[3];
        try (var stack = MemoryStack.stackPush()) {
            long box = stack.nmalloc(4, 16), clear = stack.nmalloc(4, 16), mask = stack.nmalloc(1, 4);
            GL11.nglGetIntegerv(GL11.GL_SCISSOR_BOX, box);
            GL11.nglGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clear);
            GL11.nglGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
            scissorX = MemoryUtil.memGetInt(box); scissorY = MemoryUtil.memGetInt(box + 4);
            scissorWidth = MemoryUtil.memGetInt(box + 8); scissorHeight = MemoryUtil.memGetInt(box + 12);
            clearRed = MemoryUtil.memGetFloat(clear); clearGreen = MemoryUtil.memGetFloat(clear + 4);
            clearBlue = MemoryUtil.memGetFloat(clear + 8); clearAlpha = MemoryUtil.memGetFloat(clear + 12);
            maskRed = MemoryUtil.memGetByte(mask) != 0; maskGreen = MemoryUtil.memGetByte(mask + 1) != 0;
            maskBlue = MemoryUtil.memGetByte(mask + 2) != 0; maskAlpha = MemoryUtil.memGetByte(mask + 3) != 0;
        }
    }

    @Override public void close() {
        RenderSystem.setProjectionMatrix(projection, sorting);
        if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
        if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
        if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
        if (scissor) RenderSystem.enableScissor(scissorX, scissorY, scissorWidth, scissorHeight);
        else RenderSystem.disableScissor();
        RenderSystem.depthMask(depthMask);
        RenderSystem.depthFunc(depthFunc);
        RenderSystem.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
        RenderSystem.colorMask(maskRed, maskGreen, maskBlue, maskAlpha);
        RenderSystem.setShaderColor(shaderRed, shaderGreen, shaderBlue, shaderAlpha);
        fog.install();
        RenderSystem.setShaderFogStart(fogStart); RenderSystem.setShaderFogEnd(fogEnd);
        RenderSystem.setShaderFogColor(fogRed, fogGreen, fogBlue, fogAlpha);
        RenderSystem.setShaderFogShape(fogShape);
        RenderSystem.clearColor(clearRed, clearGreen, clearBlue, clearAlpha);
        framebuffer.close();
    }
}
