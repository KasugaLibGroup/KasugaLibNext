package lib.kasuga.rendering.models.uml.dynamic.multiplexer;

/**
 * Per-instance runtime state for a {@link Multiplexer}, held EXTERNALLY by the owner.
 */
public final class MuxState<V extends Variant<V>> {

    private V current;
    private V transitionFrom;
    private V transitionTo;
    private float elapsed;
    private float duration;

    public MuxState(V initial) {
        this.current = initial;
    }

    /** The settled variant (the {@code from} side while a transition is in progress). */
    public V current() {
        return current;
    }

    public boolean inTransition() {
        return transitionTo != null;
    }

    /** Variant being blended away from during a transition (== {@link #current()} when settled). */
    public V from() {
        return transitionFrom != null ? transitionFrom : current;
    }

    /** Variant being blended toward during a transition (== {@link #current()} when settled). */
    public V to() {
        return transitionTo != null ? transitionTo : current;
    }

    /** Cross-fade progress 0..1 (1 when settled). */
    public float alpha() {
        return duration <= 0f ? 1f : Math.min(1f, elapsed / duration);
    }

    void startTransition(V from, V to, float durationSeconds) {
        this.transitionFrom = from;
        this.transitionTo = to;
        this.duration = durationSeconds;
        this.elapsed = 0f;
    }

    void advance(float dt) {
        this.elapsed += dt;
    }

    boolean transitionDone() {
        return duration <= 0f || elapsed >= duration;
    }

    void commitTransition() {
        this.current = transitionTo;
        this.transitionFrom = null;
        this.transitionTo = null;
        this.elapsed = 0f;
        this.duration = 0f;
    }

    /** Instant switch (used when a transition's cross-fade is <= 0). */
    void setCurrentInstant(V variant) {
        this.current = variant;
    }
}
