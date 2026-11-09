package lib.kasuga.rendering.models.uml.dynamic.proxy;

/** Rollback handle for a partially applied element writeback. */
@FunctionalInterface
public interface ProxyWritebackCheckpoint {

    ProxyWritebackCheckpoint NONE = () -> {
    };

    void rollback();
}
