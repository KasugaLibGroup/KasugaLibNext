package lib.kasuga.mixins.modelling;

import lib.kasuga.rendering.output.mc.HeadlessClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class HeadlessMinecraftMixin {
    @Inject(method = "addInitialScreens", at = @At("HEAD"))
    private void kasuga$onboarding(CallbackInfo ci) {
        if (HeadlessClient.enabled()) ((Minecraft) (Object) this).options.onboardAccessibility = false;
    }
    @Inject(method = "isWindowActive", at = @At("HEAD"), cancellable = true)
    private void kasuga$active(CallbackInfoReturnable<Boolean> ci) {
        if (HeadlessClient.enabled()) ci.setReturnValue(true);
    }
    @Inject(method = "getFramerateLimit", at = @At("HEAD"), cancellable = true)
    private void kasuga$rate(CallbackInfoReturnable<Integer> ci) {
        if (HeadlessClient.enabled()) ci.setReturnValue(Math.max(1, Integer.getInteger("kasuga.headless.fps", 60)));
    }
}
