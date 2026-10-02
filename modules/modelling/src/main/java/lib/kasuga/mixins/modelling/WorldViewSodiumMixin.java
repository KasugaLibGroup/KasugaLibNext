package lib.kasuga.mixins.modelling;

import lib.kasuga.rendering.output.mc.MinecraftWorldViews;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(value = SodiumWorldRenderer.class, remap = false)
abstract class WorldViewSodiumMixin {
    @Inject(method = "setupTerrain", at = @At("HEAD"))
    private void kasuga$refreshVisibility(CallbackInfo ci) {
        if (MinecraftWorldViews.currentFrameToken() != null)
            ((SodiumWorldRenderer) (Object) this).scheduleTerrainUpdate();
    }
}
