package lib.kasuga.rendering.effect;

import lib.kasuga.rendering.effect.pipeline.RenderPipelineDescriptor;
import lib.kasuga.rendering.effect.pipeline.RenderPhase;
import lib.kasuga.rendering.effect.particle.ParticleBatchRenderer;
import lib.kasuga.rendering.effect.particle.ParticleRenderPipeline;
import lib.kasuga.rendering.effect.post.PostProcessPass;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraph;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraphRegistration;
import lib.kasuga.rendering.effect.shader.RenderShaderDescriptor;
import lib.kasuga.rendering.effect.shader.ShaderLoadListener;
import lib.kasuga.rendering.effect.shader.ShaderRegistration;
import lib.kasuga.shader.ShaderProgram;
import net.minecraft.resources.ResourceLocation;

/** Shared registration surface used by static mod setup, dynamic content and scripting bridges. */
public interface RenderPipelineRegistrar {
    ResourceLocation owner();

    ShaderRegistration shader(
            RenderShaderDescriptor descriptor,
            DuplicatePolicy duplicatePolicy,
            ShaderLoadListener listener
    );

    default ShaderRegistration shader(RenderShaderDescriptor descriptor) {
        return shader(descriptor, DuplicatePolicy.FAIL, ShaderLoadListener.NONE);
    }

    default ShaderRegistration shader(RenderShaderDescriptor descriptor, DuplicatePolicy duplicatePolicy) {
        return shader(descriptor, duplicatePolicy, ShaderLoadListener.NONE);
    }

    default ShaderRegistration shader(ShaderProgram program) {
        return shader(RenderShaderDescriptor.generated(program));
    }

    default ShaderRegistration shader(ShaderProgram program, DuplicatePolicy duplicatePolicy) {
        return shader(RenderShaderDescriptor.generated(program), duplicatePolicy);
    }

    PipelineRegistration world(
            RenderPipelineDescriptor descriptor,
            DuplicatePolicy duplicatePolicy,
            WorldRenderPipeline pipeline
    );

    default PipelineRegistration world(
            RenderPipelineDescriptor descriptor,
            WorldRenderPipeline pipeline
    ) {
        return world(descriptor, DuplicatePolicy.FAIL, pipeline);
    }

    PipelineRegistration postProcess(
            RenderPipelineDescriptor descriptor,
            DuplicatePolicy duplicatePolicy,
            PostProcessPass pass
    );

    default PipelineRegistration postProcess(
            RenderPipelineDescriptor descriptor,
            PostProcessPass pass
    ) {
        return postProcess(descriptor, DuplicatePolicy.FAIL, pass);
    }

    PostProcessGraphRegistration graph(
            RenderPipelineDescriptor descriptor,
            DuplicatePolicy duplicatePolicy,
            PostProcessGraph graph
    );

    default PostProcessGraphRegistration graph(
            RenderPipelineDescriptor descriptor,
            PostProcessGraph graph
    ) {
        return graph(descriptor, DuplicatePolicy.FAIL, graph);
    }

    default PostProcessGraphRegistration graph(PostProcessGraph graph, int priority) {
        RenderPipelineDescriptor descriptor = RenderPipelineDescriptor.builder(
                        graph.id(), RenderPhase.POST_PROCESS
                )
                .priority(priority)
                .build();
        return graph(descriptor, graph);
    }

    <T extends RenderEffect> EffectRenderPipeline<T> effects(
            RenderPipelineDescriptor descriptor,
            DuplicatePolicy duplicatePolicy,
            boolean sortBackToFront,
            EffectRenderer<T> renderer
    );

    default <T extends RenderEffect> EffectRenderPipeline<T> effects(
            RenderPipelineDescriptor descriptor,
            boolean sortBackToFront,
            EffectRenderer<T> renderer
    ) {
        return effects(descriptor, DuplicatePolicy.FAIL, sortBackToFront, renderer);
    }

    ParticleRenderPipeline particles(
            RenderPipelineDescriptor descriptor,
            DuplicatePolicy duplicatePolicy,
            ParticleBatchRenderer renderer
    );

    default ParticleRenderPipeline particles(
            RenderPipelineDescriptor descriptor,
            ParticleBatchRenderer renderer
    ) {
        return particles(descriptor, DuplicatePolicy.FAIL, renderer);
    }
}
