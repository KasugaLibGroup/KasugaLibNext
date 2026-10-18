package lib.kasuga.rendering.effect.particle;

import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Optional per-instance behavior. All behaviors in a group read the same pre-update snapshot;
 * returned updates are committed only after every behavior has completed.
 */
@FunctionalInterface
public interface ParticleBehavior {
    ParticleUpdate update(
            ParticleSnapshot particle,
            ParticleGroupSnapshot group,
            ClientLevel level
    );
}
