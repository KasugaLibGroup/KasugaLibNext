package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

/** Reversible side effects created by one controller while evaluating a logical step. */
@FunctionalInterface
public interface ControllerCheckpoint {

    ControllerCheckpoint NONE = () -> {
    };

    void rollback();

    /** Applies side effects that must remain after the whole logical step commits. */
    default void commit() {
    }
}
