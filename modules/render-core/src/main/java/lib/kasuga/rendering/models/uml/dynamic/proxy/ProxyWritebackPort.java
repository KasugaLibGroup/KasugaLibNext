package lib.kasuga.rendering.models.uml.dynamic.proxy;

import java.util.Objects;

/** Writes only the outputs explicitly declared by one composition. */
@FunctionalInterface
public interface ProxyWritebackPort<T, O> {

    ProxyWritebackCheckpoint NONE = () -> {
    };

    void writeback(T element, O output);

    /** Captures reversible side effects before writeback starts. */
    default ProxyWritebackCheckpoint beginWriteback(T element) {
        Objects.requireNonNull(element, "element");
        return NONE;
    }
}
