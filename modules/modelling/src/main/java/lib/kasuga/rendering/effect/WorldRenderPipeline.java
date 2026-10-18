package lib.kasuga.rendering.effect;

/**
 * A render-thread callback invoked at a registered world render stage.
 *
 * <p>Implementations may submit buffered geometry or perform immediate rendering. Any buffer
 * batch opened by an implementation must be ended by that implementation before returning.</p>
 */
@FunctionalInterface
public interface WorldRenderPipeline {

    void render(WorldRenderPipelineContext context);
}
