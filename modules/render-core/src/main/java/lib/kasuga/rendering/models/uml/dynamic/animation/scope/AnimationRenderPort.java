package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

/**
 * Narrow render-phase port for one committed animation target batch.
 *
 * <p>The platform chooses when to sample; the animation scope only exposes the latest completed
 * batch. Implementations must not advance logical time or create a new revision.
 */
@FunctionalInterface
public interface AnimationRenderPort {

    void sample(RenderSampleContext context);
}
