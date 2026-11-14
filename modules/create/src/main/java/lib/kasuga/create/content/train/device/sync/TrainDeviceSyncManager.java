package lib.kasuga.create.content.train.device.sync;

import com.mojang.logging.LogUtils;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import lib.kasuga.create.content.train.device.TrainDeviceManager;
import lib.kasuga.create.content.train.device.TrainDeviceRegistry;
import lib.kasuga.create.content.train.device.TrainDeviceSystem;
import lib.kasuga.create.content.train.device.TrainDeviceSystemType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.*;

/**
 * 服务端设备状态同步：订阅扫描 + 脏标记 flush。
 *
 * <p>数据流：设备改完状态调 {@link TrainDeviceSystem#markSyncDirty()} → 这里记下「该列车的该设备脏了」→
 * 每个 level tick 只处理有订阅者的列车，按 (玩家, 设备) 与「上次已发快照」做字段级 diff，
 * 有变化才发包。没有任何设备声明同步、或没有玩家在附近时，整条路径不产生任何工作。</p>
 */
public final class TrainDeviceSyncManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 订阅扫描间隔（tick）。20 tick = 1 秒。 */
    public static final int SUBSCRIPTION_SCAN_INTERVAL = 20;

    /** 订阅距离：车厢所在位置 8 区块内。 */
    public static final int TRACKING_RANGE_CHUNKS = 8;

    private static final double TRACKING_RANGE = TRACKING_RANGE_CHUNKS * 16.0;

    private static final TrainDeviceSyncManager INSTANCE = new TrainDeviceSyncManager();

    public static TrainDeviceSyncManager get() {
        return INSTANCE;
    }

    /** 每列车的同步状态；只有出现过脏设备或订阅者才会创建。 */
    private static final class TrainSyncState {
        final Map<ServerPlayer, Map<ResourceLocation, CompoundTag>> lastSent = new HashMap<>();
        final Set<ResourceLocation> dirty = new HashSet<>();
        int sequence;
    }

    private final Map<UUID, TrainSyncState> states = new HashMap<>();
    private final Map<ResourceKey<Level>, Long> lastScan = new HashMap<>();

    private TrainDeviceSyncManager() {
    }

    // ------------------------------------------------------------------
    // 服务端入口
    // ------------------------------------------------------------------

    /** 设备状态变化：只记录，不在这里发包。 */
    public void markDirty(TrainDeviceManager manager, ResourceLocation typeId) {
        Train train = manager == null ? null : manager.getTrain();
        if (train == null || typeId == null) {
            return;
        }
        states.computeIfAbsent(train.id, id -> new TrainSyncState()).dirty.add(typeId);
    }

    /** 每个 level tick 调一次（仅服务端）。 */
    public void tick(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        long gameTime = level.getGameTime();
        Long previousScan = lastScan.get(level.dimension());
        if (previousScan == null || gameTime - previousScan >= SUBSCRIPTION_SCAN_INTERVAL) {
            lastScan.put(level.dimension(), gameTime);
            refreshSubscriptions(serverLevel);
        }
        flushDirty();
    }

    /** 列车拆解 / 注销：通知客户端清镜像并丢掉服务端状态。 */
    public void clearTrain(@Nullable Train train) {
        if (train != null) {
            clearTrain(train.id);
        }
    }

    /** 只有 id 时（例如 GlobalRailwayManager.removeTrain）的入口。 */
    public void clearTrain(@Nullable UUID trainId) {
        if (trainId == null) {
            return;
        }
        TrainSyncState state = states.remove(trainId);
        if (state == null) {
            return;
        }
        TrainDeviceSyncPayload payload = TrainDeviceSyncPayload.clear(trainId, ++state.sequence);
        for (ServerPlayer player : state.lastSent.keySet()) {
            if (!player.hasDisconnected()) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }

    /** 服务端卸载时的兜底清理。 */
    public void clearAll() {
        states.clear();
        lastScan.clear();
    }

    // ------------------------------------------------------------------
    // 订阅
    // ------------------------------------------------------------------

    private void refreshSubscriptions(ServerLevel level) {
        if (level.players().isEmpty()) {
            return;
        }
        for (Train train : Create.RAILWAYS.trains.values()) {
            TrainDeviceManager manager = TrainDeviceManager.getManager(train);
            if (manager == null || !manager.hasSyncSystems()) {
                continue;
            }
            Optional<BlockPos> position = train.getPositionInDimension(level.dimension());
            if (position.isEmpty()) {
                continue;
            }
            Vec3 center = Vec3.atCenterOf(position.get());
            TrainSyncState state = states.computeIfAbsent(train.id, id -> new TrainSyncState());
            for (ServerPlayer player : level.players()) {
                boolean tracking = isRiding(player, train)
                        || player.position().distanceToSqr(center) <= TRACKING_RANGE * TRACKING_RANGE;
                boolean subscribed = state.lastSent.containsKey(player);
                if (tracking && !subscribed) {
                    sendFull(player, train, manager, state);
                } else if (!tracking && subscribed) {
                    state.lastSent.remove(player);
                }
            }
        }
    }

    private static boolean isRiding(ServerPlayer player, Train train) {
        return player.getVehicle() instanceof CarriageContraptionEntity carriage
                && train.id.equals(carriage.trainId);
    }

    private void sendFull(ServerPlayer player, Train train, TrainDeviceManager manager, TrainSyncState state) {
        Map<ResourceLocation, CompoundTag> sent = new HashMap<>();
        for (Map.Entry<TrainDeviceSystemType<?>, TrainDeviceSystem> entry : manager.systemsView().entrySet()) {
            TrainDeviceSystem system = entry.getValue();
            ResourceLocation typeId = TrainDeviceRegistry.getKey(entry.getKey());
            if (typeId == null || !system.isSyncEnabled()) {
                continue;
            }
            CompoundTag snapshot = buildSnapshot(typeId, system);
            if (snapshot == null) {
                continue;
            }
            sent.put(typeId, snapshot);
            PacketDistributor.sendToPlayer(player,
                    TrainDeviceSyncPayload.full(train.id, ++state.sequence, typeId, snapshot));
        }
        state.lastSent.put(player, sent);
    }

    // ------------------------------------------------------------------
    // 脏标记 flush
    // ------------------------------------------------------------------

    private void flushDirty() {
        if (states.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, TrainSyncState> entry : states.entrySet()) {
            TrainSyncState state = entry.getValue();
            if (state.dirty.isEmpty() || state.lastSent.isEmpty()) {
                continue;
            }
            Train train = Create.RAILWAYS.trains.get(entry.getKey());
            if (train == null) {
                continue;
            }
            TrainDeviceManager manager = TrainDeviceManager.getManager(train);
            if (manager == null) {
                continue;
            }
            for (ResourceLocation typeId : state.dirty) {
                TrainDeviceSystemType<?> type = TrainDeviceRegistry.get(typeId);
                if (type == null) {
                    continue;
                }
                TrainDeviceSystem system = manager.getSystem(type);
                if (system == null || !system.isSyncEnabled()) {
                    continue;
                }
                CompoundTag snapshot = buildSnapshot(typeId, system);
                if (snapshot == null) {
                    continue;
                }
                sendDelta(train, state, typeId, snapshot);
            }
            state.dirty.clear();
        }
    }

    private void sendDelta(Train train, TrainSyncState state, ResourceLocation typeId, CompoundTag snapshot) {
        for (Map.Entry<ServerPlayer, Map<ResourceLocation, CompoundTag>> subscriber : state.lastSent.entrySet()) {
            ServerPlayer player = subscriber.getKey();
            if (player.hasDisconnected()) {
                continue;
            }
            Map<ResourceLocation, CompoundTag> sent = subscriber.getValue();
            CompoundTag previous = sent.get(typeId);
            if (previous == null) {
                sent.put(typeId, snapshot);
                PacketDistributor.sendToPlayer(player,
                        TrainDeviceSyncPayload.full(train.id, ++state.sequence, typeId, snapshot));
                continue;
            }
            TrainDeviceSyncDiff diff = TrainDeviceSyncDiff.between(snapshot, previous);
            if (diff.isEmpty()) {
                continue;
            }
            sent.put(typeId, snapshot);
            PacketDistributor.sendToPlayer(player, TrainDeviceSyncPayload.delta(
                    train.id, ++state.sequence, typeId, diff.changed(), diff.removedKeys()));
        }
    }

    @Nullable
    private CompoundTag buildSnapshot(ResourceLocation typeId, TrainDeviceSystem system) {
        CompoundTag snapshot = new CompoundTag();
        snapshot.putInt("SchemaVersion", system.schemaVersion());
        try {
            system.writeSyncTag(snapshot);
        } catch (Exception e) {
            LOGGER.warn("设备 {} 生成同步快照失败，本轮跳过", typeId, e);
            return null;
        }
        return snapshot;
    }

    /** 供诊断/单测：当前被跟踪的列车数。 */
    public int trackedTrainCount() {
        return states.size();
    }
}
