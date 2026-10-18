package lib.kasuga.rendering.effect.pipeline;

import net.minecraft.client.renderer.RenderStateShard;

public enum PipelineWriteMask {
    COLOR_AND_DEPTH(RenderStateShard.COLOR_DEPTH_WRITE),
    COLOR(RenderStateShard.COLOR_WRITE),
    DEPTH(RenderStateShard.DEPTH_WRITE);

    private final RenderStateShard.WriteMaskStateShard state;

    PipelineWriteMask(RenderStateShard.WriteMaskStateShard state) {
        this.state = state;
    }

    public RenderStateShard.WriteMaskStateShard state() {
        return state;
    }
}
