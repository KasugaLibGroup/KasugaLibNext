package lib.kasuga.rendering.models.mc.dynamic.fsm.cap;

import lib.kasuga.rendering.models.mc.dynamic.fsm.AnimationHost;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.ICapabilityProvider;

/** {@code ICapabilityProvider} for {@link AnimationCapabilities#MACHINE_BLOCK}: returns the BE when it implements {@link AnimationHost}. */
public final class BlockEntityAnimationProvider {

    public static final ICapabilityProvider<BlockEntity, Void, AnimationHost> INSTANCE =
            (blockEntity, context) -> blockEntity instanceof AnimationHost host ? host : null;

    private BlockEntityAnimationProvider() {}
}
