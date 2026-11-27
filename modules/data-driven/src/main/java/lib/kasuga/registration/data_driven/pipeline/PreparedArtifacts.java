package lib.kasuga.registration.data_driven.pipeline;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, typed objects compiled during preparation and reused during apply.
 *
 * <p>Artifacts must not retain open sources, worlds, GPU objects, or other live host state.
 */
public final class PreparedArtifacts {
    private static final PreparedArtifacts EMPTY = new PreparedArtifacts(Map.of());

    private final Map<Class<?>, Object> values;

    public PreparedArtifacts(Map<Class<?>, ?> values) {
        Objects.requireNonNull(values, "values");
        Map<Class<?>, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<Class<?>, ?> entry : values.entrySet()) {
            Class<?> type = Objects.requireNonNull(entry.getKey(), "artifact type");
            Object value = Objects.requireNonNull(entry.getValue(), "artifact value");
            if (!type.isInstance(value)) {
                throw new IllegalArgumentException(
                        "Artifact " + value.getClass().getName() + " is not assignable to "
                                + type.getName());
            }
            if (copy.putIfAbsent(type, value) != null) {
                throw new IllegalArgumentException("Duplicate artifact type " + type.getName());
            }
        }
        this.values = Collections.unmodifiableMap(copy);
    }

    public static PreparedArtifacts empty() {
        return EMPTY;
    }

    public <T> T get(Class<T> type) {
        Objects.requireNonNull(type, "type");
        return type.cast(values.get(type));
    }

    public boolean contains(Class<?> type) {
        return values.containsKey(type);
    }

    public Map<Class<?>, Object> asMap() {
        return values;
    }
}
