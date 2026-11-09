package test.kasuga.modelling;

import lib.kasuga.rendering.models.mc.proxies.ProxySessionHost;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyAttachToken;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxySession;
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
 * The fan example block entity: a {@link ProxySessionHost} with no animation code of its own.
 *
 * <p>{@link FanProxyComposition} is registered against the block-entity type and supplies the
 * machine, model, controller and visual ports. The constructor only keeps block-entity state; the
 * first ticker call creates the kernel session from that explicit registration.
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
 * machine rebuilds lazily and the first prepared input restores the layer. Note that a chunk can be saved
 * before this entity's first tick, so {@link #saveAdditional} falls back to the loaded/pending value
 * instead of assuming a live machine.
 */
public class FanBlockEntity extends BlockEntity implements ProxySessionHost {

    private final ProxyAttachToken attachToken = ProxyAttachToken.random();
    @Nullable private ProxySession<?, ?, ?, ?, ?> proxySession;

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
    public ProxyAttachToken proxyAttachToken() {
        return attachToken;
    }

    @Override
    public @Nullable ProxySession<?, ?, ?, ?, ?> proxySession() {
        return proxySession;
    }

    @Override
    public void proxySession(@Nullable ProxySession<?, ?, ?, ?, ?> session) {
        if (session != null && proxySession != null && proxySession != session) {
            throw new IllegalStateException("fan already has a proxy session");
        }
        proxySession = session;
    }

    /** Queues the server-side cycle command; the next kernel step applies it. */
    @SuppressWarnings("unchecked")
    public void requestCycle() {
        ProxySession<?, ?, ?, ?, ?> session = proxySession;
        if (session != null) {
            ((ProxySession<FanBlockEntity, FanProxyComposition.FanInput,
                    FanProxyComposition.FanOutput, FanProxyComposition.FanCommand,
                    FanProxyComposition.FanFrame>) session)
                    .submitCommand(FanProxyComposition.FanCommand.CYCLE);
        }
    }

    //region state transfer (called by the proxy template)

    /** Mirrors the committed gear into the block state. Server only. */
    void applyProxyWriteback(int gear) {
        Level level = getLevel();
        if (level == null || level.isClientSide()) return;
        lastKnownGear = Math.max(gear, 0);
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
        if (lastKnownGear >= 0) return lastKnownGear;
        return Math.max(pendingGear, 0);
    }

    //endregion

    //region lifecycle

    @Override
    public void setRemoved() {
        closeProxySession();
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {      // chunk unload does not go through setRemoved
        closeProxySession();
        super.onChunkUnloaded();
    }

    //endregion

    int pendingGear() {
        return pendingGear;
    }

    void clearPendingGear() {
        pendingGear = -1;
    }

    int nextGearIndex() {
        return (Math.max(lastKnownGear, 0) + 1) % FanFixtures.GEAR_STATE_IDS.length;
    }
}
