package lib.kasuga.rendering.effect.builtin.blackhole;

import com.mojang.blaze3d.pipeline.RenderTarget;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.effect.RenderPipelineRegistrar;
import lib.kasuga.rendering.effect.WorldRenderPipelineContext;
import lib.kasuga.rendering.effect.post.PostProcessTargetDescriptor;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraph;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraphPassDescriptor;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraphTarget;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraphRegistration;
import lib.kasuga.rendering.effect.shader.RenderShaderHandle;
import lib.kasuga.rendering.effect.shader.ShaderRegistration;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import org.joml.Vector4f;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Built-in batched black-hole post-processing effect. */
@EventBusSubscriber(value = Dist.CLIENT)
public final class BlackHoleEffects {
    public static final int MAX_VISIBLE_BLACK_HOLES = 8;
    private static final int FLOATS_PER_HOLE = 16;

    private static final ResourceLocation GRAPH_ID = id("black_hole_graph");
    private static final ResourceLocation SCENE_TARGET_ID = id("black_hole_scene");
    private static final ResourceLocation CAPTURE_PASS_ID = id("black_hole_capture");
    private static final ResourceLocation COMPOSITE_PASS_ID = id("black_hole_composite");
    private static final ResourceLocation FRAME_HOLES_ID = id("black_hole_frame_holes");
    private static final PostProcessGraphTarget MAIN = PostProcessGraphTarget.main();
    private static final PostProcessGraphTarget SCENE = PostProcessGraphTarget.managed(SCENE_TARGET_ID);
    private static final Comparator<BlackHoleEffect> EFFECT_ORDER =
            Comparator.comparing(BlackHoleEffect::id);
    private static final Comparator<ProjectedBlackHole> PROJECTED_ORDER = Comparator
            .comparingDouble(ProjectedBlackHole::radius).reversed()
            .thenComparing(value -> value.effect().id());
    private static final List<ProjectedBlackHole> PROJECTED_SCRATCH = new ArrayList<>();
    private static final float[] UNIFORM_DATA =
            new float[MAX_VISIBLE_BLACK_HOLES * FLOATS_PER_HOLE];

    private static final Object LOCK = new Object();
    private static final Map<ResourceLocation, EffectEntry> EFFECTS = new LinkedHashMap<>();
    private static volatile List<BlackHoleEffect> effectSnapshot = List.of();
    private static volatile RenderShaderHandle shaderHandle;
    private static ShaderRegistration shaderRegistration;
    private static PostProcessGraphRegistration graphRegistration;
    private static ClientLevel previousLevel;

    private BlackHoleEffects() {}

    public static void initialize(RenderPipelineRegistrar registrar) {
        Objects.requireNonNull(registrar, "registrar");
        synchronized (LOCK) {
            if (shaderRegistration == null) {
                shaderRegistration = registrar.shader(BlackHoleShaderProvider.program());
                shaderHandle = shaderRegistration.handle();
            }
            if (graphRegistration == null) {
                graphRegistration = registrar.graph(createGraph(), 1000);
            }
        }
    }

    /** Adds or replaces an effect by ResourceLocation ID. */
    public static void put(BlackHoleEffect effect) {
        Objects.requireNonNull(effect, "effect");
        requireInitialized();
        synchronized (LOCK) {
            EFFECTS.put(effect.id(), new EffectEntry(effect));
            refreshSnapshotLocked();
        }
    }

    /** Registers an owned effect whose close token cannot remove a later replacement. */
    public static Registration register(BlackHoleEffect effect) {
        Objects.requireNonNull(effect, "effect");
        requireInitialized();
        EffectEntry entry = new EffectEntry(effect);
        synchronized (LOCK) {
            EFFECTS.put(effect.id(), entry);
            refreshSnapshotLocked();
        }
        return new Registration(entry);
    }

    public static boolean remove(ResourceLocation id) {
        Objects.requireNonNull(id, "id");
        synchronized (LOCK) {
            if (EFFECTS.remove(id) == null) return false;
            refreshSnapshotLocked();
            return true;
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            EFFECTS.clear();
            effectSnapshot = List.of();
        }
    }

    public static int size() {
        synchronized (LOCK) {
            return EFFECTS.size();
        }
    }

    public static List<BlackHoleEffect> snapshot() {
        return effectSnapshot;
    }

    public static RenderShaderHandle shader() {
        RenderShaderHandle current = shaderHandle;
        if (current == null) throw new IllegalStateException("Black-hole demo pipelines have not been registered");
        return current;
    }

    private static PostProcessGraph createGraph() {
        PostProcessTargetDescriptor scene = PostProcessTargetDescriptor.builder(SCENE_TARGET_ID)
                .screenScale(1.0f)
                .useDepth(true)
                .filter(PostProcessTargetDescriptor.TextureFilter.LINEAR)
                .build();

        return PostProcessGraph.builder(GRAPH_ID)
                .target(scene)
                .prepare((context, frame) -> {
                    List<ProjectedBlackHole> visible = projectVisible(context.world());
                    if (visible.isEmpty()) return false;
                    frame.put(FRAME_HOLES_ID, visible);
                    return true;
                })
                .pass(PostProcessGraphPassDescriptor.builder(CAPTURE_PASS_ID, context -> {
                            context.copyColor(MAIN, SCENE, true);
                            context.copyDepth(MAIN, SCENE);
                        })
                        .reads(MAIN)
                        .writes(SCENE)
                        .build())
                .pass(PostProcessGraphPassDescriptor.builder(COMPOSITE_PASS_ID, context -> {
                            @SuppressWarnings("unchecked")
                            List<ProjectedBlackHole> holes = context.frame().require(FRAME_HOLES_ID, List.class);
                            RenderTarget sceneTarget = context.read(SCENE);
                            RenderShaderHandle handle = shader();
                            setUniforms(handle.require(), context.world(), holes);
                            context.fullscreen(MAIN, handle)
                                    .colorSampler("SceneSampler", sceneTarget)
                                    .depthSampler("DepthSampler", sceneTarget)
                                    .draw(false);
                        })
                        .reads(SCENE)
                        .writes(MAIN)
                        .build())
                .build();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != previousLevel) {
            clear();
            previousLevel = level;
        }
    }

    private static void requireInitialized() {
        synchronized (LOCK) {
            if (shaderRegistration == null || graphRegistration == null) {
                throw new IllegalStateException("Black-hole demo pipelines have not been registered");
            }
        }
    }

    private static List<ProjectedBlackHole> projectVisible(WorldRenderPipelineContext context) {
        RenderShaderHandle shader = shader();
        if (!shader.isReady()) return List.of();
        List<BlackHoleEffect> effects = snapshot();
        if (effects.isEmpty()) return List.of();

        RenderTarget target = context.mainRenderTarget();
        float aspect = target.viewHeight == 0 ? 1.0f : (float) target.viewWidth / target.viewHeight;
        Vec3 camera = context.camera().getPosition();
        PROJECTED_SCRATCH.clear();
        Vector4f view = new Vector4f();
        Vector4f clip = new Vector4f();

        for (BlackHoleEffect effect : effects) {
            Vec3 position = effect.position();
            view.set(
                    (float) (position.x - camera.x),
                    (float) (position.y - camera.y),
                    (float) (position.z - camera.z),
                    1.0f
            );
            context.modelViewMatrix().transform(view);
            clip.set(view);
            context.projectionMatrix().transform(clip);
            if (clip.w <= 0.001f || !Float.isFinite(clip.w)) continue;

            float inverseW = 1.0f / clip.w;
            float centerX = clip.x * inverseW * 0.5f + 0.5f;
            float centerY = clip.y * inverseW * 0.5f + 0.5f;
            float depth = clip.z * inverseW * 0.5f + 0.5f;
            float radius = Math.abs(context.projectionMatrix().m11()
                    * effect.eventHorizonRadius() * inverseW) * 0.5f;
            if (!Float.isFinite(radius) || radius <= 0.00001f || depth < 0.0f || depth > 1.0f) continue;

            float influence = radius * effect.influenceRadius();
            float horizontalInfluence = influence / Math.max(aspect, 0.0001f);
            if (centerX + horizontalInfluence < 0 || centerX - horizontalInfluence > 1
                    || centerY + influence < 0 || centerY - influence > 1) continue;
            PROJECTED_SCRATCH.add(new ProjectedBlackHole(effect, centerX, centerY, radius, depth));
        }

        PROJECTED_SCRATCH.sort(PROJECTED_ORDER);
        if (PROJECTED_SCRATCH.size() > MAX_VISIBLE_BLACK_HOLES) {
            PROJECTED_SCRATCH.subList(MAX_VISIBLE_BLACK_HOLES, PROJECTED_SCRATCH.size()).clear();
        }
        return PROJECTED_SCRATCH;
    }

    private static void setUniforms(ShaderInstance shader, WorldRenderPipelineContext context,
                                    List<ProjectedBlackHole> holes) {
        for (int index = 0; index < holes.size(); index++) {
            ProjectedBlackHole projected = holes.get(index);
            BlackHoleEffect effect = projected.effect();
            int offset = index * FLOATS_PER_HOLE;
            UNIFORM_DATA[offset] = projected.centerX();
            UNIFORM_DATA[offset + 1] = projected.centerY();
            UNIFORM_DATA[offset + 2] = projected.radius();
            UNIFORM_DATA[offset + 3] = projected.depth();
            UNIFORM_DATA[offset + 4] = effect.influenceRadius();
            UNIFORM_DATA[offset + 5] = effect.distortionStrength();
            UNIFORM_DATA[offset + 6] = effect.accretionRadius();
            UNIFORM_DATA[offset + 7] = effect.accretionWidth();
            UNIFORM_DATA[offset + 8] = effect.glowStrength();
            UNIFORM_DATA[offset + 9] = effect.chromaticAberration();
            UNIFORM_DATA[offset + 10] = effect.rotationSpeed();
            UNIFORM_DATA[offset + 11] = effect.depthTest() ? 1.0f : 0.0f;
            UNIFORM_DATA[offset + 12] = effect.glowColor().red();
            UNIFORM_DATA[offset + 13] = effect.glowColor().green();
            UNIFORM_DATA[offset + 14] = effect.glowColor().blue();
            UNIFORM_DATA[offset + 15] = effect.accretionDiskTilt();
        }
        RenderTarget target = context.mainRenderTarget();
        float partialTick = context.partialTick().getGameTimeDeltaPartialTick(false);
        float time = (context.level().getGameTime() + partialTick) / 20.0f;
        shader.safeGetUniform("HoleCount").set(holes.size());
        shader.safeGetUniform("HoleData").set(UNIFORM_DATA);
        shader.safeGetUniform("ScreenSize").set((float) target.viewWidth, (float) target.viewHeight);
        shader.safeGetUniform("Time").set(time);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(KasugaLib.MODID, path);
    }

    private static void refreshSnapshotLocked() {
        List<BlackHoleEffect> updated = new ArrayList<>(EFFECTS.size());
        for (EffectEntry entry : EFFECTS.values()) updated.add(entry.effect());
        updated.sort(EFFECT_ORDER);
        effectSnapshot = List.copyOf(updated);
    }

    private static void update(Registration registration, BlackHoleEffect effect) {
        Objects.requireNonNull(effect, "effect");
        synchronized (LOCK) {
            if (registration.closed) throw new IllegalStateException("Black-hole registration is closed");
            if (!registration.entry.effect.id().equals(effect.id())) {
                throw new IllegalArgumentException("An effect registration cannot change its ID");
            }
            EffectEntry replacement = new EffectEntry(effect);
            EFFECTS.put(effect.id(), replacement);
            registration.entry = replacement;
            refreshSnapshotLocked();
        }
    }

    private static void close(Registration registration) {
        synchronized (LOCK) {
            if (registration.closed) return;
            registration.closed = true;
            if (EFFECTS.get(registration.entry.effect.id()) == registration.entry) {
                EFFECTS.remove(registration.entry.effect.id());
                refreshSnapshotLocked();
            }
        }
    }

    private record EffectEntry(BlackHoleEffect effect) {}

    private record ProjectedBlackHole(
            BlackHoleEffect effect,
            float centerX,
            float centerY,
            float radius,
            float depth
    ) {}

    public static final class Registration implements AutoCloseable {
        private EffectEntry entry;
        private boolean closed;

        private Registration(EffectEntry entry) {
            this.entry = entry;
        }

        public ResourceLocation id() {
            return entry.effect.id();
        }

        public BlackHoleEffect effect() {
            return entry.effect;
        }

        public void update(BlackHoleEffect effect) {
            BlackHoleEffects.update(this, effect);
        }

        @Override
        public void close() {
            BlackHoleEffects.close(this);
        }
    }
}
