package lib.kasuga.rendering.models.mc.backend;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import lib.kasuga.rendering.models.mc.api.McModelAssemblies;
import lib.kasuga.rendering.models.mc.backend.ui.GuiRenderScope;
import lib.kasuga.rendering.models.mc.backend.ui.ModelPreviewCamera;
import lib.kasuga.rendering.models.mc.backend.ui.WorldUiFramebuffer;
import lib.kasuga.rendering.models.mc.backend.ui.PreviewRenderBuffers;
import lib.kasuga.rendering.models.mc.scene.MinecraftModelSource;
import lib.kasuga.rendering.models.mc.scene.LayerLayout;
import lib.kasuga.rendering.output.FrameTexture;
import lib.kasuga.rendering.models.mc.dynamic.fsm.KasugaModelPipelines;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.structure.Model;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

import java.util.Objects;
import java.util.function.Supplier;
import lib.kasuga.KasugaLib;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Owns a local model instance and transparent depth canvas, without mounting it in the world. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class GuiModelPreview implements AutoCloseable {
    private static final java.util.Set<GuiModelPreview> WORLD_PREVIEWS = new java.util.LinkedHashSet<>();
    private final MinecraftModelSource minecraftSource;
    private final WorldUiFramebuffer canvas;
    private PreviewRenderBuffers nativeBuffers;
    private volatile LayerLayout layers = LayerLayout.ASSEMBLED;
    private volatile FrameProcessor processor = frame -> frame.color();
    private String failure;
    private long resourceGeneration = -1;
    private Object boundLevel;
    private static WorldUiFramebuffer canvas(boolean sampleDepth) { return new WorldUiFramebuffer((read, draw, viewport) -> {
        com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, read);
        com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER, draw);
        RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
    }, sampleDepth); }
    private volatile View view = new View(-.55f, .25f, 1, 0, 0);
    private boolean closed;
    private long renderedFrames;
    private lib.kasuga.rendering.output.FrameTexture currentFrame;
    private MinecraftModelSource.Snapshot pickFrame;
    private ModelPreviewCamera.Frame pickCamera;
    private record View(float yaw, float pitch, float zoom, float panX, float panY) {}

    /** Resolves the current publication on every draw, including resource reloads. */
    public GuiModelPreview(ResourceLocation model, @Nullable String modelName) {
        this(() -> {
            var published = KasugaModelPipelines.publishedSource(new McModelAssemblies.Reference(model, modelName));
            return published == null ? null : published.pipeline().getModel(published.key());
        });
    }

    /** The supplier lends an asset; the preview owns only its private instance and GPU resources. */
    public GuiModelPreview(Supplier<@Nullable Model> source) {
        this(new UmlPreviewSource(source), false);
    }

    /** Takes ownership of a native/composed source. Create, structures and .litematic use this same entry. */
    public GuiModelPreview(MinecraftModelSource source) {
        this(source, true);
    }
    private GuiModelPreview(MinecraftModelSource source, boolean sampleDepth) {
        minecraftSource = Objects.requireNonNull(source); canvas = canvas(sampleDepth);
    }
    public void setLayers(LayerLayout value) { layers = Objects.requireNonNull(value); }
    public LayerLayout layers() { return layers; }
    @Nullable public String failure() { return failure; }
    public record PreviewFrame(FrameTexture color, int depthTexture, Matrix4f projection) {}
    @FunctionalInterface public interface FrameProcessor { FrameTexture process(PreviewFrame frame); }
    /** Render-thread callback. Returned texture is borrowed; the callback owns its post-process targets. */
    public void setFrameProcessor(FrameProcessor value) { processor = Objects.requireNonNull(value); }

    /** Radians; can be changed by a UI toolkit's input thread without issuing GL calls. */
    public void setView(float yaw, float pitch, float zoom) {
        setView(yaw, pitch, zoom, 0, 0);
    }

    /** Pan is measured in viewport fractions, +X right and +Y down, independently of rotation/zoom. */
    public void setView(float yaw, float pitch, float zoom, float panX, float panY) {
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch) || !Float.isFinite(zoom) || zoom <= 0
                || !Float.isFinite(panX) || !Float.isFinite(panY))
            throw new IllegalArgumentException("Invalid preview rotation, zoom or pan");
        view = new View(yaw, pitch, zoom, panX, panY);
    }

    public long renderedFrames() { return renderedFrames; }
    public boolean isClosed() { return closed; }
    /** Borrowed read-only frame, valid until the next render/resize/close; never delete its objects. */
    @Nullable public lib.kasuga.rendering.output.FrameTexture currentFrame() { return currentFrame; }
    @Nullable public ModelInstance instance() { return minecraftSource instanceof UmlPreviewSource uml ? uml.instance() : null; }
    /** Viewport fractions (0..1 from the top-left). Uses the most recently completed displayed snapshot. */
    public java.util.Optional<MinecraftModelSource.BlockHit> pick(double x, double y) {
        RenderSystem.assertOnRenderThread();
        if (closed || pickFrame == null || pickCamera == null || x < 0 || y < 0 || x > 1 || y > 1
                || !Double.isFinite(x) || !Double.isFinite(y)) return java.util.Optional.empty();
        var inverse = new Matrix4f(pickCamera.projection()).mul(pickCamera.modelView()).invert();
        var from = inverse.transformProject(new org.joml.Vector3f((float) (2 * x - 1), (float) (1 - 2 * y), -1));
        var to = inverse.transformProject(new org.joml.Vector3f((float) (2 * x - 1), (float) (1 - 2 * y), 1));
        return pickFrame.pick(new net.minecraft.world.phys.Vec3(from), new net.minecraft.world.phys.Vec3(to));
    }

    /** Client render thread only; respects the caller's pose and clipping when compositing. */
    public boolean render(GuiGraphics graphics, float x, float y, int width, int height, float partialTick) {
        return render(graphics, x, y, width, height, partialTick, 1);
    }

    public boolean render(GuiGraphics graphics, float x, float y, int width, int height, float partialTick, float opacity) {
        RenderSystem.assertOnRenderThread();
        if (!Float.isFinite(opacity) || opacity < 0 || opacity > 1) throw new IllegalArgumentException("Invalid preview opacity");
        if (closed || width <= 0 || height <= 0 || opacity == 0) return false;
        return renderMinecraft(graphics, x, y, width, height, partialTick, opacity);
    }

    private boolean renderMinecraft(GuiGraphics graphics, float x, float y, int width, int height, float partialTick, float opacity) {
        var level = Minecraft.getInstance().level;
        if (boundLevel != null && boundLevel != level) { close(); return false; }
        if (level != null) { boundLevel = level; WORLD_PREVIEWS.add(this); }
        graphics.flush();
        FrameTexture frame;
        try (var scope = new GuiRenderScope()) {
            long generation = lib.kasuga.rendering.models.mc.scene.ModelPreviewResources.generation();
            if (resourceGeneration != generation) {
                if (nativeBuffers != null) nativeBuffers.close();
                nativeBuffers = null; resourceGeneration = generation;
            }
            var snapshot = minecraftSource.snapshot(layers, partialTick);
            currentFrame = null; pickFrame = null; pickCamera = null;
            if (snapshot == null) { failure = "Model source is empty or unavailable"; return false; }
            var v = view;
            var camera = ModelPreviewCamera.frame(snapshot.bounds().minimum(), snapshot.bounds().maximum(),
                    (float) width / height, v.yaw, v.pitch, v.zoom, v.panX, v.panY);
            double scale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
            int pixelsX = Math.toIntExact((long) Math.ceil(width * scale));
            int pixelsY = Math.toIntExact((long) Math.ceil(height * scale));
            if (nativeBuffers == null) nativeBuffers = new PreviewRenderBuffers();
            frame = canvas.render(pixelsX, pixelsY, () -> {
                nativeBuffers.begin(org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER_BINDING), pixelsX, pixelsY);
                RenderSystem.setProjectionMatrix(camera.projection(), VertexSorting.DISTANCE_TO_ORIGIN);
                var modelView = RenderSystem.getModelViewStack();
                modelView.pushMatrix().identity(); RenderSystem.applyModelViewMatrix();
                try {
                    RenderSystem.disableScissor(); RenderSystem.enableDepthTest(); RenderSystem.depthFunc(GL11.GL_LEQUAL);
                    RenderSystem.depthMask(true); RenderSystem.colorMask(true, true, true, true);
                    RenderSystem.setShaderColor(1, 1, 1, 1);
                    RenderSystem.setShaderFogStart(Float.MAX_VALUE); RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
                    Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();
                    var pose = new PoseStack(); pose.mulPose(camera.modelView());
                    snapshot.render(new MinecraftModelSource.Context(pose, nativeBuffers, camera.projection(), partialTick, LightTexture.FULL_BRIGHT));
                } finally {
                    try { nativeBuffers.flush(); }
                    finally { modelView.popMatrix(); RenderSystem.applyModelViewMatrix(); }
                }
            });
            frame = Objects.requireNonNull(processor.process(new PreviewFrame(frame, canvas.depthTextureId(), camera.projection())), "Processed preview frame");
            if (frame.width() != pixelsX || frame.height() != pixelsY || frame.textureId() <= 0)
                throw new IllegalArgumentException("Post-process output must match preview dimensions");
            pickFrame = snapshot; pickCamera = camera;
            failure = null;
        } catch (RuntimeException error) {
            currentFrame = null; failure = error.getClass().getSimpleName() + ": " + error.getMessage(); return false;
        }
        try (var scope = new GuiRenderScope()) {
            RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
            RenderSystem.setShaderTexture(0, frame.textureId()); RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.enableBlend(); RenderSystem.blendEquation(GL14.GL_FUNC_ADD);
            RenderSystem.blendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            RenderSystem.disableCull(); RenderSystem.disableDepthTest(); RenderSystem.depthMask(false);
            var buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            var pose = graphics.pose().last().pose();
            buffer.addVertex(pose, x, y, 0).setUv(0, 1).setColor(opacity, opacity, opacity, opacity);
            buffer.addVertex(pose, x, y + height, 0).setUv(0, 0).setColor(opacity, opacity, opacity, opacity);
            buffer.addVertex(pose, x + width, y + height, 0).setUv(1, 0).setColor(opacity, opacity, opacity, opacity);
            buffer.addVertex(pose, x + width, y, 0).setUv(1, 1).setColor(opacity, opacity, opacity, opacity);
            BufferUploader.drawWithShader(buffer.buildOrThrow());
        }
        currentFrame = frame; renderedFrames++; return true;
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true; currentFrame = null; pickFrame = null; pickCamera = null; WORLD_PREVIEWS.remove(this); boundLevel = null;
        try { minecraftSource.close(); }
        finally { try { if (nativeBuffers != null) nativeBuffers.close(); } finally { canvas.close(); } }
    }
    @SubscribeEvent public static void worldChanged(ClientTickEvent.Post event) {
        var level = Minecraft.getInstance().level;
        for (GuiModelPreview preview : java.util.List.copyOf(WORLD_PREVIEWS)) if (preview.boundLevel != level) preview.close();
    }
}
