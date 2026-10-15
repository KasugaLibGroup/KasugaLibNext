package lib.kasuga.rendering.effect.post;

@FunctionalInterface
public interface PostProcessPass {
    void process(PostProcessContext context);
}
