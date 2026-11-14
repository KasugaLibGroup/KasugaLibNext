package lib.kasuga.create.content.train.device;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.*;

public class TrainDeviceRegistry {
    public static final HashMap<ResourceLocation, TrainDeviceSystemType<?>> REGISTRY = new HashMap<>();
    public static final HashMap<TrainDeviceSystemType<?>, ResourceLocation> REVERSE_REGISTRY = new HashMap<>();

    public static final Set<TrainDeviceSystemType<?>> CONTEXT = new HashSet<>();

    public static void register(ResourceLocation location, TrainDeviceSystemType<?> type) {
        register(location, type, false);
    }

    public static void register(ResourceLocation location, TrainDeviceSystemType<?> type, boolean contextDevice) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(type, "type");
        if (REGISTRY.containsKey(location)) {
            throw new IllegalArgumentException("Duplicate registration for " + location);
        }
        if (REVERSE_REGISTRY.containsKey(type)) {
            throw new IllegalArgumentException("Duplicate registration for " + type);
        }
        // 绑定稳定 id：类型对象自带 id 时，后续 write 不再依赖对象身份反查。
        type.assignId(location);
        REGISTRY.put(location, type);
        REVERSE_REGISTRY.put(type, location);
        if(contextDevice) {
            CONTEXT.add(type);
        }
    }

    public static Map<TrainDeviceSystemType<?>, TrainDeviceSystem> createInitialContextDevices(TrainDeviceManager manager) {
        Map<TrainDeviceSystemType<?>,TrainDeviceSystem> devices = new HashMap<>();
        for(TrainDeviceSystemType<?> type : CONTEXT) {
            devices.put(type, type.create(manager));
        }
        return devices;
    }

    @Nullable
    public static TrainDeviceSystemType<?> get(ResourceLocation id) {
        return REGISTRY.get(id);
    }

    /**
     * 反查类型的注册 id。对象身份反查失败时退回到类型自带的稳定 id，
     * 因此「每次 getter 里 new 一个 type」也不会再出现反查为空。
     */
    @Nullable
    public static ResourceLocation getKey(TrainDeviceSystemType<?> type) {
        if (type == null) {
            return null;
        }
        ResourceLocation id = REVERSE_REGISTRY.get(type);
        return id != null ? id : type.id();
    }
}
