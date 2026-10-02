package lib.kasuga.mixins.modelling;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import lib.kasuga.rendering.output.camera.CameraRenderSettings;
import lib.kasuga.rendering.output.mc.MinecraftWorldViews;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(ItemBlockRenderTypes.class) abstract class WorldViewLeafQualityMixin {
    @ModifyExpressionValue(method = {"getChunkRenderType", "getMovingBlockRenderType", "getRenderLayers"},
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/ItemBlockRenderTypes;renderCutout:Z"), require = 1)
    private static boolean kasuga$leaves(boolean original) {
        var settings = MinecraftWorldViews.currentSettings();
        return settings == null ? original : settings.quality() == CameraRenderSettings.Quality.FANCY;
    }
}
