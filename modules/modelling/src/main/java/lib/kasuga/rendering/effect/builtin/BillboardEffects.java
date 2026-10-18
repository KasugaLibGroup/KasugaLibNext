package lib.kasuga.rendering.effect.builtin;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.effect.EffectRenderPipeline;
import lib.kasuga.rendering.effect.RenderPipelineRegistrar;
import lib.kasuga.rendering.effect.pipeline.*;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.resources.ResourceLocation;

/** Built-in camera-facing effect pipelines. */
public final class BillboardEffects {
    private static EffectRenderPipeline<BillboardEffect> translucent;
    private static EffectRenderPipeline<BillboardEffect> additive;

    private BillboardEffects() {}

    public static synchronized void initialize(RenderPipelineRegistrar registrar) {
        if (translucent != null) return;
        java.util.Objects.requireNonNull(registrar, "registrar");
        RenderPipelineDescriptor translucentDescriptor = billboardDescriptor(
                ResourceLocation.fromNamespaceAndPath(KasugaLib.MODID, "effects/billboard_translucent"),
                0,
                PipelineBlendMode.TRANSLUCENT
        );
        RenderPipelineDescriptor additiveDescriptor = billboardDescriptor(
                ResourceLocation.fromNamespaceAndPath(KasugaLib.MODID, "effects/billboard_additive"),
                100,
                PipelineBlendMode.ADDITIVE
        );
        translucent = registrar.effects(
                translucentDescriptor, true, new BillboardEffectRenderer()
        );
        additive = registrar.effects(
                additiveDescriptor, false, new BillboardEffectRenderer()
        );
    }

    public static BillboardEffect spawn(BillboardBlendMode blendMode, BillboardEffect effect) {
        if (translucent == null || additive == null) {
            throw new IllegalStateException("Billboard pipelines have not been registered");
        }
        switch (blendMode) {
            case TRANSLUCENT -> translucent.spawn(effect);
            case ADDITIVE -> additive.spawn(effect);
        }
        return effect;
    }

    public static void clear() {
        if (translucent != null) translucent.clear();
        if (additive != null) additive.clear();
    }

    private static RenderPipelineDescriptor billboardDescriptor(ResourceLocation id, int priority,
                                                                PipelineBlendMode blendMode) {
        return RenderPipelineDescriptor.builder(id, RenderPhase.AFTER_PARTICLES)
                .priority(priority)
                .draw(draw -> draw
                        .vertexFormat(DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP)
                        .primitiveMode(VertexFormat.Mode.QUADS)
                        .bufferSize(1536)
                        .sortOnUpload(true)
                        .shaderState(RenderStateShard.POSITION_COLOR_TEX_LIGHTMAP_SHADER)
                        .blend(blendMode)
                        .depthTest(PipelineDepthTest.LEQUAL)
                        .cull(PipelineCullMode.DISABLED)
                        .writeMask(PipelineWriteMask.COLOR)
                        .target(PipelineTarget.PARTICLES))
                .build();
    }
}
