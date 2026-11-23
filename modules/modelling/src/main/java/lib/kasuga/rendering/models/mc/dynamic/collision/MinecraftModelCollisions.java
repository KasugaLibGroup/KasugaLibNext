package lib.kasuga.rendering.models.mc.dynamic.collision;

import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.FrameInputSnapshot;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.RenderFrameToken;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.RenderSampleContext;
import lib.kasuga.rendering.models.uml.dynamic.physics.BoneCollisionBounds;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Owns collidable model instances on the logical server and their chunk snapshots on clients. */
@EventBusSubscriber(modid = KasugaLib.MODID)
public final class MinecraftModelCollisions {
    private static final Map<Level, World> WORLDS = Collections.synchronizedMap(new IdentityHashMap<>());

    private MinecraftModelCollisions() {}

    private static final class World {
        final ModelCollisionIndex index = new ModelCollisionIndex();
        final Map<BlockEntity, Handle> handles = new IdentityHashMap<>();
    }

    /** Places the model at the standard block-model origin, at the bottom center of the host block. */
    public static Handle attach(BlockEntity owner, ServerCollisionModels.Asset asset) {
        BlockPos pos = owner.getBlockPos();
        return attach(owner, asset, new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5), new Transform());
    }

    /** The supplied root transform is relative to the double-precision anchor. Call on the server thread. */
    public static Handle attach(BlockEntity owner, ServerCollisionModels.Asset asset, Vec3 anchor, Transform localRoot) {
        if (!(owner.getLevel() instanceof ServerLevel level) || owner.isRemoved()) {
            throw new IllegalArgumentException("Model collision requires a live server block entity");
        }
        if (!level.hasChunkAt(owner.getBlockPos())) throw new IllegalArgumentException("Collision host chunk is unloaded");
        World world = WORLDS.computeIfAbsent(level, ignored -> new World());
        Handle handle = new Handle(owner, level, world, asset, anchor, localRoot);
        try {
            handle.publishPose();
        } catch (RuntimeException | Error failure) {
            handle.instance.close();
            throw failure;
        }
        Handle previous = world.handles.put(owner, handle);
        if (previous != null) previous.instance.close();
        return handle;
    }

    /** A host drives its pose once per server tick and closes the handle on removal. */
    public static final class Handle implements AutoCloseable {
        private final BlockEntity owner;
        private final ServerLevel level;
        private final World world;
        private final ModelInstance instance;
        private final BoneCollisionBounds.WorldSampler bounds;
        private List<BoneCollisionBounds.Box> publishedBounds;

        private Handle(BlockEntity owner, ServerLevel level, World world, ServerCollisionModels.Asset asset,
                       Vec3 anchor, Transform localRoot) {
            this.owner = owner;
            this.level = level;
            this.world = world;
            this.bounds = asset.bounds().newWorldSampler();
            this.instance = ModelInstance.forSimulation(asset.model(), localRoot.copy());
            this.instance.getSkeletonInstance().enableFloatingOrigin(new Vector3d(anchor.x, anchor.y, anchor.z));
        }

        public ModelInstance instance() {
            return instance;
        }

        public boolean isClosed() {
            return instance.isClosed();
        }

        /** Advances locally driven clips and evaluates an already committed animation scope, if present. */
        public void tick(float deltaSeconds) {
            if (isClosed()) return;
            instance.animate(deltaSeconds);
            if (instance.getAnimationRenderPort() == null) {
                instance.sample(1f);
            } else {
                instance.sample(new RenderSampleContext(new RenderFrameToken(level.getGameTime()),
                        1.0, FrameInputSnapshot.empty()));
            }
            instance.getMorph().update();
            instance.tick(deltaSeconds);
            publish();
        }

        private boolean ownerAlive() {
            return !owner.isRemoved() && owner.getLevel() == level && level.hasChunkAt(owner.getBlockPos());
        }

        /** Publishes the most recently evaluated bones, e.g. after a caller-managed physics scene steps. */
        public void publish() {
            if (isClosed() || !ownerAlive()) {
                close();
                return;
            }
            publishPose();
        }

        private void publishPose() {
            List<BoneCollisionBounds.Box> sampled = bounds.sample(instance.getSkeletonInstance());
            if (sampled == publishedBounds) return;
            List<AABB> boxes = new ArrayList<>(sampled.size());
            for (BoneCollisionBounds.Box box : sampled) {
                boxes.add(new AABB(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()));
            }
            world.index.replace(owner, boxes);
            publishedBounds = sampled;
        }

        @Override public void close() {
            if (world.handles.remove(owner, this)) world.index.replace(owner, List.of());
            instance.close();
        }
    }

    public static List<VoxelShape> append(Level level, @Nullable Entity entity, AABB area, List<VoxelShape> vanilla) {
        if (entity != null && (entity.noPhysics || entity.isSpectator())) return vanilla;
        World world = WORLDS.get(level);
        return world == null ? vanilla : world.index.collisions(area, entity, vanilla);
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").optional().playToClient(ModelCollisionPayload.TYPE, ModelCollisionPayload.CODEC,
                (payload, context) -> receive(context.player().level(), payload));
    }

    private static void receive(Level level, ModelCollisionPayload payload) {
        if (!level.isClientSide() || !level.dimension().location().equals(payload.dimension())) return;
        ChunkPos chunk = new ChunkPos(payload.chunk());
        if (!level.getChunkSource().hasChunk(chunk.x, chunk.z)) return;
        WORLDS.computeIfAbsent(level, ignored -> new World()).index.receive(payload.chunk(), payload.boxes());
    }

    @SubscribeEvent
    public static void sent(ChunkWatchEvent.Sent event) {
        World world = WORLDS.get(event.getLevel());
        if (world == null) return;
        long key = event.getPos().toLong();
        List<AABB> boxes = world.index.snapshot(key);
        if (!boxes.isEmpty()) send(event.getPlayer(), new ModelCollisionPayload(event.getLevel().dimension().location(), key, boxes));
    }

    @SubscribeEvent
    public static void tick(LevelTickEvent.Post event) {
        Level level = event.getLevel();
        World world = WORLDS.get(level);
        if (world == null) return;
        if (!(level instanceof ServerLevel server)) {
            world.index.dropUnloadedSnapshots(key -> level.getChunkSource().hasChunk(ChunkPos.getX(key), ChunkPos.getZ(key)));
            return;
        }
        for (Handle handle : List.copyOf(world.handles.values())) {
            if (handle.isClosed() || !handle.ownerAlive()) handle.close();
        }
        for (long chunk : world.index.drainDirtyChunks()) {
            List<ServerPlayer> players = server.getChunkSource().chunkMap.getPlayers(new ChunkPos(chunk), false);
            if (players.isEmpty()) continue;
            ModelCollisionPayload payload = new ModelCollisionPayload(level.dimension().location(), chunk, world.index.snapshot(chunk));
            for (ServerPlayer player : players) send(player, payload);
        }
    }

    private static void send(ServerPlayer player, ModelCollisionPayload payload) {
        if (NetworkRegistry.hasChannel(player.connection, ModelCollisionPayload.TYPE.id())) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    @SubscribeEvent
    public static void chunkUnloaded(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof Level level)) return;
        World world = WORLDS.get(level);
        if (world == null) return;
        ChunkPos pos = event.getChunk().getPos();
        if (level.isClientSide()) {
            world.index.receive(pos.toLong(), List.of());
            return;
        }
        for (Handle handle : List.copyOf(world.handles.values())) {
            if (new ChunkPos(handle.owner.getBlockPos()).equals(pos)) handle.close();
        }
    }

    @SubscribeEvent
    public static void levelUnloaded(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof Level level)) return;
        World world = WORLDS.remove(level);
        if (world == null) return;
        for (Handle handle : List.copyOf(world.handles.values())) handle.close();
    }
}
