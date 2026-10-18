package lib.kasuga.rendering.effect.post;

import lib.kasuga.rendering.effect.DuplicatePolicy;
import lib.kasuga.rendering.effect.PipelineRegistration;
import lib.kasuga.rendering.effect.WorldRenderPipelineRegistry;
import lib.kasuga.rendering.effect.pipeline.RenderPhase;
import lib.kasuga.rendering.effect.pipeline.RenderPipelineDescriptor;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** Standard entry point for passes running in the semantic POST_PROCESS stage. */
public final class PostProcessPipelineRegistry {
    private PostProcessPipelineRegistry() {}

    public static PipelineRegistration register(
            ResourceLocation owner,
            RenderPipelineDescriptor descriptor,
            DuplicatePolicy duplicatePolicy,
            PostProcessPass pass
    ) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(duplicatePolicy, "duplicatePolicy");
        Objects.requireNonNull(pass, "pass");
        if (descriptor.phase().orElse(null) != RenderPhase.POST_PROCESS) {
            throw new IllegalArgumentException("Post-process pipelines must use RenderPhase.POST_PROCESS");
        }
        return WorldRenderPipelineRegistry.register(owner, descriptor, duplicatePolicy, world -> {
            PostProcessContext context = new PostProcessContext(
                    world, PostProcessTargetPool.getInstance()
            );
            try {
                pass.process(context);
            } finally {
                context.restoreMainTarget();
            }
        });
    }
}
