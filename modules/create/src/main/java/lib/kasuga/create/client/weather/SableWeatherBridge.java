package lib.kasuga.create.client.weather;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import lib.kasuga.create.CreateConfig;
import lib.kasuga.create.client.model.SableSceneHosts;
import lib.kasuga.rendering.effect.builtin.weather.WeatherDepthPassContext;
import lib.kasuga.rendering.effect.builtin.weather.WeatherFilters;
import lib.kasuga.rendering.effect.builtin.weather.WeatherOccluder;
import lib.kasuga.rendering.models.mc.scene.BlockModelSource;
import lib.kasuga.rendering.models.mc.scene.LayerLayout;
import lib.kasuga.rendering.models.mc.scene.MinecraftModelSource;
import lib.kasuga.rendering.models.mc.scene.MinecraftModelSources;
import lib.kasuga.rendering.models.mc.scene.ModelPreviewResources;
import lib.kasuga.scene.ScenePose;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.UUID;

/**
 * Optional Sable / Aeronautics bridge for weather occlusion.
 * Isolated from {@link CreateWeatherOccluders} so Create integration loads without Sable Companion.
 */
public final class SableWeatherBridge {
    private static final int MAX_PLOT_SPAN = 48;
    private static final long REFRESH_INTERVAL_TICKS = 10L;
    private static final long STALE_CACHE_TICKS = 100L;

    private static final Map<UUID, SablePlotCache> PLOT_CACHES = new HashMap<>();
    private static boolean registered;

    private SableWeatherBridge() {}

    static void register() {
        if (registered) {
            return;
        }
        registered = true;
        WeatherFilters.registerOccluder(new SableWeatherOccluder());
        NeoForge.EVENT_BUS.addListener(SableWeatherBridge::onClientTick);
        NeoForge.EVENT_BUS.addListener(SableWeatherBridge::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(SableWeatherBridge::onLoggingOut);
    }

    /**
     * Pure/testable CPU roof raycast against a Sable sublevel pose and plot block storage.
     */
    public static OptionalDouble sampleSableRoof(
            Pose3dc renderPose,
            BoundingBox3dc plotBounds,
            BlockGetter plotBlocks,
            double worldX,
            double minY,
            double maxY,
            double worldZ
    ) {
        if (maxY <= minY) {
            return OptionalDouble.empty();
        }
        Vector3d localTop = renderPose.transformPositionInverse(new Vector3d(worldX, maxY, worldZ));
        Vector3d localBottom = renderPose.transformPositionInverse(new Vector3d(worldX, minY, worldZ));

        int minBX = Mth.floor(Math.min(localTop.x, localBottom.x)) - 1;
        int minBY = Mth.floor(Math.min(localTop.y, localBottom.y)) - 1;
        int minBZ = Mth.floor(Math.min(localTop.z, localBottom.z)) - 1;
        int maxBX = Mth.floor(Math.max(localTop.x, localBottom.x)) + 1;
        int maxBY = Mth.floor(Math.max(localTop.y, localBottom.y)) + 1;
        int maxBZ = Mth.floor(Math.max(localTop.z, localBottom.z)) + 1;

        if (plotBounds != null && plotBounds.maxX() > plotBounds.minX()
                && plotBounds.maxY() > plotBounds.minY() && plotBounds.maxZ() > plotBounds.minZ()) {
            minBX = Math.max(minBX, Mth.floor(plotBounds.minX()));
            minBY = Math.max(minBY, Mth.floor(plotBounds.minY()));
            minBZ = Math.max(minBZ, Mth.floor(plotBounds.minZ()));
            maxBX = Math.min(maxBX, Mth.ceil(plotBounds.maxX()) - 1);
            maxBY = Math.min(maxBY, Mth.ceil(plotBounds.maxY()) - 1);
            maxBZ = Math.min(maxBZ, Mth.ceil(plotBounds.maxZ()) - 1);
        }

        if (minBX > maxBX || minBY > maxBY || minBZ > maxBZ) {
            return OptionalDouble.empty();
        }

        Vec3 hitLocal = ContraptionWeatherRaycast.raycastLocalSegment(
                new Vec3(localTop.x, localTop.y, localTop.z),
                new Vec3(localBottom.x, localBottom.y, localBottom.z),
                minBX, minBY, minBZ,
                maxBX, maxBY, maxBZ,
                plotBlocks
        );
        if (hitLocal == null) {
            return OptionalDouble.empty();
        }
        Vector3d hitWorld = renderPose.transformPosition(new Vector3d(hitLocal.x, hitLocal.y, hitLocal.z));
        if (hitWorld.y >= minY - 1e-4 && hitWorld.y <= maxY + 1e-4) {
            return OptionalDouble.of(Math.clamp(hitWorld.y, minY, maxY));
        }
        return OptionalDouble.empty();
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        if (!SableSceneHosts.isAvailable()) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            clearCaches();
            return;
        }
        long gameTime = level.getGameTime();
        Iterator<Map.Entry<UUID, SablePlotCache>> iterator = PLOT_CACHES.entrySet().iterator();
        while (iterator.hasNext()) {
            SablePlotCache entry = iterator.next().getValue();
            if (entry.level != level || gameTime - entry.lastUsedTick > STALE_CACHE_TICKS) {
                iterator.remove();
                closeOnRenderThread(entry);
            }
        }
    }

    private static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ClientLevel) {
            clearCaches();
        }
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clearCaches();
    }

    private static void clearCaches() {
        if (PLOT_CACHES.isEmpty()) {
            return;
        }
        List<SablePlotCache> retired = new ArrayList<>(PLOT_CACHES.values());
        PLOT_CACHES.clear();
        for (SablePlotCache entry : retired) {
            closeOnRenderThread(entry);
        }
    }

    private static void closeOnRenderThread(SablePlotCache entry) {
        if (RenderSystem.isOnRenderThread()) {
            entry.close();
        } else {
            RenderSystem.recordRenderCall(entry::close);
        }
    }

    private static final class SableWeatherOccluder implements WeatherOccluder {
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
            if (!CreateConfig.isSableWeatherFilterEnabled()) {
                return false;
            }
            BoundingBox3d query = new BoundingBox3d(
                    camera.x - horizontalRadius, minWorldY, camera.z - horizontalRadius,
                    camera.x + horizontalRadius, maxWorldY, camera.z + horizontalRadius
            );
            for (SubLevelAccess sub : SableCompanion.INSTANCE.getAllIntersecting(level, query)) {
                if (sub instanceof ClientSubLevelAccess) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void renderDepth(WeatherDepthPassContext context) {
            if (!CreateConfig.isSableWeatherFilterEnabled()) {
                return;
            }
            ClientLevel level = context.level();
            Vec3 camera = context.camera();
            double radius = context.horizontalRadius();
            float partialTick = context.partialTick();
            BoundingBox3d query = new BoundingBox3d(
                    camera.x - radius, context.minWorldY(), camera.z - radius,
                    camera.x + radius, context.maxWorldY(), camera.z + radius
            );

            for (SubLevelAccess sub : SableCompanion.INSTANCE.getAllIntersecting(level, query)) {
                if (!(sub instanceof ClientSubLevelAccess clientSub)) {
                    continue;
                }
                Pose3dc renderPose = clientSub.renderPose(partialTick);
                BoundingBox3d plotQuery = query.transformInverse(renderPose, new BoundingBox3d());
                BoundingBox3dc plotBounds = clientSub.boundingBox();
                if (plotBounds != null && plotBounds.maxX() > plotBounds.minX()
                        && plotBounds.maxY() > plotBounds.minY() && plotBounds.maxZ() > plotBounds.minZ()) {
                    plotQuery.intersect(plotBounds);
                }
                if (plotQuery.maxX() <= plotQuery.minX()
                        || plotQuery.maxY() <= plotQuery.minY()
                        || plotQuery.maxZ() <= plotQuery.minZ()) {
                    continue;
                }

                BlockPos minPos = clampedBoundMin(plotQuery, level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
                BlockPos maxPos = clampedBoundMax(plotQuery, minPos, level.getMaxBuildHeight() - 1);
                if (minPos.getX() > maxPos.getX() || minPos.getY() > maxPos.getY() || minPos.getZ() > maxPos.getZ()) {
                    continue;
                }

                SablePlotCache cache = resolvePlotCache(level, clientSub, minPos, maxPos);
                if (cache.modelSource == null) {
                    continue;
                }
                MinecraftModelSource.Snapshot snapshot = cache.modelSource.snapshot(LayerLayout.ASSEMBLED, partialTick);
                if (snapshot == null) {
                    continue;
                }
                ScenePose anchored = SableSceneHosts.Bridge.anchored(renderPose, Vec3.atLowerCornerOf(cache.origin));
                context.poseStack().pushPose();
                try {
                    context.poseStack().mulPose(anchored.relativeTo(camera));
                    snapshot.render(new MinecraftModelSource.Context(
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
            if (!CreateConfig.isSableWeatherFilterEnabled() || maxY <= minY) {
                return OptionalDouble.empty();
            }
            BoundingBox3d query = new BoundingBox3d(
                    worldX - 1.0, minY, worldZ - 1.0,
                    worldX + 1.0, maxY, worldZ + 1.0
            );
            double best = Double.NEGATIVE_INFINITY;
            for (SubLevelAccess sub : SableCompanion.INSTANCE.getAllIntersecting(level, query)) {
                if (!(sub instanceof ClientSubLevelAccess clientSub)) {
                    continue;
                }
                OptionalDouble hit = sampleSableRoof(
                        clientSub.renderPose(partialTick),
                        clientSub.boundingBox(),
                        level,
                        worldX,
                        minY,
                        maxY,
                        worldZ
                );
                if (hit.isPresent() && hit.getAsDouble() > best) {
                    best = hit.getAsDouble();
                }
            }
            return best > Double.NEGATIVE_INFINITY ? OptionalDouble.of(best) : OptionalDouble.empty();
        }

        private static BlockPos clampedBoundMin(BoundingBox3d box, int minBuildY, int maxBuildY) {
            Vector3d center = box.center();
            int half = MAX_PLOT_SPAN / 2;
            int minX = Math.max(Mth.floor(box.minX()), Mth.floor(center.x) - half);
            int minY = Math.clamp(Math.max(Mth.floor(box.minY()), Mth.floor(center.y) - half), minBuildY, maxBuildY);
            int minZ = Math.max(Mth.floor(box.minZ()), Mth.floor(center.z) - half);
            return new BlockPos(minX, minY, minZ);
        }

        private static BlockPos clampedBoundMax(BoundingBox3d box, BlockPos minPos, int maxBuildY) {
            int maxX = Math.min(Mth.ceil(box.maxX()) - 1, minPos.getX() + MAX_PLOT_SPAN - 1);
            int maxY = Math.min(Math.min(Mth.ceil(box.maxY()) - 1, minPos.getY() + MAX_PLOT_SPAN - 1), maxBuildY);
            int maxZ = Math.min(Mth.ceil(box.maxZ()) - 1, minPos.getZ() + MAX_PLOT_SPAN - 1);
            return new BlockPos(maxX, maxY, maxZ);
        }

        private static SablePlotCache resolvePlotCache(
                ClientLevel level,
                ClientSubLevelAccess clientSub,
                BlockPos minPos,
                BlockPos maxPos
        ) {
            UUID id = clientSub.getUniqueId();
            long gameTime = level.getGameTime();
            long generation = ModelPreviewResources.generation();
            SablePlotCache existing = PLOT_CACHES.get(id);
            if (existing != null
                    && existing.level == level
                    && existing.resourceGeneration == generation
                    && existing.covers(minPos, maxPos)
                    && gameTime - existing.builtTick < REFRESH_INTERVAL_TICKS) {
                existing.lastUsedTick = gameTime;
                return existing;
            }
            if (existing != null) {
                PLOT_CACHES.remove(id);
                closeOnRenderThread(existing);
            }
            SablePlotCache created = SablePlotCache.capture(level, minPos, maxPos, gameTime, generation);
            PLOT_CACHES.put(id, created);
            return created;
        }
    }

    private static final class SablePlotCache implements AutoCloseable {
        final ClientLevel level;
        final BlockPos origin;
        final BlockPos min;
        final BlockPos max;
        final long builtTick;
        final long resourceGeneration;
        final @Nullable BlockModelSource modelSource;
        long lastUsedTick;

        private SablePlotCache(
                ClientLevel level,
                BlockPos origin,
                BlockPos min,
                BlockPos max,
                long builtTick,
                long resourceGeneration,
                @Nullable BlockModelSource modelSource
        ) {
            this.level = level;
            this.origin = origin;
            this.min = min;
            this.max = max;
            this.builtTick = builtTick;
            this.resourceGeneration = resourceGeneration;
            this.modelSource = modelSource;
            this.lastUsedTick = builtTick;
        }

        boolean covers(BlockPos queryMin, BlockPos queryMax) {
            return queryMin.getX() >= min.getX() && queryMin.getY() >= min.getY() && queryMin.getZ() >= min.getZ()
                    && queryMax.getX() <= max.getX() && queryMax.getY() <= max.getY() && queryMax.getZ() <= max.getZ();
        }

        static SablePlotCache capture(ClientLevel level, BlockPos min, BlockPos max, long tick, long generation) {
            List<StructureBlockInfo> infos = new ArrayList<>();
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    for (int x = min.getX(); x <= max.getX(); x++) {
                        if (!level.hasChunkAt(cursor.set(x, y, z))) {
                            continue;
                        }
                        BlockState state = level.getBlockState(cursor);
                        if (!state.isAir()) {
                            infos.add(new StructureBlockInfo(cursor.immutable(), state, null));
                            if (infos.size() >= MinecraftModelSources.MAX_BLOCKS) {
                                break;
                            }
                        }
                    }
                    if (infos.size() >= MinecraftModelSources.MAX_BLOCKS) break;
                }
                if (infos.size() >= MinecraftModelSources.MAX_BLOCKS) break;
            }
            BlockModelSource source = null;
            if (!infos.isEmpty()) {
                try {
                    source = new BlockModelSource(level, infos, min, false);
                } catch (IllegalArgumentException ignored) {
                    // Bounds exceed BlockModelSource limit; CPU raycast continues to operate.
                }
            }
            return new SablePlotCache(level, min.immutable(), min.immutable(), max.immutable(), tick, generation, source);
        }

        @Override
        public void close() {
            if (modelSource != null) {
                modelSource.close();
            }
        }
    }
}
