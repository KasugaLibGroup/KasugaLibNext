package lib.kasuga.create.content.train.device.sync;

import com.simibubi.create.content.trains.entity.Train;
import lib.kasuga.create.content.train.device.TrainDeviceManager;
import lib.kasuga.create.content.train.device.TrainDeviceRegistry;
import lib.kasuga.create.content.train.device.TrainDeviceSystem;
import lib.kasuga.create.content.train.device.TrainDeviceSystemType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;

/**
 * 客户端只读查询入口：渲染 / UI 从这里取设备状态，不需要知道同步包的存在。
 *
 * <p>设备也可以覆写 {@link TrainDeviceSystem#readSyncTag(CompoundTag, int)} 把状态解析到自己的字段，
 * 然后直接读字段；本类只是给不愿意改设备的调用方一个通用出口。</p>
 */
public final class TrainDeviceState {

    private TrainDeviceState() {
    }

    public static Optional<CompoundTag> get(UUID trainId, ResourceLocation systemId) {
        return Optional.ofNullable(TrainDeviceSyncClient.getOrNull(trainId, systemId));
    }

    public static Optional<CompoundTag> get(Train train, TrainDeviceSystemType<?> type) {
        if (train == null || type == null) {
            return Optional.empty();
        }
        ResourceLocation id = TrainDeviceRegistry.getKey(type);
        if (id == null) {
            return Optional.empty();
        }
        return get(train.id, id);
    }

    public static boolean isTracking(Train train) {
        return train != null && TrainDeviceSyncClient.isTracking(train.id);
    }

    public static void clearAll() {
        TrainDeviceSyncClient.clearAll();
    }

    /** 便捷取值：读不到或类型不对时返回默认值。 */
    public static int getInt(UUID trainId, ResourceLocation systemId, String key, int fallback) {
        CompoundTag tag = TrainDeviceSyncClient.getOrNull(trainId, systemId);
        return tag == null || !tag.contains(key) ? fallback : tag.getInt(key);
    }

    public static boolean getBoolean(UUID trainId, ResourceLocation systemId, String key, boolean fallback) {
        CompoundTag tag = TrainDeviceSyncClient.getOrNull(trainId, systemId);
        return tag == null || !tag.contains(key) ? fallback : tag.getBoolean(key);
    }

    public static double getDouble(UUID trainId, ResourceLocation systemId, String key, double fallback) {
        CompoundTag tag = TrainDeviceSyncClient.getOrNull(trainId, systemId);
        return tag == null || !tag.contains(key) ? fallback : tag.getDouble(key);
    }

    @Nullable
    public static String getString(UUID trainId, ResourceLocation systemId, String key, @Nullable String fallback) {
        CompoundTag tag = TrainDeviceSyncClient.getOrNull(trainId, systemId);
        return tag == null || !tag.contains(key) ? fallback : tag.getString(key);
    }
}
