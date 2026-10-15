package lib.kasuga.mixins.modelling;

import lib.kasuga.rendering.output.mc.MinecraftWorldViews;
import lib.kasuga.rendering.output.camera.CameraRenderSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.CloudStatus;
import lib.kasuga.rendering.effect.builtin.cloud.CloudEffects;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Options.class)
abstract class WorldViewOptionsMixin {
    @Inject(method = "getCloudsType", at = @At("HEAD"), cancellable = true)
    private void kasuga$volumetricClouds(CallbackInfoReturnable<CloudStatus> ci) {
        // Override the effective type rather than changing the user's saved option.
        // Vanilla and Sodium both query this before rendering their cloud layer.
        if (CloudEffects.isEnabled()) ci.setReturnValue(CloudStatus.OFF);
    }

    @Inject(method = "getEffectiveRenderDistance", at = @At("HEAD"), cancellable = true)
    private void kasuga$distance(CallbackInfoReturnable<Integer> ci) {
        var settings = MinecraftWorldViews.currentSettings();
        if (settings != null) ci.setReturnValue(settings.renderDistance());
    }
}
