package lib.kasuga.rendering.output.mc.stream;

import com.mojang.blaze3d.systems.RenderSystem;
import io.netty.buffer.Unpooled;
import lib.kasuga.mixins.modelling.WorldViewBiomeAccessor;
import lib.kasuga.mixins.modelling.WorldViewLevelDataAccessor;
import lib.kasuga.rendering.output.WorldCameraView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.SectionPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import java.util.*;
import lib.kasuga.mixins.modelling.WorldViewConnectionAccessor;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.client.player.RemotePlayer;

/** Routes camera snapshots exclusively into that camera's ClientLevel, including light and block entities. */
public final class CameraChunkClient {
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private CameraChunkClient() {}
    public static void receive(CameraChunkFragment fragment) {
        RenderSystem.assertOnRenderThread();
        Session session = SESSIONS.get(fragment.camera());
        if (session != null) session.receive(fragment);
    }
    public static void receiveEntity(CameraEntityPayload payload) {
        RenderSystem.assertOnRenderThread();
        var session = SESSIONS.get(payload.camera());
        if (session != null) session.receiveEntity(payload);
    }
    public static void tickEntities() {
        for (var session : List.copyOf(SESSIONS.values())) if (!session.closed) session.level.tickEntities();
    }
    public static final class Session implements AutoCloseable {
        private final UUID id = UUID.randomUUID();
        private final int radius;
        public final ClientLevel level;
        private final net.minecraft.network.ProtocolInfo<net.minecraft.network.protocol.game.ClientGamePacketListener> entityProtocol;
        private final Set<Long> loaded = new HashSet<>();
        private final Map<Long, Assembly> assembling = new HashMap<>();
        private long epoch, lastRequest;
        private long lastEntityVisualTick = Long.MIN_VALUE;
        private int x = Integer.MIN_VALUE, z = Integer.MIN_VALUE;
        private boolean subscribed, closed;
        public Session(ClientLevel main, LevelRenderer renderer, int radius) {
            this.radius = radius;
            var mc = Minecraft.getInstance();
            var data = new ClientLevel.ClientLevelData(main.getDifficulty(), main.getLevelData().isHardcore(),
                    ((WorldViewLevelDataAccessor) main.getLevelData()).kasuga$isFlat());
            level = new ClientLevel(mc.getConnection(), data, main.dimension(), main.dimensionTypeRegistration(), radius,
                    0, mc::getProfiler, renderer, main.isDebug(), ((WorldViewBiomeAccessor) main.getBiomeManager()).kasuga$seed());
            entityProtocol = GameProtocols.CLIENTBOUND_TEMPLATE.bind(b -> new RegistryFriendlyByteBuf(b, level.registryAccess()));
            SESSIONS.put(id, this);
        }
        public void update(WorldCameraView view, ClientLevel main) {
            if (closed) throw new IllegalStateException("Camera world closed");
            int cx = SectionPos.blockToSectionCoord(view.x()), cz = SectionPos.blockToSectionCoord(view.z());
            if (x != cx || z != cz) {
                x = cx; z = cz; epoch++; assembling.clear();
                level.getChunkSource().updateViewCenter(x, z);
                for (long key : List.copyOf(loaded)) if (!inRange(new ChunkPos(key).x, new ChunkPos(key).z)) drop(key);
                // Copy only already-present nearby chunks for immediate startup; distant chunks come from the server.
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    var chunk = main.getChunkSource().getChunk(cx + dx, cz + dz, false);
                    if (chunk != null) apply(new ClientboundLevelChunkWithLightPacket(chunk, main.getLightEngine(), null, null));
                }
                subscribed = false;
            }
            level.setGameTime(main.getGameTime()); level.setDayTime(main.getDayTime());
            level.setRainLevel(main.getRainLevel(1)); level.setThunderLevel(main.getThunderLevel(1));
            level.updateSkyBrightness();
            long tick = main.getGameTime();
            CameraEntityVisuals.synchronize(level, main, tick != lastEntityVisualTick);
            lastEntityVisualTick = tick;
            long now = System.nanoTime();
            if (!subscribed || now - lastRequest > 5_000_000_000L) {
                if (!NetworkRegistry.hasChannel(Minecraft.getInstance().getConnection(), CameraChunkChannel.REQUEST.getEntry().id()))
                    throw new IllegalStateException("Server does not support independent camera chunk subscriptions");
                PacketDistributor.sendToServer(request(true)); subscribed = true; lastRequest = now;
            }
            level.pollLightUpdates(); level.getLightEngine().runLightUpdates();
        }
        private boolean inRange(int cx, int cz) { return Math.abs((long) cx - x) <= radius + 1 && Math.abs((long) cz - z) <= radius + 1; }
        private CameraChunkRequest request(boolean open) { return new CameraChunkRequest(id, epoch, level.dimension().location(), x, z, radius, open); }
        public void pause() {
            if (subscribed && Minecraft.getInstance().getConnection() != null) PacketDistributor.sendToServer(request(false));
            subscribed = false; epoch++; assembling.clear();
        }
        private void receive(CameraChunkFragment fragment) {
            if (closed || !subscribed || fragment.epoch() != epoch || !inRange(fragment.x(), fragment.z())) return;
            if (assembling.size() > 16) throw new IllegalStateException("Too many incomplete camera chunks");
            Assembly assembly = assembling.computeIfAbsent(fragment.sequence(), ignored -> new Assembly(fragment));
            byte[] packetBytes = assembly.accept(fragment);
            if (packetBytes == null) return;
            assembling.remove(fragment.sequence());
            var buffer = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(packetBytes), level.registryAccess());
            try {
                var packet = ClientboundLevelChunkWithLightPacket.STREAM_CODEC.decode(buffer);
                if (packet.getX() != fragment.x() || packet.getZ() != fragment.z() || buffer.isReadable())
                    throw new IllegalArgumentException("Camera chunk envelope mismatch");
                apply(packet);
            } finally { buffer.release(); }
        }
        private void receiveEntity(CameraEntityPayload payload) {
            if (closed || !subscribed || payload.epoch() != epoch) return;
            var buffer = Unpooled.wrappedBuffer(payload.packet());
            try {
                var packet = entityProtocol.codec().decode(buffer);
                if (buffer.isReadable() || !CameraEntityPackets.allowed(packet)) throw new IllegalArgumentException("Unexpected camera entity packet");
                var connection = Minecraft.getInstance().getConnection();
                if (packet instanceof ClientboundAddEntityPacket spawn) {
                    Entity entity;
                    if (spawn.getType() == EntityType.PLAYER) {
                        var info = connection.getPlayerInfo(spawn.getUUID());
                        if (info == null) return;
                        entity = new RemotePlayer(level, info.getProfile());
                    } else entity = spawn.getType().create(level);
                    if (entity != null) { entity.recreateFromPacket(spawn); level.addEntity(entity); }
                } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket movement) {
                    var entity = level.getEntity(movement.getId());
                    if (entity != null) {
                        entity.syncPacketPositionCodec(movement.getX(), movement.getY(), movement.getZ());
                        // Subscription updates every five ticks; vanilla's three-step lerp stalls between packets.
                        entity.lerpTo(movement.getX(), movement.getY(), movement.getZ(),
                                movement.getyRot() * 360 / 256.0f, movement.getxRot() * 360 / 256.0f, 5);
                        entity.setOnGround(movement.isOnGround());
                    }
                } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundRotateHeadPacket head) {
                    var entity = head.getEntity(level);
                    if (entity != null) entity.lerpHeadTo(head.getYHeadRot() * 360 / 256.0f, 5);
                } else {
                    // Existing entity handlers use their private level. Always restore the actual main listener.
                    var access = (WorldViewConnectionAccessor) connection;
                    var previous = access.kasuga$getLevel();
                    try { access.kasuga$setLevel(level); packet.handle(connection); }
                    finally { access.kasuga$setLevel(previous); }
                }
            } finally { buffer.release(); }
        }
        private void apply(ClientboundLevelChunkWithLightPacket packet) {
            var data = packet.getChunkData();
            var chunk = level.getChunkSource().replaceWithPacketData(packet.getX(), packet.getZ(), data.getReadBuffer(),
                    data.getHeightmaps(), data.getBlockEntitiesTagsConsumer(packet.getX(), packet.getZ()));
            if (chunk == null) return;
            loaded.add(chunk.getPos().toLong());
            var engine = level.getLightEngine();
            var lights = packet.getLightData();
            applyLayer(packet.getX(), packet.getZ(), LightLayer.SKY, lights.getSkyYMask(), lights.getEmptySkyYMask(), lights.getSkyUpdates());
            applyLayer(packet.getX(), packet.getZ(), LightLayer.BLOCK, lights.getBlockYMask(), lights.getEmptyBlockYMask(), lights.getBlockUpdates());
            engine.setLightEnabled(chunk.getPos(), true);
            for (int i = 0; i < chunk.getSections().length; i++) {
                int y = level.getSectionYFromSectionIndex(i);
                engine.updateSectionStatus(SectionPos.of(chunk.getPos(), y), chunk.getSections()[i].hasOnlyAir());
                level.setSectionDirtyWithNeighbors(packet.getX(), y, packet.getZ());
            }
            if (net.neoforged.fml.ModList.get().isLoaded("sodium"))
                CameraSodiumChunks.lightReady(level, packet.getX(), packet.getZ());
        }
        private void applyLayer(int x, int z, LightLayer layer, BitSet mask, BitSet empty, List<byte[]> updates) {
            var engine = level.getLightEngine(); var iterator = updates.iterator();
            for (int i = 0; i < engine.getLightSectionCount(); i++) if (mask.get(i) || empty.get(i)) {
                int y = engine.getMinLightSection() + i;
                engine.queueSectionData(layer, SectionPos.of(x, y, z), mask.get(i) ? new DataLayer(iterator.next().clone()) : new DataLayer());
                level.setSectionDirtyWithNeighbors(x, y, z);
            }
        }
        private void drop(long key) {
            ChunkPos pos = new ChunkPos(key); level.getChunkSource().drop(pos); loaded.remove(key);
            if (net.neoforged.fml.ModList.get().isLoaded("sodium")) CameraSodiumChunks.unloaded(level, pos.x, pos.z);
            var engine = level.getLightEngine(); engine.setLightEnabled(pos, false);
            for (int y = engine.getMinLightSection(); y < engine.getMaxLightSection(); y++) {
                engine.queueSectionData(LightLayer.BLOCK, SectionPos.of(pos, y), null);
                engine.queueSectionData(LightLayer.SKY, SectionPos.of(pos, y), null);
            }
        }
        @Override public void close() {
            if (closed) return;
            pause(); closed = true; SESSIONS.remove(id, this);
            for (long key : List.copyOf(loaded)) drop(key);
            NeoForge.EVENT_BUS.post(new LevelEvent.Unload(level));
        }
    }
    private static final class Assembly {
        final int x, z; final byte[][] parts; int received;
        Assembly(CameraChunkFragment first) { x = first.x(); z = first.z(); parts = new byte[first.count()][]; }
        byte[] accept(CameraChunkFragment fragment) {
            if (parts.length != fragment.count() || x != fragment.x() || z != fragment.z())
                throw new IllegalArgumentException("Inconsistent camera chunk fragments");
            if (parts[fragment.index()] != null) throw new IllegalArgumentException("Duplicate camera chunk fragment");
            parts[fragment.index()] = fragment.bytes();
            if (++received != parts.length) return null;
            int size = Arrays.stream(parts).mapToInt(p -> p.length).sum(), offset = 0;
            byte[] bytes = new byte[size];
            for (byte[] part : parts) { System.arraycopy(part, 0, bytes, offset, part.length); offset += part.length; }
            return bytes;
        }
    }
}
