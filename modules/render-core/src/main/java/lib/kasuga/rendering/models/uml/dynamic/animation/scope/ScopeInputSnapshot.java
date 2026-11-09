package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Immutable input and trigger state read by controllers during one logical step. */
public final class ScopeInputSnapshot {

    private final Map<InputKey<?>, Object> values;
    private final Set<InputTriggerKey> triggers;

    ScopeInputSnapshot(Map<InputKey<?>, Object> values, Set<InputTriggerKey> triggers) {
        this.values = Map.copyOf(values);
        this.triggers = Set.copyOf(triggers);
    }

    public <T> Optional<T> value(InputKey<T> key) {
        Object value = values.get(key);
        return value == null ? Optional.empty() : Optional.of(key.type().cast(value));
    }

    public boolean triggered(InputTriggerKey key) {
        return triggers.contains(key);
    }
}
