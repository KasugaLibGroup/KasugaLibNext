package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Owns persistent inputs and one-shot triggers, and freezes them at a scope commit boundary.
 *
 * <p>Triggers are removed only after the snapshot that observed them commits. A failed transaction
 * can therefore retry with the same trigger still present.
 */
public final class ScopeInputStore {

    private final Map<InputKey<?>, Object> values = new HashMap<>();
    private final Set<InputTriggerKey> triggers = new HashSet<>();
    private final AnimationInputPort inputPort = new AnimationInputPort() {
        @Override
        public <T> void write(InputKey<T> key, T value) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
            synchronized (ScopeInputStore.this) {
                values.put(key, value);
            }
        }

        @Override
        public void trigger(InputTriggerKey key) {
            Objects.requireNonNull(key, "key");
            synchronized (ScopeInputStore.this) {
                triggers.add(key);
            }
        }
    };

    public AnimationInputPort inputPort() {
        return inputPort;
    }

    public synchronized ScopeInputSnapshot freeze() {
        return new ScopeInputSnapshot(values, triggers);
    }

    public synchronized void commit(ScopeInputSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        triggers.removeIf(snapshot::triggered);
    }
}
