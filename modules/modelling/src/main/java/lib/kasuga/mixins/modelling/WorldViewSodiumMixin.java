package lib.kasuga.mixins.modelling;

import lib.kasuga.rendering.output.mc.MinecraftWorldViews;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(value = SodiumWorldRenderer.class, remap = false)
abstract class WorldViewSodiumMixin {
    @Unique private float kasuga$lastRoll;

    @Inject(method = "setupTerrain", at = @At("HEAD"))
    private void kasuga$refreshVisibility(CallbackInfo ci) {
        // Sodium 0.6 already invalidates on position, yaw, pitch, fog and
        // projection changes. Only detached-camera roll needs an extra signal.
        var view = MinecraftWorldViews.currentView();
        if (view != null && view.roll() != kasuga$lastRoll) {
            ((SodiumWorldRenderer) (Object) this).scheduleTerrainUpdate();
            kasuga$lastRoll = view.roll();
        }
    }
}
