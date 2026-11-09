package test.kasuga.modelling;

import lib.kasuga.registration.minecraft.block_entity.BlockEntityReg;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntityType;

/** Explicit registration entry points for the two fan fixture block entity types. */
public final class FanProxyDefinitions {

    private FanProxyDefinitions() {
    }

    public static BlockEntityReg<FanBlockEntity> registerWhenReady(
            BlockEntityReg<FanBlockEntity> registration
    ) {
        return registration.onRegistered(type ->
                register(type, registration.getResourceLocation()));
    }

    private static void register(BlockEntityType<FanBlockEntity> type, ResourceLocation id) {
        FanProxyComposition.register(type, id);
    }
}
