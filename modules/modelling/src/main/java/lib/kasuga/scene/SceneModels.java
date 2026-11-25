package lib.kasuga.scene;

import com.mojang.blaze3d.systems.RenderSystem;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.effect.RenderPipelineScope;
import lib.kasuga.rendering.effect.WorldRenderPipelineContext;
import lib.kasuga.rendering.effect.pipeline.RenderPhase;
import lib.kasuga.rendering.effect.pipeline.RenderPipelineDescriptor;
import lib.kasuga.rendering.models.mc.scene.LayerLayout;
import lib.kasuga.rendering.models.mc.scene.MinecraftModelSource;
import lib.kasuga.rendering.output.mc.DistantWorldRendering;
import lib.kasuga.rendering.output.mc.MinecraftFrameOutputs;
import lib.kasuga.rendering.output.mc.MinecraftWorldViews;
import lib.kasuga.rendering.effect.EffectHandle;
import lib.kasuga.rendering.effect.builtin.light.WorldLight;
import lib.kasuga.rendering.effect.builtin.light.WorldLights;
import lib.kasuga.rendering.effect.builtin.weather.WeatherDepthPassContext;
import lib.kasuga.rendering.effect.builtin.weather.WeatherFilterEffect;
import lib.kasuga.rendering.effect.builtin.weather.WeatherFilters;
import lib.kasuga.rendering.lighting.LightFrame;
import lib.kasuga.rendering.models.mc.scene.display.WorldDisplayTarget;
import lib.kasuga.rendering.models.mc.scene.display.WorldLightTarget;
import lib.kasuga.rendering.models.mc.scene.display.WorldWeatherFilterTarget;
import lib.kasuga.rendering.weather.WeatherFilterFrame;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Matrix4fc;
import org.joml.Matrix4d;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** The same native/composed model sources in world scenes, before the shared post-process stages. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class SceneModels implements AutoCloseable {
    private static final Set<SceneModels> ACTIVE = new LinkedHashSet<>();
    private static final AtomicLong IDS = new AtomicLong();
    private final ClientLevel level;
    private final ResourceLocation id;
    private final RenderPipelineScope scope;
    private final List<Entry> models = new ArrayList<>();
    private boolean closed;
    private lib.kasuga.rendering.models.mc.backend.ui.PreviewRenderBuffers buffers;
    private long resourceGeneration=-1;
    private long displayTick;
    private float renderDistance;
    private boolean occludeWeather = true;

    private SceneModels(ClientLevel level) {
        this.level = java.util.Objects.requireNonNull(level);
        id = ResourceLocation.fromNamespaceAndPath(KasugaLib.MODID, "scene/models/" + IDS.incrementAndGet());
        scope = RenderPipelineScope.create(id);
        scope.world(RenderPipelineDescriptor.builder(id, RenderPhase.AFTER_BLOCK_ENTITIES).build(), this::render);
        ACTIVE.add(this);
    }
    public static SceneModels open(ClientLevel level) { RenderSystem.assertOnRenderThread(); return new SceneModels(level); }

    /**
     * Draws this scene in the main view out to the supplied distance using the terrain's depth projection.
     * Static placements can remain visible after the surrounding client chunks are unloaded. Zero restores
     * the default world range. A placement that borrows a host still expires when its host is removed.
     */
    public SceneModels distantRange(float blocks) {
        if (closed) throw new IllegalStateException("Model scene is closed");
        DistantWorldRendering.setRange(id, blocks);
        renderDistance = blocks;
        return this;
    }

    /** Controls whether geometry in this scene automatically occludes rain and snow. Enabled by default. */
    public SceneModels occludeWeather(boolean value) {
        if (closed) throw new IllegalStateException("Model scene is closed");
        occludeWeather = value;
        return this;
    }

    public boolean occludesWeather() {
        return occludeWeather;
    }
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
    private static boolean sameWorld(ClientLevel sceneLevel, ClientLevel renderLevel) {
        return renderLevel == sceneLevel || (MinecraftWorldViews.sourceLevel() == sceneLevel
                && renderLevel.dimension().equals(sceneLevel.dimension()));
    }

    public static boolean hasWeatherOccluders(ClientLevel level, Vec3 camera, double radius, float partialTick) {
        for (SceneModels scene : List.copyOf(ACTIVE)) {
            if (scene.closed || !scene.occludeWeather || !sameWorld(scene.level, level)) continue;
            for (Entry entry : List.copyOf(scene.models)) {
                Frame frame = entry.weatherFrame(partialTick);
                if (frame != null && overlapsHorizontally(frame, camera, radius)) return true;
            }
        }
        return false;
    }

    public static void renderWeatherDepth(WeatherDepthPassContext context) {
        Vec3 camera = context.camera();
        float partialTick = context.partialTick();
        double radius = context.horizontalRadius();
        for (SceneModels scene : List.copyOf(ACTIVE)) {
            if (scene.closed || !scene.occludeWeather || !sameWorld(scene.level, context.level())) continue;
            long generation = lib.kasuga.rendering.models.mc.scene.ModelPreviewResources.generation();
            if (generation != scene.resourceGeneration) {
                if (scene.buffers != null) scene.buffers.close();
                scene.buffers = null;
                scene.resourceGeneration = generation;
            }
            for (Entry entry : List.copyOf(scene.models)) {
                Frame frame = entry.weatherFrame(partialTick);
                if (frame == null || !overlapsHorizontally(frame, camera, radius)) continue;
                context.poseStack().pushPose();
                try {
                    context.poseStack().mulPose(frame.pose.relativeTo(camera));
                    frame.snapshot.render(new MinecraftModelSource.Context(
                            context.poseStack(),
                            context.buffers(),
                            context.projectionMatrix(),
                            partialTick,
                            LightTexture.FULL_BRIGHT,
                            context.viewMatrix()
                    ));
                } finally {
                    context.poseStack().popPose();
                }
            }
        }
    }

    public static java.util.OptionalDouble sampleWeatherRoof(ClientLevel level, double worldX, double minY, double maxY,
                                                              double worldZ, float partialTick) {
        double best = Double.NEGATIVE_INFINITY;
        Vec3 top = new Vec3(worldX, maxY, worldZ);
        Vec3 bottom = new Vec3(worldX, minY, worldZ);
        for (SceneModels scene : List.copyOf(ACTIVE)) {
            if (scene.closed || !scene.occludeWeather || !sameWorld(scene.level, level)) continue;
            for (Entry entry : List.copyOf(scene.models)) {
                Frame frame = entry.weatherFrame(partialTick);
                if (frame == null) continue;
                var worldBounds = frame.snapshot.bounds().transform(frame.pose.transform())
                        .move(frame.pose.origin().x, frame.pose.origin().y, frame.pose.origin().z);
                if (worldX < worldBounds.minX() || worldX > worldBounds.maxX()
                        || worldZ < worldBounds.minZ() || worldZ > worldBounds.maxZ()
                        || maxY < worldBounds.minY() || minY > worldBounds.maxY()) {
                    continue;
                }
                var hit = frame.snapshot.pick(frame.pose.toLocal(top), frame.pose.toLocal(bottom)).orElse(null);
                if (hit == null) continue;
                double hitY = frame.pose.toWorld(hit.location()).y;
                if (hitY >= minY && hitY <= maxY && hitY > best) {
                    best = hitY;
                }
            }
        }
        return best > Double.NEGATIVE_INFINITY ? java.util.OptionalDouble.of(best) : java.util.OptionalDouble.empty();
    }

    private static boolean overlapsHorizontally(Frame frame, Vec3 camera, double radius) {
        var bounds = frame.snapshot.bounds().transform(frame.pose.transform())
                .move(frame.pose.origin().x, frame.pose.origin().y, frame.pose.origin().z);
        return bounds.maxX() >= camera.x - radius && bounds.minX() <= camera.x + radius
                && bounds.maxZ() >= camera.z - radius && bounds.minZ() <= camera.z + radius;
    }

    private void render(WorldRenderPipelineContext context) {
        boolean sameWorld = context.level() == level || (MinecraftWorldViews.sourceLevel() == level
                && context.level().dimension().equals(level.dimension()));
        if (closed || !sameWorld) return;
        float partial = context.partialTick().getGameTimeDeltaPartialTick(false);
        long generation=lib.kasuga.rendering.models.mc.scene.ModelPreviewResources.generation();
        if (generation!=resourceGeneration) {
            if (buffers!=null) buffers.close();buffers=null;resourceGeneration=generation;
        }
        if (buffers==null) buffers=new lib.kasuga.rendering.models.mc.backend.ui.PreviewRenderBuffers();
        buffers.begin(context.mainRenderTarget().frameBufferId,context.mainRenderTarget().width,context.mainRenderTarget().height);
        boolean mainView = MinecraftFrameOutputs.MAIN_VIEW.equals(context.viewId());
        if (mainView && renderDistance > 0) {
            DistantWorldRendering.withFog(renderDistance, () -> renderEntries(context, partial, true));
        } else {
            renderEntries(context, partial, mainView);
        }
    }

    private void renderEntries(WorldRenderPipelineContext context, float partial, boolean mainView) {
        Vec3 camera = context.camera().getPosition();
        try {
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
                if (mainView && renderDistance > 0 && camera.distanceToSqr(pose.origin()) > renderDistance * renderDistance) continue;
                context.poseStack().pushPose();
                try {
                    context.poseStack().mulPose(pose.relativeTo(camera));
                    snapshot.render(new MinecraftModelSource.Context(context.poseStack(), buffers, context.projectionMatrix(),
                            partial, lightAt(pose.origin(), mainView && renderDistance > 0), context.modelViewMatrix()));
                } finally { context.poseStack().popPose(); }
            }
        } finally { buffers.flush(); }
    }

    private int lightAt(Vec3 position, boolean distant) {
        BlockPos pos = BlockPos.containing(position);
        if (distant && !level.getChunkSource().hasChunk(SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getZ()))) {
            return LightTexture.pack(0, level.dimensionType().hasSkyLight() ? 15 : 0);
        }
        return LevelRenderer.getLightColor(level, pos);
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
        if (renderDistance > 0) DistantWorldRendering.setRange(id, 0);
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
        @Getter
        private boolean closed;
        private final Set<net.minecraft.client.gui.components.Renderable> mountedPanels = new LinkedHashSet<>();
        private final Map<WorldLightTarget, EffectHandle<WorldLight>> mountedLights = new LinkedHashMap<>();
        private final Map<WorldWeatherFilterTarget, EffectHandle<WeatherFilterEffect>> mountedWeatherFilters = new LinkedHashMap<>();
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
        Frame frame(float partialTick, boolean mainView) {
            if (closed) return null;
            var pose = mainView ? placement.capture(partialTick) : placement.sample(partialTick);
            if (pose == null) { close(); return null; }
            if (!visible) return null;
            syncLights(pose);
            syncWeatherFilters(pose);
            var snapshot = source.snapshot(layout, partialTick);
            if (closed) return null;
            if (mainView) { lastSnapshot = snapshot; syncPanels(pose); }
            return new Frame(pose, snapshot);
        }
        Frame weatherFrame(float partialTick) {
            if (closed || !visible || !placement.isAlive()) return null;
            ScenePose pose = placement.followsHost() && placement.lastFrame() != null
                    ? placement.lastFrame()
                    : placement.sample(partialTick);
            if (pose == null) return null;
            MinecraftModelSource.Snapshot snapshot = lastSnapshot != null
                    ? lastSnapshot
                    : source.snapshot(layout, partialTick);
            return snapshot == null ? null : new Frame(pose, snapshot);
        }
        Frame pickingFrame(float partialTick) {
            if (closed) return null;
            if (!placement.isAlive()) { close(); return null; }
            if (!visible) return null;
            if (!placement.followsHost() || placement.lastFrame() == null) return frame(partialTick, true);
            return new Frame(placement.lastFrame(), lastSnapshot);
        }
        public Entry layers(LayerLayout value) { requireOpen(); layout = java.util.Objects.requireNonNull(value); return this; }
        public Entry visible(boolean value) {
            requireOpen(); visible = value;
            if (!visible) { removePanels(); removeLights(); removeWeatherFilters(); }
            return this;
        }
        private void requireOpen() { if (closed) throw new IllegalStateException("Scene entry is closed"); }
        @Override public void close() {
            if (closed) return;
            closed = true; placement.expire(); lastSnapshot = null;
            if (owner != null) owner.models.remove(this);
            try { removePanels(); } finally { try { removeLights(); } finally { try { removeWeatherFilters(); } finally { source.close(); } } }
        }
        private void removeLights() {
            for (EffectHandle<WorldLight> handle : mountedLights.values()) handle.remove();
            mountedLights.clear();
        }
        private void removeWeatherFilters() {
            for (EffectHandle<WeatherFilterEffect> handle : mountedWeatherFilters.values()) handle.remove();
            mountedWeatherFilters.clear();
        }
        private void syncWeatherFilters(ScenePose pose) {
            if (!(source instanceof WorldDisplayTarget display)) return;
            List<WorldDisplayTarget.WeatherFilter> filters = display.weatherFilters();
            Set<WorldWeatherFilterTarget> current = new LinkedHashSet<>();
            for (WorldDisplayTarget.WeatherFilter filter : filters) {
                current.add(filter.target());
                EffectHandle<WeatherFilterEffect> handle = mountedWeatherFilters.get(filter.target());
                if (handle == null || !handle.isActive()) {
                    handle = WeatherFilters.spawn(owner.level, filter.volume());
                    mountedWeatherFilters.put(filter.target(), handle);
                }
                Matrix4d transform = new Matrix4d().translation(pose.origin().x, pose.origin().y, pose.origin().z)
                        .mul(new Matrix4d(pose.transform())).mul(new Matrix4d(filter.transform()));
                handle.effect().frame(new WeatherFilterFrame(filter.volume(), transform));
            }
            for (WorldWeatherFilterTarget target : List.copyOf(mountedWeatherFilters.keySet())) {
                if (!current.contains(target)) mountedWeatherFilters.remove(target).remove();
            }
        }
        private void syncLights(ScenePose pose) {
            if (!(source instanceof WorldDisplayTarget display)) return;
            List<WorldDisplayTarget.Light> lights = display.lights();
            Set<WorldLightTarget> current = new LinkedHashSet<>();
            for (WorldDisplayTarget.Light light : lights) {
                current.add(light.target());
                EffectHandle<WorldLight> handle = mountedLights.get(light.target());
                if (handle == null || !handle.isActive()) {
                    handle = WorldLights.spawn(owner.level, light.source());
                    mountedLights.put(light.target(), handle);
                }
                Matrix4d transform = new Matrix4d().translation(pose.origin().x, pose.origin().y, pose.origin().z)
                        .mul(new Matrix4d(pose.transform())).mul(new Matrix4d(light.transform()));
                handle.effect().frame(new LightFrame(light.source(), transform));
            }
            for (WorldLightTarget target : List.copyOf(mountedLights.keySet())) {
                if (!current.contains(target)) mountedLights.remove(target).remove();
            }
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
