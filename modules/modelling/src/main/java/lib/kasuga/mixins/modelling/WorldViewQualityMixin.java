package lib.kasuga.mixins.modelling;

import lib.kasuga.rendering.output.mc.MinecraftWorldViews;
import lib.kasuga.rendering.output.camera.CameraRenderSettings;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
abstract class WorldViewQualityMixin {
    @Inject(method = "useFancyGraphics", at = @At("HEAD"), cancellable = true)
    private static void kasuga$fancy(CallbackInfoReturnable<Boolean> ci) {
        var settings = MinecraftWorldViews.currentSettings();
        if (settings != null) ci.setReturnValue(settings.quality() == CameraRenderSettings.Quality.FANCY);
    }
    @Inject(method = "useAmbientOcclusion", at = @At("HEAD"), cancellable = true)
    private static void kasuga$ao(CallbackInfoReturnable<Boolean> ci) {
        var settings = MinecraftWorldViews.currentSettings();
        if (settings != null) ci.setReturnValue(settings.ambientOcclusion());
    }
    @Inject(method = "useShaderTransparency", at = @At("HEAD"), cancellable = true)
    private static void kasuga$transparency(CallbackInfoReturnable<Boolean> ci) {
        if (MinecraftWorldViews.currentSettings() != null) ci.setReturnValue(false);
    }
}
