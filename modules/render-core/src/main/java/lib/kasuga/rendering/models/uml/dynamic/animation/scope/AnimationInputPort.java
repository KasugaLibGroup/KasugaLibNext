package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.Objects;

/** Narrow write port used by platform adapters and scripts to feed a scope. */
public interface AnimationInputPort {

    <T> void write(InputKey<T> key, T value);

    void trigger(InputTriggerKey key);

    default <T> void writeIfPresent(InputKey<T> key, T value) {
        Objects.requireNonNull(value, "value");
        write(key, value);
    }
}
