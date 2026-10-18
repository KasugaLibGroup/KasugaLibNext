package lib.kasuga.rendering.effect;

import lib.kasuga.rendering.effect.pipeline.CompiledRenderPipeline;
import lib.kasuga.rendering.effect.pipeline.RenderPipelineDescriptor;

/** Registration of one scheduled world or post-processing callback. */
public interface PipelineRegistration extends RenderRegistration {
    RenderPipelineDescriptor descriptor();

    CompiledRenderPipeline compiledPipeline();
}
