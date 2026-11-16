package lib.kasuga.create.content.train.device.sync;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.trains.entity.Train;
import lib.kasuga.create.content.train.device.TrainDeviceManager;
import lib.kasuga.create.content.train.device.TrainDeviceRegistry;
import lib.kasuga.create.content.train.device.TrainDeviceSystem;
import lib.kasuga.create.content.train.device.TrainDeviceSystemType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * 客户端每列车一份的设备状态镜像。
 *
 * <p>只保存原始同步 tag，不持有 Train 实例；查询走 {@link TrainDeviceState}。
 * 镜像按「每列车单调递增的 sequence」丢弃过期包，30 秒没有更新即视为失效并清理，
 * 因此断线重连、维度切换后不需要额外的清理逻辑。</p>
 */
public final class TrainDeviceSyncClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 超过这个时间没有更新的列车镜像会被丢弃（重连后由 FULL 自然覆盖）。 */
    public static final long STALE_MILLIS = 30_000L;

    private static final Map<UUID, TrainMirror> MIRROR = new HashMap<>();

    /**
     * 本地 Train 的解析入口，由 {@code TrainDeviceSyncNetwork} 在运行时装上（避免本类直接依赖
     * Create 的静态管理器，让镜像逻辑能脱离游戏运行时单测）。
     */
    private static Function<UUID, Train> trainResolver = id -> null;

    public static void installTrainResolver(Function<UUID, Train> resolver) {
        trainResolver = resolver == null ? id -> null : resolver;
    }

    private TrainDeviceSyncClient() {
    }

    private static final class TrainMirror {
        final Map<ResourceLocation, CompoundTag> systems = new HashMap<>();
        int sequence = Integer.MIN_VALUE;
        long updatedAt = System.currentTimeMillis();
    }

    /** 收到同步包（已切到客户端主线程）。 */
    public static void apply(TrainDeviceSyncPayload payload) {
        UUID trainId = payload.trainId();
        if (payload.op() == TrainDeviceSyncPayload.Op.CLEAR) {
            MIRROR.remove(trainId);
            return;
        }

        TrainMirror mirror = MIRROR.computeIfAbsent(trainId, id -> new TrainMirror());
        if (payload.sequence() <= mirror.sequence) {
            return;
        }
        mirror.sequence = payload.sequence();
        mirror.updatedAt = System.currentTimeMillis();

        ResourceLocation systemId = payload.systemId();
        if (systemId == null) {
            return;
        }
        if (payload.op() == TrainDeviceSyncPayload.Op.FULL) {
            mirror.systems.put(systemId, payload.changed().copy());
        } else {
            CompoundTag merged = mirror.systems.getOrDefault(systemId, new CompoundTag()).copy();
            for (String key : payload.removedKeys()) {
                merged.remove(key);
            }
            for (String key : payload.changed().getAllKeys()) {
                Tag value = payload.changed().get(key);
                if (value != null) {
                    merged.put(key, value.copy());
                }
            }
            mirror.systems.put(systemId, merged);
        }
        pushToLocalDevice(trainId, systemId, mirror.systems.get(systemId));
    }

    @Nullable
    public static CompoundTag getOrNull(UUID trainId, ResourceLocation systemId) {
        TrainMirror mirror = MIRROR.get(trainId);
        if (mirror == null) {
            return null;
        }
        if (System.currentTimeMillis() - mirror.updatedAt > STALE_MILLIS) {
            MIRROR.remove(trainId);
            return null;
        }
        CompoundTag tag = mirror.systems.get(systemId);
        return tag == null ? null : tag.copy();
    }

    public static boolean isTracking(UUID trainId) {
        return MIRROR.containsKey(trainId);
    }

    /** 清空全部镜像（客户端断开连接 / 换世界时调用）。 */
    public static void clearAll() {
        MIRROR.clear();
    }

    private static void pushToLocalDevice(UUID trainId, ResourceLocation systemId, @Nullable CompoundTag tag) {
        if (tag == null) {
            return;
        }
        Train train = trainResolver.apply(trainId);
        if (train == null) {
            return;
        }
        TrainDeviceManager manager = TrainDeviceManager.getManager(train);
        if (manager == null) {
            return;
        }
        TrainDeviceSystemType<?> type = TrainDeviceRegistry.get(systemId);
        if (type == null) {
            return;
        }
        TrainDeviceSystem system = manager.getSystem(type);
        if (system == null) {
            return;
        }
        try {
            int version = tag.getInt("SchemaVersion");
            system.readSyncTag(tag, version);
        } catch (Exception e) {
            LOGGER.debug("设备 {} 在客户端解析同步状态失败", systemId, e);
        }
    }
}
