package lib.kasuga.rendering.effect;

/** Thread-safe lifecycle control for one managed effect instance. */
public interface EffectHandle<T extends RenderEffect> {
    T effect();

    boolean isActive();

    /** Marks the instance for removal before the next tick or render traversal. */
    boolean remove();
}
