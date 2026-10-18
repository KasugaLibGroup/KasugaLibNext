package lib.kasuga.rendering.models.mc.backend;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import lib.kasuga.rendering.models.mc.compat.iris.IrisCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.neoforge.client.GlStateBackup;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

import java.util.List;

/**
 * Owns the weighted-blended OIT target and the two geometry subpasses used to
 * fill it. The ordinary sorted translucent path remains the caller's fallback.
 */
final class OitRenderer implements AutoCloseable {

    static final int NORMAL = 0;
    static final int ACCUMULATION = 1;
    static final int REVEALAGE = 2;
    /** Native BLEND geometry, but only alpha=1 fragments, with color/depth writes. */
    static final int OPAQUE_COVERAGE = 3;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ThreadLocal<GeometryBinding> ACTIVE_GEOMETRY = new ThreadLocal<>();

    private boolean nativeDisabled;
    private boolean irisDisabled;
    private boolean nativeActivationLogged;
    private boolean irisActivationLogged;
    private OitTarget target;

    /**
     * @return true when the translucent pass was handled by OIT. False tells
     * the backend to use its conventional sorted fallback.
     */
    boolean render(MCBackend backend, MCBackendContext context) {
        boolean iris = BackendInstance.isIrisEnabled();
        if (!canUse(iris)) return false;

        RenderTarget mainTarget = Minecraft.getInstance().getMainRenderTarget();
        ShaderInstance composite = RenderState.OIT_COMPOSITE_SHADER_INSTANCE;
        if (!mainTarget.useDepth || mainTarget.frameBufferId < 0
                || mainTarget.width <= 0 || mainTarget.height <= 0
                || composite == null) {
            return false;
        }

        RenderSystem.assertOnRenderThread();
        GlStateBackup backup = new GlStateBackup();
        RenderSystem.backupGlState(backup);
        boolean resolveAttempted = false;
        Destination destination = null;
        try {
            // Both subpasses must see the same pose, lighting and uploaded
            // geometry. In particular, Iris transform feedback runs only once.
            List<MCBackend.PreparedModelDraw> models = backend.prepareTranslucent(context);
            if (models.isEmpty()) return true;
            destination = captureDestination(mainTarget, iris);
            if (destination == null) return false;
            if (target == null) target = new OitTarget();
            target.prepare(destination.framebufferId(), destination.width(), destination.height());

            // On the native path, opaque coverage has already written the
            // solid texels of BLEND materials into scene depth. The target
            // owns a copy of scene depth. Both geometry passes
            // test it, but their COLOR_WRITE-only states leave that depth
            // unchanged so translucent fragments never hide one another.
            renderGeometryPass(models, context, ACCUMULATION, iris);
            renderGeometryPass(models, context, REVEALAGE, iris);

            // The resolve samples only the two OIT textures and writes to the
            // captured world target. It therefore never reads a color
            // attachment while writing that same attachment.
            resolveAttempted = true;
            resolve(destination, composite);
            if (iris && !irisActivationLogged) {
                irisActivationLogged = true;
                LOGGER.info("Kasuga Iris OIT active: constant-weight accumulation and revealage "
                        + "resolved to framebuffer {} ({}x{})", destination.framebufferId(),
                        destination.width(), destination.height());
            } else if (!iris && !nativeActivationLogged) {
                nativeActivationLogged = true;
                LOGGER.info("Kasuga native weighted OIT active with opaque coverage ({}x{})",
                        destination.width(), destination.height());
            }
            return true;
        } catch (RuntimeException exception) {
            if (iris) {
                irisDisabled = true;
            } else {
                nativeDisabled = true;
            }
            if (target != null) {
                try {
                    target.close();
                } catch (RuntimeException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
                target = null;
            }
            LOGGER.warn("Disabling Kasuga {} weighted OIT after a rendering failure; "
                    + "using sorted translucent fallback", iris ? "Iris" : "native", exception);
            // If the resolve was already entered, the main target may have
            // been touched. Do not draw the fallback over a partial resolve.
            return resolveAttempted;
        } finally {
            ACTIVE_GEOMETRY.remove();
            RenderSystem.restoreGlState(backup);
            if (destination != null) {
                destination.bind();
            } else if (iris) {
                IrisCompat.bindWorldFramebuffer();
            } else {
                mainTarget.bindWrite(true);
            }
        }
    }

    private void renderGeometryPass(List<MCBackend.PreparedModelDraw> models, MCBackendContext context,
                                    int oitMode, boolean iris) {
        RenderType renderType = RenderState.getOitRenderType(oitMode, iris);
        GeometryBinding binding = new GeometryBinding(target, oitMode, iris);
        ACTIVE_GEOMETRY.set(binding);
        try {
            binding.bind();
            for (MCBackend.PreparedModelDraw model : models) {
                model.draw(context, renderType, oitMode);
            }
        } finally {
            ACTIVE_GEOMETRY.remove();
        }
    }

    /** Called immediately after ShaderInstance.apply(), which is where Iris
     * binds its gbuffer and applies shader-pack blend overrides. */
    static void rebindAfterShaderApply(int oitMode) {
        if (oitMode == NORMAL || oitMode == OPAQUE_COVERAGE) return;
        GeometryBinding binding = ACTIVE_GEOMETRY.get();
        if (binding != null && binding.oitMode() == oitMode) {
            binding.bind();
        }
    }

    private Destination captureDestination(RenderTarget mainTarget, boolean iris) {
        if (!iris) {
            mainTarget.bindWrite(true);
            return new Destination(mainTarget.frameBufferId, mainTarget.width, mainTarget.height);
        }
        if (!IrisCompat.bindWorldFramebuffer()) {
            return null;
        }
        int framebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        return new Destination(framebuffer, mainTarget.width, mainTarget.height);
    }

    private void resolve(Destination destination, ShaderInstance shader) {
        OitTarget resolvedTarget = target;
        if (resolvedTarget == null) return;

        destination.bind();
        RenderSystem.disableDepthTest();
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        RenderSystem.depthMask(false);
        GL11.glDepthMask(false);
        RenderSystem.disableCull();
        GL11.glDisable(GL11.GL_CULL_FACE);
        RenderSystem.enableBlend();
        GL11.glEnable(GL11.GL_BLEND);
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        shader.setSampler("AccumulationSampler", resolvedTarget.accumulationTextureId());
        shader.setSampler("RevealageSampler", resolvedTarget.revealageTextureId());
        try {
            shader.apply();

            // Iris' unknown-shader compatibility hook can bind its default
            // target from inside ShaderInstance.apply(). Rebind the exact
            // destination captured before depth copy, then reassert state.
            destination.bind();
            RenderSystem.disableDepthTest();
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            RenderSystem.depthMask(false);
            GL11.glDepthMask(false);
            RenderSystem.disableCull();
            GL11.glDisable(GL11.GL_CULL_FACE);
            RenderSystem.enableBlend();
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendEquation(GL14.GL_FUNC_ADD);
            RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA,
                    GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                    GlStateManager.SourceFactor.ONE,
                    GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);

            BufferBuilder builder = RenderSystem.renderThreadTesselator().begin(
                    VertexFormat.Mode.QUADS, DefaultVertexFormat.BLIT_SCREEN);
            builder.addVertex(0.0f, 0.0f, 0.0f);
            builder.addVertex(1.0f, 0.0f, 0.0f);
            builder.addVertex(1.0f, 1.0f, 0.0f);
            builder.addVertex(0.0f, 1.0f, 0.0f);
            BufferUploader.draw(builder.buildOrThrow());
        } finally {
            shader.clear();
            destination.bind();
        }
    }

    private boolean canUse(boolean iris) {
        if ((iris ? irisDisabled : nativeDisabled)
                || RenderState.OIT_ACCUMULATION_RENDER_TYPE == null
                || RenderState.OIT_REVEALAGE_RENDER_TYPE == null
                || RenderState.OIT_COMPOSITE_SHADER_INSTANCE == null) {
            return false;
        }
        if (iris && (RenderState.IRIS_OIT_ACCUMULATION_RENDER_TYPE == null
                || RenderState.IRIS_OIT_REVEALAGE_RENDER_TYPE == null)) {
            return false;
        }
        try {
            if (!GL.getCapabilities().OpenGL30) return false;
            return GL11.glGetInteger(GL30.GL_MAX_COLOR_ATTACHMENTS) >= 2
                    && GL11.glGetInteger(GL30.GL_MAX_DRAW_BUFFERS) >= 2;
        } catch (RuntimeException ignored) {
            // A client without a current GL context or without the required
            // capability fails closed to the already-proven sorted path.
            return false;
        }
    }

    @Override
    public void close() {
        if (target != null) {
            target.close();
            target = null;
        }
    }

    private record GeometryBinding(OitTarget target, int oitMode, boolean iris) {
        void bind() {
            if (oitMode == ACCUMULATION) {
                target.bindAccumulation();
            } else if (oitMode == REVEALAGE) {
                target.bindRevealage();
            } else {
                throw new IllegalArgumentException("Unknown OIT geometry mode: " + oitMode);
            }

            RenderSystem.enableDepthTest();
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            RenderSystem.depthMask(false);
            GL11.glDepthMask(false);
            RenderSystem.colorMask(true, true, true, true);
            GL11.glColorMask(true, true, true, true);
            RenderSystem.enableBlend();
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendEquation(GL14.GL_FUNC_ADD);
            if (oitMode == ACCUMULATION && iris) {
                // Iris supplies straight shader-pack color. Constant-weight
                // WBOIT accumulates rgb*alpha in RGB and alpha in A.
                RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA,
                        GlStateManager.DestFactor.ONE,
                        GlStateManager.SourceFactor.ONE,
                        GlStateManager.DestFactor.ONE);
                GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE,
                        GL11.GL_ONE, GL11.GL_ONE);
            } else if (oitMode == ACCUMULATION) {
                // Kasuga's own shader already emits premultiplied weighted
                // color and alpha*weight.
                RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE,
                        GlStateManager.DestFactor.ONE);
                GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE,
                        GL11.GL_ONE, GL11.GL_ONE);
            } else {
                RenderSystem.blendFunc(GlStateManager.SourceFactor.ZERO,
                        GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
                GL14.glBlendFuncSeparate(GL11.GL_ZERO, GL11.GL_ONE_MINUS_SRC_ALPHA,
                        GL11.GL_ZERO, GL11.GL_ONE_MINUS_SRC_ALPHA);
            }
        }
    }

    private record Destination(int framebufferId, int width, int height) {
        void bind() {
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebufferId);
            GlStateManager._viewport(0, 0, width, height);
        }
    }
}
