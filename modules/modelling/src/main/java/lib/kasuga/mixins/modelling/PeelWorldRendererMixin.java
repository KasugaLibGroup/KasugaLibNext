package lib.kasuga.mixins.modelling;

import lib.kasuga.rendering.models.mc.backend.LayeredTransparency;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(value = SodiumWorldRenderer.class, remap = false)
abstract class PeelWorldRendererMixin {
    @Inject(method = "drawChunkLayer", at = @At("HEAD"), cancellable = true)
    private void kasuga$worldLayers(RenderType type, ChunkRenderMatrices matrices,
                                    double x, double y, double z, CallbackInfo ci) {
        if (type == RenderType.translucent() && LayeredTransparency.renderWorld(() ->
                ((SodiumWorldRenderer) (Object) this).drawChunkLayer(type, matrices, x, y, z))) {
            ci.cancel();
        }
    }
}
