package lib.kasuga.create.content.train.device;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.signal.TrackEdgePoint;
import lib.kasuga.structure.Pair;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.Optional;

public class TrainDeviceSystem {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 设备状态当前的存档 schema 版本。设备覆写 {@link #schemaVersion()} 表示自己升到了更高版本。 */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** 没有 {@code SchemaVersion} 键的旧档按 0 处理。 */
    public static final int LEGACY_SCHEMA_VERSION = 0;

    protected static final String TAG_SCHEMA_VERSION = "SchemaVersion";
    protected static final String TAG_DATA = "Data";

    protected final TrainDeviceManager manager;
    private boolean initialized = false;
    private TrainDeviceSystemType<?> type;

    public TrainDeviceSystem(TrainDeviceManager manager) {
        this.manager = manager;
    }

    /** 由 {@link TrainDeviceSystemType#create(TrainDeviceManager)} 回填，用于日志与迁移表查找。 */
    @Nullable
    public TrainDeviceSystemType<?> getType() {
        return type;
    }

    void attachType(@Nullable TrainDeviceSystemType<?> type) {
        if (this.type != null && this.type != type) {
            throw new IllegalStateException("TrainDeviceSystem 已被绑定到 " + this.type + "，不能重新绑定到 " + type);
        }
        this.type = type;
    }

    /**
     * 当前设备状态的 schema 版本。字段改名/增删时提升它，并在
     * {@link TrainDeviceSystemType#addMigration(int, java.util.function.Consumer)} 里登记迁移。
     */
    public int schemaVersion() {
        return CURRENT_SCHEMA_VERSION;
    }

    /**
     * 写档。默认把设备字段放进 {@code Data} 子 tag，并写入 {@code SchemaVersion}，因此即使设备什么都不存，
     * 存档里也会留下版本号，为将来的迁移留下依据。
     */
    public void write(CompoundTag systemTag) {
        systemTag.putInt(TAG_SCHEMA_VERSION, schemaVersion());
        CompoundTag data = new CompoundTag();
        writeData(data);
        systemTag.put(TAG_DATA, data);
    }

    /**
     * 读档。缺 {@code Data} 键时把整个 tag 当成设备数据（旧档字段平铺在根上），版本按 0 处理，
     * 因此升级前的存档不需要额外迁移脚本就能读。
     */
    public void read(CompoundTag systemTag) {
        int version = systemTag.contains(TAG_SCHEMA_VERSION) ? systemTag.getInt(TAG_SCHEMA_VERSION) : LEGACY_SCHEMA_VERSION;
        CompoundTag data = systemTag.contains(TAG_DATA, Tag.TAG_COMPOUND) ? systemTag.getCompound(TAG_DATA) : systemTag;
        if (version > schemaVersion()) {
            LOGGER.warn("设备 {} 的存档 schema 版本 {} 高于当前实现 {}，按当前版本尽力读取",
                    describeType(), version, schemaVersion());
        }
        if (type != null) {
            type.migrate(data, version);
        }
        readData(data, version);
    }

    /** 设备状态的写入覆盖点。{@code data} 是 {@code Data} 子 tag（旧档迁移后同样指向设备数据）。 */
    protected void writeData(CompoundTag data) {
    }

    /** 设备状态的读取覆盖点。{@code version} 是写档时的 schema 版本（旧档为 0）。 */
    protected void readData(CompoundTag data, int version) {
    }

    public void tick(Level level) {
        if (!initialized && !level.isClientSide) {
            initializeServer(level);
            initialized = true;
        }
    }

    public Optional<Double> beforeSpeed() {
        return Optional.empty();
    }

    public void notifySpeed(double speed) {}

    public boolean notifySignalBack(Double distance, Pair<TrackEdgePoint, Couple<TrackNode>> pair) {
        return false;
    }

    public boolean notifySignalFront(Double distance, Pair<TrackEdgePoint, Couple<TrackNode>> pair) {
        return false;
    }

    public boolean cancelSlowdown() {
        return false;
    }

    public void notifyDistance(double distance) {}

    public void initializeServer(Level level) {}

    public void notifyDisassemble(Direction assemblyDirection, BlockPos pos) {}

    public TrainDeviceManager getManager() {
        return manager;
    }

    public void notifySignalCollection() {}

    public void earlyTick(Level level) {}

    public void onSegmentUpdated(ResourceLocation segmentType) {}

    private String describeType() {
        TrainDeviceSystemType<?> current = type;
        if (current == null) {
            return getClass().getName();
        }
        ResourceLocation id = current.id();
        return id == null ? getClass().getName() : id.toString();
    }
}
