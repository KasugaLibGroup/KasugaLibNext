package lib.kasuga.rendering.output.mc.stream;

import com.mojang.logging.LogUtils;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.phys.AABB;
import com.mojang.datafixers.util.Pair;
import java.util.concurrent.ConcurrentHashMap;

/** Camera tickets and snapshot production run on the server thread. Chunk generation remains asynchronous. */
public final class CameraChunkServer {
    private record Key(UUID player, UUID camera) {}
    private static final Map<Key, Subscription> SUBSCRIPTIONS = new LinkedHashMap<>();
    private static final Set<Dirty> DIRTY = ConcurrentHashMap.newKeySet();
    private record Dirty(ServerLevel level, long chunk) {}
    private static final TicketType<UUID> TICKET = TicketType.create("kasuga_camera", UUID::compareTo, 600);
    private CameraChunkServer() {}

    public static void dirty(ServerLevel level, ChunkPos chunk) { DIRTY.add(new Dirty(level, chunk.toLong())); }
    public static void request(ServerPlayer player, CameraChunkRequest request) {
        Key key = new Key(player.getUUID(), request.camera());
        Subscription previous = SUBSCRIPTIONS.get(key);
        if (previous != null && request.epoch() < previous.request.epoch()) return;
        if (!request.open() || !player.serverLevel().dimension().location().equals(request.dimension())) {
            if (previous != null) { SUBSCRIPTIONS.remove(key); previous.close(); }
            return;
        }
        if (previous != null && previous.request.equals(request)) { previous.lastRequest = player.server.getTickCount(); return; }
        if (previous != null) previous.close();
        SUBSCRIPTIONS.put(key, new Subscription(player, request));
    }
    public static void tick(MinecraftServer server) {
        Set<Dirty> changes = new HashSet<>(DIRTY); DIRTY.removeAll(changes);
        for (var entry : List.copyOf(SUBSCRIPTIONS.entrySet())) {
            Subscription s = entry.getValue();
            if (s.player.server != server) continue;
            if (s.player.hasDisconnected() || s.player.serverLevel() != s.level || server.getTickCount() - s.lastRequest > 400) {
                SUBSCRIPTIONS.remove(entry.getKey()); s.close(); continue;
            }
            for (Dirty change : changes) if (change.level == s.level && s.sent.contains(change.chunk)) s.pending.add(change.chunk);
            try { s.tick(server.getTickCount()); }
            catch (RuntimeException failure) {
                SUBSCRIPTIONS.remove(entry.getKey()); s.close();
                LogUtils.getLogger().error("Camera chunk subscription failed for {}", entry.getKey(), failure);
            }
        }
    }
    public static void removePlayer(UUID player) {
        for (var entry : List.copyOf(SUBSCRIPTIONS.entrySet())) if (entry.getKey().player.equals(player)) {
            SUBSCRIPTIONS.remove(entry.getKey()); entry.getValue().close();
        }
    }
    public static void stop(MinecraftServer server) {
        for (var entry : List.copyOf(SUBSCRIPTIONS.entrySet())) if (entry.getValue().player.server == server) {
            SUBSCRIPTIONS.remove(entry.getKey()); entry.getValue().close();
        }
        DIRTY.removeIf(d -> d.level.getServer() == server);
    }
    private static final class Subscription {
        final ServerPlayer player;
        final ServerLevel level;
        final CameraChunkRequest request;
        final ArrayDeque<Long> ticketQueue = new ArrayDeque<>();
        final Set<Long> tickets = new LinkedHashSet<>(), sent = new HashSet<>(), pending = new LinkedHashSet<>();
        int lastRequest;
        final net.minecraft.network.ProtocolInfo<ClientGamePacketListener> entityProtocol;
        long sequence;
        final Set<Integer> trackedEntities = new HashSet<>();
        Subscription(ServerPlayer player, CameraChunkRequest request) {
            this.player = player; this.level = player.serverLevel(); this.request = request;
            lastRequest = player.server.getTickCount();
            entityProtocol = GameProtocols.CLIENTBOUND_TEMPLATE.bind(b -> new RegistryFriendlyByteBuf(b, level.registryAccess()));
            // Near-to-far rings, including one light/meshing neighbour around the requested view.
            for (int r = 0; r <= request.radius() + 1; r++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++)
                if (Math.max(Math.abs(dx), Math.abs(dz)) == r) ticketQueue.add(ChunkPos.asLong(request.x() + dx, request.z() + dz));
        }
        void tick(int tick) {
            var source = level.getChunkSource();
            for (int n = 0; n < 8 && !ticketQueue.isEmpty(); n++) {
                long pos = ticketQueue.remove(); tickets.add(pos);
                source.addRegionTicket(TICKET, new ChunkPos(pos), 0, request.camera());
                pending.add(pos);
            }
            if (tick % 100 == 0) for (long pos : tickets)
                source.addRegionTicket(TICKET, new ChunkPos(pos), 0, request.camera());
            if (tick % 5 == 0) syncEntities();
            int budget = 4;
            for (long pos : List.copyOf(pending)) {
                if (budget == 0) break;
                ChunkPos chunkPos = new ChunkPos(pos);
                var chunk = source.getChunkNow(chunkPos.x, chunkPos.z);
                if (chunk == null || !chunk.isLightCorrect()) continue;
                var performance = lib.kasuga.rendering.output.profile.CameraChunkSnapshotEvent.start();
                var packet = new ClientboundLevelChunkWithLightPacket(chunk, source.getLightEngine(), null, null);
                var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
                try {
                    ClientboundLevelChunkWithLightPacket.STREAM_CODEC.encode(buffer, packet);
                    int length = buffer.readableBytes();
                    int count = (length + CameraChunkFragment.PART_SIZE - 1) / CameraChunkFragment.PART_SIZE;
                    if (count > CameraChunkFragment.MAX_PARTS) throw new IllegalStateException("Camera chunk exceeds protocol bound");
                    long serial = sequence++;
                    for (int index = 0; index < count; index++) {
                        byte[] bytes = new byte[Math.min(buffer.readableBytes(), CameraChunkFragment.PART_SIZE)];
                        buffer.readBytes(bytes);
                        PacketDistributor.sendToPlayer(player, new CameraChunkFragment(request.camera(), request.epoch(), serial,
                                chunkPos.x, chunkPos.z, index, count, bytes));
                    }
                } finally { if (performance != null) performance.finish(buffer.writerIndex()); buffer.release(); }
                pending.remove(pos); sent.add(pos); budget--;
            }
        }
        void syncEntities() {
            double minX = (request.x() - request.radius()) * 16.0, minZ = (request.z() - request.radius()) * 16.0;
            double maxX = (request.x() + request.radius() + 1) * 16.0, maxZ = (request.z() + request.radius() + 1) * 16.0;
            var visible = level.getEntities((Entity) null,
                    new AABB(minX, level.getMinBuildHeight(), minZ, maxX, level.getMaxBuildHeight(), maxZ), e -> !e.isRemoved());
            Set<Integer> present = new HashSet<>();
            for (Entity entity : visible) {
                present.add(entity.getId());
                if (trackedEntities.add(entity.getId())) {
                    // Never consume packDirty or run ServerEntity.sendChanges: main player tracking retains ownership of those.
                    var tracker = new ServerEntity(level, entity, 1, true, ignored -> {});
                    sendEntity(entity.getAddEntityPacket(tracker));
                }
                sendEntity(new ClientboundTeleportEntityPacket(entity));
                sendEntity(new ClientboundRotateHeadPacket(entity, (byte) net.minecraft.util.Mth.floor(entity.getYHeadRot() * 256 / 360)));
                var data = Arrays.stream(((lib.kasuga.mixins.modelling.CameraEntityDataAccessor) entity.getEntityData()).kasuga$items())
                        .<net.minecraft.network.syncher.SynchedEntityData.DataValue<?>>map(item -> item.value()).toList();
                if (data != null) sendEntity(new ClientboundSetEntityDataPacket(entity.getId(), data));
                if (entity instanceof LivingEntity living) {
                    List<Pair<EquipmentSlot, net.minecraft.world.item.ItemStack>> equipment = new ArrayList<>();
                    for (EquipmentSlot slot : EquipmentSlot.values()) equipment.add(Pair.of(slot, living.getItemBySlot(slot).copy()));
                    sendEntity(new ClientboundSetEquipmentPacket(entity.getId(), equipment));
                    sendEntity(new ClientboundUpdateAttributesPacket(entity.getId(), living.getAttributes().getSyncableAttributes()));
                }
                sendEntity(new ClientboundSetPassengersPacket(entity));
                if (entity instanceof net.minecraft.world.entity.Leashable leashable && leashable.isLeashed())
                    sendEntity(new ClientboundSetEntityLinkPacket(entity, leashable.getLeashHolder()));
            }
            int[] removed = trackedEntities.stream().filter(id -> !present.contains(id)).mapToInt(Integer::intValue).toArray();
            if (removed.length != 0) sendEntity(new ClientboundRemoveEntitiesPacket(removed));
            trackedEntities.retainAll(present);
        }
        void sendEntity(Packet<? super ClientGamePacketListener> packet) {
            if (!CameraEntityPackets.allowed(packet)) return;
            var buffer = Unpooled.buffer();
            try {
                entityProtocol.codec().encode(buffer, packet);
                if (buffer.readableBytes() > 700_000) throw new IllegalStateException("Camera entity exceeds protocol bound");
                byte[] bytes = new byte[buffer.readableBytes()]; buffer.readBytes(bytes);
                PacketDistributor.sendToPlayer(player, new CameraEntityPayload(request.camera(), request.epoch(), bytes));
            } finally { buffer.release(); }
        }
        void close() {
            for (long pos : tickets) level.getChunkSource().removeRegionTicket(TICKET, new ChunkPos(pos), 0, request.camera());
            tickets.clear(); pending.clear(); ticketQueue.clear(); sent.clear(); trackedEntities.clear();
        }
    }
}
