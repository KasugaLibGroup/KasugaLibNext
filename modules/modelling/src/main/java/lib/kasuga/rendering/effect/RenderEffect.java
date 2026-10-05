package lib.kasuga.rendering.effect;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** A client-side effect instance managed by an {@link EffectRenderPipeline}. */
public interface RenderEffect {

    void tick(ClientLevel level);

    boolean isAlive();

    Vec3 position(float partialTick);

    /** Override to avoid allocating an interpolated position solely for back-to-front sorting. */
    default double distanceToSqr(float partialTick, Vec3 observer) {
        return position(partialTick).distanceToSqr(observer);
    }

    AABB bounds(float partialTick);

    /** Override for sky volumes that extend beyond the terrain far plane. */
    default boolean isVisible(WorldRenderPipelineContext context, float partialTick) {
        return context.frustum() == null || context.frustum().isVisible(bounds(partialTick));
    }
}
