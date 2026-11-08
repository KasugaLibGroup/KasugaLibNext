package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

/**
 * Facade for one animation authority scope.
 *
 * <p>The scope owns logical time, lifecycle and revision semantics. It does not own render
 * scheduling, platform clocks or concrete model writes.
 */
public interface AnimationScope extends AutoCloseable {

    ScopeAdvanceResult advance(ScopeAdvance advance);

    AnimationInputPort inputPort();

    PlaybackControlPort playbackPort();

    ScopeView view();

    ScopeAdvanceResult pause();

    ScopeAdvanceResult resume();

    ScopeAdvanceResult seek(double scopeTime);

    boolean isPaused();

    boolean isClosed();

    @Override
    void close();
}
