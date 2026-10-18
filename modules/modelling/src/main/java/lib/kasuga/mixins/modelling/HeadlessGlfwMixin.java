package lib.kasuga.mixins.modelling;

import com.mojang.blaze3d.platform.GLX;
import lib.kasuga.rendering.output.mc.HeadlessClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GLX.class)
abstract class HeadlessGlfwMixin {
    @Inject(method = "_initGlfw", at = @At("HEAD"))
    private static void kasuga$platform(CallbackInfoReturnable<java.util.function.LongSupplier> ci) {
        HeadlessClient.initializePlatform();
    }
}
