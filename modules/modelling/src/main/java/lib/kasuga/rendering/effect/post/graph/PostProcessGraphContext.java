package lib.kasuga.rendering.effect.post.graph;

import com.mojang.blaze3d.pipeline.RenderTarget;
import lib.kasuga.rendering.effect.WorldRenderPipelineContext;
import lib.kasuga.rendering.effect.post.FullscreenPassBuilder;
import lib.kasuga.rendering.effect.post.PostProcessContext;
import lib.kasuga.rendering.effect.shader.RenderShaderHandle;
import net.minecraft.client.renderer.ShaderInstance;

import java.util.Map;
import java.util.Objects;

/** Runtime view restricted to the resources declared by the active graph pass. */
public final class PostProcessGraphContext {
    private final PostProcessContext post;
    private final PostProcessGraphPassDescriptor pass;
    private final Map<PostProcessGraphTarget, RenderTarget> targets;
    private final PostProcessGraphFrame frame;

    PostProcessGraphContext(PostProcessContext post, PostProcessGraphPassDescriptor pass,
                            Map<PostProcessGraphTarget, RenderTarget> targets,
                            PostProcessGraphFrame frame) {
        this.post = Objects.requireNonNull(post, "post");
        this.pass = Objects.requireNonNull(pass, "pass");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.frame = Objects.requireNonNull(frame, "frame");
    }

    public PostProcessGraphPassDescriptor pass() {
        return pass;
    }

    public WorldRenderPipelineContext world() {
        return post.world();
    }

    public PostProcessGraphFrame frame() {
        return frame;
    }

    public RenderTarget read(PostProcessGraphTarget target) {
        Objects.requireNonNull(target, "target");
        if (!pass.reads().contains(target)) {
            throw new IllegalStateException("Pass " + pass.id() + " did not declare read access to " + target);
        }
        return resolve(target);
    }

    public RenderTarget write(PostProcessGraphTarget target) {
        Objects.requireNonNull(target, "target");
        if (!pass.writes().contains(target)) {
            throw new IllegalStateException("Pass " + pass.id() + " did not declare write access to " + target);
        }
        return resolve(target);
    }

    public void copyColor(PostProcessGraphTarget source, PostProcessGraphTarget destination,
                          boolean linearFilter) {
        post.copyColor(read(source), write(destination), linearFilter);
    }

    public void copyDepth(PostProcessGraphTarget source, PostProcessGraphTarget destination) {
        post.copyDepth(read(source), write(destination));
    }

    public FullscreenPassBuilder fullscreen(PostProcessGraphTarget output, ShaderInstance shader) {
        return post.fullscreen(write(output), shader);
    }

    public FullscreenPassBuilder fullscreen(PostProcessGraphTarget output, RenderShaderHandle shader) {
        return post.fullscreen(write(output), shader);
    }

    /** Raw escape hatch for integrations that need APIs not covered by this graph context. */
    public PostProcessContext raw() {
        return post;
    }

    private RenderTarget resolve(PostProcessGraphTarget target) {
        RenderTarget resolved = targets.get(target);
        if (resolved == null) throw new IllegalStateException("Graph target is not allocated: " + target);
        return resolved;
    }
}
