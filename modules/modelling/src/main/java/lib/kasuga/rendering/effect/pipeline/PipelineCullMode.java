package lib.kasuga.rendering.effect.pipeline;

import net.minecraft.client.renderer.RenderStateShard;

public enum PipelineCullMode {
    ENABLED(RenderStateShard.CULL),
    DISABLED(RenderStateShard.NO_CULL);

    private final RenderStateShard.CullStateShard state;

    PipelineCullMode(RenderStateShard.CullStateShard state) {
        this.state = state;
    }

    public RenderStateShard.CullStateShard state() {
        return state;
    }
}
