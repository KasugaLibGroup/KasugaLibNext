package lib.kasuga.rendering.models.uml.dynamic.fsm;

import lib.kasuga.rendering.models.uml.dynamic.fsm.state.MutableStateMap;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.ParameterSpec;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateMap;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVar;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Owner-generic animation state machine. The whole API is parameterized by {@link Owner}, so
 * {@code ctx.owner()} is fully typed (no casts). Built via the lambda DSL:
 *
 * <pre>{@code
 * StateMachine<MyActor> machine = StateMachine.<MyActor>builder(actor)
 *         .layer("locomotion", layer -> { ... })
 *         .layer("upper_body", layer -> { ... })
 *         .build();
 * machine.tick();
 * }</pre>
 *
 * <p>Layers run in parallel (orthogonality); each tick composes their poses (BASE/ADDITIVE/OVERRIDE)
 * into a {@link Blender} and flushes via the {@link PoseSink} (null on a logic-only server).
 *
 * <p>The typed value store ({@link #vars()} / {@link #mutableVars()}) holds {@link StateVar}-keyed values
 * (the FSM analog of Minecraft's {@code DataComponentMap}); signals are ordinary typed vars, and triggers are
 * ephemeral {@code StateVar<Boolean>}s cleared at the end of every tick.
 */
public final class StateMachine<Owner> {

    private final Owner owner;
    private final List<Layer<Owner>> layers = new ArrayList<>();
    private final Map<String, Layer<Owner>> layersById = new HashMap<>();
    private PoseSink sink;
    private boolean clientSide;
    private int version;
    private long tickCount;

    private final MutableStateMap vars = MutableStateMap.create();
    private final Map<String, Integer> locks = new HashMap<>();

    private boolean logicEnabled = true;
    private final Set<StateVar<Boolean>> bufferedTriggers = new HashSet<>();
    private final Set<StateVar<?>> declaredVars = new HashSet<>();
    private final Blender blender = new Blender();

    private StateMachine(Owner owner) {
        this.owner = owner;
    }

    public static <O> Builder<O> builder(O owner) {
        return new Builder<>(owner);
    }

    //region api

    public Owner owner() {
        return owner;
    }

    /**
     * Layer list in build order. Note: layers appended directly here (bypassing the builder) are
     * not indexed in {@code layersById} — {@link #layerOrNull(String)} falls back to a linear scan
     * so lookups still find them.
     */
    public List<Layer<Owner>> layers() {
        return layers;
    }

    /**
     * Look up a layer by id; non-null by contract &mdash; throws {@link NoSuchElementException} if no layer
     * has that id (fail-fast on programmer error). For lookups driven by external input (scripting /
     * JSON), use {@link #layerOrNull(String)}.
     */
    public Layer<Owner> layer(String id) {
        Layer<Owner> layer = layerOrNull(id);
        if (layer == null) {
            throw new NoSuchElementException("layer not found: " + id);
        }
        return layer;
    }

    /** Nullable layer lookup for best-effort callers (scripting / JSON); {@code null} if the id is unknown. */
    public @Nullable Layer<Owner> layerOrNull(String id) {
        if (id == null) {
            return null;
        }
        Layer<Owner> layer = layersById.get(id);
        if (layer != null) {
            return layer;
        }
        // Fallback: layers appended directly to layers() bypass the by-id index.
        for (Layer<Owner> candidate : layers) {
            if (candidate.id().equals(id)) {
                return candidate;
            }
        }
        return null;
    }

    public void setSink(PoseSink sink) {
        this.sink = sink;
    }

    public void setClientSide(boolean clientSide) {
        this.clientSide = clientSide;
    }

    public int version() {
        return version;
    }

    public boolean isClientSide() {
        return clientSide;
    }

    /**
     * Whether this machine evaluates transitions, runs state actions, bumps {@link #version()} and clears
     * ephemeral vars on tick. Default {@code true}. Client "puppet" machines (driven by server snapshots via
     * {@link #conform(StateMachineSnapshot)}) set this {@code false} so they only advance cross-fade
     * interpolation and never locally evaluate guards on vars they do not sync.
     */
    public boolean logicEnabled() {
        return logicEnabled;
    }

    public void setLogicEnabled(boolean logicEnabled) {
        this.logicEnabled = logicEnabled;
    }

    public long tickCount() {
        return tickCount;
    }

    public void tick() {
        tick(1f / 20f);
    }

    public void tick(float dt) {
        blender.reset();
        boolean changed = false;
        for (Layer<Owner> layer : layers) {
            if (logicEnabled) {
                if (layer.tick(this, dt, tickCount)) {
                    changed = true;
                }
            } else {
                layer.advancePuppet(dt);
            }
            blender.applyLayer(layer.mode(), layer.activePose(), layer.weight(), layer.boneMask());
        }
        // Always flush when a sink is attached: even an empty blender must reach the sink so it can
        // neutralize channels posed last frame but absent this frame (ModelInstancePoseSink residue fix).
        if (sink != null) {
            sink.apply(blender);
        }
        if (logicEnabled) {
            vars.removeEphemeral();
            if (changed) {
                version++;
            }
        }
        tickCount++;
    }

    //endregion

    //region parameter face (parameter-store — typed get / role-checked set / internal set / declare)

    /**
     * Read a declared parameter — same value store as {@link #vars()}; returns the spec's default when
     * unset. Any reader may call this on the main thread.
     */
    public <T> T get(ParameterSpec<T> spec) {
        return vars.get(spec);
    }

    /**
     * External write (interaction / controller / redstone / signal): validated against
     * {@link ParameterSpec#externalWritable()} — derived parameters ({@code externalWritable=false})
     * throw {@link IllegalStateException} here; machine-internal writers use {@link #setInternal}.
     */
    public <T> T set(ParameterSpec<T> spec, T value) {
        if (!spec.externalWritable()) {
            throw new IllegalStateException("parameter " + spec.id() + " is not externally writable "
                    + "(externalWritable=false: derived, machine-internal writers only)");
        }
        return vars.set(spec, value);
    }

    /**
     * Machine-internal write (var provider / action / FSM sync landing): bypasses the
     * {@code externalWritable} check. Still type-safe and validator-checked via {@link MutableStateMap}.
     */
    public <T> T setInternal(ParameterSpec<T> spec, T value) {
        return vars.set(spec, value);
    }

    /**
     * Declare parameters on this machine (both sides must declare the same set — e.g. from a shared
     * definition or a host's {@code onMachineBuilt} hook). Declared-but-unset parameters read their
     * defaults via {@link #vars()}; the declaration also feeds the sync projection
     * ({@link ParameterSpec#sync()}).
     */
    public void declare(ParameterSpec<?>... specs) {
        if (specs != null) {
            for (ParameterSpec<?> spec : specs) {
                if (spec != null) {
                    declaredVars.add(spec);
                }
            }
        }
    }

    //endregion

    //region value store / triggers / locks

    /** The typed value store (read-only view); values default to each var's default when unset. */
    public StateMap vars() {
        return vars;
    }

    /** The typed value store (mutable); {@code set} validates against each var's validator. */
    public MutableStateMap mutableVars() {
        return vars;
    }

    /**
     * The vars declared for this machine (inline + referenced), per its definition — the full set including
     * unset vars (which still read their defaults via {@link #vars()}). Distinct from
     * {@link StateMap#keySet()}, which only lists vars with an explicitly-set value.
     */
    public Set<StateVar<?>> declaredVars() {
        return Collections.unmodifiableSet(declaredVars);
    }

    /**
     * Fire a tick-scoped trigger — an ephemeral {@link StateVar}{@code <Boolean>} — for this tick. It is
     * cleared at the end of the tick by {@link #tick(float)} (along with every other ephemeral var).
     */
    public void trigger(StateVar<Boolean> trigger) {
        if (trigger != null) {
            vars.set(trigger, Boolean.TRUE);
        }
    }

    /** Whether {@code trigger} is set this tick. */
    public boolean isTriggered(StateVar<Boolean> trigger) {
        return trigger != null && Boolean.TRUE.equals(vars.get(trigger));
    }

    /**
     * Raise a buffered (latched) trigger. Unlike {@link #trigger(StateVar<Boolean>)}, it is NOT cleared at
     * the end of the tick — it stays raised until a {@link Transition#onBuffered(StateVar)} transition
     * consumes it. Use a <b>non-ephemeral</b> {@code StateVar<Boolean>} for buffered triggers (the latch set
     * owns the lifecycle; an ephemeral var would be swept at tick end before the transition sees it).
     */
    public void triggerBuffered(StateVar<Boolean> trigger) {
        if (trigger != null) {
            bufferedTriggers.add(trigger);
        }
    }

    /** Whether a buffered trigger is currently latched. */
    public boolean isBufferedTriggered(StateVar<Boolean> trigger) {
        return trigger != null && bufferedTriggers.contains(trigger);
    }

    /** Consume (clear) a buffered trigger — called when a transition gated on it fires. */
    public void consumeBufferedTrigger(StateVar<Boolean> trigger) {
        if (trigger != null) {
            bufferedTriggers.remove(trigger);
        }
    }

    void lockLayer(String id, int ticks) {
        if (id != null && ticks > 0) {
            locks.merge(id, ticks, Integer::max);
        }
    }

    /** Drop any lock on {@code id} (cancels {@link #lockLayer}); inert if the layer is not locked. */
    public void unlockLayer(String id) {
        if (id != null) {
            locks.remove(id);
        }
    }

    /** If this layer is locked, consume one tick of the lock and return true (skip its tick). */
    boolean consumeLock(String id) {
        Integer remaining = locks.get(id);
        if (remaining == null || remaining <= 0) {
            return false;
        }
        if (remaining <= 1) {
            locks.remove(id);
        } else {
            locks.put(id, remaining - 1);
        }
        return true;
    }

    /** Non-consuming check: is the named layer currently locked? */
    public boolean isLayerLocked(String id) {
        Integer remaining = locks.get(id);
        return remaining != null && remaining > 0;
    }

    //endregion

    //region reconcile surface (server→client sync: snapshot()/conform(StateMachineSnapshot))

    /**
     * Imperative switch by id — scripting/JSON-friendly; inert if the layer/state is unknown. The switch is
     * <b>instant</b> (no cross-fade) and takes effect on the next {@link #tick(float)}.
     */
    public void goTo(String layerId, String stateId) {
        Layer<Owner> layer = layerOrNull(layerId);
        if (layer == null) {
            return;
        }
        State<Owner> target = layer.findState(stateId);
        if (target != null) {
            layer.goTo(target);
        }
    }

    /** Debug/host inspection surface: current layer → active state ids. Network sync uses {@link #snapshot()}. */
    public Map<String, String> activeStates() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (Layer<Owner> layer : layers) {
            snapshot.put(layer.id(), layer.active() == null ? null : layer.active().id());
        }
        return snapshot;
    }

    /**
     * Force each layer's active state by id (silent, no callbacks). <b>Compatibility-only</b> legacy
     * reconcile surface: unconditionally bumps {@link #version} and drops in-flight transition state.
     * The network sync path uses {@link #conform(StateMachineSnapshot)} instead.
     */
    public void conform(Map<String, String> snapshot) {
        if (snapshot == null) {
            return;
        }
        for (Layer<Owner> layer : layers) {
            String stateId = snapshot.get(layer.id());
            if (stateId != null) {
                layer.conformTo(stateId);
            }
        }
        version++;
    }

    /**
     * Immutable snapshot of the machine's runtime state — per-layer active state, elapsed ticks and
     * the in-flight cross-fade. Server-authoritative; sent to clients over the FSM sync channel
     * (see {@code lib.kasuga.rendering.models.uml.dynamic.fsm.sync}).
     */
    public StateMachineSnapshot snapshot() {
        List<StateMachineSnapshot.LayerState> layerStates = new ArrayList<>(layers.size());
        for (Layer<Owner> layer : layers) {
            State<Owner> active = layer.active();
            Transition<Owner> transition = layer.activeTransition();
            layerStates.add(new StateMachineSnapshot.LayerState(
                    layer.id(),
                    active == null ? null : active.id(),
                    layer.stateElapsedTicks(),
                    transition == null ? null : transition.id(),
                    layer.transitionElapsed()
            ));
        }
        return new StateMachineSnapshot(version, layerStates);
    }

    /**
     * Apply a server snapshot. Layers are matched by id (unknown ids ignored). Each layer is first
     * conformed to its active state with elapsed ticks, then its in-flight transition is restored —
     * {@code conformTo} clears the active transition, so the order matters. {@link #version} is
     * bumped only when at least one layer actually applied; returns true in that case.
     */
    public boolean conform(StateMachineSnapshot snapshot) {
        if (snapshot == null) {
            return false;
        }
        boolean applied = false;
        for (StateMachineSnapshot.LayerState layerState : snapshot.layers()) {
            Layer<Owner> layer = layerOrNull(layerState.layerId());
            if (layer == null) {
                continue;
            }
            boolean changed = layer.conformTo(layerState.stateId(), layerState.elapsedTicks());
            if (layerState.transitionId() != null) {
                layer.conformTransition(layerState.transitionId(), layerState.transitionElapsedSeconds());
            }
            applied |= changed;
        }
        if (applied) {
            version++;
        }
        return applied;
    }

    //endregion

    //region typed read surface — direct, type-safe accessors (no path strings).

    /** Active state id of the layer, or {@code null} if the layer is unknown or has no active state. */
    @Nullable
    public String activeStateId(String layerId) {
        Layer<Owner> layer = layerOrNull(layerId);
        State<Owner> active = layer == null ? null : layer.active();
        return active == null ? null : active.id();
    }

    /** Ticks spent in the active state; {@code 0} if the layer is unknown. */
    public int layerElapsedTicks(String layerId) {
        Layer<Owner> layer = layerOrNull(layerId);
        return layer == null ? 0 : layer.stateElapsedTicks();
    }

    /** Active state duration in ticks; {@code -1} if the layer is unknown or has no active state. */
    public int layerDurationTicks(String layerId) {
        Layer<Owner> layer = layerOrNull(layerId);
        State<Owner> active = layer == null ? null : layer.active();
        return active == null ? -1 : active.durationTicks();
    }

    /** Layer blend mode, or {@code null} if the layer is unknown. */
    @Nullable
    public BlendMode layerMode(String layerId) {
        Layer<Owner> layer = layerOrNull(layerId);
        return layer == null ? null : layer.mode();
    }

    /** Layer blend weight; {@code 0f} if the layer is unknown. */
    public float layerWeight(String layerId) {
        Layer<Owner> layer = layerOrNull(layerId);
        return layer == null ? 0f : layer.weight();
    }

    /** In-flight cross-fade transition of the layer, or {@code null}. */
    @Nullable
    public Transition<Owner> activeTransition(String layerId) {
        Layer<Owner> layer = layerOrNull(layerId);
        return layer == null ? null : layer.activeTransition();
    }

    /** Seconds into the in-flight cross-fade. */
    public float layerTransitionElapsed(String layerId) {
        Layer<Owner> layer = layerOrNull(layerId);
        return layer == null ? 0f : layer.transitionElapsed();
    }

    //endregion

    public static final class Builder<O> {

        private final StateMachine<O> machine;

        Builder(O owner) {
            this.machine = new StateMachine<>(owner);
        }

        public Builder<O> layer(String id, Consumer<Layer<O>> config) {
            Layer<O> layer = new Layer<>(id);
            config.accept(layer);
            return layer(layer);
        }

        /** Register a pre-built layer: starts it, appends it, and indexes it by id. */
        public Builder<O> layer(Layer<O> layer) {
            if (machine.layersById.containsKey(layer.id())) {
                throw new IllegalStateException("duplicate layer id '" + layer.id() + "'");
            }
            layer.start();
            machine.layers.add(layer);
            machine.layersById.put(layer.id(), layer);
            return this;
        }

        public Builder<O> sink(PoseSink sink) {
            machine.sink = sink;
            return this;
        }

        public Builder<O> declaredVars(Set<StateVar<?>> vars) {
            if (vars != null) {
                machine.declaredVars.addAll(vars);
            }
            return this;
        }

        public Builder<O> clientSide(boolean clientSide) {
            machine.clientSide = clientSide;
            return this;
        }

        public StateMachine<O> build() {
            return machine;
        }
    }
}
