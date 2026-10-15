package lib.kasuga.mixins.modelling;

import lib.kasuga.rendering.models.mc.backend.PeelShaderSource;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gl.shader.ShaderLoader", remap = false)
abstract class PeelShaderLoaderMixin {
    @Inject(method = "getShaderSource", at = @At("RETURN"), cancellable = true)
    private static void kasuga$peelSource(ResourceLocation name, CallbackInfoReturnable<String> cir) {
        if (name.getNamespace().equals("sodium") && name.getPath().equals("blocks/block_layer_opaque.fsh")) {
            cir.setReturnValue(PeelShaderSource.wrap(cir.getReturnValue()));
        }
    }
}
