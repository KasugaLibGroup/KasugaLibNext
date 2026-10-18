package lib.kasuga.rendering.effect.particle;

import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Allocation-free group update path over reusable packed direct buffers.
 *
 * <p>{@code next} initially contains an exact copy of {@code current}. Controllers mutate or remove
 * entries in {@code next}; the group commits it after the callback returns.</p>
 */
@FunctionalInterface
public interface ParticleBufferGroupBehavior {
    void update(
            ParticleInstanceBuffer current,
            ParticleInstanceBuffer next,
            ClientLevel level
    );
}
