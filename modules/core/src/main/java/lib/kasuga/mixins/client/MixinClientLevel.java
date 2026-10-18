package lib.kasuga.mixins.client;

import lib.kasuga.client.ClientBlockUpdateHooks;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class MixinClientLevel {

    @Inject(method = "sendBlockUpdated", at = @At("TAIL"))
    private void kasugaLib$onSendBlockUpdated(BlockPos pos, BlockState oldState,
                                              BlockState newState, int flags, CallbackInfo ci) {
        ClientBlockUpdateHooks.dispatch((ClientLevel) (Object) this, pos.asLong(), oldState, newState);
    }
}
