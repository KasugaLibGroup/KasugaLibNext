package lib.kasuga.create.content.train.device;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.signal.TrackEdgePoint;
import lib.kasuga.create.content.train.device.sync.TrainDeviceSyncManager;
import lib.kasuga.structure.Pair;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.*;

public class TrainDeviceManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 同一设备同一回调的异常日志间隔：窗口内的重复异常只记 debug，避免每 tick 刷屏。 */
    public static final long FAILURE_LOG_INTERVAL_MILLIS = 60_000L;

    public static final String TAG_SYSTEMS = "TrainDeviceSystems";
    public static final String TAG_SYSTEM_TYPE = "Type";
    public static final String TAG_FORMAT = "TrainDeviceSystemsFormat";
    public static final int CURRENT_FORMAT = 1;

    private final Train train;

    public TrainDeviceManager(Train train) {
        this.train = train;
        this.systems.putAll(TrainDeviceRegistry.createInitialContextDevices(this));
    }

    private boolean cachedCancelSlowdown = false;

    public static Pair<TrainDeviceManager, TrainDeviceLocation> getManager(MovementContext context) {
        if(context.contraption == null || !(context.contraption.entity instanceof CarriageContraptionEntity entity))
            return null;
        return Pair.of(
                getManager(entity.getCarriage().train),
                new TrainDeviceLocation(
                        entity.trainId,
                        entity.carriageIndex,
                        context.localPos
                ));
    }

    public static Pair<TrainDeviceManager, TrainDeviceLocation> getManager(AbstractContraptionEntity contraption, BlockPos position) {
        if(!(contraption instanceof CarriageContraptionEntity))
            return null;
        CarriageContraptionEntity entity = ((CarriageContraptionEntity)contraption);
        return Pair.of(
                getManager(entity.getCarriage().train),
                new TrainDeviceLocation(
                        entity.trainId,
                        entity.carriageIndex,
                        position
                ));
    }

    public static TrainDeviceManager getManager(Train train) {
        return ((TrainDeviceProvider) train).kasugaLib$getDeviceManager();
    }

    /**
     * 列车级设备容器。用 {@link LinkedHashMap} 保证遍历顺序稳定（等于注册/读档顺序），
     * 便于复现问题，也让存档写出的条目顺序可预期。
     */
    protected final HashMap<TrainDeviceSystemType<?>, TrainDeviceSystem> systems = new LinkedHashMap<>();

    /** 认不出类型、或类型当前未注册的存档条目，原样保留并在下次写档时回写。 */
    private final List<CompoundTag> foreignSystems = new ArrayList<>();

    /** 异常日志限流器：同一设备同一回调的重复异常不会刷屏。 */
    private final DeviceFailureLog failureLog = new DeviceFailureLog(FAILURE_LOG_INTERVAL_MILLIS);

    // ------------------------------------------------------------------
    // 持久化
    // ------------------------------------------------------------------

    /**
     * 读档。不再无条件清空：构造期创建的上下文设备实例会被复用，存档里缺的条目按
     * {@code contextDevice} 补建，认不出类型的条目原样保留，单条读失败只影响那一条。
     */
    public void read(CompoundTag tag) {
        Map<TrainDeviceSystemType<?>, TrainDeviceSystem> existing = new LinkedHashMap<>(this.systems);
        List<CompoundTag> foreign = new ArrayList<>();
        this.systems.clear();
        this.failureLog.clear();

        if (!tag.contains(TAG_SYSTEMS)) {
            // 升级前的存档，或列车 NBT 被其它模组重建过：没有设备数据，全部按上下文设备重建。
            for (TrainDeviceSystemType<?> type : TrainDeviceRegistry.CONTEXT) {
                this.systems.put(type, existing.getOrDefault(type, type.create(this)));
            }
            LOGGER.info("列车 {} 的存档中没有设备数据，已重建 {} 个上下文设备", describeTrain(), this.systems.size());
            updateCache();
            return;
        }

        ListTag systemList = tag.getList(TAG_SYSTEMS, Tag.TAG_COMPOUND);
        for (int i = 0; i < systemList.size(); i++) {
            CompoundTag systemTag = systemList.getCompound(i);
            String typeString = systemTag.getString(TAG_SYSTEM_TYPE);
            ResourceLocation typeId = ResourceLocation.tryParse(typeString);
            if (typeId == null) {
                LOGGER.warn("列车 {} 的设备条目缺少合法 Type（{}），原始 NBT 已保留", describeTrain(), typeString);
                foreign.add(systemTag.copy());
                continue;
            }
            TrainDeviceSystemType<?> type = TrainDeviceRegistry.get(typeId);
            if (type == null) {
                LOGGER.warn("列车 {} 引用了未知或未注册的设备类型 {}，原始 NBT 已保留", describeTrain(), typeId);
                foreign.add(systemTag.copy());
                continue;
            }
            TrainDeviceSystem system = existing.get(type);
            if (system == null) {
                system = type.create(this);
            }
            try {
                system.read(systemTag);
            } catch (Exception e) {
                LOGGER.warn("设备 {} 在列车 {} 上读档失败，已重置为默认状态", typeId, describeTrain(), e);
                system = type.create(this);
            }
            this.systems.put(type, system);
        }

        // 存档里缺的上下文设备自动补建（老版本 read() 会把它们清掉）。
        for (TrainDeviceSystemType<?> type : TrainDeviceRegistry.CONTEXT) {
            if (this.systems.containsKey(type)) {
                continue;
            }
            TrainDeviceSystem system = existing.get(type);
            this.systems.put(type, system != null ? system : type.create(this));
            LOGGER.info("列车 {} 的存档缺少上下文设备 {}，已补建", describeTrain(), TrainDeviceRegistry.getKey(type));
        }

        this.foreignSystems.clear();
        this.foreignSystems.addAll(foreign);
        updateCache();
    }

    /**
     * 写档。反查不到注册 id 的类型会被跳过并记 error（绝不 NPE），写失败的单条设备同样跳过，
     * 无法识别的旧条目原样回写。
     */
    public void write(CompoundTag tag) {
        tag.putInt(TAG_FORMAT, CURRENT_FORMAT);
        ListTag systemList = new ListTag();
        for (Map.Entry<TrainDeviceSystemType<?>, TrainDeviceSystem> entry : this.systems.entrySet()) {
            ResourceLocation id = TrainDeviceRegistry.getKey(entry.getKey());
            if (id == null) {
                LOGGER.error("列车 {} 上的设备类型 {} 未注册，已跳过该条的写档",
                        describeTrain(), entry.getValue().getClass().getName());
                continue;
            }
            CompoundTag systemTag = new CompoundTag();
            systemTag.putString(TAG_SYSTEM_TYPE, id.toString());
            try {
                entry.getValue().write(systemTag);
            } catch (Exception e) {
                LOGGER.error("设备 {} 在列车 {} 上写档失败，已跳过该条", id, describeTrain(), e);
                continue;
            }
            systemList.add(systemTag);
        }
        for (CompoundTag foreign : this.foreignSystems) {
            systemList.add(foreign.copy());
        }
        tag.put(TAG_SYSTEMS, systemList);
    }

    // ------------------------------------------------------------------
    // tick / 信号等回调：全部经 safeInvoke，异常不外抛
    // ------------------------------------------------------------------

    public void tick(Level level) {
        for (Map.Entry<TrainDeviceSystemType<?>, TrainDeviceSystem> entry : this.systems.entrySet()) {
            TrainDeviceSystem system = entry.getValue();
            safeInvoke(entry.getKey(), "tick", () -> system.tick(level));
        }
    }

    public Optional<Double> beforeSpeed() {
        for (Map.Entry<TrainDeviceSystemType<?>, TrainDeviceSystem> entry : this.systems.entrySet()) {
            TrainDeviceSystem system = entry.getValue();
            Optional<Double> newSpeed = safeInvoke(entry.getKey(), "beforeSpeed", system::beforeSpeed, Optional.empty());
            if(newSpeed.isPresent())
                return newSpeed;
        }
        return Optional.empty();
    }

    public void notifySpeed(double speed) {
        for (Map.Entry<TrainDeviceSystemType<?>, TrainDeviceSystem> entry : this.systems.entrySet()) {
            TrainDeviceSystem system = entry.getValue();
            safeInvoke(entry.getKey(), "notifySpeed", () -> system.notifySpeed(speed));
        }
    }

    public boolean notifySignalFront(Double distance, Pair<TrackEdgePoint, Couple<TrackNode>> pair) {
        for (Map.Entry<TrainDeviceSystemType<?>, TrainDeviceSystem> entry : this.systems.entrySet()) {
            TrainDeviceSystem system = entry.getValue();
            // 抛异常时视为「不拦截」，交回 Create 原本的信号 listener。
            if(safeInvoke(entry.getKey(), "notifySignalFront", () -> system.notifySignalFront(distance, pair), false)){
                return true;
            }
        }
        return false;
    }

    public boolean notifySignalBack(Double distance, Pair<TrackEdgePoint, Couple<TrackNode>> pair) {
        for (Map.Entry<TrainDeviceSystemType<?>, TrainDeviceSystem> entry : this.systems.entrySet()) {
            TrainDeviceSystem system = entry.getValue();
            if(safeInvoke(entry.getKey(), "notifySignalBack", () -> system.notifySignalBack(distance, pair), false)){
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 设备查询 / 创建
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    @Nullable
    public <T extends TrainDeviceSystem> T getSystem(TrainDeviceSystemType<T> type) {
        if(type == null) {
            return null;
        }
        TrainDeviceSystem system = this.systems.get(type);
        return system == null ? null : (T) system;
    }

    /**
     * 取或创建设备。<b>未注册的类型直接抛异常</b>（fail fast），把故障暴露在调用点的栈上，
     * 而不是等到存档写入时才 NPE。
     */
    @SuppressWarnings("unchecked")
    public <T extends TrainDeviceSystem> T getOrCreateSystem(TrainDeviceSystemType<T> type) {
        Objects.requireNonNull(type, "type");
        TrainDeviceSystem existing = this.systems.get(type);
        if(existing != null) {
            return (T) existing;
        }
        ResourceLocation id = TrainDeviceRegistry.getKey(type);
        if(id == null) {
            throw new IllegalStateException("设备类型未注册：" + type + "（列车 " + describeTrain()
                    + "），调用点：" + describeCaller());
        }
        T system = type.create(this);
        this.systems.put(type, system);
        if(system.isSyncEnabled()) {
            // 玩家可能已经订阅了这列车：新设备要补一次 FULL。
            markSyncDirty(system);
        }
        updateCache();
        return system;
    }

    public boolean cancelSlowdown(){
        return cachedCancelSlowdown;
    }

    public void updateCache(){
        cachedCancelSlowdown = false;
        for (Map.Entry<TrainDeviceSystemType<?>, TrainDeviceSystem> entry : this.systems.entrySet()) {
            TrainDeviceSystem system = entry.getValue();
            cachedCancelSlowdown |= safeInvoke(entry.getKey(), "cancelSlowdown", system::cancelSlowdown, false);
        }
    }

    public void notifyDistance(double distance) {
        for (Map.Entry<TrainDeviceSystemType<?>, TrainDeviceSystem> entry : this.systems.entrySet()) {
            TrainDeviceSystem system = entry.getValue();
            safeInvoke(entry.getKey(), "notifyDistance", () -> system.notifyDistance(distance));
        }
    }

    public Train getTrain() {
        return train;
    }

    public void notifyDisassemble(Direction assemblyDirection, BlockPos pos) {
        for (Map.Entry<TrainDeviceSystemType<?>, TrainDeviceSystem> entry : this.systems.entrySet()) {
            TrainDeviceSystem system = entry.getValue();
            safeInvoke(entry.getKey(), "notifyDisassemble", () -> system.notifyDisassemble(assemblyDirection, pos));
        }
    }

    public void notifySignalCollection() {
        for (Map.Entry<TrainDeviceSystemType<?>, TrainDeviceSystem> entry : this.systems.entrySet()) {
            TrainDeviceSystem system = entry.getValue();
            safeInvoke(entry.getKey(), "notifySignalCollection", system::notifySignalCollection);
        }
    }

    public void earlyTick(Level level) {
        for (Map.Entry<TrainDeviceSystemType<?>, TrainDeviceSystem> entry : this.systems.entrySet()) {
            TrainDeviceSystem system = entry.getValue();
            safeInvoke(entry.getKey(), "earlyTick", () -> system.earlyTick(level));
        }
    }

    public void onSegmentUpdated(ResourceLocation segmentType) {
        for (Map.Entry<TrainDeviceSystemType<?>, TrainDeviceSystem> entry : this.systems.entrySet()) {
            TrainDeviceSystem system = entry.getValue();
            safeInvoke(entry.getKey(), "onSegmentUpdated", () -> system.onSegmentUpdated(segmentType));
        }
    }

    // ------------------------------------------------------------------
    // 状态同步（I-02）
    // ------------------------------------------------------------------

    /** 设备调用 {@link TrainDeviceSystem#markSyncDirty()} 后落到这里：只置脏，不发包。 */
    public void markSyncDirty(TrainDeviceSystem system) {
        if (system == null || !system.isSyncEnabled()) {
            return;
        }
        TrainDeviceSystemType<?> type = system.getType();
        if (type == null) {
            return;
        }
        TrainDeviceSyncManager.get().markDirty(this, TrainDeviceRegistry.getKey(type));
    }

    /** 是否有任何设备声明了同步；没有时同步通道整个跳过（零开销）。 */
    public boolean hasSyncSystems() {
        for (TrainDeviceSystem system : this.systems.values()) {
            if (system.isSyncEnabled()) {
                return true;
            }
        }
        return false;
    }

    /** 只读视图，供同步管理器按注册 id 序列化。 */
    public Map<TrainDeviceSystemType<?>, TrainDeviceSystem> systemsView() {
        return Collections.unmodifiableMap(this.systems);
    }

    // ------------------------------------------------------------------
    // 异常隔离
    // ------------------------------------------------------------------

    private void safeInvoke(TrainDeviceSystemType<?> type, String callback, Runnable body) {
        try {
            body.run();
        } catch (Exception e) {
            logFailure(type, callback, e);
        }
    }

    private <T> T safeInvoke(TrainDeviceSystemType<?> type, String callback, java.util.function.Supplier<T> body, T fallback) {
        try {
            return body.get();
        } catch (Exception e) {
            logFailure(type, callback, e);
            return fallback;
        }
    }

    /**
     * 记录设备回调异常：首次带完整栈；同一设备同一回调在 {@link #FAILURE_LOG_INTERVAL_MILLIS}
     * 窗口内只记 debug，窗口外的下一条再记 warn。设备本身不计数、不停用。
     */
    private void logFailure(@Nullable TrainDeviceSystemType<?> type, String callback, Exception error) {
        String typeId = describeTypeId(type);
        if (!failureLog.shouldLog(typeId + '#' + callback, System.currentTimeMillis())) {
            LOGGER.debug("设备 {} 的回调 {} 在列车 {} 上再次抛出异常（{} 秒内已记录过）：{}",
                    typeId, callback, describeTrain(), FAILURE_LOG_INTERVAL_MILLIS / 1000, error.toString());
            return;
        }
        LOGGER.warn("设备 {} 的回调 {} 在列车 {} 上抛出异常，已捕获并跳过本次调用：{}",
                typeId, callback, describeTrain(), error.toString(), error);
    }

    private static String describeTypeId(@Nullable TrainDeviceSystemType<?> type) {
        if (type == null) {
            return "<unknown>";
        }
        ResourceLocation id = TrainDeviceRegistry.getKey(type);
        return id == null ? type.getClass().getName() : id.toString();
    }

    private String describeTrain() {
        return train == null ? "<detached>" : String.valueOf(train.id);
    }

    private static String describeCaller() {
        return StackWalker.getInstance().walk(frames -> frames
                .skip(2)
                .findFirst()
                .map(frame -> frame.getClassName() + "#" + frame.getMethodName() + ":" + frame.getLineNumber())
                .orElse("<unknown>"));
    }
}
