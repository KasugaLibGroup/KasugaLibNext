package lib.kasuga.rendering.models.uml.dynamic.fsm.state;

import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Mutable {@link StateMap} — the FSM analog of Minecraft's {@code PatchedDataComponentMap}, minus the
 * prototype indirection (defaults live on each {@link StateVar}, so a flat map keyed by id is enough).
 *
 * <p>Backed by {@code Map<Id, Entry>}. Because {@link StateVar} equality is by id alone, two vars
 * with the same id but different value types would otherwise collide silently and {@code get} would
 * unchecked-cast at the call site; instead {@link #set} detects the type mismatch and throws
 * {@link IllegalStateException} at the offender. {@link #get} returns the var's default (defensively copied for
 * mutable types like {@link org.joml.Vector3f}) when no value is set.
 */
public final class MutableStateMap implements StateMap {

    private record Entry(StateVar<?> var, Object value) {}

    private final Map<Id, Entry> values = new HashMap<>();

    public MutableStateMap() {}

    public static MutableStateMap create() {
        return new MutableStateMap();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(StateVar<? extends T> var) {
        Entry entry = values.get(var.id());
        if (entry == null) {
            return (T) var.copyOfDefault();
        }
        checkType(var, entry);
        return (T) entry.value();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> getOptional(StateVar<? extends T> var) {
        Entry entry = values.get(var.id());
        if (entry == null) {
            return Optional.empty();
        }
        checkType(var, entry);
        return Optional.of((T) entry.value());
    }

    @Override
    public boolean has(StateVar<?> var) {
        return values.containsKey(var.id());
    }

    @Override
    public Set<StateVar<?>> keySet() {
        Set<StateVar<?>> keys = new HashSet<>();
        for (Entry entry : values.values()) {
            keys.add(entry.var());
        }
        return Collections.unmodifiableSet(keys);
    }

    /**
     * Validate and store {@code value} for {@code var}. Returns {@code value}. Throws
     * {@link IllegalStateException} if a value for the same id is already stored under a different type.
     */
    public <T> T set(StateVar<T> var, T value) {
        if (value == null) {
            throw new IllegalArgumentException("null is not a valid value for state var " + var);
        }
        if (!var.isValid(value)) {
            throw new IllegalArgumentException("value " + value + " rejected by validator for " + var);
        }
        Entry existing = values.get(var.id());
        if (existing != null && existing.var().type() != var.type()) {
            throw new IllegalStateException("state var type collision for " + var.id()
                    + ": already registered as " + existing.var().type().getSimpleName()
                    + ", now set as " + var.type().getSimpleName());
        }
        values.put(var.id(), new Entry(var, value));
        return value;
    }

    /** Remove the explicitly-set value for {@code var} (subsequent {@link #get} returns the default). */
    @SuppressWarnings("unchecked")
    public <T> T remove(StateVar<T> var) {
        Entry previous = values.remove(var.id());
        return previous != null ? (T) previous.value() : null;
    }

    /** Remove every explicitly-set value whose var is {@link StateVar#ephemeral()} (tick-scoped). */
    public void removeEphemeral() {
        values.values().removeIf(entry -> entry.var().ephemeral());
    }

    public void clear() {
        values.clear();
    }

    public MutableStateMap copy() {
        MutableStateMap clone = new MutableStateMap();
        clone.values.putAll(this.values);
        return clone;
    }

    private static void checkType(StateVar<?> requested, Entry entry) {
        if (entry.var().type() != requested.type()) {
            throw new IllegalStateException("state var type collision for " + requested.id()
                    + ": stored as " + entry.var().type().getSimpleName()
                    + ", requested as " + requested.type().getSimpleName());
        }
    }
}
