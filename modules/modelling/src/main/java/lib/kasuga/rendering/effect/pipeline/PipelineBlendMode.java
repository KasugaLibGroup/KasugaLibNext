package lib.kasuga.rendering.effect.pipeline;

import net.minecraft.client.renderer.RenderStateShard;

public enum PipelineBlendMode {
    NONE(RenderStateShard.NO_TRANSPARENCY),
    TRANSLUCENT(RenderStateShard.TRANSLUCENT_TRANSPARENCY),
    ADDITIVE(RenderStateShard.ADDITIVE_TRANSPARENCY),
    LIGHTNING(RenderStateShard.LIGHTNING_TRANSPARENCY),
    GLINT(RenderStateShard.GLINT_TRANSPARENCY),
    CRUMBLING(RenderStateShard.CRUMBLING_TRANSPARENCY);

    private final RenderStateShard.TransparencyStateShard state;

    PipelineBlendMode(RenderStateShard.TransparencyStateShard state) {
        this.state = state;
    }

    public RenderStateShard.TransparencyStateShard state() {
        return state;
    }
}
