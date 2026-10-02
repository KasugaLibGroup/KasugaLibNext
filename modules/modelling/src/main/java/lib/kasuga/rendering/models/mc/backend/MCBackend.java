package lib.kasuga.rendering.models.mc.backend;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import lib.kasuga.rendering.models.mc.backend.data_type.KasugaShaderInstance;
import lib.kasuga.rendering.models.mc.backend.data_type.MCRenderableContext;
import lib.kasuga.rendering.models.mc.compat.iris.IrisCompat;
import lib.kasuga.rendering.models.mc.util.RotHelper;
import lib.kasuga.rendering.models.uml.backend.Backend;
import lib.kasuga.rendering.models.uml.backend.BackendContext;
import lib.kasuga.rendering.models.uml.bridge.Bridge;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.SkeletonInstance;
import lib.kasuga.rendering.models.uml.framework.schedule.ModelRenderScheduling;
import lib.kasuga.rendering.models.uml.framework.schedule.RenderScheduler;
import lib.kasuga.rendering.models.uml.framework.schedule.FrameSampleCache;
import lib.kasuga.rendering.output.mc.MinecraftWorldViews;
import lib.kasuga.rendering.models.uml.math.QuaternionHelper;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lombok.Getter;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.Map;
import java.util.Objects;
import java.util.IdentityHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.ExecutorService;

import static java.util.concurrent.Executors.newFixedThreadPool;

public class MCBackend extends Backend<MCBridge, BackendInstance, MCBackendContext, MCBackend.BackendTransform> implements AutoCloseable {

    @Getter
    public final ExecutorService executor;

    private float t = 0;
    private final GlobalModelBatcher globalBatcher = new GlobalModelBatcher();
    private final OitRenderer oitRenderer = new OitRenderer();
    private final LayeredTransparency layeredTransparency = new LayeredTransparency(this);
    private final FrameSampleCache<ModelInstance> sampledThisFrame = new FrameSampleCache<>();
    private Object renderFrameToken = new Object();
    /** Static bounding radius per instance, computed on first frustum test. */
    private final Map<ModelInstance, Float> boundsCache = new IdentityHashMap<>();
    // Reused scratch for the per-frame visibility box (render thread only).
    private final Vector3f visibleBoundsScratchMin = new Vector3f();
    private final Vector3f visibleBoundsScratchMax = new Vector3f();

    public MCBackend() {
        executor = newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    }

    public RenderScheduler<ModelInstance> getScheduler() {
        return ModelRenderScheduling.scheduler();
    }

    @Override
    protected MCRenderableContext createContext(MCBridge bridge, ModelInstance instance) {
        return new MCRenderableContext(bridge, instance,
                model -> bridge.createRenderable(model, executor));
    }

    @Override
    public void render(BackendContext<MCBridge, BackendInstance, MCBackendContext, BackendTransform> renderable, MCBackendContext context) {
        render(renderable, context, ModelRenderPass.OPAQUE);
    }

    private void render(BackendContext<MCBridge, BackendInstance, MCBackendContext, BackendTransform> renderable,
                        MCBackendContext context, ModelRenderPass pass) {
        render(renderable, context, pass, RenderState.getRenderType(pass), OitRenderer.NORMAL);
    }

    private void render(BackendContext<MCBridge, BackendInstance, MCBackendContext, BackendTransform> renderable,
                        MCBackendContext context, ModelRenderPass pass, RenderType renderType,
                        int oitMode) {
        render(renderable, context, pass, renderType, oitMode, null);
    }

    private void render(BackendContext<MCBridge, BackendInstance, MCBackendContext, BackendTransform> renderable,
                        MCBackendContext context, ModelRenderPass pass, RenderType renderType,
                        int oitMode, @Nullable List<PreparedModelDraw> prepared) {
        checkOpen();
        // Scheduling gate, mirroring vanilla's "renderer not called" semantics:
        // schedule mode → view distance → frustum. Culled instances neither
        // sample animation nor touch GPU buffers this frame.
        ModelInstance model = renderable.getModelInstance();
        BackendTransform transform = renderable.beforeRender(context);
        if (!passesSchedule(model, transform, context)) return;

        PoseStack poseStack = context.getPoseStack();
        poseStack.pushPose();
        Vec3 cameraPosition = context.getCamera() == null ? Vec3.ZERO : context.getCamera().getPosition();
        var worldOrigin = model.getSkeletonInstance().getWorldOrigin();
        // Compose camera-relative translation in double before it enters the
        // float pose matrix. Origin-local skeletons therefore never subtract
        // two huge float values during rendering.
        Vector3f cameraRelativeOrigin = cameraRelativeOrigin(worldOrigin, cameraPosition);
        poseStack.translate(cameraRelativeOrigin.x, cameraRelativeOrigin.y, cameraRelativeOrigin.z);

        // Animation is sampled once per rendered frame. Configured ragdoll
        // physics is advanced by MinecraftRagdollRuntime independently of
        // render visibility; tying it to this method froze culled instances.
        if (sampledThisFrame.firstSample(model)) {
            model.sample(context.getPartialTickFraction());
        }

        LightData lightData;
        int overlay;
        float emissive;
        if (transform != null) {
            if (transform.isAppliesTransform()) {
                transform.applyTransform(poseStack);
            }
            lightData = transform.getLightAndBrightness(context.getLevel());
            overlay = transform.getOverlay();
            emissive = transform.emissiveStrength;
        } else {
            lightData = new LightData(0, 0, LightTexture.FULL_BLOCK, (float) Math.abs(Math.sin(t)));
            t += 0.05f;
            overlay = OverlayTexture.NO_OVERLAY;
            emissive = 1f;
        }

        BackendInstance instance = renderable.apply();
        instance.renderInFrame(renderFrameToken);
        float ambientLightEnhancement = effectiveAmbientLightEnhancement(
                model, BackendInstance.isIrisEnabled());
        instance.updateLightData(lightData.packedLight(), overlay, lightData.brightness());
        if (prepared != null) {
            if (instance.prepareDraw(pass)) {
                prepared.add(new PreparedModelDraw(instance, poseStack.last().copy(),
                        emissive, ambientLightEnhancement));
            }
        } else if (pass != ModelRenderPass.TRANSLUCENT && globalBatcher.isCollecting()
                && globalBatcher.submit(instance, pass, poseStack.last().pose(), poseStack.last().normal(),
                emissive, ambientLightEnhancement)) {
            // The opaque/mask batch is flushed before any translucent pass.
            // This preserves pass order even though both paths share a global
            // batching implementation.
        } else {
            instance.drawBuffer(pass, poseStack.last(), renderType,
                    context.getModelViewMatrix(), context.getProjectionMatrix(), emissive,
                    ambientLightEnhancement, oitMode);
        }

        poseStack.popPose();
    }

    /** Frame-local capture: callbacks, culling, lighting, morphs and uploads run once. */
    List<PreparedModelDraw> prepareTranslucent(MCBackendContext context) {
        List<PreparedModelDraw> draws = new ArrayList<>();
        for (var renderable : new ArrayList<>(getRenderingObjects().values())) {
            if (renderable.isRender()) {
                render(renderable, context, ModelRenderPass.TRANSLUCENT,
                        RenderState.getRenderType(ModelRenderPass.TRANSLUCENT), OitRenderer.NORMAL, draws);
            }
        }
        return draws;
    }

    record PreparedModelDraw(BackendInstance instance, PoseStack.Pose pose,
                             float emissive, float ambientLightEnhancement) {
        void draw(MCBackendContext context, int mode) {
            draw(context, RenderState.getRenderType(false, ModelRenderPass.OPAQUE), mode);
        }

        void draw(MCBackendContext context, RenderType renderType, int mode) {
            instance.drawPreparedBuffer(ModelRenderPass.TRANSLUCENT, pose, renderType,
                    context.getModelViewMatrix(), context.getProjectionMatrix(),
                    emissive, ambientLightEnhancement, mode);
        }
    }

    static float effectiveAmbientLightEnhancement(ModelInstance model, boolean irisEnabled) {
        return irisEnabled ? 1f : model.getAmbientLightEnhancement();
    }

    /**
     * Per-frame visibility decision for one instance: scheduler mode first
     * (ALWAYS / MANUAL / VANILLA_RENDERER marks), then view distance, then
     * frustum. The bounds are taken from the LIVE evaluated skeleton — bone
     * origin-local absolutes include root, IK and physics writeback — because a ragdoll's
     * rendered geometry wanders far from its root transform; testing against
     * static bind-pose bounds culled on-screen ragdolls (they flickered
     * invisible whenever physics dragged the pose outside the authored box).
     */
    private boolean passesSchedule(ModelInstance model,
                                   @Nullable BackendTransform transform,
                                   MCBackendContext context) {
        if (!getScheduler().shouldRender(model)) return false;

        Vector3f position = transform == null ? null : transform.getPosition();
        if (position == null && !hasEvaluatedBones(model)) return true;

        visibleBoundsScratchMin.set(Float.MAX_VALUE);
        visibleBoundsScratchMax.set(-Float.MAX_VALUE);
        boolean live = scanEvaluatedBounds(model,
                visibleBoundsScratchMin, visibleBoundsScratchMax);
        if (!live) {
            // Skeleton never evaluated yet: conservative static box at the root.
            if (position == null) return true;
            float radius = boundsRadius(model);
            visibleBoundsScratchMin.set(position.x - radius, position.y - radius, position.z - radius);
            visibleBoundsScratchMax.set(position.x + radius, position.y + radius, position.z + radius);
        }

        var worldOrigin = model.getSkeletonInstance().getWorldOrigin();
        double centerX = worldOrigin.x
                + (visibleBoundsScratchMin.x + visibleBoundsScratchMax.x) * 0.5;
        double centerY = worldOrigin.y
                + (visibleBoundsScratchMin.y + visibleBoundsScratchMax.y) * 0.5;
        double centerZ = worldOrigin.z
                + (visibleBoundsScratchMin.z + visibleBoundsScratchMax.z) * 0.5;

        Vec3 camera = context.getCamera() != null ? context.getCamera().getPosition() : null;
        if (camera != null && !getScheduler().withinRenderDistance(model,
                (float) camera.distanceToSqr(centerX, centerY, centerZ))) {
            return false;
        }

        Frustum frustum = context.getFrustum();
        if (frustum == null) return true;
        float margin = boundsRadius(model);
        return frustum.isVisible(new AABB(
                worldOrigin.x + visibleBoundsScratchMin.x - margin,
                worldOrigin.y + visibleBoundsScratchMin.y - margin,
                worldOrigin.z + visibleBoundsScratchMin.z - margin,
                worldOrigin.x + visibleBoundsScratchMax.x + margin,
                worldOrigin.y + visibleBoundsScratchMax.y + margin,
                worldOrigin.z + visibleBoundsScratchMax.z + margin));
    }

    private static boolean hasEvaluatedBones(ModelInstance model) {
        return !model.getSkeletonInstance().getAbsoluteTransforms().isEmpty();
    }

    static Vector3f cameraRelativeOrigin(org.joml.Vector3dc worldOrigin, Vec3 cameraPosition) {
        return new Vector3f((float) (worldOrigin.x() - cameraPosition.x),
                (float) (worldOrigin.y() - cameraPosition.y),
                (float) (worldOrigin.z() - cameraPosition.z));
    }

    /**
     * Scans every evaluated bone's origin-local position into {@code min}/{@code max}.
     * Returns false when nothing has been evaluated yet. Package-private so
     * the extent math stays unit-testable without Minecraft types.
     */
    static boolean scanEvaluatedBounds(ModelInstance model, Vector3f min, Vector3f max) {
        var absolute = model.getSkeletonInstance().getAbsoluteTransforms();
        if (absolute.isEmpty()) return false;
        for (lib.kasuga.rendering.models.uml.math.Transform transform : absolute.values()) {
            Matrix4f m = transform.transform();
            float x = m.m30(), y = m.m31(), z = m.m32();
            min.set(Math.min(min.x, x), Math.min(min.y, y), Math.min(min.z, z));
            max.set(Math.max(max.x, x), Math.max(max.y, y), Math.max(max.z, z));
        }
        return true;
    }

    /** Static bounding radius of the authored vertex cloud — used as growth margin. */
    private float boundsRadius(ModelInstance model) {
        return boundsCache.computeIfAbsent(model, ignored -> {
            Vector3f minimum = new Vector3f(Float.MAX_VALUE);
            Vector3f maximum = new Vector3f(-Float.MAX_VALUE);
            for (Vertex vertex : model.getModel().getVertices()) {
                Vector3f p = vertex.getPosition();
                minimum.min(p);
                maximum.max(p);
            }
            Vector3f half = maximum.sub(minimum, new Vector3f()).mul(0.5f).absolute();
            return half.length();
        });
    }

    @Override
    public void renderAllObjects(MCBackendContext context) {
        renderAllObjects(context, ModelRenderPass.OPAQUE, true, false);
        renderAllObjects(context, ModelRenderPass.MASK, false, false);
        renderAllObjects(context, ModelRenderPass.TRANSLUCENT, false, true);
    }

    /**
     * Renders one material pass. The frame is flipped once before OPAQUE and
     * the sampled-instance cache is shared by all views of a multi-view frame, so a
     * model mounted through a vanilla renderer is sampled at most once even
     * though its geometry can participate in multiple passes. Native BLEND
     * rendering selects WBOIT; unsupported paths use the sorted fallback.
     */
    public void renderAllObjects(MCBackendContext context, ModelRenderPass pass,
                                 boolean frameStart, boolean frameEnd) {
        checkOpen();
        if (frameStart) {
            Object sharedFrame = MinecraftWorldViews.currentFrameToken();
            Object nextFrame = sharedFrame == null ? new Object() : sharedFrame;
            sampledThisFrame.beginFrame(nextFrame);
            renderFrameToken = nextFrame;
            globalBatcher.beginFrame();
            layeredTransparency.beginFrame();
            getScheduler().flipFrame();
        }

        try {
            if (pass == ModelRenderPass.TRANSLUCENT && layeredTransparency.handled()) return;
            if (pass == ModelRenderPass.TRANSLUCENT && oitRenderer.render(this, context)) {
                return;
            }

            renderObjectsInternal(context, pass, RenderState.getRenderType(pass),
                    OitRenderer.NORMAL, pass == ModelRenderPass.TRANSLUCENT);

            if (pass == ModelRenderPass.MASK && !BackendInstance.isIrisEnabled()) {
                // A PMX texture can contain both opaque skin/cloth and soft
                // alpha edges. Reuse the BLEND geometry, rejecting everything
                // except fully opaque fragments in the native shader. Do this
                // before translucent world blocks and before OIT copies depth,
                // so surfaces behind solid texels never enter the OIT average.
                // Iris shader-pack programs do not implement our alpha split.
                renderObjectsInternal(context, ModelRenderPass.TRANSLUCENT,
                        RenderState.getRenderType(false, ModelRenderPass.OPAQUE),
                        OitRenderer.OPAQUE_COVERAGE, false);
                layeredTransparency.arm(context);
            }
        } finally {
            if (frameEnd) {
                if (MinecraftWorldViews.currentFrameToken() == null)
                    sampledThisFrame.clear();
                globalBatcher.endFrame();
            }
        }
    }

    /**
     * Draws a pass without touching the frame scheduler. OIT instead prepares
     * the BLEND queue once and replays it into both accumulation targets.
     */
    void renderObjectsInternal(MCBackendContext context, ModelRenderPass pass,
                               RenderType renderType, int oitMode, boolean sortTranslucent) {

        boolean batch = pass != ModelRenderPass.TRANSLUCENT
                && !BackendInstance.isIrisEnabled()
                && RenderState.GLOBAL_BATCH_RENDER_TYPE != null;
        try {
            if (batch) {
                globalBatcher.begin(context.getModelViewMatrix(), context.getProjectionMatrix());
            }

            List<BackendContext<MCBridge, BackendInstance, MCBackendContext, BackendTransform>> renderables =
                    new ArrayList<>(getRenderingObjects().values());
            renderables.removeIf(renderable -> !renderable.isRender());
            if (pass == ModelRenderPass.TRANSLUCENT && sortTranslucent) {
                Vec3 camera = context.getCamera() == null ? null : context.getCamera().getPosition();
                if (camera != null) {
                    renderables.sort(Comparator.comparingDouble(
                            renderable -> -renderDistanceSquared(renderable.getModelInstance(), camera)));
                }
            }
            for (BackendContext<MCBridge, BackendInstance, MCBackendContext, BackendTransform> renderable
                    : renderables) {
                render(renderable, context, pass, renderType, oitMode);
            }
        } finally {
            if (batch) {
                globalBatcher.flush();
            }
        }
    }

    private static double renderDistanceSquared(ModelInstance model, Vec3 camera) {
        Vector3d origin = new Vector3d(model.getSkeletonInstance().getWorldOrigin());
        var rootTransform = model.getSkeletonInstance().getTransform();
        if (rootTransform != null && !rootTransform.isIdentity()) {
            Vector3f position = rootTransform.getPosition();
            origin.add(position.x, position.y, position.z);
        }
        double dx = origin.x - camera.x;
        double dy = origin.y - camera.y;
        double dz = origin.z - camera.z;
        return dx * dx + dy * dy + dz * dz;
    }

    @Override
    protected void onContextReleased(BackendContext<MCBridge, BackendInstance, MCBackendContext, BackendTransform> mounted) {
        ModelInstance model = mounted.getModelInstance();
        boundsCache.remove(model);
        sampledThisFrame.forget(model);
        boolean stillMounted = getRenderingObjects().values().stream()
                .anyMatch(context -> context.getModelInstance() == model);
        if (!stillMounted) getScheduler().detach(model);
    }

    @Override
    public void close() throws Exception {
        if (isClosed()) return;
        Exception failure = null;
        AutoCloseable[] resources = {super::close, layeredTransparency, oitRenderer, globalBatcher};
        for (AutoCloseable resource : resources) {
            try { resource.close(); }
            catch (Exception cleanup) {
                if (failure == null) failure = cleanup;
                else failure.addSuppressed(cleanup);
            }
        }
        boundsCache.clear();
        sampledThisFrame.clear();
        executor.shutdown();
        if (failure != null) throw failure;
    }

    public record LightData(int blockLight, int skyLight, int packedLight, float brightness) {

        @Override
        public int hashCode() {
            return Objects.hash(blockLight, skyLight, packedLight, brightness);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            LightData other = (LightData) obj;
            return blockLight == other.blockLight &&
                    skyLight == other.skyLight &&
                    packedLight == other.packedLight &&
                    Float.compare(other.brightness, brightness) == 0;
        }
    }

    @Getter
    public static class BackendTransform {

        @Nullable
        private final Vector3f position, rotation, scale;
        @Nullable
        private final Vector3d preciseWorldPosition;
        private final boolean isHurt, isGlowing, enableWorldLightAndBrightness, enableAutoOverlay;
        private final float emissiveStrength, brightness;
        private final int directlyGivenPackedLight, directlyGivenPackedOverlay;
        /** When false the TRS still feeds lighting/culling but is NOT pushed into the pose stack. */
        private final boolean appliesTransform;

        public BackendTransform(Vector3f position, Vector3f rotation, Vector3f scale,
                                boolean isHurt, boolean isGlowing, boolean enableWorldLightAndBrightness,
                                boolean enableAutoOverlay,
                                float emissiveStrength, float brightness,
                                int directlyGivenPackedLight, int directlyGivenPackedOverlay) {
            this(position, rotation, scale, isHurt, isGlowing, enableWorldLightAndBrightness,
                    enableAutoOverlay, emissiveStrength, brightness,
                    directlyGivenPackedLight, directlyGivenPackedOverlay, true, null);
        }

        public BackendTransform(Vector3f position, Vector3f rotation, Vector3f scale,
                                boolean isHurt, boolean isGlowing, boolean enableWorldLightAndBrightness,
                                boolean enableAutoOverlay,
                                float emissiveStrength, float brightness,
                                int directlyGivenPackedLight, int directlyGivenPackedOverlay,
                                boolean appliesTransform) {
            this(position, rotation, scale, isHurt, isGlowing, enableWorldLightAndBrightness,
                    enableAutoOverlay, emissiveStrength, brightness,
                    directlyGivenPackedLight, directlyGivenPackedOverlay,
                    appliesTransform, null);
        }

        public BackendTransform(Vector3f position, Vector3f rotation, Vector3f scale,
                                boolean isHurt, boolean isGlowing, boolean enableWorldLightAndBrightness,
                                boolean enableAutoOverlay,
                                float emissiveStrength, float brightness,
                                int directlyGivenPackedLight, int directlyGivenPackedOverlay,
                                boolean appliesTransform, @Nullable Vector3d preciseWorldPosition) {
            this.position = position;
            this.rotation = rotation;
            this.scale = scale;
            this.preciseWorldPosition = preciseWorldPosition == null
                    ? null : new Vector3d(preciseWorldPosition);
            this.isHurt = isHurt;
            this.isGlowing = isGlowing;
            this.enableWorldLightAndBrightness = enableWorldLightAndBrightness;
            this.emissiveStrength = emissiveStrength;
            this.directlyGivenPackedLight = directlyGivenPackedLight;
            this.enableAutoOverlay = enableAutoOverlay;
            this.directlyGivenPackedOverlay = directlyGivenPackedOverlay;
            this.brightness = brightness;
            this.appliesTransform = appliesTransform;
        }

        public void applyTransform(PoseStack poseStack) {
            if (position != null) {
                poseStack.translate(position.x(), position.y(), position.z());
            }
            if (rotation != null) {
                poseStack.mulPose(QuaternionHelper.fromXYZDegrees(rotation));
            }
            if (scale != null) {
                poseStack.scale(scale.x(), scale.y(), scale.z());
            }
        }

        public int getOverlay() {
            if (enableAutoOverlay) {
                return isHurt ? OverlayTexture.pack(0, true) :
                        (isGlowing ? OverlayTexture.pack(0, OverlayTexture.WHITE_OVERLAY_V) : OverlayTexture.NO_OVERLAY);
            } else {
                return directlyGivenPackedOverlay;
            }
        }

        public LightData getLightAndBrightness(Level level) {
            if (!enableWorldLightAndBrightness) {
                return new LightData(
                        0, 0, directlyGivenPackedLight, brightness
                );
            }
            boolean isPositionGiven = preciseWorldPosition != null || position != null;
            double x = preciseWorldPosition != null ? preciseWorldPosition.x
                    : isPositionGiven ? position.x() : 0.0;
            double y = preciseWorldPosition != null ? preciseWorldPosition.y
                    : isPositionGiven ? position.y() : 0.0;
            double z = preciseWorldPosition != null ? preciseWorldPosition.z
                    : isPositionGiven ? position.z() : 0.0;
            BlockPos pos = BlockPos.containing(x, y, z);
            return getLightData(level, pos);
        }
    }

    public static LightData getLightData(Level level, BlockPos pos) {
        int blockLight = level.getBrightness(LightLayer.BLOCK, pos);
        int skyLight = level.getBrightness(LightLayer.SKY, pos);
        int packedLight = LightTexture.pack(blockLight, skyLight);
        float brightness = LightTexture.getBrightness(level.dimensionType(), level.getMaxLocalRawBrightness(pos));
        return new LightData(blockLight, skyLight, packedLight, brightness);
    }
}
