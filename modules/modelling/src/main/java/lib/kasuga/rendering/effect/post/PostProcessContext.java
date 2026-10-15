package lib.kasuga.rendering.effect.post;

import com.mojang.blaze3d.pipeline.RenderTarget;
import lib.kasuga.rendering.effect.WorldRenderPipelineContext;
import lib.kasuga.rendering.effect.shader.RenderShaderHandle;
import net.minecraft.client.renderer.ShaderInstance;

import java.util.Objects;

/** Restricted context for one descriptor-driven post-processing pass. */
public final class PostProcessContext {
    private final WorldRenderPipelineContext world;
    private final PostProcessTargetPool targets;

    PostProcessContext(WorldRenderPipelineContext world, PostProcessTargetPool targets) {
        this.world = Objects.requireNonNull(world, "world");
        this.targets = Objects.requireNonNull(targets, "targets");
    }

    public WorldRenderPipelineContext world() {
        return world;
    }

    public PostProcessTargetPool targets() {
        return targets;
    }

    public RenderTarget mainTarget() {
        return world.mainRenderTarget();
    }

    public RenderTarget acquire(PostProcessTargetDescriptor descriptor) {
        return targets.acquire(descriptor, mainTarget());
    }

    public RenderTarget captureSceneColor(PostProcessTargetDescriptor descriptor) {
        RenderTarget target = acquire(descriptor);
        boolean linear = descriptor.filter() == PostProcessTargetDescriptor.TextureFilter.LINEAR;
        targets.copyColor(mainTarget(), target, linear);
        return target;
    }

    public void copyColor(RenderTarget source, RenderTarget destination, boolean linearFilter) {
        targets.copyColor(source, destination, linearFilter);
    }

    public void copyDepth(RenderTarget source, RenderTarget destination) {
        targets.copyDepth(source, destination);
    }

    public void bindColorSampler(ShaderInstance shader, String sampler, RenderTarget target) {
        shader.setSampler(sampler, target.getColorTextureId());
    }

    public void bindDepthSampler(ShaderInstance shader, String sampler, RenderTarget target) {
        if (!target.useDepth) throw new IllegalArgumentException("Render target has no depth attachment");
        shader.setSampler(sampler, target.getDepthTextureId());
    }

    public FullscreenPassBuilder fullscreen(RenderTarget output, ShaderInstance shader) {
        return new FullscreenPassBuilder(output, shader);
    }

    /** Resolves the current shader generation, so callers survive F3+T resource reloads. */
    public FullscreenPassBuilder fullscreen(RenderTarget output, RenderShaderHandle shader) {
        RenderShaderHandle handle = Objects.requireNonNull(shader, "shader");
        return fullscreen(output, handle.require()).parameters(handle.parameters());
    }

    public void restoreMainTarget() {
        mainTarget().bindWrite(true);
    }
}
