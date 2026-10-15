package lib.kasuga.rendering.models.uml.dynamic.multiplexer;

import java.util.HashMap;
import java.util.Map;

/**
 * Open, typed key→value store — the user-extension channel for the multiplexer ({@link Context}
 * implementations, e.g. {@code McContext}). Add custom sensor data without editing any framework type.
 *
 * <p><b>Note:</b> the FSM does not use {@code Blackboard}; its value store is the strongly-typed
 * {@link lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateMap} (DataComponent-style, keyed by
 * {@link lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVar}). This class lives in the multiplexer
 * package and serves only the multiplexer subsystem. Two key spaces:
 *
 * <ul>
 *   <li><b>typed</b> ({@link Key}): compile-time-safe channels Java code declares and reads,
 *       e.g. {@code ctx.data().get(MY_FLAG)} where {@code MY_FLAG = Blackboard.Key.of("flag")}.</li>
 *   <li><b>raw</b> (String): dynamically-named values for JSON/scripts,
 *       e.g. {@code ctx.data().put("speed", 0.5)} / {@code ctx.data().get("speed")}.</li>
 * </ul>
 */
public final class Blackboard {

    /** Typed, self-describing key (the {@code T} makes {@link #get(Key)} cast-free). */
    public record Key<T>(String name) {
        public static <T> Key<T> of(String name) {
            return new Key<>(name);
        }
    }

    private final Map<Key<?>, Object> typed = new HashMap<>();
    private final Map<String, Object> raw = new HashMap<>();

    public Blackboard() {}

    public static Blackboard empty() {
        return new Blackboard();
    }

    //region typed

    public <T> void put(Key<T> key, T value) {
        typed.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(Key<T> key) {
        return (T) typed.get(key);
    }

    public <T> T getOrDefault(Key<T> key, T defaultValue) {
        T value = get(key);
        return value == null ? defaultValue : value;
    }

    public boolean has(Key<?> key) {
        return typed.containsKey(key);
    }

    @SuppressWarnings("unchecked")
    public <T> T remove(Key<T> key) {
        return (T) typed.remove(key);
    }

    //endregion

    //region raw (string-named — JSON / scripts / dynamic)

    public void put(String name, Object value) {
        raw.put(name, value);
    }

    public Object get(String name) {
        return raw.get(name);
    }

    public <T> T get(String name, Class<T> type) {
        return type.cast(raw.get(name));
    }

    public boolean has(String name) {
        return raw.containsKey(name);
    }

    public Object remove(String name) {
        return raw.remove(name);
    }

    //endregion

    public void clear() {
        typed.clear();
        raw.clear();
    }
}
