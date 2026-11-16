package lib.kasuga.create.content.train.device;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.signal.TrackEdgePoint;
import lib.kasuga.create.compat.createconsist.TrainAssemblies;
import lib.kasuga.create.compat.createconsist.TrainAssemblyChanged;
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
        this.baselineCarriageIds = TrainAssemblies.carriageIds(train);
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

    /**
     * 认不出类型、类型当前未注册、或读档失败的存档条目：<b>原样保留</b>，下次写档原字节回写。
     * 这类条目不会用默认实例、也不会用新数据覆盖。
     */
    private final List<CompoundTag> preservedSystems = new ArrayList<>();

    /** 读档失败（已原样保留）的类型：既不会补建默认实例，也不允许被新实例顶掉。 */
    private final Set<TrainDeviceSystemType<?>> unreadableTypes = new HashSet<>();

    /** 每个设备最近一次读档时的原始条目：写档失败时用它原样回写，绝不丢条目。 */
    private final Map<TrainDeviceSystemType<?>, CompoundTag> rawOnRead = new LinkedHashMap<>();

    /** 上一次已知的编组（车厢 UUID 序列），用于下一 tick 开头的重映射。 */
    private List<UUID> baselineCarriageIds = List.of();

    /** 编组变化信号（世界事件里只置位，动作推到下一 tick）。 */
    private boolean consistDirty = false;

    /** 异常日志限流器：同一设备同一回调的重复异常不会刷屏。 */
    private final DeviceFailureLog failureLog = new DeviceFailureLog(FAILURE_LOG_INTERVAL_MILLIS);

    // ------------------------------------------------------------------
    // 持久化
    // ------------------------------------------------------------------

    /**
     * 读档。构造期创建的上下文设备实例会被复用，存档里缺的条目按 {@code contextDevice} 补建。
     *
     * <p>数据保全（review 口径）：认不出类型 / 未注册 / 读档失败的条目一律<b>原样保留</b>，
     * 不用默认实例顶掉、不写回「能解析的那部分」。读档失败的条目在下次写档时原字节回写，
     * 因此坏条目里的原始数据不会被这次读档吞掉。</p>
     */
    public void read(CompoundTag tag) {
        Map<TrainDeviceSystemType<?>, TrainDeviceSystem> existing = new LinkedHashMap<>(this.systems);
        List<CompoundTag> preserved = new ArrayList<>();
        this.systems.clear();
        this.unreadableTypes.clear();
        this.rawOnRead.clear();
        this.failureLog.clear();

        if (!tag.contains(TAG_SYSTEMS)) {
            // 升级前的存档，或列车 NBT 被其它模组重建过：没有设备数据，全部按上下文设备重建。
            for (TrainDeviceSystemType<?> type : TrainDeviceRegistry.CONTEXT) {
                this.systems.put(type, existing.getOrDefault(type, type.create(this)));
            }
            LOGGER.info("列车 {} 的存档中没有设备数据，已重建 {} 个上下文设备", describeTrain(), this.systems.size());
            resetConsistBaseline();
            updateCache();
            return;
        }

        ListTag systemList = tag.getList(TAG_SYSTEMS, Tag.TAG_COMPOUND);
        for (int i = 0; i < systemList.size(); i++) {
            CompoundTag systemTag = systemList.getCompound(i);
            // 先留一份原始字节：迁移/读取可能就地改 tag，回写兜底用的必须是磁盘上的原样。
            CompoundTag raw = systemTag.copy();
            String typeString = systemTag.getString(TAG_SYSTEM_TYPE);
            ResourceLocation typeId = ResourceLocation.tryParse(typeString);
            if (typeId == null) {
                LOGGER.error("列车 {} 的设备条目缺少合法 Type（{}），原始 NBT 已原样保留", describeTrain(), typeString);
                preserved.add(raw);
                continue;
            }
            TrainDeviceSystemType<?> type = TrainDeviceRegistry.get(typeId);
            if (type == null) {
                LOGGER.warn("列车 {} 引用了未知或未注册的设备类型 {}，原始 NBT 已原样保留", describeTrain(), typeId);
                preserved.add(raw);
                continue;
            }
            TrainDeviceSystem system = existing.get(type);
            if (system == null) {
                system = type.create(this);
            }
            try {
                system.read(systemTag);
            } catch (Exception e) {
                // 不重置为默认实例（那等于把这条原始数据删掉），原样保留并跳过补建。
                LOGGER.error("设备 {} 在列车 {} 上读档失败，该条目已原样保留（不补默认值、不覆盖）",
                        typeId, describeTrain(), e);
                preserved.add(raw);
                this.unreadableTypes.add(type);
                this.rawOnRead.put(type, raw);
                continue;
            }
            this.systems.put(type, system);
            this.rawOnRead.put(type, raw);
        }

        // 存档里缺的上下文设备自动补建（老版本 read() 会把它们清掉）；读档失败的类型不补建。
        for (TrainDeviceSystemType<?> type : TrainDeviceRegistry.CONTEXT) {
            if (this.systems.containsKey(type) || this.unreadableTypes.contains(type)) {
                continue;
            }
            TrainDeviceSystem system = existing.get(type);
            this.systems.put(type, system != null ? system : type.create(this));
            LOGGER.info("列车 {} 的存档缺少上下文设备 {}，已补建", describeTrain(), TrainDeviceRegistry.getKey(type));
        }

        this.preservedSystems.clear();
        this.preservedSystems.addAll(preserved);
        resetConsistBaseline();
        updateCache();
    }

    /**
     * 写档。<b>绝不静默丢条目</b>：
     * <ul>
     *     <li>读不懂 / 未注册的历史条目原样回写；</li>
     *     <li>单条设备写失败时回写它读档时的原始 NBT（磁盘数据不丢，内存里的改动不落盘并记 error）；</li>
     *     <li>既没有注册 id 又没有原始 NBT 可回退（即写下去必然丢数据）时直接抛异常，不静默跳过。</li>
     * </ul>
     */
    public void write(CompoundTag tag) {
        tag.putInt(TAG_FORMAT, CURRENT_FORMAT);
        ListTag systemList = new ListTag();
        for (Map.Entry<TrainDeviceSystemType<?>, TrainDeviceSystem> entry : this.systems.entrySet()) {
            TrainDeviceSystemType<?> type = entry.getKey();
            ResourceLocation id = TrainDeviceRegistry.getKey(type);
            if (id == null) {
                throw new IllegalStateException("列车 " + describeTrain() + " 上的设备类型 "
                        + entry.getValue().getClass().getName()
                        + " 没有注册 id，写档必定丢数据，已中止（原始 NBT 未被覆盖）");
            }
            CompoundTag systemTag = new CompoundTag();
            systemTag.putString(TAG_SYSTEM_TYPE, id.toString());
            try {
                entry.getValue().write(systemTag);
            } catch (Exception e) {
                CompoundTag raw = this.rawOnRead.get(type);
                if (raw == null) {
                    throw new IllegalStateException("设备 " + id + " 在列车 " + describeTrain()
                            + " 上写档失败，且没有读档时的原始 NBT 可回退，已中止写档（不覆盖磁盘数据）", e);
                }
                LOGGER.error("设备 {} 在列车 {} 上写档失败，已回写读档时的原始 NBT（本次改动未落盘）",
                        id, describeTrain(), e);
                systemList.add(raw.copy());
                continue;
            }
            if (this.unreadableTypes.contains(type)) {
                LOGGER.error("设备 {} 在列车 {} 上同时存在读档失败的原条目与新实例，两份都会保留",
                        id, describeTrain());
            }
            systemList.add(systemTag);
        }
        for (CompoundTag preserved : this.preservedSystems) {
            systemList.add(preserved.copy());
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
        if(this.unreadableTypes.contains(type)) {
            // 该类型在存档里有一条读不懂的原始条目还等着回写：绝不能用新实例顶掉它。
            throw new IllegalStateException("设备类型 " + id + "（列车 " + describeTrain()
                    + "）的存档条目读档失败、已原样保留，拒绝用新实例覆盖；原始数据会原字节回写");
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
        // 编组重映射只在下一次 tick 开头生效，绝不在 setTrain / read 等世界事件里改状态。
        if (level != null && !level.isClientSide) {
            remapConsistIfNeeded();
        }
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
    // 编组变化（I-05）
    // ------------------------------------------------------------------

    /** 世界事件（setTrain / addTrain / removeTrain / relocate）里只置位，不动状态。 */
    public void markConsistDirty() {
        this.consistDirty = true;
    }

    /** 上一轮已知的编组，供诊断与单测。 */
    public List<UUID> getBaselineCarriageIds() {
        return List.copyOf(baselineCarriageIds);
    }

    /** 编组是否与基线一致；不一致时的重映射动作推到下一 tick 开头。 */
    public boolean isConsistDirty() {
        return consistDirty;
    }

    /**
     * 编组重映射：幂等。序列与基线一致时什么都不做，因此重复读档、多维度 tick 都不会重复触发。
     *
     * <p>身份不完整（缺 {@code KasugaCarriageId}）或身份重复时不猜、不合成 UUID：直接放弃这一轮
     * 重映射，设备状态原样保留（既不清空、也不按错误映射重排），只记一条 error。</p>
     */
    public void remapConsistIfNeeded() {
        if (train == null) {
            return;
        }
        List<UUID> current = TrainAssemblies.carriageIds(train);
        if (!consistDirty && current.equals(baselineCarriageIds)) {
            return;
        }
        String problem = describeIdentityProblem(baselineCarriageIds, current);
        if (problem != null) {
            // 记一次基线，避免同一状况每 tick 反复打印；设备状态一处都不动。
            baselineCarriageIds = List.copyOf(current);
            consistDirty = false;
            if (failureLog.shouldLog("consist#identity", System.currentTimeMillis())) {
                LOGGER.error("列车 {} 的编组身份不可用（{}），已跳过本轮重映射：不合成 UUID、不清空设备状态",
                        describeTrain(), problem);
            } else {
                LOGGER.debug("列车 {} 的编组身份仍不可用（{}），继续跳过重映射", describeTrain(), problem);
            }
            return;
        }
        TrainAssemblyChanged change = TrainAssemblyChanged.of(train.id, baselineCarriageIds, current);
        baselineCarriageIds = List.copyOf(current);
        consistDirty = false;
        if (change.isNoop()) {
            return;
        }
        LOGGER.debug("列车 {} 编组变化：{}", describeTrain(), change);
        for (Map.Entry<TrainDeviceSystemType<?>, TrainDeviceSystem> entry : this.systems.entrySet()) {
            TrainDeviceSystem system = entry.getValue();
            safeInvoke(entry.getKey(), "onConsistChanged", () -> system.onConsistChanged(change));
        }
    }

    /**
     * @return 身份不可用的原因（缺失 / 重复）；{@code null} 表示两份序列都完整且唯一
     */
    @Nullable
    private static String describeIdentityProblem(List<UUID> before, List<UUID> after) {
        String problem = describeIdentityProblem("之前", before);
        return problem != null ? problem : describeIdentityProblem("当前", after);
    }

    @Nullable
    private static String describeIdentityProblem(String side, List<UUID> ids) {
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < ids.size(); i++) {
            UUID id = ids.get(i);
            if (id == null) {
                return side + "编组第 " + i + " 节车厢没有 KasugaCarriageId";
            }
            if (!seen.add(id)) {
                return side + "编组第 " + i + " 节车厢的 id 与前面重复（" + id + "）";
            }
        }
        return null;
    }

    private void resetConsistBaseline() {
        this.baselineCarriageIds = TrainAssemblies.carriageIds(train);
        this.consistDirty = false;
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
