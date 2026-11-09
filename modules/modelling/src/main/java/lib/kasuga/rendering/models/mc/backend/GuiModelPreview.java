package lib.kasuga.rendering.models.mc.backend;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import lib.kasuga.rendering.models.mc.api.McModelAssemblies;
import lib.kasuga.rendering.models.mc.backend.ui.GuiRenderScope;
import lib.kasuga.rendering.models.mc.backend.ui.ModelPreviewCamera;
import lib.kasuga.rendering.models.mc.backend.ui.WorldUiFramebuffer;
import lib.kasuga.rendering.models.mc.dynamic.fsm.KasugaModelPipelines;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSetInstance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

import java.util.Objects;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;

/** Owns a local model instance and transparent depth canvas, without mounting it in the world. */
public final class GuiModelPreview implements AutoCloseable {
    private final Supplier<@Nullable Model> source;
    private final WorldUiFramebuffer canvas = new WorldUiFramebuffer((read, draw, viewport) -> {
        com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, read);
        com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER, draw);
        RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
    });
    private Model asset;
    private ModelInstance instance;
    private BackendInstance gpu;
    private final Vector3f min = new Vector3f(), max = new Vector3f();
    private volatile View view = new View(-.55f, .25f, 1);
    private boolean closed;
    private long renderedFrames;
    private lib.kasuga.rendering.output.FrameTexture currentFrame;
    private record View(float yaw, float pitch, float zoom) {}

    /** Resolves the current publication on every draw, including resource reloads. */
    public GuiModelPreview(ResourceLocation model, @Nullable String modelName) {
        this(() -> {
            var published = KasugaModelPipelines.publishedSource(new McModelAssemblies.Reference(model, modelName));
            return published == null ? null : published.pipeline().getModel(published.key());
        });
    }

    /** The supplier lends an asset; the preview owns only its private instance and GPU resources. */
    public GuiModelPreview(Supplier<@Nullable Model> source) { this.source = Objects.requireNonNull(source); }

    /** Radians; can be changed by a UI toolkit's input thread without issuing GL calls. */
    public void setView(float yaw, float pitch, float zoom) {
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch) || !Float.isFinite(zoom) || zoom <= 0)
            throw new IllegalArgumentException("Invalid preview rotation or zoom");
        view = new View(yaw, pitch, zoom);
    }

    public long renderedFrames() { return renderedFrames; }
    public boolean isClosed() { return closed; }
    /** Borrowed read-only frame, valid until the next render/resize/close; never delete its objects. */
    @Nullable public lib.kasuga.rendering.output.FrameTexture currentFrame() { return currentFrame; }
    @Nullable public ModelInstance instance() { return instance; }

    /** Client render thread only; respects the caller's pose and clipping when compositing. */
    public boolean render(GuiGraphics graphics, float x, float y, int width, int height, float partialTick) {
        return render(graphics, x, y, width, height, partialTick, 1);
    }

    public boolean render(GuiGraphics graphics, float x, float y, int width, int height, float partialTick, float opacity) {
        RenderSystem.assertOnRenderThread();
        if (!Float.isFinite(opacity) || opacity < 0 || opacity > 1) throw new IllegalArgumentException("Invalid preview opacity");
        if (closed || width <= 0 || height <= 0 || opacity == 0) return false;
        var next = source.get();
        if (next != asset) {
            releaseModel();
            if (next != null) {
                asset = next;
                instance = new ModelInstance(asset, new Transform(), null, null,
                        new MaterialSetInstance(asset.getMaterialSet()), null);
                instance.updateImmediate();
                min.set(Float.POSITIVE_INFINITY); max.set(Float.NEGATIVE_INFINITY);
                for (var vertex : asset.getVertices()) { min.min(vertex.getPosition()); max.max(vertex.getPosition()); }
                if (!min.isFinite() || !max.isFinite()) { min.set(-.5f); max.set(.5f); }
            }
        }
        if (instance == null) return false;
        graphics.flush();
        var v = view;
        var camera = ModelPreviewCamera.frame(min, max, (float) width / height, v.yaw, v.pitch, v.zoom);
        double scale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
        lib.kasuga.rendering.output.FrameTexture frame;
        try (var ignored = new GuiRenderScope()) {
            if (gpu == null) gpu = new BackendInstance(instance, ForkJoinPool.commonPool(), false, true);
            frame = canvas.render((int) Math.ceil(width * scale), (int) Math.ceil(height * scale), () -> {
                RenderSystem.disableScissor();
                RenderSystem.setShaderColor(1, 1, 1, 1);
                var pose = new PoseStack();
                pose.mulPose(camera.modelView());
                instance.sample(partialTick);
                gpu.updateLightData(LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 1);
                for (var pass : ModelRenderPass.values()) {
                    gpu.drawBuffer(pass, pose.last(), PreviewTypes.TYPES[pass.ordinal()],
                            new Matrix4f(), camera.projection(), 0, instance.getAmbientLightEnhancement());
                }
            });
        }
        try (var ignored = new GuiRenderScope()) {
            RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
            RenderSystem.setShaderTexture(0, frame.textureId());
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.enableBlend();
            RenderSystem.blendEquation(GL14.GL_FUNC_ADD);
            RenderSystem.blendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            RenderSystem.disableCull();
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            var buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            var pose = graphics.pose().last().pose();
            buffer.addVertex(pose, x, y, 0).setUv(0, 1).setColor(opacity, opacity, opacity, opacity);
            buffer.addVertex(pose, x, y + height, 0).setUv(0, 0).setColor(opacity, opacity, opacity, opacity);
            buffer.addVertex(pose, x + width, y + height, 0).setUv(1, 0).setColor(opacity, opacity, opacity, opacity);
            buffer.addVertex(pose, x + width, y, 0).setUv(1, 1).setColor(opacity, opacity, opacity, opacity);
            BufferUploader.drawWithShader(buffer.buildOrThrow());
        }
        currentFrame = frame;
        renderedFrames++;
        return true;
    }

    private void releaseModel() {
        var previousGpu = gpu;
        var previousInstance = instance;
        gpu = null;
        instance = null;
        asset = null;
        currentFrame = null;
        try {
            if (previousGpu != null) previousGpu.close();
        } catch (Exception e) { throw new IllegalStateException("Cannot release preview GPU resources", e); }
        finally {
            if (previousInstance != null) previousInstance.close();
        }
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        try { releaseModel(); } finally { canvas.close(); }
    }

    /** Local-target render types: no world framebuffer, scheduling, batching or Iris programs. */
    private static final class PreviewTypes {
        private static final RenderStateShard.OutputStateShard TARGET =
                new RenderStateShard.OutputStateShard("kasuga_ui_model_target", () -> {}, () -> {});
        private static final RenderStateShard.TransparencyStateShard ALPHA = new RenderStateShard.TransparencyStateShard(
                "kasuga_ui_model_alpha", () -> {
                    RenderSystem.enableBlend();
                    RenderSystem.blendEquation(GL14.GL_FUNC_ADD);
                    RenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                            GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
                }, () -> { RenderSystem.disableBlend(); RenderSystem.defaultBlendFunc(); });
        private static final RenderType[] TYPES = java.util.Arrays.stream(ModelRenderPass.values()).<RenderType>map(pass ->
                RenderType.create("kasuga_ui_model_" + pass.name().toLowerCase(java.util.Locale.ROOT),
                        RenderState.UML_VERTEX_FORMAT, VertexFormat.Mode.QUADS, 256, true, pass == ModelRenderPass.TRANSLUCENT,
                        RenderType.CompositeState.builder().setShaderState(RenderState.UML_SHADER)
                                .setTextureState(RenderState.UML_TEXTURE_STATE)
                                .setTransparencyState(pass == ModelRenderPass.TRANSLUCENT ? ALPHA : RenderStateShard.NO_TRANSPARENCY)
                                .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST).setCullState(RenderStateShard.CULL)
                                .setLightmapState(RenderStateShard.LIGHTMAP).setOverlayState(RenderStateShard.OVERLAY)
                                .setOutputState(TARGET).setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
                                .createCompositeState(false))).toArray(RenderType[]::new);
    }
}
