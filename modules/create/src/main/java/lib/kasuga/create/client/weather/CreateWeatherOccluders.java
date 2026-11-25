package lib.kasuga.create.client.weather;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import lib.kasuga.KasugaLib;
import lib.kasuga.create.CreateConfig;
import lib.kasuga.create.client.model.CreateSceneHosts;
import lib.kasuga.create.client.model.SableSceneHosts;
import lib.kasuga.rendering.effect.RegisterRenderPipelinesEvent;
import lib.kasuga.rendering.effect.builtin.weather.WeatherDepthPassContext;
import lib.kasuga.rendering.effect.builtin.weather.WeatherFilters;
import lib.kasuga.rendering.effect.builtin.weather.WeatherOccluder;
import lib.kasuga.rendering.models.mc.scene.BlockModelSource;
import lib.kasuga.rendering.models.mc.scene.CachedBlockGeometry;
import lib.kasuga.rendering.models.mc.scene.LayerLayout;
import lib.kasuga.rendering.models.mc.scene.MinecraftModelSource;
import lib.kasuga.rendering.models.mc.scene.MinecraftModelSources;
import lib.kasuga.rendering.models.mc.scene.ModelPreviewResources;
import lib.kasuga.scene.SceneHost;
import lib.kasuga.scene.ScenePose;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Registers weather occlusion support for Create {@link AbstractContraptionEntity} instances
 * (including trains) and, when present, optional Sable / Aeronautics physics sublevels.
 */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class CreateWeatherOccluders {
    private static final double CONTRAPTION_QUERY_MARGIN = 6.0;
    private static final long STALE_CACHE_TICKS = 100L;

    private static final Map<Integer, ContraptionCacheEntry> CACHE = new HashMap<>();
    private static boolean registered;

    private CreateWeatherOccluders() {}

    @SubscribeEvent
    public static void onRegisterRenderPipelines(RegisterRenderPipelinesEvent event) {
        ensureRegistered();
    }

    public static void ensureRegistered() {
        if (registered) {
            return;
        }
        registered = true;
        WeatherFilters.markAdapterEntityClass(AbstractContraptionEntity.class);
        WeatherFilters.registerOccluder(new ContraptionWeatherOccluder());
        if (SableSceneHosts.isAvailable()) {
            SableWeatherBridge.register();
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            clearAllCaches();
            return;
        }
        long gameTime = level.getGameTime();
        Iterator<Map.Entry<Integer, ContraptionCacheEntry>> iterator = CACHE.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, ContraptionCacheEntry> entry = iterator.next();
            Entity entity = level.getEntity(entry.getKey());
            ContraptionCacheEntry cached = entry.getValue();
            if (!(entity instanceof AbstractContraptionEntity contraptionEntity)
                    || contraptionEntity.isRemoved()
                    || contraptionEntity.getContraption() != cached.contraption
                    || gameTime - cached.lastUsedTick > STALE_CACHE_TICKS) {
                iterator.remove();
                closeOnRenderThread(cached);
            }
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ClientLevel) {
            clearAllCaches();
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clearAllCaches();
    }

    private static void clearAllCaches() {
        if (CACHE.isEmpty()) {
            return;
        }
        List<ContraptionCacheEntry> retired = new ArrayList<>(CACHE.values());
        CACHE.clear();
        for (ContraptionCacheEntry entry : retired) {
            closeOnRenderThread(entry);
        }
    }

    private static void closeOnRenderThread(ContraptionCacheEntry entry) {
        if (RenderSystem.isOnRenderThread()) {
            entry.close();
        } else {
            RenderSystem.recordRenderCall(entry::close);
        }
    }

    @Nullable
    private static ScenePose resolvePose(AbstractContraptionEntity entity, float partialTick) {
        if (SableSceneHosts.isAvailable()) {
            var sableHost = SableSceneHosts.contraption(entity);
            if (sableHost.isPresent()) {
                return sableHost.get().pose(partialTick);
            }
        }
        if (!entity.level().isClientSide() || entity.getContraption() == null || entity.isRemoved()) {
            return null;
        }
        SceneHost host = CreateSceneHosts.contraption(entity);
        return host.pose(partialTick);
    }

    private static ContraptionCacheEntry resolveEntry(ClientLevel level, AbstractContraptionEntity entity) {
        Contraption contraption = entity.getContraption();
        long generation = ModelPreviewResources.generation();
        int blockCount = contraption.getBlocks().size();
        ContraptionCacheEntry existing = CACHE.get(entity.getId());
        if (existing != null
                && existing.level == level
                && existing.contraption == contraption
                && existing.blockCount == blockCount
                && existing.resourceGeneration == generation) {
            existing.lastUsedTick = level.getGameTime();
            return existing;
        }
        if (existing != null) {
            CACHE.remove(entity.getId());
            closeOnRenderThread(existing);
        }
        ContraptionCacheEntry created = ContraptionCacheEntry.build(level, contraption, generation);
        CACHE.put(entity.getId(), created);
        return created;
    }

    private static final class ContraptionWeatherOccluder implements WeatherOccluder {
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
            if (!CreateConfig.isContraptionWeatherFilterEnabled()) {
                return false;
            }
            AABB query = searchBox(camera, horizontalRadius, minWorldY, maxWorldY);
            List<AbstractContraptionEntity> entities = level.getEntitiesOfClass(AbstractContraptionEntity.class, query);
            for (AbstractContraptionEntity entity : entities) {
                if (isActive(entity)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void renderDepth(WeatherDepthPassContext context) {
            if (!CreateConfig.isContraptionWeatherFilterEnabled()) {
                return;
            }
            ClientLevel level = context.level();
            Vec3 camera = context.camera();
            float partialTick = context.partialTick();
            AABB query = searchBox(camera, context.horizontalRadius(), context.minWorldY(), context.maxWorldY());
            List<AbstractContraptionEntity> entities = level.getEntitiesOfClass(AbstractContraptionEntity.class, query);
            for (AbstractContraptionEntity entity : entities) {
                if (!isActive(entity)) {
                    continue;
                }
                ScenePose pose = resolvePose(entity, partialTick);
                if (pose == null) {
                    continue;
                }
                ContraptionCacheEntry entry = resolveEntry(level, entity);
                context.poseStack().pushPose();
                try {
                    context.poseStack().mulPose(pose.relativeTo(camera));
                    entry.renderDepth(new MinecraftModelSource.Context(
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
            if (!CreateConfig.isContraptionWeatherFilterEnabled()) {
                return OptionalDouble.empty();
            }
            AABB query = new AABB(
                    worldX - CONTRAPTION_QUERY_MARGIN, minY - 2.0, worldZ - CONTRAPTION_QUERY_MARGIN,
                    worldX + CONTRAPTION_QUERY_MARGIN, maxY + 2.0, worldZ + CONTRAPTION_QUERY_MARGIN
            );
            List<AbstractContraptionEntity> entities = level.getEntitiesOfClass(AbstractContraptionEntity.class, query);
            double best = Double.NEGATIVE_INFINITY;
            for (AbstractContraptionEntity entity : entities) {
                if (!isActive(entity)) {
                    continue;
                }
                ScenePose pose = resolvePose(entity, partialTick);
                if (pose == null) {
                    continue;
                }
                ContraptionCacheEntry entry = resolveEntry(level, entity);
                OptionalDouble hit = ContraptionWeatherRaycast.raycastVertical(
                        pose,
                        entry.minBX, entry.minBY, entry.minBZ,
                        entry.maxBX, entry.maxBY, entry.maxBZ,
                        entry.blockGetter,
                        worldX, minY, maxY, worldZ
                );
                if (hit.isPresent() && hit.getAsDouble() > best) {
                    best = hit.getAsDouble();
                }
            }
            return best > Double.NEGATIVE_INFINITY ? OptionalDouble.of(best) : OptionalDouble.empty();
        }

        private static boolean isActive(AbstractContraptionEntity entity) {
            if (entity.getContraption() == null || entity.getContraption().getBlocks().isEmpty()) {
                return false;
            }
            return WeatherFilters.shouldFilterEntity(entity, true);
        }

        private static AABB searchBox(Vec3 camera, double horizontalRadius, double minWorldY, double maxWorldY) {
            double rx = horizontalRadius + CONTRAPTION_QUERY_MARGIN;
            return new AABB(
                    camera.x - rx, minWorldY - CONTRAPTION_QUERY_MARGIN, camera.z - rx,
                    camera.x + rx, maxWorldY + CONTRAPTION_QUERY_MARGIN, camera.z + rx
            );
        }
    }

    private static final class ContraptionCacheEntry implements AutoCloseable {
        final ClientLevel level;
        final Contraption contraption;
        final int blockCount;
        final long resourceGeneration;
        final @Nullable BlockModelSource modelSource;
        final BlockGetter blockGetter;
        @Nullable
        private CachedBlockGeometry glassDepth;
        final int minBX;
        final int minBY;
        final int minBZ;
        final int maxBX;
        final int maxBY;
        final int maxBZ;
        long lastUsedTick;

        private ContraptionCacheEntry(
                ClientLevel level,
                Contraption contraption,
                int blockCount,
                long resourceGeneration,
                @Nullable BlockModelSource modelSource,
                BlockGetter blockGetter,
                int minBX, int minBY, int minBZ,
                int maxBX, int maxBY, int maxBZ
        ) {
            this.level = level;
            this.contraption = contraption;
            this.blockCount = blockCount;
            this.resourceGeneration = resourceGeneration;
            this.modelSource = modelSource;
            this.blockGetter = blockGetter;
            this.minBX = minBX;
            this.minBY = minBY;
            this.minBZ = minBZ;
            this.maxBX = maxBX;
            this.maxBY = maxBY;
            this.maxBZ = maxBZ;
            this.lastUsedTick = level.getGameTime();
        }

        static ContraptionCacheEntry build(ClientLevel level, Contraption contraption, long generation) {
            Map<BlockPos, StructureBlockInfo> blockMap = contraption.getBlocks();
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxY = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;
            List<StructureBlockInfo> infos = new ArrayList<>(Math.min(blockMap.size(), MinecraftModelSources.MAX_BLOCKS));
            boolean withinModelLimit = blockMap.size() <= MinecraftModelSources.MAX_BLOCKS;

            for (StructureBlockInfo info : blockMap.values()) {
                if (info.state().isAir()) {
                    continue;
                }
                BlockPos pos = info.pos();
                if (pos.getX() < minX) minX = pos.getX();
                if (pos.getY() < minY) minY = pos.getY();
                if (pos.getZ() < minZ) minZ = pos.getZ();
                if (pos.getX() > maxX) maxX = pos.getX();
                if (pos.getY() > maxY) maxY = pos.getY();
                if (pos.getZ() > maxZ) maxZ = pos.getZ();
                if (withinModelLimit) {
                    infos.add(new StructureBlockInfo(pos.immutable(), info.state(), null));
                }
            }

            if (minX > maxX) {
                minX = minY = minZ = 0;
                maxX = maxY = maxZ = -1;
            }

            BlockModelSource source = null;
            if (withinModelLimit && !infos.isEmpty()) {
                try {
                    source = new BlockModelSource(level, infos, false);
                } catch (IllegalArgumentException ignored) {
                    // Exceeds BlockModelSource span limit; CPU raycast still functions via contraption block map.
                }
            }

            BlockGetter getter = new BlockGetter() {
                @Override
                public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
                    return null;
                }

                @Override
                public BlockState getBlockState(BlockPos pos) {
                    StructureBlockInfo info = blockMap.get(pos);
                    return info == null ? Blocks.AIR.defaultBlockState() : info.state();
                }

                @Override
                public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos) {
                    return getBlockState(pos).getFluidState();
                }

                @Override
                public int getHeight() {
                    return level.getHeight();
                }

                @Override
                public int getMinBuildHeight() {
                    return level.getMinBuildHeight();
                }
            };

            return new ContraptionCacheEntry(
                    level,
                    contraption,
                    blockMap.size(),
                    generation,
                    source,
                    getter,
                    minX, minY, minZ,
                    maxX, maxY, maxZ
            );
        }

        private void renderDepth(MinecraftModelSource.Context context) {
            if (modelSource != null) {
                MinecraftModelSource.Snapshot snapshot = modelSource.snapshot(LayerLayout.ASSEMBLED, context.partialTick());
                if (snapshot != null) {
                    snapshot.render(context);
                }
            }
            if (glassDepth == null) {
                glassDepth = CachedBlockGeometry.build(this::renderGlassDepth);
            }
            glassDepth.render(context);
        }

        private void renderGlassDepth(MinecraftModelSource.Context context) {
            // The glass texture can discard fragments even in a depth-only pass. Rasterize
            // its collision shape without an alpha test, like the CPU roof query.
            for (StructureBlockInfo info : contraption.getBlocks().values()) {
                BlockState state = info.state();
                if (!state.is(Tags.Blocks.GLASS_BLOCKS) && !state.is(Tags.Blocks.GLASS_PANES)) {
                    continue;
                }
                BlockPos pos = info.pos();
                for (AABB box : state.getCollisionShape(blockGetter, pos).toAabbs()) {
                    VertexConsumer vertices = context.buffers().getBuffer(RenderType.debugQuads());
                    for (Direction face : Direction.values()) {
                        LevelRenderer.renderFace(context.pose(), vertices, face,
                                (float) (pos.getX() + box.minX), (float) (pos.getY() + box.minY), (float) (pos.getZ() + box.minZ),
                                (float) (pos.getX() + box.maxX), (float) (pos.getY() + box.maxY), (float) (pos.getZ() + box.maxZ),
                                1.0f, 1.0f, 1.0f, 1.0f);
                    }
                }
            }
        }

        @Override
        public void close() {
            if (glassDepth != null) {
                glassDepth.close();
            }
            if (modelSource != null) {
                modelSource.close();
            }
        }
    }
}
