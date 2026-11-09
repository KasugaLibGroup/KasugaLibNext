package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

/** Mutable clock view shared by logical evaluators and their external-timeline observer. */
public final class TimelineClock {

    private volatile float duration;
    private volatile boolean loop;

    public TimelineClock(float duration, boolean loop) {
        configure(duration, loop);
    }

    public void configure(float nextDuration, boolean nextLoop) {
        if (!Float.isFinite(nextDuration) || nextDuration < 0.0f) {
            throw new IllegalArgumentException("duration must be finite and non-negative");
        }
        duration = nextDuration;
        loop = nextLoop;
    }

    public float duration() {
        return duration;
    }

    public boolean loop() {
        return loop;
    }
}
