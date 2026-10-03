package lib.kasuga.rendering.models.uml.dynamic.fsm.state;

import java.util.Optional;
import java.util.Set;

/**
 * Read-only, strongly-typed view over a state machine's value store — the FSM analog of Minecraft's
 * {@code DataComponentMap}. Values are addressed by typed {@link StateVar} keys (no string keys, no casts).
 *
 * <p>{@link #get} returns the set value, or the var's {@link StateVar#defaultValue()} if absent — so a
 * registered var never reads as {@code null}. The type parameter on {@code StateVar<T>} makes the return type
 * concrete at the call site: {@code float speed = ctx.vars().get(SPEED);} compiles with no cast. Type safety
 * is by construction: {@link MutableStateMap#set(StateVar, Object)} only accepts a value of the var's own type.
 */
public interface StateMap {

    /**
     * Read the value of {@code var}, or its {@link StateVar#defaultValue()} if not set. Never {@code null} for a
     * registered var.
     */
    <T> T get(StateVar<? extends T> var);

    /** Like {@link #get} but distinguishes "absent" (empty) from the default. */
    <T> Optional<T> getOptional(StateVar<? extends T> var);

    /** True iff a value is explicitly set for {@code var} (a default value does not count). */
    boolean has(StateVar<?> var);

    /** The vars with an explicitly-set value (defaults are not included). */
    Set<StateVar<?>> keySet();

    default boolean getBool(StateVar<Boolean> var) {
        return get(var);
    }

    default int getInt(StateVar<Integer> var) {
        return get(var);
    }

    default float getFloat(StateVar<Float> var) {
        return get(var);
    }

    default String getString(StateVar<String> var) {
        return get(var);
    }
}
