package lib.kasuga.rendering.models.mc.dynamic.fsm;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmMachineBuilder;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmPoseDriver;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import lib.kasuga.rendering.models.uml.dynamic.fsm.VarProvider;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.StateMachineDefinition;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.ParameterSpec;
import lib.kasuga.rendering.models.uml.dynamic.fsm.sync.FsmSyncKey;
import lib.kasuga.rendering.models.mc.dynamic.fsm.sync.FsmSyncClient;
import lib.kasuga.rendering.models.mc.dynamic.fsm.sync.FsmSyncServer;
import lib.kasuga.rendering.models.uml.math.Transform;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Host-side coupling of a data-driven {@link StateMachine} with a rendered {@link ModelInstance}. Owns the
 * whole per-host animation lifecycle that {@code AnimationBlockEntity} used to wire by hand: lazy machine build,
 * client model bind / self-heal, {@link FsmPoseDriver} attachment (the FSM-as-pose-driver plugged into the
 * instance), per-tick drive + GPU flush, and server→client sync (push on the server, bind/apply on the client).
 *
 * <p>Server side: holds a logic-only machine (no model, no driver) and pushes authoritative state. Client side:
 * binds a {@link ModelInstance} via {@link KasugaModelPipelines}, builds a puppet machine (logic disabled), plugs
 * an {@link FsmPoseDriver} into the instance, and drives the pose every tick; {@code FsmSyncClient} conforms the
 * puppet to server snapshots on the main thread between ticks.
 *
 * <p>Host-agnostic: the host (a block entity today; entities/scripts later) supplies its identity
 * ({@code owner}, {@code ownerDiscriminator}) and a root-transform supplier. The FSM core
 * ({@code lib.kasuga.rendering.models.uml.dynamic.fsm}) stays MC-free; this class is the MC boundary.
 */
public final class FsmAnimatedModel {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final Object owner;
    private final long ownerDiscriminator;
    private final Supplier<Transform> rootTransform;
    private final Consumer<StateMachine<?>> onMachineBuilt;
    private final Consumer<ModelInstance> onClientModelBound;

    @Nullable
    private Id stateMachineId;
    @Nullable
    private ResourceLocation modelLoc;
    @Nullable
    private String modelName;
    private boolean definitionWarned;

    @Nullable
    private volatile StateMachine<?> machine;
    @Nullable
    private ModelInstance modelInstance;
    @Nullable
    private FsmPoseDriver driver;
    @Nullable
    private VarProvider varProvider;
    private boolean modelBound;
    private int lastLoggedVersion = -1;

    public FsmAnimatedModel(Object owner, long ownerDiscriminator, Supplier<Transform> rootTransform,
                            @Nullable Id stateMachineId, @Nullable ResourceLocation modelLoc, @Nullable String modelName,
                            @Nullable Consumer<StateMachine<?>> onMachineBuilt,
                            @Nullable Consumer<ModelInstance> onClientModelBound) {
        this.owner = owner;
        this.ownerDiscriminator = ownerDiscriminator;
        this.rootTransform = rootTransform;
        this.stateMachineId = stateMachineId;
        this.modelLoc = modelLoc;
        this.modelName = modelName;
        this.onMachineBuilt = onMachineBuilt;
        this.onClientModelBound = onClientModelBound;
    }

    //region accessors

    public @Nullable Id stateMachineId() {
        return stateMachineId;
    }

    public @Nullable ResourceLocation modelLoc() {
        return modelLoc;
    }

    public @Nullable String modelName() {
        return modelName;
    }

    public boolean isModelBound() {
        return modelBound;
    }

    /** {@code -1} until the machine is built (definitions not ready / model not bound yet). */
    public int machineVersion() {
        StateMachine<?> m = machine;
        return m == null ? -1 : m.version();
    }

    public Map<String, String> activeStates() {
        StateMachine<?> m = machine;
        return m == null ? Map.of() : m.activeStates();
    }

    //region parameter face (container facade — forwards to the machine; tolerates a not-yet-built machine)

    /** Read a declared parameter; returns the spec's default when the machine is not built yet. */
    public <T> T get(ParameterSpec<T> spec) {
        StateMachine<?> m = machine;
        return m == null ? spec.defaultValue() : m.get(spec);
    }

    /** External write — forwards to {@link StateMachine#set} (rejects derived parameters); no-op before build. */
    public <T> T set(ParameterSpec<T> spec, T value) {
        StateMachine<?> m = machine;
        if (m != null) {
            m.set(spec, value);
        }
        return value;
    }

    /** Machine-internal write (provider / action / sync landing) — no-op before build. */
    public <T> T setInternal(ParameterSpec<T> spec, T value) {
        StateMachine<?> m = machine;
        if (m != null) {
            m.setInternal(spec, value);
        }
        return value;
    }

    /**
     * Attach (or swap) the per-entity rendering variable provider. The provider is driven on the main
     * thread each tick by the {@link FsmPoseDriver} and derives the render projection for formula tracks;
     * see {@link VarProvider}. Takes effect on the next driver (re)attachment — typically the next tick.
     */
    public void setVarProvider(@Nullable VarProvider provider) {
        this.varProvider = provider;
    }

    //endregion

    //endregion

    //region config (idempotent; trigger lazy rebuild / rebind)

    /** Programmatic machine assignment; takes effect on the next tick (machine rebuilt lazily). */
    public void stateMachine(@Nullable Id id, @Nullable Level level) {
        if (Objects.equals(stateMachineId, id)) {
            return;
        }
        // machineId is part of FsmSyncKey — unbind the OLD key before it changes (else it leaks in the
        // client/server sync tables)
        if (level != null && machine != null) {
            if (level.isClientSide()) {
                FsmSyncClient.INSTANCE.unbind(syncKey(level));
            } else {
                FsmSyncServer.GLOBAL.unbind(syncKey(level));
            }
        }
        stateMachineId = id;
        machine = null;
        // discard the driver bound to the old machine; a fresh one attaches when the new machine builds
        if (driver != null && modelInstance != null) {
            modelInstance.setPoseDriver(null);
        }
        driver = null;
        definitionWarned = false;
    }

    /** Programmatic model assignment; unbinds the previous binding, if any. */
    public void model(@Nullable ResourceLocation loc, @Nullable String name, @Nullable Level level) {
        if (Objects.equals(modelLoc, loc) && Objects.equals(modelName, name)) {
            return;
        }
        unbindClientModel(level);
        modelLoc = loc;
        modelName = name;
        modelInstance = null;
        driver = null; // old driver pointed at the old instance; reattaches when the new instance binds
    }

    //endregion

    //region AnimationHost — lazy machine

    /**
     * The machine owned by this host, built lazily from its definition. {@code null} when no definition id is
     * set or the definition is not (yet) available. The build is synchronized so cap lookups from other threads
     * never trigger a duplicate build.
     */
    public synchronized @Nullable StateMachine<?> machine(Level level) {
        if (machine != null) {
            return machine;
        }
        if (stateMachineId == null) {
            return null;
        }
        StateMachineDefinition definition = FsmMachineBuilder.findDefinition(stateMachineId);
        if (definition == null) {
            if (!definitionWarned) {
                definitionWarned = true;
                LOGGER.warn("[FsmAnimatedModel] no state machine definition '{}' for {}", stateMachineId, owner);
            }
            return null;
        }
        boolean client = isClientSide(level);
        // Built logic-only (sink=null). On the client the FsmPoseDriver owns the sink and flushes on the
        // render thread; this thread never flushes. setLogicEnabled(false) makes a client machine a puppet
        // (advancePuppet only) — driven via the driver's tick.
        StateMachine<Object> built = FsmMachineBuilder.build(owner, definition, null);
        if (built == null) {
            return null;
        }
        if (client) {
            // Puppet mode: the client machine does not evaluate transitions / run actions (server is
            // authoritative via FsmSyncClient); it only smooth-interpolates in-flight cross-fades.
            built.setClientSide(true);
            built.setLogicEnabled(false);
        }
        machine = built;
        definitionWarned = false;
        LOGGER.debug("[FsmAnimatedModel] machine built for {} from '{}' ({} layer(s), client={}, version={})",
                owner, stateMachineId, built.layers().size(), built.isClientSide(), built.version());
        if (client) {
            // Bind on client machine build (resets the per-key version record).
            FsmSyncClient.INSTANCE.bind(syncKey(level), built);
        }
        if (onMachineBuilt != null) {
            onMachineBuilt.accept(built);
        }
        return built;
    }

    //endregion

    //region ticking

    public void tick(Level level, BlockPos pos) {
        if (level == null) {
            return;
        }
        if (level.isClientSide()) {
            tickClient(level);
        } else {
            tickServer(level, pos);
        }
    }

    /** Server: logic-only machine, then push authoritative state to chunk-tracking players. */
    private void tickServer(Level level, BlockPos pos) {
        StateMachine<?> m = machine(level);
        if (m == null) {
            return;
        }
        m.tick();
        pushSync(level, pos, m);
    }

    /**
     * Client: (re)bind model → build puppet machine → advance the FSM one game tick + publish a {@link PoseTarget}
     * for the render thread. The pose is sampled + flushed + uploaded on the render thread (backend calls
     * {@code instance.sample(partialTick)}); this thread only advances logic and never flushes. Sync is applied by
     * {@code FsmSyncClient} on the main thread once the machine is bound; this method never applies sync itself.
     */
    private void tickClient(Level level) {
        ensureClientModel(level);
        StateMachine<?> m = machine(level);
        if (m == null) {
            return;
        }
        attachDriverIfReady();
        if (driver != null) {
            // Main-thread advance: tick the machine (puppet, sink=null → no flush) + publish PoseTarget.
            driver.tick(1f / 20f);
        } else {
            // Model not bound yet — keep the puppet ticking so timers are ready when it binds.
            m.tick();
        }
        logVersionChange(m);
    }

    /**
     * Link the {@link FsmPoseDriver} to the current (machine, instance) pair. Creates the driver once per
     * machine build; rebinds (swaps the sink target only, machine preserved) when the model instance changed
     * (resource-reload self-heal); idempotent otherwise. Cheap ref-equality checks — safe to call every tick.
     */
    private void attachDriverIfReady() {
        StateMachine<?> m = machine;
        if (m == null || modelInstance == null) {
            return;
        }
        if (driver == null || driver.machine() != m) {
            driver = new FsmPoseDriver(m, modelInstance, varProvider);
            modelInstance.setPoseDriver(driver);
            LOGGER.debug("[FsmAnimatedModel] driver attached for {} (version {})", owner, m.version());
        } else if (driver.model() != modelInstance) {
            // Self-heal rebind: machine state preserved, only the pose destination changes.
            driver.rebind(modelInstance);
            LOGGER.debug("[FsmAnimatedModel] driver rebound for {} (self-heal)", owner);
        }
    }

    private void logVersionChange(StateMachine<?> m) {
        int version = m.version();
        if (version != lastLoggedVersion) {
            lastLoggedVersion = version;
            LOGGER.debug("[FsmAnimatedModel] {} version -> {} (active {})", owner, version, m.activeStates());
        }
    }

    //endregion

    //region server sync

    private void pushSync(Level level, BlockPos pos, StateMachine<?> m) {
        if (stateMachineId == null || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        // Recipients are players tracking this block's chunk; dedup/20-tick heartbeat live in FsmSyncServer.
        FsmSyncServer.GLOBAL.pushToChunkTrackers(syncKey(level), m, serverLevel, pos);
    }

    private void unbindServerSync(Level level) {
        if (stateMachineId == null || level == null || level.isClientSide()) {
            return;
        }
        FsmSyncServer.GLOBAL.unbind(syncKey(level)); // drop this key's dedup and heartbeat state on teardown
    }

    /** The sync key; {@code dimension} is a plain String (the uml FSM core is MC-free). */
    public FsmSyncKey syncKey(Level level) {
        String dimension = (level != null ? level.dimension().location() : Level.OVERWORLD.location()).toString();
        return new FsmSyncKey(stateMachineId, dimension, ownerDiscriminator);
    }

    //endregion

    //region lifecycle

    public void onLoad(Level level) {
        if (isClientSide(level) && modelLoc != null) {
            ensureClientModel(level);
        }
    }

    public void onChunkUnloaded(Level level) {
        unbindClientModel(level);
        unbindClientSync(level);
        unbindServerSync(level);
    }

    public void onRemoved(Level level) {
        unbindClientModel(level);
        unbindClientSync(level);
        unbindServerSync(level);
    }

    private void unbindClientSync(Level level) {
        if (!isClientSide(level) || machine == null) {
            return;
        }
        FsmSyncClient.INSTANCE.unbind(syncKey(level)); // drop the bind entry + version record
    }

    //endregion

    //region client model binding

    /**
     * Lazily binds (or self-heals) the client model. Silent when the model is not published yet — the next tick
     * retries. On a rebind (resource reload dropped the renderer entry) the machine keeps its state; the
     * {@link FsmPoseDriver} is rebound to the fresh instance on the next {@link #attachDriverIfReady()}.
     */
    private void ensureClientModel(Level level) {
        if (modelLoc == null) {
            return;
        }
        // modelName is MMD-only (KasugaModelPipelines.resolveLoc uses it solely for the PMX pipeline);
        // non-MMD models legitimately pass null — the gate is modelLoc alone.
        ResourceLocation instance = instanceLoc();
        if (modelBound && modelInstance != null && KasugaModelPipelines.isRendering(modelLoc, modelName, instance)) {
            return; // healthy
        }
        boolean rebinding = modelBound || modelInstance != null;
        ModelInstance fresh = KasugaModelPipelines.createAndBind(modelLoc, instance, modelName, rootTransform.get());
        if (fresh == null) {
            return; // model not ready yet (lazy create); retry next tick
        }
        modelInstance = fresh;
        modelBound = true;
        LOGGER.debug("[FsmAnimatedModel] model bound for {} (instance '{}', self-heal={}, isRendering={})",
                owner, instance, rebinding, KasugaModelPipelines.isRendering(modelLoc, modelName, instance));
        if (onClientModelBound != null) {
            onClientModelBound.accept(fresh);
        }
    }

    /** Per-host unique instance identifier, so two hosts never share one {@link ModelInstance}. */
    private ResourceLocation instanceLoc() {
        ResourceLocation base = modelLoc != null
                ? modelLoc
                : ResourceLocation.fromNamespaceAndPath("kasuga_lib", "unknown");
        return ResourceLocation.fromNamespaceAndPath(base.getNamespace(), "fsm_" + ownerDiscriminator);
    }

    private void unbindClientModel(Level level) {
        if (!isClientSide(level)) {
            return;
        }
        if (modelLoc != null && (modelBound || modelInstance != null)) {
            // modelName may be null for non-MMD models — KasugaModelPipelines.unbind is null-safe.
            KasugaModelPipelines.unbind(modelLoc, modelName, instanceLoc());
            LOGGER.debug("[FsmAnimatedModel] model unbound for {}", owner);
        }
        modelInstance = null;
        modelBound = false;
        driver = null; // instance gone
    }

    //endregion

    private boolean isClientSide(Level level) {
        return level != null && level.isClientSide();
    }
}
