package lib.kasuga.rendering.effect.pipeline;

import net.minecraft.client.renderer.RenderStateShard;

public enum PipelineLayering {
    NONE(RenderStateShard.NO_LAYERING),
    POLYGON_OFFSET(RenderStateShard.POLYGON_OFFSET_LAYERING),
    VIEW_OFFSET_Z(RenderStateShard.VIEW_OFFSET_Z_LAYERING);

    private final RenderStateShard.LayeringStateShard state;

    PipelineLayering(RenderStateShard.LayeringStateShard state) {
        this.state = state;
    }

    public RenderStateShard.LayeringStateShard state() {
        return state;
    }
}
