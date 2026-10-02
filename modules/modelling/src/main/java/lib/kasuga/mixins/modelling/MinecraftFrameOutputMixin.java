package lib.kasuga.mixins.modelling;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import lib.kasuga.rendering.output.mc.MinecraftFrameOutputs;
import lib.kasuga.rendering.output.mc.MinecraftWorldViews;
import lib.kasuga.rendering.output.mc.MinecraftCameras;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
abstract class MinecraftFrameOutputMixin {
    @WrapOperation(method = "runTick", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;blitToScreen(II)V"), require = 1)
    private void kasuga$presentFrame(RenderTarget source, int width, int height, Operation<Void> original) {
        MinecraftFrameOutputs.presentMain(width, height,
                (outputWidth, outputHeight) -> original.call(source, outputWidth, outputHeight));
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void kasuga$releaseOutputs(CallbackInfo ci) {
        lib.kasuga.rendering.output.mc.DualCameraDebug.close();
        lib.kasuga.rendering.output.mc.MinecraftFrameWindows.shutdown();
        MinecraftCameras.shutdown();
        MinecraftWorldViews.shutdown();
        MinecraftFrameOutputs.shutdown();
    }

    @Inject(method = "getMainRenderTarget", at = @At("HEAD"), cancellable = true)
    private void kasuga$worldTarget(CallbackInfoReturnable<RenderTarget> ci) {
        RenderTarget target = MinecraftWorldViews.currentTarget();
        if (target != null) ci.setReturnValue(target);
    }

    @Inject(method = "updateLevelInEngines", at = @At("HEAD"))
    private void kasuga$releaseCameraWorld(ClientLevel level, CallbackInfo ci) {
        MinecraftWorldViews.worldChanged();
    }
}
