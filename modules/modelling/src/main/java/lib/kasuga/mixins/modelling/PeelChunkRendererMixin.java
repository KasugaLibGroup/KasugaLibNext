package lib.kasuga.mixins.modelling;

import lib.kasuga.rendering.models.mc.backend.LayeredTransparency;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer", remap = false)
abstract class PeelChunkRendererMixin {
    @Inject(method = "begin", at = @At("TAIL"))
    private void kasuga$peelState(CallbackInfo ci) {
        LayeredTransparency.bindShader();
    }
}
