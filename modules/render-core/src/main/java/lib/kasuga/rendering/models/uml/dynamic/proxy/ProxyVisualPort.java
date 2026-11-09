package lib.kasuga.rendering.models.uml.dynamic.proxy;

import lib.kasuga.rendering.models.uml.dynamic.animation.scope.AnimationRenderPort;

/**
 * Visual subtree owned by one composition. The logical scope remains the owner of revision and
 * logical time; this port only binds targets, publishes frame-domain inputs and releases visuals.
 */
public interface ProxyVisualPort<T, F> extends AutoCloseable {

    boolean isReady(T element);

    void bind(AnimationRenderPort logicalRenderPort);

    void publishFrame(F frame);

    /** Runs one visual maintenance pass for the same logical step, never a second clock. */
    default void tick(T element) {
    }

    default void suspend() {
    }

    default void resume() {
    }

    /**
     * Builds or adopts a fresh visual subtree. Implementations must keep the old subtree on
     * failure and return whether the replacement became ready.
     */
    default boolean rebind(T element) {
        return isReady(element);
    }

    @Override
    default void close() {
    }

    static <T, F> ProxyVisualPort<T, F> none() {
        return new ProxyVisualPort<>() {
            @Override
            public boolean isReady(T element) {
                return true;
            }

            @Override
            public void bind(AnimationRenderPort logicalRenderPort) {
            }

            @Override
            public void publishFrame(F frame) {
            }
        };
    }
}
