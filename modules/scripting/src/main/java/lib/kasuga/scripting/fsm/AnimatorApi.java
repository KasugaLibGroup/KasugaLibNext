package lib.kasuga.scripting.fsm;

import com.mojang.logging.LogUtils;
import jakarta.annotation.Nullable;
import lib.kasuga.rendering.models.uml.dynamic.fsm.BlendMode;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmMachines;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmRegistries;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.MutableStateMap;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateMap;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVar;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVarRegistry;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVarType;
import lib.kasuga.scripting.ScriptEngine;
import lib.kasuga.scripting.security.Api;
import lib.kasuga.scripting.value.ScriptFunction;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Script-facing control surface for {@link StateMachine}s — a polyglot-neutral API keyed by a int handle
 * (from {@link FsmMachines#register}). Registered as the engine global "Animator" via
 * {@link FsmApiRegistration#install}.
 *
 * <p>Typed value access (the data-component-style channel): scripts read/write registered {@link StateVar}s
 * by id — {@code Animator.get(h, "kasuga_lib:my_machine/speed")}, {@code Animator.set(h, id, value)}. Vars are
 * registered with {@link AnimatorBuilderApi#registerStateVar}. The boundary validates and coerces the value
 * against the var's declared type (JS numbers arrive as Integer/Double) via {@link StateValueCoercer}.
 *
 * <p>Structural reads are narrow, named methods ({@code getState}, {@code getLayerMode}, ...). Invalid handles
 * and unknown vars are <b>logged once per id and otherwise no-op</b> (return {@code null} / {@code false} / a
 * zero default) — more audible than swallowing silently, and never throws across the script boundary.
 */
public final class AnimatorApi {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final FsmMachines machines;
    private final StateVarRegistry stateVars;
    /**
     * Back-reference to the engine that owns this API instance. Nullable (Java host callers don't supply
     * one). Required for the {@code ScriptFunction}-based callbacks ({@code onTick}/{@code onStateChanged})
     * which must wrap Java objects into {@link ScriptValue}s to invoke JS.
     */
    @Nullable
    private final ScriptEngine engine;
    /** This engine's auto-tick hub (null for Java-host/unit-test callers without an engine). */
    @Nullable
    private final FsmAutoTickModule tickHub;
    // Bounded LRU (cap 256): dedupes warn-once keys without unbounded growth from misspelled ids.
    private final Map<String, Boolean> warnedUnknown = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > 256;
                }
            });

    public AnimatorApi() {
        this(FsmRegistries.GLOBAL, null);
    }

    public AnimatorApi(FsmRegistries registries) {
        this(registries, null);
    }

    /** Engine-aware ctor used by {@code FsmApiRegistration} (the supplier receives the engine). */
    public AnimatorApi(ScriptEngine engine) {
        this(FsmRegistries.GLOBAL, engine);
    }

    public AnimatorApi(@Nullable FsmRegistries registries, @Nullable ScriptEngine engine) {
        this.machines = registries.machines();
        this.stateVars = registries.vars();
        this.engine = engine;
        this.tickHub = engine == null ? null : FsmAutoTickModule.forEngine(engine);
    }

    private StateMachine<?> machine(int handle) {
        return machines.resolve(handle);
    }

    //region typed value store

    @Api
    public Object get(int handle, String varId) {
        StateMachine<?> m = machine(handle);
        if (m == null) {
            return null;
        }
        StateVar<?> var = resolveVar(m, varId);
        if (var == null) {
            return null;
        }
        return StateValueCoercer.box(readValue(m.vars(), var));
    }

    @Api
    public boolean has(int handle, String varId) {
        StateMachine<?> m = machine(handle);
        if (m == null) {
            return false;
        }
        StateVar<?> var = resolveVar(m, varId);
        return var != null && m.vars().has(var);
    }

    @Api
    public void set(int handle, String varId, Object value) {
        StateMachine<?> m = machine(handle);
        if (m == null) {
            return;
        }
        StateVar<?> var = resolveVar(m, varId);
        if (var == null) {
            return;
        }
        StateVarType<?> type = StateVarType.byClass(var.type());
        if (type == null) {
            warnOnce("unsupported-type", varId + ":" + var.type().getName(),
                    () -> LOGGER.warn("Animator.set({}, {}): var type {} is not script-settable",
                            handle, varId, var.type().getName()));
            return;
        }
        Object coerced = StateValueCoercer.coerce(type, value);
        if (coerced == null || !isValid(var, coerced)) {
            warnOnce("wrong-type", varId + ":" + type.token(),
                    () -> LOGGER.warn("Animator.set({}, {}) rejected value of type {}; expected {}",
                            handle, varId, StateValueCoercer.className(value), type.token()));
            return;
        }
        writeValue(m.mutableVars(), var, coerced);
    }

    /** The ids of every var with an explicitly-set value on this machine (defaults are not listed). */
    @Api
    public String[] listVars(int handle) {
        StateMachine<?> m = machine(handle);
        if (m == null) {
            return new String[0];
        }
        Set<StateVar<?>> keys = m.vars().keySet();
        String[] ids = new String[keys.size()];
        int i = 0;
        for (StateVar<?> v : keys) {
            ids[i++] = v.id().toString();
        }
        return ids;
    }

    /**
     * The ids of every var <b>declared</b> by this machine's definition (inline + referenced, including
     * unset ones with only a default) — the discoverable set, as opposed to {@link #listVars} which only
     * lists vars with an explicitly-set value. Lets a script enumerate a machine's vars without guessing ids.
     */
    @Api
    public String[] declaredVars(int handle) {
        StateMachine<?> m = machine(handle);
        if (m == null) {
            return new String[0];
        }
        Set<StateVar<?>> declared = m.declaredVars();
        String[] ids = new String[declared.size()];
        int i = 0;
        for (StateVar<?> v : declared) {
            ids[i++] = v.id().toString();
        }
        return ids;
    }

    /**
     * Release a scripting handle and its machine (multi-instance: other handles stay live). Also drops the
     * handle from auto-tick and tears down its {@code onTick} callback if one was registered. Inert for an
     * unknown/already-released handle. Host-owned machines are not registered here and are unaffected.
     */
    @Api
    public void dispose(int handle) {
        if (tickHub != null) {
            tickHub.remove(handle);
        }
        machines.release(handle);
    }

    /**
     * Toggle per-tick auto-advancement for the machine bound to {@code handle} (no callback). Removes the
     * "every author hand-rolls {@code timer.setInterval(() => Animator.tick(h), 50)}" friction: the machine
     * is advanced on the owning script thread each server tick. Inert for an invalid handle or an API
     * instance without an engine (Java host callers should tick manually).
     */
    @Api
    public void autoTick(int handle, boolean on) {
        if (tickHub == null) {
            warnOnce("no-engine", "autoTick",
                    () -> LOGGER.warn("Animator.autoTick requires an engine-backed Animator; no-op"));
            return;
        }
        tickHub.setAutoTick(handle, on);
    }

    /**
     * Auto-advance the machine each tick AND invoke {@code callback} (a no-arg JS function) after each
     * advance — the script-side hook for "react to the tick". Use the {@link AnimatorApi} read methods
     * inside the callback to inspect state. Replaces any prior onTick callback for the handle.
     */
    @Api
    public void onTick(int handle, ScriptFunction callback) {
        if (tickHub == null) {
            warnOnce("no-engine", "onTick",
                    () -> LOGGER.warn("Animator.onTick requires an engine-backed Animator; no-op"));
            return;
        }
        tickHub.setOnTick(handle, callback);
    }

    /**
     * Auto-advance the machine each tick AND invoke {@code callback} (a no-arg JS function) only when the
     * machine's active state changes (a state switch or cross-fade starts/stops — detected via the version
     * bump). The efficient alternative to polling {@link #getState} every tick. Uses the {@link AnimatorApi}
     * read methods inside the callback to inspect the new state.
     */
    @Api
    public void onStateChanged(int handle, ScriptFunction callback) {
        if (tickHub == null) {
            warnOnce("no-engine", "onStateChanged",
                    () -> LOGGER.warn("Animator.onStateChanged requires an engine-backed Animator; no-op"));
            return;
        }
        tickHub.setOnStateChanged(handle, callback);
    }

    /** The built-in type token of {@code varId} ({@code "bool"/"int"/"float"/...}), or {@code ""} if unknown. */
    @Api
    public String varType(String varId) {
        StateVar<?> var = stateVars.resolve(varId);
        if (var == null) {
            return "";
        }
        StateVarType<?> type = StateVarType.byClass(var.type());
        return type == null ? var.type().getSimpleName() : type.token();
    }

    //endregion

    //region structural reads (typed, named — no path strings)

    @Api
    public String getState(int handle, String layerId) {
        StateMachine<?> m = machine(handle);
        if (m == null) {
            return "";
        }
        String id = m.activeStateId(layerId);
        return id == null ? "" : id;
    }

    @Api
    public int getStateElapsed(int handle, String layerId) {
        StateMachine<?> m = machine(handle);
        return m == null ? 0 : m.layerElapsedTicks(layerId);
    }

    @Api
    public int getStateDuration(int handle, String layerId) {
        StateMachine<?> m = machine(handle);
        return m == null ? -1 : m.layerDurationTicks(layerId);
    }

    @Api
    public String getLayerMode(int handle, String layerId) {
        StateMachine<?> m = machine(handle);
        if (m == null) {
            return "";
        }
        BlendMode mode = m.layerMode(layerId);
        return mode == null ? "" : mode.serialName();
    }

    @Api
    public double getLayerWeight(int handle, String layerId) {
        StateMachine<?> m = machine(handle);
        return m == null ? 0.0 : m.layerWeight(layerId);
    }

    @Api
    public boolean isLayerLocked(int handle, String layerId) {
        StateMachine<?> m = machine(handle);
        return m != null && m.isLayerLocked(layerId);
    }

    /**
     * Total tick count the machine has been advanced. Returns {@code double} (not {@code long}) so it
     * arrives in JS as a plain Number — a {@code long} would become a JS BigInt, which breaks arithmetic
     * and {@code JSON.stringify}. The value is exact well beyond 2^53 ticks (~14 million years at 20 tps).
     */
    @Api
    public double getTick(int handle) {
        StateMachine<?> m = machine(handle);
        return m == null ? 0.0 : m.tickCount();
    }

    @Api
    public int getVersion(int handle) {
        StateMachine<?> m = machine(handle);
        return m == null ? 0 : m.version();
    }

    //endregion

    //region triggers / imperative

    /** Fire a tick-scoped trigger (an ephemeral boolean var) by full id or short name. */
    @Api
    public void trigger(int handle, String triggerVarId) {
        StateMachine<?> m = machine(handle);
        if (m == null) {
            return;
        }
        StateVar<Boolean> trigger = resolveTrigger(m, triggerVarId);
        if (trigger != null) {
            m.trigger(trigger);
        }
    }

    /** Whether the tick-scoped trigger {@code triggerVarId} is set this tick. */
    @Api
    public boolean isTriggered(int handle, String triggerVarId) {
        StateMachine<?> m = machine(handle);
        if (m == null) {
            return false;
        }
        StateVar<Boolean> trigger = resolveTrigger(m, triggerVarId);
        return trigger != null && m.isTriggered(trigger);
    }

    @Api
    public void goTo(int handle, String layerId, String stateId) {
        StateMachine<?> m = machine(handle);
        if (m != null) {
            m.goTo(layerId, stateId);
        }
    }

    /**
     * Advance the machine bound to {@code handle} by one tick (1/20s). Inert for invalid handles.
     */
    @Api
    public void tick(int handle) {
        StateMachine<?> m = machine(handle);
        if (m != null) {
            m.tick();
        }
    }

    /**
     * Advance the machine bound to {@code handle} by {@code dt} seconds. Takes {@code double} (not
     * {@code float}) so JS numbers — which the bridge always delivers as Integer/Double — match without
     * an "Illegal invocation". The value is narrowed to float for the engine-internal tick.
     */
    @Api
    public void tick(int handle, double dt) {
        StateMachine<?> m = machine(handle);
        if (m != null) {
            m.tick((float) dt);
        }
    }

    //endregion

    //region internal resolution

    /**
     * Resolve a var by full id ({@code "namespace:path"}) or — when the machine is known — by short name
     * (the last path segment, e.g. {@code "go"} for {@code "kasuga_lib:my_machine/go"}). Full ids are tried
     * first and stay compatible; the short-name fallback matches a declared var whose id ends in
     * {@code "/<shortName>"}, requiring an unambiguous match.
     */
    private StateVar<?> resolveVar(StateMachine<?> m, String varId) {
        StateVar<?> var = resolveRaw(m, varId);
        if (var == null) {
            warnOnce("unknown-var", varId,
                    () -> LOGGER.warn("Animator: unknown state var '{}' (not registered, no short-name match); no-op", varId));
        }
        return var;
    }

    /** Full-id lookup first, then unambiguous short-name fallback. No logging — caller warns. */
    private StateVar<?> resolveRaw(StateMachine<?> m, String varId) {
        StateVar<?> var = stateVars.resolve(varId);
        return var != null ? var : resolveShortName(m, varId);
    }

    private StateVar<?> resolveShortName(StateMachine<?> m, String varId) {
        if (varId == null || varId.indexOf(':') >= 0 || varId.indexOf('/') >= 0) {
            return null;
        }
        String suffix = "/" + varId;
        StateVar<?> match = null;
        for (StateVar<?> v : m.declaredVars()) {
            if (v.id().getPath().endsWith(suffix)) {
                if (match != null) {
                    // Ambiguous short name — bail rather than guess.
                    return null;
                }
                match = v;
            }
        }
        return match;
    }

    @SuppressWarnings("rawtypes")
    private static StateVar<Boolean> asBooleanVar(StateVar var) {
        return var != null && var.type() == Boolean.class ? (StateVar<Boolean>) var : null;
    }

    private StateVar<Boolean> resolveTrigger(StateMachine<?> m, String id) {
        StateVar<?> var = resolveRaw(m, id);
        if (var == null) {
            warnOnce("unknown-var", id,
                    () -> LOGGER.warn("Animator: unknown trigger var '{}' (not registered, no short-name match); no-op", id));
            return null;
        }
        StateVar<Boolean> trigger = asBooleanVar(var);
        if (trigger == null) {
            warnOnce("wrong-type", id + ":" + var.type().getSimpleName(),
                    () -> LOGGER.warn("Animator: trigger var '{}' is not boolean (got {}); no-op",
                            id, var.type().getSimpleName()));
        }
        return trigger;
    }

    private void warnOnce(String category, String key, Runnable logger) {
        if (warnedUnknown.put(category + ":" + key, Boolean.TRUE) == null) {
            logger.run();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object readValue(StateMap vars, StateVar var) {
        return vars.get(var);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void writeValue(MutableStateMap vars, StateVar var, Object value) {
        vars.set(var, value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean isValid(StateVar var, Object value) {
        return var.isValid(value);
    }

    //endregion
}
