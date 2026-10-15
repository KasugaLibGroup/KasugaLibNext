package lib.kasuga.rendering.effect.post.graph;

import lib.kasuga.rendering.effect.PipelineRegistration;

/** Owned registration of one validated post-processing graph. */
public interface PostProcessGraphRegistration extends PipelineRegistration {
    PostProcessGraph graph();
}
