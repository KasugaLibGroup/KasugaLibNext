package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, type-keyed set of values for one render frame.
 */
public final class FrameInputSnapshot {

    private static final FrameInputSnapshot EMPTY = new FrameInputSnapshot(Map.of());

    private final Map<FrameInputKey<?>, Object> values;

    private FrameInputSnapshot(Map<FrameInputKey<?>, Object> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public static FrameInputSnapshot empty() {
        return EMPTY;
    }

    public <T> FrameInputSnapshot with(FrameInputKey<T> key, T value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        key.type().cast(value);
        Map<FrameInputKey<?>, Object> updated = new LinkedHashMap<>(values);
        updated.put(key, value);
        return new FrameInputSnapshot(updated);
    }

    public <T> Optional<T> value(FrameInputKey<T> key) {
        Objects.requireNonNull(key, "key");
        Object value = values.get(key);
        return value == null ? Optional.empty() : Optional.of(key.type().cast(value));
    }
}
