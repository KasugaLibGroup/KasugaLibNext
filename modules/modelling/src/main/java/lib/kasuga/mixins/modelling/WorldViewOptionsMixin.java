package lib.kasuga.mixins.modelling;

import lib.kasuga.rendering.output.mc.MinecraftWorldViews;
import lib.kasuga.rendering.output.camera.CameraRenderSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Options.class)
abstract class WorldViewOptionsMixin {
    @Inject(method = "getEffectiveRenderDistance", at = @At("HEAD"), cancellable = true)
    private void kasuga$distance(CallbackInfoReturnable<Integer> ci) {
        var settings = MinecraftWorldViews.currentSettings();
        if (settings != null) ci.setReturnValue(settings.renderDistance());
    }
}
