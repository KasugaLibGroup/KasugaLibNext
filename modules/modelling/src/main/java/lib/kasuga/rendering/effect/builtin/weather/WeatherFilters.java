package lib.kasuga.rendering.effect.builtin.weather;

import com.mojang.blaze3d.systems.RenderSystem;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.effect.EffectHandle;
import lib.kasuga.rendering.effect.EffectRenderPipeline;
import lib.kasuga.rendering.effect.EffectRenderer;
import lib.kasuga.rendering.effect.RenderPipelineRegistrar;
import lib.kasuga.rendering.effect.WorldRenderPipelineContext;
import lib.kasuga.rendering.effect.pipeline.RenderPhase;
import lib.kasuga.rendering.effect.pipeline.RenderPipelineDescriptor;
import lib.kasuga.rendering.models.mc.compat.iris.IrisCompat;
import lib.kasuga.rendering.weather.WeatherFilterFrame;
import lib.kasuga.rendering.weather.WeatherFilterVolume;
import lib.kasuga.scene.SceneModels;
import lombok.Setter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Built-in 3D weather occlusion and precipitation filter system.
 * Combines a top-down orthographic roof depth pass with explicit OBB {@link WeatherFilterVolume}s
 * and zero-readback CPU height queries for rain/snow columns, rain splashes, and muffled rain audio.
 */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class WeatherFilters {
    public static final TagKey<EntityType<?>> OCCLUDES_WEATHER_TAG = TagKey.create(
            Registries.ENTITY_TYPE,
            ResourceLocation.fromNamespaceAndPath(KasugaLib.MODID, "occludes_weather")
    );

    private static final float SPLASH_SUPPRESS_THRESHOLD = 0.5f;
    private static final float AUDIO_SHELTER_THRESHOLD = 0.5f;

    private static final CopyOnWriteArrayList<WeatherOccluder> OCCLUDERS = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<WeatherFilterFrame> DIRECT_VOLUMES = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<WeatherFilterEffect> ACTIVE_EFFECTS = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<Predicate<Entity>> ENTITY_FILTERS = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<Class<? extends Entity>> ADAPTER_ENTITY_CLASSES = new CopyOnWriteArrayList<>();

    private static final Map<EntityType<?>, Integer> INCLUDED_TYPE_COUNTS = Collections.synchronizedMap(new HashMap<>());
    private static final Map<EntityType<?>, Integer> EXCLUDED_TYPE_COUNTS = Collections.synchronizedMap(new HashMap<>());
    /**
     * Optional global gate over {@link EntityType}s eligible for weather filtering.
     * Setting {@code null} restores default matching.
     */
    @Setter
    private static volatile @Nullable Predicate<EntityType<?>> optionalEntityTypeFilter;

    private static @Nullable EffectRenderPipeline<WeatherFilterEffect> pipeline;
    private static @Nullable WeatherFilterRenderer renderer;
    private static boolean builtinOccludersRegistered;

    private static @Nullable ShaderInstance activeWeatherShader;
    private static boolean activeCpuColumnClamp;
    private static float activeRenderPartialTick = 1.0f;

    private WeatherFilters() {}

    public static void initialize(RenderPipelineRegistrar registrar) {
        Objects.requireNonNull(registrar, "registrar");
        if (pipeline != null && pipeline.isActive()) {
            return;
        }
        renderer = new WeatherFilterRenderer(registrar);
        pipeline = registrar.effects(
                RenderPipelineDescriptor.builder(id("collect"), RenderPhase.AFTER_WEATHER)
                        .priority(700)
                        .build(),
                false,
                new EffectRenderer<>() {
                    @Override public void begin(WorldRenderPipelineContext context) {}
                    @Override public void render(WeatherFilterEffect effect, WorldRenderPipelineContext context) {}
                }
        );
        if (!builtinOccludersRegistered) {
            builtinOccludersRegistered = true;
            OCCLUDERS.add(new SceneModelsWeatherOccluder());
            OCCLUDERS.add(new GenericEntityWeatherOccluder());
        }
    }

    /** Spawns a level-scoped explicit OBB weather filter volume managed by the effect runtime. */
    public static EffectHandle<WeatherFilterEffect> spawn(ClientLevel level, WeatherFilterVolume volume) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(volume, "volume");
        if (pipeline == null || !pipeline.isActive()) {
            throw new IllegalStateException("Weather filter pipeline is not registered");
        }
        WeatherFilterEffect effect = new WeatherFilterEffect(level, volume);
        ACTIVE_EFFECTS.add(effect);
        EffectHandle<WeatherFilterEffect> handle = pipeline.spawn(effect);
        return new EffectHandle<>() {
            @Override public WeatherFilterEffect effect() { return handle.effect(); }
            @Override public boolean isActive() { return handle.isActive(); }
            @Override public boolean remove() {
                ACTIVE_EFFECTS.remove(effect);
                effect.close();
                return handle.remove();
            }
        };
    }

    /** Registers a standalone world-space volume frame (primarily for callers/tests outside EffectRenderPipeline). */
    public static AutoCloseable addVolume(WeatherFilterVolume volume) {
        return addVolume(WeatherFilterFrame.world(volume));
    }

    /** Registers a standalone world-space volume frame and returns a handle that removes it on close. */
    public static AutoCloseable addVolume(WeatherFilterFrame frame) {
        Objects.requireNonNull(frame, "frame");
        DIRECT_VOLUMES.add(frame);
        return () -> DIRECT_VOLUMES.remove(frame);
    }

    /** Registers a custom geometric/height occluder (for structures, contraptions, or sublevels). */
    public static AutoCloseable registerOccluder(WeatherOccluder occluder) {
        Objects.requireNonNull(occluder, "occluder");
        OCCLUDERS.addIfAbsent(occluder);
        return () -> OCCLUDERS.remove(occluder);
    }

    /** Marks an entity base class as handled by a dedicated adapter so the generic entity occluder skips it. */
    public static void markAdapterEntityClass(Class<? extends Entity> entityClass) {
        Objects.requireNonNull(entityClass, "entityClass");
        ADAPTER_ENTITY_CLASSES.addIfAbsent(entityClass);
    }

    public static boolean isHandledByDedicatedAdapter(Entity entity) {
        for (Class<? extends Entity> type : ADAPTER_ENTITY_CLASSES) {
            if (type.isInstance(entity)) {
                return true;
            }
        }
        return false;
    }

    /** Opts the supplied entity types into automatic weather occlusion. Close the returned handle to undo. */
    public static AutoCloseable includeEntityTypes(EntityType<?>... types) {
        Objects.requireNonNull(types, "types");
        return includeEntityTypes(List.of(types));
    }

    public static AutoCloseable includeEntityTypes(Collection<? extends EntityType<?>> types) {
        Objects.requireNonNull(types, "types");
        Set<EntityType<?>> unique = new HashSet<>(types);
        incrementCounts(INCLUDED_TYPE_COUNTS, unique);
        return () -> decrementCounts(INCLUDED_TYPE_COUNTS, unique);
    }

    /** Excludes the supplied entity types from weather occlusion, overriding default adapters. */
    public static AutoCloseable excludeEntityTypes(EntityType<?>... types) {
        Objects.requireNonNull(types, "types");
        return excludeEntityTypes(List.of(types));
    }

    public static AutoCloseable excludeEntityTypes(Collection<? extends EntityType<?>> types) {
        Objects.requireNonNull(types, "types");
        Set<EntityType<?>> unique = new HashSet<>(types);
        incrementCounts(EXCLUDED_TYPE_COUNTS, unique);
        return () -> decrementCounts(EXCLUDED_TYPE_COUNTS, unique);
    }

    /** Registers a custom predicate deciding whether an arbitrary entity should occlude weather. */
    public static AutoCloseable registerEntityFilter(Predicate<Entity> filter) {
        Objects.requireNonNull(filter, "filter");
        ENTITY_FILTERS.add(filter);
        return () -> ENTITY_FILTERS.remove(filter);
    }

    @Nullable
    public static Predicate<EntityType<?>> optionalEntityTypeFilter() {
        return optionalEntityTypeFilter;
    }

    /**
     * Evaluates whether {@code entity} should participate in weather occlusion under the current
     * optional type filter, inclusions, exclusions, config lists, entity tags, and custom predicates.
     */
    public static boolean shouldFilterEntity(@Nullable Entity entity, boolean matchedByDefaultAdapter) {
        boolean invalidOrSpectator = entity == null || entity.isRemoved() || entity.isSpectator();
        EntityType<?> type = invalidOrSpectator ? null : entity.getType();
        ResourceLocation typeId = type != null && WeatherFilterConfig.hasTypeListsConfigured()
                ? BuiltInRegistries.ENTITY_TYPE.getKey(type)
                : null;
        return evaluateEntitySelection(
                invalidOrSpectator,
                type,
                typeId,
                INCLUDED_TYPE_COUNTS,
                EXCLUDED_TYPE_COUNTS,
                optionalEntityTypeFilter,
                matchedByDefaultAdapter,
                type != null && hasWeatherTag(type),
                () -> {
                    for (Predicate<Entity> predicate : ENTITY_FILTERS) {
                        if (predicate.test(entity)) {
                            return true;
                        }
                    }
                    return false;
                }
        );
    }

    static <T> boolean evaluateEntitySelection(
            boolean invalidOrSpectator,
            @Nullable T type,
            @Nullable ResourceLocation typeId,
            Map<T, Integer> includedTypeCounts,
            Map<T, Integer> excludedTypeCounts,
            @Nullable Predicate<T> optionalGate,
            boolean matchedByDefaultAdapter,
            boolean hasTag,
            BooleanSupplier customFiltersMatch
    ) {
        if (invalidOrSpectator || type == null) {
            return false;
        }
        synchronized (excludedTypeCounts) {
            if (excludedTypeCounts.containsKey(type)) {
                return false;
            }
        }
        if (WeatherFilterConfig.isExcluded(typeId)) {
            return false;
        }
        if (optionalGate != null && !optionalGate.test(type)) {
            return false;
        }
        if (matchedByDefaultAdapter || WeatherFilterConfig.isIncluded(typeId) || hasTag) {
            return true;
        }
        synchronized (includedTypeCounts) {
            if (includedTypeCounts.containsKey(type)) {
                return true;
            }
        }
        return customFiltersMatch.getAsBoolean();
    }

    private static boolean hasWeatherTag(EntityType<?> type) {
        try {
            return type.is(OCCLUDES_WEATHER_TAG);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public static void beginWeatherRender(ClientLevel level, double camX, double camY, double camZ, float partialTick) {
        activeWeatherShader = null;
        activeCpuColumnClamp = false;
        activeRenderPartialTick = partialTick;
        if (level == null || level.getRainLevel(partialTick) <= 0.0f || !WeatherFilterConfig.isWeatherFilterEnabled()) {
            return;
        }
        Vec3 camera = new Vec3(camX, camY, camZ);
        double minWorldY = camY + WeatherFilterRenderer.BOTTOM_REL_Y;
        double maxWorldY = camY + WeatherFilterRenderer.TOP_REL_Y;
        List<WeatherFilterFrame> nearbyVolumes = collectNearbyVolumes(level, camera, WeatherFilterRenderer.HORIZONTAL_RADIUS, minWorldY, maxWorldY);

        if (IrisCompat.isUsingShaderPack() || renderer == null || !renderer.isShaderReady()) {
            activeCpuColumnClamp = !nearbyVolumes.isEmpty()
                    || hasAnyNearbyOccluders(level, camera, WeatherFilterRenderer.HORIZONTAL_RADIUS, minWorldY, maxWorldY, partialTick);
            return;
        }

        ShaderInstance prepared = renderer.prepareAndBind(
                level,
                camX,
                camY,
                camZ,
                partialTick,
                List.copyOf(OCCLUDERS),
                nearbyVolumes
        );
        if (prepared != null) {
            activeWeatherShader = prepared;
        } else {
            activeCpuColumnClamp = !nearbyVolumes.isEmpty()
                    || hasAnyNearbyOccluders(level, camera, WeatherFilterRenderer.HORIZONTAL_RADIUS, minWorldY, maxWorldY, partialTick);
        }
    }

    public static void endWeatherRender() {
        activeWeatherShader = null;
        activeCpuColumnClamp = false;
    }

    public static Supplier<ShaderInstance> resolveWeatherShader(Supplier<ShaderInstance> vanillaSupplier) {
        ShaderInstance current = activeWeatherShader;
        return current != null ? () -> current : vanillaSupplier;
    }

    public static boolean shouldUseCpuColumnClamp() {
        return activeCpuColumnClamp;
    }

    public static int effectiveMotionBlockingHeight(
            ClientLevel level,
            int blockX,
            int blockZ,
            int vanillaHeight,
            int maxRainY
    ) {
        if (!activeCpuColumnClamp) {
            return vanillaHeight;
        }
        return computeClampedColumnHeight(level, blockX, blockZ, vanillaHeight, maxRainY, activeRenderPartialTick);
    }

    /** Pure/testable CPU column height clamping helper. */
    public static int computeClampedColumnHeight(
            @Nullable ClientLevel level,
            int blockX,
            int blockZ,
            int vanillaHeight,
            int maxRainY,
            float partialTick
    ) {
        if (!WeatherFilterConfig.isWeatherFilterEnabled() || maxRainY <= vanillaHeight) {
            return vanillaHeight;
        }
        double sampleX = blockX + 0.5;
        double sampleZ = blockZ + 0.5;
        double bestY = vanillaHeight;
        Entity cameraEntity = currentCameraEntity();

        for (WeatherOccluder occluder : OCCLUDERS) {
            OptionalDouble hit = occluder.sampleRoofHeight(level, cameraEntity, sampleX, vanillaHeight, maxRainY, sampleZ, partialTick);
            if (hit.isPresent() && hit.getAsDouble() > bestY) {
                bestY = hit.getAsDouble();
            }
        }
        for (WeatherFilterFrame frame : activeFrames(level)) {
            OptionalDouble hit = frame.clipVertical(sampleX, vanillaHeight, maxRainY, sampleZ);
            if (hit.isPresent() && hit.getAsDouble() > bestY) {
                bestY = hit.getAsDouble();
            }
        }
        return bestY > vanillaHeight ? (int) Math.ceil(bestY) : vanillaHeight;
    }

    public static BlockPos adjustRainHeightmapPos(
            LevelReader reader,
            BlockPos queryPos,
            BlockPos vanillaPos,
            Vec3 cameraPos
    ) {
        if (!WeatherFilterConfig.isWeatherFilterEnabled() || !(reader instanceof ClientLevel level)) {
            return vanillaPos;
        }
        double x = queryPos.getX() + 0.5;
        double z = queryPos.getZ() + 0.5;
        double minY = vanillaPos.getY();
        double maxY = cameraPos.y + 10.0;
        OptionalDouble roof = sampleHighestRoof(level, x, minY, maxY, z, 1.0f);
        if (roof.isPresent() && roof.getAsDouble() > minY) {
            return BlockPos.containing(x, Math.ceil(roof.getAsDouble()), z);
        }
        return vanillaPos;
    }

    public static double resolveRainParticleY(ClientLevel level, double x, double vanillaY, double z) {
        if (!WeatherFilterConfig.isWeatherFilterEnabled()) {
            return vanillaY;
        }
        OptionalDouble roof = sampleHighestRoof(level, x, vanillaY - 1.5, vanillaY + 2.0, z, 1.0f);
        if (roof.isPresent() && roof.getAsDouble() > vanillaY - 0.5) {
            return roof.getAsDouble() + 0.02;
        }
        return vanillaY;
    }

    public static boolean shouldSpawnRainParticle(@Nullable ClientLevel level, double x, double y, double z) {
        if (!WeatherFilterConfig.isWeatherFilterEnabled()) {
            return true;
        }
        float occlusion = maxVolumeOcclusionAt(level, x, y, z);
        if (occlusion >= SPLASH_SUPPRESS_THRESHOLD) {
            return false;
        }
        return true;
    }

    /** Avoid spawning vanilla splashes that can fall through a dynamic roof. */
    public static boolean shouldSpawnVanillaRainSplash(ClientLevel level, double x, double y, double z, double maxRoofY) {
        if (!WeatherFilterConfig.isWeatherFilterEnabled()) {
            return true;
        }
        return shouldSpawnRainParticle(level, x, y, z)
                && sampleHighestRoof(level, x, y - 0.05, maxRoofY, z, 1.0f).isEmpty();
    }

    public static boolean isPositionSheltered(@Nullable ClientLevel level, Vec3 position) {
        if (!WeatherFilterConfig.isWeatherFilterEnabled()) {
            return false;
        }
        if (maxVolumeOcclusionAt(level, position.x, position.y, position.z) >= AUDIO_SHELTER_THRESHOLD) {
            return true;
        }
        if (level == null) {
            return false;
        }
        OptionalDouble roof = sampleHighestRoof(level, position.x, position.y + 0.05, position.y + 96.0, position.z, 1.0f);
        return roof.isPresent();
    }

    public static OptionalDouble sampleHighestRoof(
            ClientLevel level,
            double worldX,
            double minY,
            double maxY,
            double worldZ,
            float partialTick
    ) {
        if (maxY <= minY) {
            return OptionalDouble.empty();
        }
        Entity cameraEntity = currentCameraEntity();
        double best = Double.NEGATIVE_INFINITY;
        for (WeatherOccluder occluder : OCCLUDERS) {
            OptionalDouble hit = occluder.sampleRoofHeight(level, cameraEntity, worldX, minY, maxY, worldZ, partialTick);
            if (hit.isPresent() && hit.getAsDouble() > best) {
                best = hit.getAsDouble();
            }
        }
        return best > Double.NEGATIVE_INFINITY ? OptionalDouble.of(best) : OptionalDouble.empty();
    }

    public static float maxVolumeOcclusionAt(@Nullable ClientLevel level, double x, double y, double z) {
        float strongest = 0.0f;
        for (WeatherFilterFrame frame : activeFrames(level)) {
            float weight = frame.occlusionAt(x, y, z);
            if (weight > strongest) {
                strongest = weight;
            }
        }
        return strongest;
    }

    public static void clear() {
        if (pipeline != null) {
            pipeline.clear();
        }
        ACTIVE_EFFECTS.clear();
        DIRECT_VOLUMES.clear();
    }

    static void resetForTests() {
        ACTIVE_EFFECTS.clear();
        DIRECT_VOLUMES.clear();
        ENTITY_FILTERS.clear();
        ADAPTER_ENTITY_CLASSES.clear();
        synchronized (INCLUDED_TYPE_COUNTS) {
            INCLUDED_TYPE_COUNTS.clear();
        }
        synchronized (EXCLUDED_TYPE_COUNTS) {
            EXCLUDED_TYPE_COUNTS.clear();
        }
        optionalEntityTypeFilter = null;
        activeWeatherShader = null;
        activeCpuColumnClamp = false;
    }

    static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(KasugaLib.MODID, "weather/" + path);
    }

    private static boolean hasAnyNearbyOccluders(
            ClientLevel level,
            Vec3 camera,
            double radius,
            double minWorldY,
            double maxWorldY,
            float partialTick
    ) {
        Entity cameraEntity = currentCameraEntity();
        for (WeatherOccluder occluder : OCCLUDERS) {
            if (occluder.hasNearbyOccluders(level, camera, cameraEntity, radius, minWorldY, maxWorldY, partialTick)) {
                return true;
            }
        }
        return false;
    }

    private static List<WeatherFilterFrame> collectNearbyVolumes(
            ClientLevel level,
            Vec3 camera,
            double radius,
            double minWorldY,
            double maxWorldY
    ) {
        List<WeatherFilterFrame> result = new ArrayList<>();
        double minX = camera.x - radius;
        double maxX = camera.x + radius;
        double minZ = camera.z - radius;
        double maxZ = camera.z + radius;
        for (WeatherFilterFrame frame : activeFrames(level)) {
            if (frame.volume().strength() <= 0.0f) {
                continue;
            }
            if (frame.intersectsHorizontal(minX, minZ, maxX, maxZ)
                    && frame.maxY() >= minWorldY && frame.minY() <= maxWorldY) {
                result.add(frame);
            }
        }
        return result;
    }

    private static List<WeatherFilterFrame> activeFrames(@Nullable ClientLevel level) {
        List<WeatherFilterFrame> result = new ArrayList<>(ACTIVE_EFFECTS.size() + DIRECT_VOLUMES.size());
        for (WeatherFilterEffect effect : ACTIVE_EFFECTS) {
            if (!effect.isAlive()) {
                ACTIVE_EFFECTS.remove(effect);
                continue;
            }
            if (level == null || effect.matchesLevel(level)) {
                result.add(effect.frame());
            }
        }
        result.addAll(DIRECT_VOLUMES);
        return result;
    }

    @Nullable
    private static Entity currentCameraEntity() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft == null ? null : minecraft.getCameraEntity();
    }

    static <T> void incrementCounts(Map<T, Integer> counts, Set<T> types) {
        synchronized (counts) {
            for (T type : types) {
                counts.merge(type, 1, Integer::sum);
            }
        }
    }

    static <T> void decrementCounts(Map<T, Integer> counts, Set<T> types) {
        synchronized (counts) {
            for (T type : types) {
                Integer current = counts.get(type);
                if (current == null || current <= 1) {
                    counts.remove(type);
                } else {
                    counts.put(type, current - 1);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof ClientLevel unloaded)) {
            return;
        }
        for (WeatherFilterEffect effect : ACTIVE_EFFECTS) {
            if (effect.level() == unloaded) {
                effect.close();
                ACTIVE_EFFECTS.remove(effect);
            }
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
        if (renderer == null) {
            return;
        }
        WeatherFilterRenderer retired = renderer;
        if (RenderSystem.isOnRenderThread()) {
            retired.release();
        } else {
            RenderSystem.recordRenderCall(retired::release);
        }
    }

    private static final class SceneModelsWeatherOccluder implements WeatherOccluder {
        @Override
        public boolean hasNearbyOccluders(
                ClientLevel level,
                Vec3 camera,
                @Nullable Entity cameraEntity,
                double horizontalRadius,
                double minWorldY,
                double maxWorldY,
                float partialTick
        ) {
            return SceneModels.hasWeatherOccluders(level, camera, horizontalRadius, partialTick);
        }

        @Override
        public void renderDepth(WeatherDepthPassContext context) {
            SceneModels.renderWeatherDepth(context);
        }

        @Override
        public OptionalDouble sampleRoofHeight(
                ClientLevel level,
                @Nullable Entity cameraEntity,
                double worldX,
                double minY,
                double maxY,
                double worldZ,
                float partialTick
        ) {
            if (level == null) {
                return OptionalDouble.empty();
            }
            return SceneModels.sampleWeatherRoof(level, worldX, minY, maxY, worldZ, partialTick);
        }
    }
}
