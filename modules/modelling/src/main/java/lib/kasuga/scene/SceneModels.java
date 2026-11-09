package lib.kasuga.scene;

import com.mojang.blaze3d.systems.RenderSystem;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.effect.RenderPipelineScope;
import lib.kasuga.rendering.effect.WorldRenderPipelineContext;
import lib.kasuga.rendering.effect.pipeline.RenderPhase;
import lib.kasuga.rendering.effect.pipeline.RenderPipelineDescriptor;
import lib.kasuga.rendering.models.mc.scene.LayerLayout;
import lib.kasuga.rendering.models.mc.scene.MinecraftModelSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** The same native/composed model sources in world scenes, before the shared post-process stages. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class SceneModels implements AutoCloseable {
    private static final Set<SceneModels> ACTIVE = new LinkedHashSet<>();
    private static final AtomicLong IDS = new AtomicLong();
    private final ClientLevel level;
    private final RenderPipelineScope scope;
    private final List<Entry> models = new ArrayList<>();
    private boolean closed;
    private lib.kasuga.rendering.models.mc.backend.ui.PreviewRenderBuffers buffers;
    private long resourceGeneration=-1;

    private SceneModels(ClientLevel level) {
        this.level = java.util.Objects.requireNonNull(level);
        var id = ResourceLocation.fromNamespaceAndPath(KasugaLib.MODID, "scene/models/" + IDS.incrementAndGet());
        scope = RenderPipelineScope.create(id);
        scope.world(RenderPipelineDescriptor.builder(id, RenderPhase.AFTER_BLOCK_ENTITIES).build(), this::render);
        ACTIVE.add(this);
    }
    public static SceneModels open(ClientLevel level) { RenderSystem.assertOnRenderThread(); return new SceneModels(level); }
    /** Takes ownership. Position stays double precision until the camera has been subtracted. */
    public Entry add(MinecraftModelSource source, Vec3 position) {
        if (closed) throw new IllegalStateException("Model scene is closed");
        if (models.stream().anyMatch(model -> model.source == source)) throw new IllegalArgumentException("Source is already owned by this scene");
        var entry = new Entry(java.util.Objects.requireNonNull(source), position); models.add(entry); return entry;
    }
    /** Read-only model picking. Rays and hit locations are world-space; block positions remain source-local. */
    public java.util.Optional<MinecraftModelSource.BlockHit> pick(Vec3 from, Vec3 to, float partialTick) {
        RenderSystem.assertOnRenderThread();
        if (closed) return java.util.Optional.empty();
        MinecraftModelSource.BlockHit nearest = null; double distance = Double.POSITIVE_INFINITY;
        for (var entry : models) if (entry.visible) {
            var snapshot = entry.source.snapshot(entry.layout, partialTick);
            if (snapshot == null) continue;
            var hit = MinecraftModelSource.placedPicker(entry.transform, snapshot::pick)
                    .pick(from.subtract(entry.position), to.subtract(entry.position)).orElse(null);
            if (hit == null) continue;
            var world = hit.location().add(entry.position); double next = world.distanceToSqr(from);
            if (next < distance) {
                distance = next; nearest = new MinecraftModelSource.BlockHit(hit.position(), hit.state(), world, hit.face());
            }
        }
        return java.util.Optional.ofNullable(nearest);
    }
    private void render(WorldRenderPipelineContext context) {
        if (closed || context.level() != level) return;
        var camera = context.camera().getPosition();
        float partial = context.partialTick().getGameTimeDeltaPartialTick(false);
        long generation=lib.kasuga.rendering.models.mc.scene.ModelPreviewResources.generation();
        if (generation!=resourceGeneration) {
            if (buffers!=null) buffers.close();buffers=null;resourceGeneration=generation;
        }
        if (buffers==null) buffers=new lib.kasuga.rendering.models.mc.backend.ui.PreviewRenderBuffers();
        buffers.begin(context.mainRenderTarget().frameBufferId,context.mainRenderTarget().width,context.mainRenderTarget().height);
        try {
            for (Entry entry : models) if (entry.visible) {
                var snapshot = entry.source.snapshot(entry.layout, partial);
                if (snapshot == null) continue;
                context.poseStack().pushPose();
                try {
                    context.poseStack().translate(entry.position.x - camera.x, entry.position.y - camera.y, entry.position.z - camera.z);
                    context.poseStack().mulPose(entry.transform);
                    snapshot.render(new MinecraftModelSource.Context(context.poseStack(), buffers, context.projectionMatrix(),
                            partial, LevelRenderer.getLightColor(level, BlockPos.containing(entry.position)), context.modelViewMatrix()));
                } finally { context.poseStack().popPose(); }
            }
        } finally { buffers.flush(); }
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        for (var scene : List.copyOf(ACTIVE)) if (Minecraft.getInstance().level != scene.level) scene.close();
    }
    @Override public void close() {
        if (closed) return;
        closed = true; ACTIVE.remove(this);
        try { try { scope.close(); } finally { if (buffers!=null) buffers.close(); } }
        finally {
            RuntimeException failure = null;
            for (Entry entry : models) try { entry.source.close(); }
            catch (RuntimeException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            models.clear();
            if (failure != null) throw failure;
        }
    }
    public static final class Entry {
        private final MinecraftModelSource source;
        private Vec3 position;
        private Matrix4f transform = new Matrix4f();
        private LayerLayout layout = LayerLayout.ASSEMBLED;
        private boolean visible = true;
        private Entry(MinecraftModelSource source, Vec3 position) { this.source = source; position(position); }
        public Entry position(Vec3 value) {
            if (!Double.isFinite(value.x) || !Double.isFinite(value.y) || !Double.isFinite(value.z)) throw new IllegalArgumentException("Non-finite position");
            position = value; return this;
        }
        public Entry transform(Matrix4fc value) {
            if (!value.isFinite() || !value.isAffine() || value.determinant() == 0) throw new IllegalArgumentException("Invalid model transform");
            transform = new Matrix4f(value); return this;
        }
        public Entry layers(LayerLayout value) { layout = java.util.Objects.requireNonNull(value); return this; }
        public Entry visible(boolean value) { visible = value; return this; }
    }
}
