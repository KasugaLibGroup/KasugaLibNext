package lib.kasuga.rendering.cloud.gl;

import org.lwjgl.opengl.*;

/** Raw GL changes are fully restored, so the host's cached render state remains valid. */
final class CloudGlState implements AutoCloseable {
    final int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
    final int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
    final int[] viewport = new int[4];
    private final int[] scissorBox = new int[4];
    private final int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM), vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
    private final int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
    private final int[] textures2d = new int[6], textures3d = new int[6];
    private final int[] samplers = GL.getCapabilities().OpenGL33 ? new int[6] : null;
    private final boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST), blend = GL11.glIsEnabled(GL11.GL_BLEND);
    private final boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE), scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
    private final boolean stencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST), discard = GL11.glIsEnabled(GL30.GL_RASTERIZER_DISCARD);
    private final boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
    private final byte[] colorMask = new byte[4];
    private final int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
    private final int srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
    private final int eqRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB), eqAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
    private static final int[] UNPACK_PARAMETERS = {GL11.GL_UNPACK_ALIGNMENT, GL11.GL_UNPACK_ROW_LENGTH,
            GL11.GL_UNPACK_SKIP_PIXELS, GL11.GL_UNPACK_SKIP_ROWS, GL12.GL_UNPACK_IMAGE_HEIGHT, GL12.GL_UNPACK_SKIP_IMAGES,
            GL11.GL_UNPACK_SWAP_BYTES};
    private final int[] unpack = new int[UNPACK_PARAMETERS.length];
    private final int unpackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);

    CloudGlState() {
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox);
        for (int i = 0; i < unpack.length; i++) unpack[i] = GL11.glGetInteger(UNPACK_PARAMETERS[i]);
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            var mask = stack.malloc(4);
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask); mask.get(colorMask);
        }
        for (int i = 0; i < textures2d.length; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
            textures2d[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            textures3d[i] = GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D);
            if (samplers != null) samplers[i] = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        }
        GL13.glActiveTexture(activeTexture);
    }

    @Override public void close() {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read); GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
        GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        GL11.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
        GL20.glUseProgram(program); GL30.glBindVertexArray(vao);
        enable(GL11.GL_DEPTH_TEST, depth); enable(GL11.GL_BLEND, blend); enable(GL11.GL_CULL_FACE, cull); enable(GL11.GL_SCISSOR_TEST, scissor);
        enable(GL11.GL_STENCIL_TEST, stencil); enable(GL30.GL_RASTERIZER_DISCARD, discard);
        GL11.glDepthMask(depthMask); GL11.glColorMask(colorMask[0] != 0, colorMask[1] != 0, colorMask[2] != 0, colorMask[3] != 0);
        GL14.glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha); GL20.glBlendEquationSeparate(eqRgb, eqAlpha);
        for (int i = 0; i < unpack.length; i++) GL11.glPixelStorei(UNPACK_PARAMETERS[i], unpack[i]);
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
        for (int i = 0; i < textures2d.length; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures2d[i]); GL11.glBindTexture(GL12.GL_TEXTURE_3D, textures3d[i]);
            if (samplers != null) GL33.glBindSampler(i, samplers[i]);
        }
        GL13.glActiveTexture(activeTexture);
    }

    static void tightUnpack() {
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        for (int parameter : UNPACK_PARAMETERS) GL11.glPixelStorei(parameter, parameter == GL11.GL_UNPACK_ALIGNMENT ? 1 : 0);
    }

    private static void enable(int capability, boolean enabled) { if (enabled) GL11.glEnable(capability); else GL11.glDisable(capability); }
}
