package lib.kasuga.rendering.effect.pipeline;

import net.minecraft.client.renderer.RenderStateShard;

public enum PipelineTarget {
    MAIN(RenderStateShard.MAIN_TARGET),
    OUTLINE(RenderStateShard.OUTLINE_TARGET),
    TRANSLUCENT(RenderStateShard.TRANSLUCENT_TARGET),
    PARTICLES(RenderStateShard.PARTICLES_TARGET),
    WEATHER(RenderStateShard.WEATHER_TARGET),
    CLOUDS(RenderStateShard.CLOUDS_TARGET),
    ITEM_ENTITY(RenderStateShard.ITEM_ENTITY_TARGET);

    private final RenderStateShard.OutputStateShard state;

    PipelineTarget(RenderStateShard.OutputStateShard state) {
        this.state = state;
    }

    public RenderStateShard.OutputStateShard state() {
        return state;
    }
}
