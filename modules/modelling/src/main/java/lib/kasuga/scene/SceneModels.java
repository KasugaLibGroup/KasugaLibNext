package lib.kasuga.scene;

import com.mojang.blaze3d.systems.RenderSystem;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.effect.RenderPipelineScope;
import lib.kasuga.rendering.effect.WorldRenderPipelineContext;
import lib.kasuga.rendering.effect.pipeline.RenderPhase;
import lib.kasuga.rendering.effect.pipeline.RenderPipelineDescriptor;
import lib.kasuga.rendering.models.mc.scene.LayerLayout;
import lib.kasuga.rendering.models.mc.scene.MinecraftModelSource;
import lib.kasuga.rendering.output.mc.MinecraftFrameOutputs;
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
    private long displayTick;

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
        var entry = new Entry(this, java.util.Objects.requireNonNull(source), position);
        if (source instanceof lib.kasuga.rendering.models.mc.scene.display.WorldDisplayTarget display) display.claim(this);
        models.add(entry); return entry;
    }
    /** Takes ownership of the source, borrowing the host. Coordinates are host-local. */
    public Entry add(MinecraftModelSource source, SceneHost host) {
        java.util.Objects.requireNonNull(host);
        return add(source, Vec3.ZERO).follow(host);
    }
    public void remove(Entry entry) {
        if (models.contains(entry)) entry.close();
    }
    /** Read-only model picking. Rays and hit locations are world-space; block positions remain source-local. */
    public java.util.Optional<MinecraftModelSource.BlockHit> pick(Vec3 from, Vec3 to, float partialTick) {
        RenderSystem.assertOnRenderThread();
        if (closed) return java.util.Optional.empty();
        MinecraftModelSource.BlockHit nearest = null; double distance = Double.POSITIVE_INFINITY;
        for (var entry : List.copyOf(models)) if (entry.visible && !entry.closed) {
            if (!entry.placement.isAlive()) { entry.close(); continue; }
            var frame = entry.pickingFrame(partialTick);
            if (frame == null || frame.snapshot == null) continue;
            var pose = frame.pose;
            var hit = frame.snapshot.pick(pose.toLocal(from), pose.toLocal(to)).orElse(null);
            if (hit == null) continue;
            var world = pose.toWorld(hit.location()); double next = world.distanceToSqr(from);
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
            boolean mainView = MinecraftFrameOutputs.MAIN_VIEW.equals(context.viewId());
            for (Entry entry : List.copyOf(models)) {
                Frame frame;
                try {
                    frame = entry.frame(partial, mainView);
                } catch (RuntimeException failure) {
                    entry.close();
                    com.mojang.logging.LogUtils.getLogger().error("Scene host failed and was detached", failure);
                    continue;
                }
                if (frame == null) continue;
                var pose = frame.pose;
                var snapshot = frame.snapshot;
                if (snapshot == null) continue;
                context.poseStack().pushPose();
                try {
                    context.poseStack().mulPose(pose.relativeTo(camera));
                    snapshot.render(new MinecraftModelSource.Context(context.poseStack(), buffers, context.projectionMatrix(),
                            partial, LevelRenderer.getLightColor(level, BlockPos.containing(pose.origin())), context.modelViewMatrix()));
                } finally { context.poseStack().popPose(); }
            }
        } finally { buffers.flush(); }
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        for (var scene : List.copyOf(ACTIVE)) {
            if (Minecraft.getInstance().level != scene.level) scene.close();
            else if (!Minecraft.getInstance().isPaused()) {
                long tick=++scene.displayTick;
                for (var entry:List.copyOf(scene.models)) {
                    if (!entry.placement.isAlive()) { entry.close(); continue; }
                    if (entry.source instanceof lib.kasuga.rendering.models.mc.scene.display.WorldDisplayTarget display) display.tick(tick,.05);
                }
            }
        }
    }
    @Override public void close() {
        if (closed) return;
        closed = true; ACTIVE.remove(this);
        try { try { scope.close(); } finally { if (buffers!=null) buffers.close(); } }
        finally {
            RuntimeException failure = null;
            for (Entry entry : List.copyOf(models)) try { entry.close(); }
            catch (RuntimeException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            models.clear();
            if (failure != null) throw failure;
        }
    }
    record Frame(ScenePose pose, MinecraftModelSource.Snapshot snapshot) {}
    public static final class Entry implements AutoCloseable {
        private final SceneModels owner;
        private final MinecraftModelSource source;
        private final ScenePlacement placement;
        private MinecraftModelSource.Snapshot lastSnapshot;
        private LayerLayout layout = LayerLayout.ASSEMBLED;
        private boolean visible = true;
        private boolean closed;
        private final Set<net.minecraft.client.gui.components.Renderable> mountedPanels = new LinkedHashSet<>();
        Entry(SceneModels owner, MinecraftModelSource source, Vec3 position) {
            this.owner = owner; this.source = source; placement = new ScenePlacement(position);
        }
        /** With a moving host, this offset is expressed in host-local blocks. */
        public Entry position(Vec3 value) {
            requireOpen(); placement.position(value); return this;
        }
        public Entry transform(Matrix4fc value) {
            requireOpen(); placement.transform(value); return this;
        }
        public Entry follow(SceneHost host) { requireOpen(); placement.follow(host); lastSnapshot = null; return this; }
        public ScenePose lastPose() { return placement.lastFrame(); }
        public boolean isClosed() { return closed; }
        Frame frame(float partialTick, boolean mainView) {
            if (closed) return null;
            var pose = mainView ? placement.capture(partialTick) : placement.sample(partialTick);
            if (pose == null) { close(); return null; }
            if (!visible) return null;
            var snapshot = source.snapshot(layout, partialTick);
            if (closed) return null;
            if (mainView) { lastSnapshot = snapshot; syncPanels(pose); }
            return new Frame(pose, snapshot);
        }
        Frame pickingFrame(float partialTick) {
            if (closed) return null;
            if (!placement.isAlive()) { close(); return null; }
            if (!visible) return null;
            if (!placement.followsHost() || placement.lastFrame() == null) return frame(partialTick, true);
            return new Frame(placement.lastFrame(), lastSnapshot);
        }
        public Entry layers(LayerLayout value) { requireOpen(); layout = java.util.Objects.requireNonNull(value); return this; }
        public Entry visible(boolean value) { requireOpen(); visible = value; if(!visible)removePanels(); return this; }
        private void requireOpen() { if (closed) throw new IllegalStateException("Scene entry is closed"); }
        @Override public void close() {
            if (closed) return;
            closed = true; placement.expire(); lastSnapshot = null;
            if (owner != null) owner.models.remove(this);
            try { removePanels(); } finally { source.close(); }
        }
        private void removePanels() {
            if (mountedPanels.isEmpty()) return;
            var backend=lib.kasuga.rendering.models.mc.Constants.UI_BACKEND;
            if(backend!=null) for(var panel:mountedPanels)backend.removeRenderable(panel);
            mountedPanels.clear();
        }
        private void syncPanels(ScenePose pose) {
            if(!(source instanceof lib.kasuga.rendering.models.mc.scene.display.WorldDisplayTarget display))return;
            var backend=lib.kasuga.rendering.models.mc.Constants.UI_BACKEND;
            if(backend==null)return;
            var panels=visible?display.panels():List.<lib.kasuga.rendering.models.mc.scene.display.WorldDisplayTarget.Panel>of();
            var current=new LinkedHashSet<net.minecraft.client.gui.components.Renderable>();
            for(var panel:panels) {
                current.add(panel.view());
                backend.addRenderable(panel.view(), pose.compose(Vec3.ZERO, panel.transform()));
            }
            for(var old:mountedPanels)if(!current.contains(old))backend.removeRenderable(old);
            mountedPanels.clear();mountedPanels.addAll(current);
        }
    }
}
