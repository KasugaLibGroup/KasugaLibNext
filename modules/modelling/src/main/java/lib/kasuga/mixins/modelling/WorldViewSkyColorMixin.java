package lib.kasuga.mixins.modelling;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import lib.kasuga.rendering.output.mc.MinecraftWorldViews;
import lib.kasuga.rendering.output.mc.ScalarGaussianSampler;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.CubicSampler;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ClientLevel.class)
abstract class WorldViewSkyColorMixin {
    @WrapOperation(method = "getSkyColor", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/CubicSampler;gaussianSampleVec3(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/util/CubicSampler$Vec3Fetcher;)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 kasuga$packedSkyColor(Vec3 point, CubicSampler.Vec3Fetcher fetcher, Operation<Vec3> original) {
        if (MinecraftWorldViews.currentSettings() == null) return original.call(point, fetcher);
        var biomes = ((ClientLevel) (Object) this).getBiomeManager();
        return ScalarGaussianSampler.sampleRgb(point, (x, y, z) -> biomes.getNoiseBiomeAtQuart(x, y, z).value().getSkyColor());
    }
}
