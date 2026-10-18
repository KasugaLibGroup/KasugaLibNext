package lib.kasuga.rendering.effect;

/** GPU-side renderer shared by every instance in an effect pipeline. */
public interface EffectRenderer<T extends RenderEffect> {

    default void begin(WorldRenderPipelineContext context) {}

    void render(T effect, WorldRenderPipelineContext context);

    default void end(WorldRenderPipelineContext context) {}
}
