package lib.kasuga.create.content.train.device;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;

public class TrainDeviceSystemType<T extends TrainDeviceSystem> {

    private final Function<TrainDeviceManager, T> factory;
    private final TreeMap<Integer, Consumer<CompoundTag>> migrations = new TreeMap<>();
    private ResourceLocation id;

    public TrainDeviceSystemType(Function<TrainDeviceManager, T> factory) {
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    public static <T extends TrainDeviceSystem> TrainDeviceSystemType<T> of(
            Function<TrainDeviceManager, T> factory
    ) {
        return new TrainDeviceSystemType<>(factory);
    }

    /**
     * 带稳定 id 的写法。id 与 {@link TrainDeviceRegistry#register(ResourceLocation, TrainDeviceSystemType, boolean)}
     * 传入的一致即可（重复绑定到同一个 id 是幂等的）。
     */
    public static <T extends TrainDeviceSystem> TrainDeviceSystemType<T> of(
            ResourceLocation id,
            Function<TrainDeviceManager, T> factory
    ) {
        TrainDeviceSystemType<T> type = new TrainDeviceSystemType<>(factory);
        type.assignId(id);
        return type;
    }

    public T create(TrainDeviceManager trainDeviceManager) {
        T system = factory.apply(trainDeviceManager);
        if (system != null) {
            system.attachType(this);
        }
        return system;
    }

    /** 稳定 id；未注册且未显式指定时为 {@code null}。 */
    @Nullable
    public ResourceLocation id() {
        return id;
    }

    void assignId(@Nullable ResourceLocation id) {
        if (id == null) {
            return;
        }
        if (this.id != null && !this.id.equals(id)) {
            throw new IllegalStateException("TrainDeviceSystemType 已绑定到 " + this.id + "，不能再绑定到 " + id);
        }
        this.id = id;
    }

    /**
     * 登记一条迁移：从 {@code fromVersion} 版本的存档迁移到下一个版本。迁移按版本升序执行，
     * 旧档（版本 0）会依次跑完所有登记的迁移。
     */
    public TrainDeviceSystemType<T> addMigration(int fromVersion, Consumer<CompoundTag> migration) {
        Objects.requireNonNull(migration, "migration");
        migrations.put(fromVersion, migration);
        return this;
    }

    /** 对设备数据就地执行从 {@code version} 开始的所有迁移。 */
    public void migrate(CompoundTag data, int version) {
        if (migrations.isEmpty()) {
            return;
        }
        for (Map.Entry<Integer, Consumer<CompoundTag>> entry : migrations.tailMap(version, true).entrySet()) {
            entry.getValue().accept(data);
        }
    }
}
