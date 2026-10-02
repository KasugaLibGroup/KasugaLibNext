package lib.kasuga.mixins.modelling;
import lib.kasuga.rendering.output.mc.MinecraftWorldViews;
import lib.kasuga.rendering.output.mc.ScalarGaussianSampler;
import net.minecraft.util.CubicSampler;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(CubicSampler.class) abstract class WorldViewGaussianSamplerMixin {
    @Inject(method = "gaussianSampleVec3", at = @At("HEAD"), cancellable = true)
    private static void kasuga$scalar(Vec3 point, CubicSampler.Vec3Fetcher fetcher, CallbackInfoReturnable<Vec3> ci) {
        if (MinecraftWorldViews.currentSettings() != null) ci.setReturnValue(ScalarGaussianSampler.sample(point, fetcher));
    }
}
