package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationTimeline;

/** Small shared lifecycle and time-advance operations for target-specific Scope adapters. */
public final class ScopedPlaybackSupport {

    private ScopedPlaybackSupport() {}

    public static boolean advance(ManagedAnimationScope scope, float deltaSeconds) {
        ScopeAdvanceResult result = scope.advance(
                new ScopeAdvance(deltaSeconds, ScopeAdvanceCause.GAME_TICK));
        return !result.aborted();
    }

    public static boolean isDiscontinuity(
            AnimationTimeline.Snapshot previous,
            AnimationTimeline.Snapshot current
    ) {
        return previous == null
                || current.previous() == current.seconds()
                || previous.seconds() != current.previous();
    }

    public static double retainedStart(AnimationTimeline.Snapshot snapshot) {
        return snapshot.loop()
                ? snapshot.previous() - snapshot.duration()
                : snapshot.previous();
    }

    public static float validatedDuration(float duration) {
        if (!Float.isFinite(duration)) {
            throw new IllegalArgumentException("Animation duration must be finite");
        }
        return Math.max(0.0f, duration);
    }

    public static void close(ManagedAnimationScope scope) {
        if (scope != null) {
            scope.close();
        }
    }
}
