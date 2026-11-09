package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

/**
 * One runtime endpoint of an animation scope.
 *
 * <p>A target consumes immutable output for one stable address. It does not advance logical time,
 * resolve animation sources, evaluate other targets, or write another target's slot.
 *
 * @param <O> immutable, target-specific output type
 */
public interface AnimationTarget<O> extends AutoCloseable {

    AnimationTargetRef ref();

    /** Receives the output state for one committed logical revision. */
    void publish(TargetPublication<O> publication);

    /**
     * Writes this target's slot for one render frame.
     *
     * <p>The model-level render entry point owns invocation order. A target may only read its
     * latest publication and must not advance logical time or submit playback commands.
     */
    default void sample(RenderSampleContext context) {
    }

    /**
     * Writes this target from one completed publication batch.
     *
     * <p>All targets in a batch receive the same immutable revision even if a newer logical step is
     * being evaluated concurrently. Implementations that need result interpolation can override this
     * method and use the supplied publication directly.
     */
    void sample(TargetPublication<O> publication, RenderSampleContext context);

    /** Detaches or releases this target's runtime references. Must be idempotent. */
    @Override
    default void close() {
    }
}
