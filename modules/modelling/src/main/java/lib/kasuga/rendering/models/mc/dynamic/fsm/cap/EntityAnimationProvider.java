package lib.kasuga.rendering.models.mc.dynamic.fsm.cap;

import lib.kasuga.rendering.models.mc.dynamic.fsm.AnimationHost;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.capabilities.ICapabilityProvider;

/** {@code ICapabilityProvider} for {@link AnimationCapabilities#MACHINE_ENTITY}: returns the entity when it implements {@link AnimationHost}. */
public final class EntityAnimationProvider {

    public static final ICapabilityProvider<Entity, Void, AnimationHost> INSTANCE =
            (entity, context) -> entity instanceof AnimationHost host ? host : null;

    private EntityAnimationProvider() {}
}
