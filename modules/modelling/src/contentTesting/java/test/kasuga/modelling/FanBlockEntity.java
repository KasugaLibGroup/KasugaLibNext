package test.kasuga.modelling;

import lib.kasuga.rendering.models.mc.proxies.ElementProxy;
import lib.kasuga.rendering.models.mc.proxies.Proxies;
import lib.kasuga.rendering.models.mc.proxies.ProxyConfig;
import lib.kasuga.rendering.models.mc.proxies.ProxyHost;
import lib.kasuga.rendering.models.mc.proxies.ProxyInstance;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Layer;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import lib.kasuga.rendering.models.uml.dynamic.fsm.VarProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * The fan example block entity: a {@link ProxyHost} with no animation code of its own.
 *
 * <p>Everything the model system needs is declared by {@link Template}: the machine, the model and the
 * render projection are handed to the proxy layer, which owns the per-host lifecycle (lazy build, client
 * model binding, tick, sync, release). The block entity keeps only what is genuinely block-entity work —
 * persistence and the two state-transfer directions.
 *
 * <p>Two construction paths share this class:
 * <ul>
 *     <li>{@link #FanBlockEntity(BlockEntityType, BlockPos, BlockState)} — code-registered fixture, binds
 *     {@link FanFixtures#MACHINE_ID} / {@link FanFixtures#MODEL};</li>
 *     <li>{@link #FanBlockEntity(BlockEntityType, BlockPos, BlockState, Id, ResourceLocation)} — the
 *     data-driven fixture, with ids read from the content file's {@code params}.</li>
 * </ul>
 *
 * <p>Persistence: only the gear index (byte 0..3) is stored. Runtime state is not serialized; on load the
 * machine rebuilds lazily and the first {@code pull} restores the layer. Note that a chunk can be saved
 * before this entity's first tick, so {@link #saveAdditional} falls back to the loaded/pending value
 * instead of assuming a live machine.
 */
public class FanBlockEntity extends BlockEntity implements ProxyHost {

    private final ProxyInstance<FanBlockEntity> proxy = Proxies.install(this, new Template());

    @Nullable
    private final Id machineId;
    @Nullable
    private final ResourceLocation model;

    /** Persisted gear waiting for the machine to exist ({@code -1} = nothing pending). */
    private int pendingGear = -1;
    /** Last gear seen while a machine was live — lets saving survive {@code release}. */
    private int lastKnownGear = -1;

    public FanBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState blockState) {
        this(type, pos, blockState, FanFixtures.MACHINE_ID, FanFixtures.MODEL);
    }

    public FanBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState blockState,
                          @Nullable Id machineId, @Nullable ResourceLocation model) {
        super(type, pos, blockState);
        this.machineId = machineId;
        this.model = model;
    }

    /** Machine definition this entity binds; {@code null} disables the machine. */
    public @Nullable Id machineId() {
        return machineId;
    }

    /** Model this entity binds; {@code null} disables rendering. */
    public @Nullable ResourceLocation fanModel() {
        return model;
    }

    @Override
    public ProxyInstance<?> proxy() {
        return proxy;
    }

    /** Forwarded by the block's ticker; a no-op before the first tick. */
    public void tickProxy(Level level, BlockPos pos) {
        proxy.tick(level, pos);
    }

    //region state transfer (called by the proxy template)

    /**
     * Restores the persisted gear once the machine exists. Runs on the server only — the client is a
     * puppet and gets its state from the sync snapshot. Kept pending until the machine actually reports
     * the target, so a {@code goTo} swallowed by a rebuild is retried on the next tick.
     */
    void applyPendingGear(StateMachine<?> machine) {
        if (pendingGear < 0) return;
        Level level = getLevel();
        if (level != null && level.isClientSide()) return;
        int target = Math.min(pendingGear, FanFixtures.GEAR_STATE_IDS.length - 1);
        if (gearIndex(machine) == target) {
            pendingGear = -1;
            return;
        }
        machine.goTo("gear", FanFixtures.GEAR_STATE_IDS[target]);
        lastKnownGear = target;
    }

    /**
     * Mirrors "is the fan spinning" into the block state so the world can observe the machine → world
     * direction (redstone/comparator). Server only.
     */
    void publishRunning(StateMachine<?> machine) {
        Level level = getLevel();
        if (level == null || level.isClientSide()) return;
        lastKnownGear = gearIndex(machine);
        boolean running = lastKnownGear > 0;
        BlockState state = getBlockState();
        if (state.getValue(FanBlock.RUNNING) != running) {
            level.setBlock(getBlockPos(), state.setValue(FanBlock.RUNNING, running), 3);
        }
    }

    //endregion

    //region persistence — the gear index only

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putByte("gear", (byte) gearIndex());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        pendingGear = tag.getByte("gear");     // the machine does not exist yet: restored by pull
    }

    private int gearIndex() {
        StateMachine<?> machine = proxy.machine();
        if (machine != null) return gearIndex(machine);
        if (lastKnownGear >= 0) return lastKnownGear;
        return Math.max(pendingGear, 0);
    }

    private static int gearIndex(StateMachine<?> machine) {
        Layer<?> gear = machine.layerOrNull("gear");
        int index = gear == null ? -1 : gear.activeStateIndex();
        return Math.max(index, 0);
    }

    //endregion

    //region lifecycle

    @Override
    public void setRemoved() {
        proxy.release(level);
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {      // chunk unload does not go through setRemoved
        proxy.release(level);
        super.onChunkUnloaded();
    }

    //endregion

    /**
     * The content-side description of the fan: what to attach and how to exchange data with the machine.
     * It is an implementation detail of this block entity, not a separate content class — attaching a
     * proxy is the default way to bring content into the model system.
     */
    private static final class Template implements ElementProxy<FanBlockEntity> {

        @Override
        public @Nullable ProxyConfig configure(FanBlockEntity element) {
            return new ProxyConfig(element.machineId(), element.fanModel(), null, null);
        }

        @Override
        public @Nullable VarProvider varProvider(FanBlockEntity element) {
            return new FanVarProvider();       // per instance: keeps currentSpeed / angle across ticks
        }

        @Override
        public void pull(FanBlockEntity element, StateMachine<?> machine) {
            element.applyPendingGear(machine);
        }

        @Override
        public void push(FanBlockEntity element, StateMachine<?> machine) {
            element.publishRunning(machine);
        }
    }
}
