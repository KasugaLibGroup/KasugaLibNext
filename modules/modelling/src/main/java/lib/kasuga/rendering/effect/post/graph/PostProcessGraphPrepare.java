package lib.kasuga.rendering.effect.post.graph;

import lib.kasuga.rendering.effect.post.PostProcessContext;

/** Builds per-frame graph state and returns false to skip allocation and every pass. */
@FunctionalInterface
public interface PostProcessGraphPrepare {
    PostProcessGraphPrepare ALWAYS = (context, frame) -> true;

    boolean prepare(PostProcessContext context, PostProcessGraphFrame frame);
}
