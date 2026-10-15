package lib.kasuga.rendering.effect.pipeline;

import net.minecraft.client.renderer.RenderStateShard;

public enum PipelineDepthTest {
    NONE(RenderStateShard.NO_DEPTH_TEST),
    EQUAL(RenderStateShard.EQUAL_DEPTH_TEST),
    LEQUAL(RenderStateShard.LEQUAL_DEPTH_TEST),
    GREATER(RenderStateShard.GREATER_DEPTH_TEST);

    private final RenderStateShard.DepthTestStateShard state;

    PipelineDepthTest(RenderStateShard.DepthTestStateShard state) {
        this.state = state;
    }

    public RenderStateShard.DepthTestStateShard state() {
        return state;
    }
}
