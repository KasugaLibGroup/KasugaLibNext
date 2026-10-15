package lib.kasuga.mixins.modelling;

import lib.kasuga.rendering.output.mc.iris.CameraIrisSession;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.PipelineManager;
import net.irisshaders.iris.shaderpack.ShaderPack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.Optional;

@Pseudo @Mixin(value = Iris.class, remap = false)
abstract class WorldViewIrisMixin {
    @Inject(method = "getPipelineManager", at = @At("HEAD"), cancellable = true)
    private static void kasuga$pipeline(CallbackInfoReturnable<PipelineManager> ci) {
        var session = CameraIrisSession.current();
        if (session != null) ci.setReturnValue(session.pipelines());
    }
    @Inject(method = "getCurrentPack", at = @At("HEAD"), cancellable = true)
    private static void kasuga$pack(CallbackInfoReturnable<Optional<ShaderPack>> ci) {
        var session = CameraIrisSession.current();
        if (session != null) ci.setReturnValue(Optional.ofNullable(session.pack()));
    }
    @Inject(method = "getCurrentPackName", at = @At("HEAD"), cancellable = true)
    private static void kasuga$name(CallbackInfoReturnable<String> ci) {
        var session = CameraIrisSession.current();
        if (session != null) ci.setReturnValue(session.packName());
    }
    @Inject(method = "isFallback", at = @At("HEAD"), cancellable = true)
    private static void kasuga$fallback(CallbackInfoReturnable<Boolean> ci) {
        if (CameraIrisSession.current() != null) ci.setReturnValue(false);
    }
}
