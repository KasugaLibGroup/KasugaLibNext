package test.kasuga.modelling.demo.nebula;

import lib.kasuga.rendering.effect.post.PostProcessTargetDescriptor;
import lib.kasuga.rendering.effect.post.graph.*;
import net.minecraft.resources.ResourceLocation;

/** Declarative graph, shared by the running demo and its topology contract tests. */
final class NebulaPipeline {
    static final PostProcessGraphTarget GBUFFER = PostProcessGraphTarget.managed(id("starship_gbuffer"));
    static final PostProcessGraphTarget VOLUME = PostProcessGraphTarget.managed(id("nebula_volume_target"));

    static PostProcessGraph create(PostProcessGraphPrepare prepare, PostProcessGraphPass geometry,
                                    PostProcessGraphPass volume) {
        return PostProcessGraph.builder(id("nebula_graph"))
                .target(PostProcessTargetDescriptor.builder(GBUFFER.id()).screenScale(1).useDepth(true)
                        .colorAttachments(PostProcessTargetDescriptor.ColorFormat.RGBA16F,
                                PostProcessTargetDescriptor.ColorFormat.RGBA16F,
                                PostProcessTargetDescriptor.ColorFormat.RGBA16F).build())
                .target(PostProcessTargetDescriptor.builder(VOLUME.id()).screenScale(1)
                        .filter(PostProcessTargetDescriptor.TextureFilter.LINEAR).build())
                .prepare(prepare)
                .pass(PostProcessGraphPassDescriptor.builder(id("starship_geometry_pass"), geometry)
                        .writes(GBUFFER).build())
                .pass(PostProcessGraphPassDescriptor.builder(id("nebula_march"), volume)
                        .reads(GBUFFER).writes(VOLUME).build())
                .pass(PostProcessGraphPassDescriptor.builder(id("nebula_present"), context ->
                                context.copyColor(VOLUME, PostProcessGraphTarget.main(), false))
                        .reads(VOLUME).writesMain().build())
                .build();
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("kasuga_demo", path);
    }
}
