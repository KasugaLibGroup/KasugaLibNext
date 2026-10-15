package lib.kasuga.rendering.effect.post.graph;

@FunctionalInterface
public interface PostProcessGraphPass {
    void execute(PostProcessGraphContext context);
}
