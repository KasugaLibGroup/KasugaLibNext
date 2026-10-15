package lib.kasuga.rendering.effect.particle;

import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Updates any number of instances from one stable group snapshot. This is the preferred path for
 * uniform fields such as smoke flow, rain, snow and camera-relative weather volumes.
 */
@FunctionalInterface
public interface ParticleGroupBehavior {
    ParticleGroupBehavior NONE = (group, updates, level) -> {};

    void update(
            ParticleGroupSnapshot group,
            UpdateSink updates,
            ClientLevel level
    );

    @FunctionalInterface
    interface UpdateSink {
        void submit(long instanceId, ParticleUpdate update);
    }
}
