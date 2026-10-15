package lib.kasuga.scripting.fsm;

import lib.kasuga.rendering.models.uml.dynamic.fsm.StateContext;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVar;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVarRegistry;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVarType;
import lib.kasuga.scripting.security.Api;

/**
 * The script-facing context handed to JS guards/actions. A thin {@code @Api}-annotated adapter over the
 * real {@link StateContext} (which lives in the modelling module and cannot be exported to JS directly —
 * the {@code @Api} marker is in this scripting module, and modelling must not depend on scripting).
 *
 * <p>JS receives this object (proxied by the engine) when a guard/action callback fires, so
 * {@code ctx.owner()}, {@code ctx.get("ns:speed")}, {@code ctx.trigger("ns:jump")} are all callable from
 * JS. Var access is by full id string ({@code "namespace:path"}), resolved against the shared
 * {@link StateVarRegistry}; values are coerced/boxed via {@link StateValueCoercer} the same way
 * {@link AnimatorApi} does it, so floats arrive as JS numbers, vec3 as {@code [x,y,z]} arrays, etc.
 *
 * <p>Owner identity is preserved: a JS object passed as the owner to {@code AnimatorBuilder.instantiate}
 * round-trips as the same underlying V8 object, so JS-side mutations stay visible to guards and writes
 * made through {@code ctx.owner()} propagate back.
 */
public final class ScriptFsmContext {

    private final StateContext<?> delegate;
    private final StateVarRegistry stateVars;

    public ScriptFsmContext(StateContext<?> delegate, StateVarRegistry stateVars) {
        this.delegate = delegate;
        this.stateVars = stateVars;
    }

    /** The owning actor passed to {@code AnimatorBuilder.instantiate} — for JS, the live JS owner object. */
    @Api
    public Object owner() {
        return delegate.owner();
    }

    @Api
    public boolean isClientSide() {
        return delegate.isClientSide();
    }

    /** Ticks the active state has been running (0 immediately after entering). */
    @Api
    public int stateElapsedTicks() {
        return delegate.stateElapsedTicks();
    }

    /** Configured duration of the active state, or {@code -1} if it has none. */
    @Api
    public int stateDurationTicks() {
        return delegate.stateDurationTicks();
    }

    @Api
    public boolean isLayerLocked() {
        return delegate.isLayerLocked();
    }

    //region typed value store (string-id access — the JS analog of StateContext.get/set)

    /** Read a typed var by full id; returns the var's default when unset. {@code null} if the id is unknown. */
    @Api
    public Object get(String varId) {
        StateVar<?> var = stateVars.resolve(varId);
        if (var == null) {
            return null;
        }
        return StateValueCoercer.box(readRaw(var));
    }

    @Api
    public boolean has(String varId) {
        StateVar<?> var = stateVars.resolve(varId);
        return var != null && delegate.has(var);
    }

    /**
     * Validate and store {@code value} for {@code varId}. JS numbers arrive as Integer/Double and are
     * coerced to the var's type. No-op (returns) if the id is unknown or the value is not coerceable.
     */
    @Api
    public void set(String varId, Object value) {
        StateVar<?> var = stateVars.resolve(varId);
        if (var == null) {
            return;
        }
        StateVarType<?> type = StateVarType.byClass(var.type());
        if (type == null) {
            return;
        }
        Object coerced = StateValueCoercer.coerce(type, value);
        if (coerced == null || !isValid(var, coerced)) {
            return;
        }
        writeRaw(var, coerced);
    }

    /** Fire a tick-scoped ephemeral boolean trigger by id. No-op if unknown or non-boolean. */
    @Api
    public void trigger(String triggerId) {
        StateVar<Boolean> trigger = asBooleanVar(stateVars.resolve(triggerId));
        if (trigger != null) {
            delegate.trigger(trigger);
        }
    }

    @Api
    public boolean isTriggered(String triggerId) {
        StateVar<Boolean> trigger = asBooleanVar(stateVars.resolve(triggerId));
        return trigger != null && delegate.isTriggered(trigger);
    }

    //endregion

    @SuppressWarnings("rawtypes")
    private static StateVar<Boolean> asBooleanVar(StateVar var) {
        return var != null && var.type() == Boolean.class ? (StateVar<Boolean>) var : null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object readRaw(StateVar var) {
        return delegate.get(var);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void writeRaw(StateVar var, Object value) {
        delegate.set(var, value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean isValid(StateVar var, Object value) {
        return var.isValid(value);
    }
}
