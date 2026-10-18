package lib.kasuga.rendering.effect.pipeline;

import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Stable semantic names for the standard NeoForge world render stages. */
public enum RenderPhase {
    AFTER_SKY(RenderLevelStageEvent.Stage.AFTER_SKY),
    AFTER_SOLID_BLOCKS(RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS),
    AFTER_CUTOUT_MIPPED_BLOCKS(RenderLevelStageEvent.Stage.AFTER_CUTOUT_MIPPED_BLOCKS_BLOCKS),
    AFTER_CUTOUT_BLOCKS(RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS),
    AFTER_ENTITIES(RenderLevelStageEvent.Stage.AFTER_ENTITIES),
    AFTER_BLOCK_ENTITIES(RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES),
    AFTER_TRANSLUCENT_BLOCKS(RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS),
    AFTER_TRIPWIRE_BLOCKS(RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS),
    AFTER_PARTICLES(RenderLevelStageEvent.Stage.AFTER_PARTICLES),
    AFTER_WEATHER(RenderLevelStageEvent.Stage.AFTER_WEATHER),
    /** Full-screen and scene-color processing after the world has completed rendering. */
    POST_PROCESS(RenderLevelStageEvent.Stage.AFTER_LEVEL),
    AFTER_LEVEL(RenderLevelStageEvent.Stage.AFTER_LEVEL);

    private final RenderLevelStageEvent.Stage nativeStage;

    RenderPhase(RenderLevelStageEvent.Stage nativeStage) {
        this.nativeStage = nativeStage;
    }

    public RenderLevelStageEvent.Stage nativeStage() {
        return nativeStage;
    }
}
