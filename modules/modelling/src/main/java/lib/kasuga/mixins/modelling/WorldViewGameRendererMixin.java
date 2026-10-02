package lib.kasuga.mixins.modelling;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import lib.kasuga.rendering.output.mc.MinecraftWorldViews;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
abstract class WorldViewGameRendererMixin {
    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V"), require = 1)
    private void kasuga$worldViews(GameRenderer renderer, DeltaTracker delta, Operation<Void> original) {
        MinecraftWorldViews.renderViewsAndMain(renderer, delta, () -> original.call(renderer, delta));
    }

    @ModifyExpressionValue(method = "renderLevel", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/renderer/GameRenderer;mainCamera:Lnet/minecraft/client/Camera;"), require = 1)
    private Camera kasuga$camera(Camera original) {
        Camera camera = MinecraftWorldViews.currentCamera();
        return camera == null ? original : camera;
    }

    @Inject(method = "getMainCamera", at = @At("HEAD"), cancellable = true)
    private void kasuga$currentCamera(CallbackInfoReturnable<Camera> ci) {
        Camera camera = MinecraftWorldViews.currentCamera();
        if (camera != null) ci.setReturnValue(camera);
    }

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;setup(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V"), require = 1)
    private void kasuga$pose(Camera camera, BlockGetter level, Entity entity, boolean detached,
                             boolean reverse, float partialTick, Operation<Void> original) {
        if (MinecraftWorldViews.currentView() == null) original.call(camera, level, entity, detached, reverse, partialTick);
        else MinecraftWorldViews.configureCamera(partialTick);
    }

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;pick(F)V"), require = 1)
    private void kasuga$keepMainPick(GameRenderer renderer, float partialTick, Operation<Void> original) {
        if (MinecraftWorldViews.currentView() == null) original.call(renderer, partialTick);
    }

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LightTexture;updateLightTexture(F)V"), require = 1)
    private void kasuga$light(LightTexture texture, float partialTick, Operation<Void> original) {
        if (MinecraftWorldViews.shouldUpdateLight()) original.call(texture, partialTick);
    }

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;bobHurt(Lcom/mojang/blaze3d/vertex/PoseStack;F)V"), require = 1)
    private void kasuga$noExtraHurtBob(GameRenderer renderer, PoseStack pose, float partialTick, Operation<Void> original) {
        if (MinecraftWorldViews.currentView() == null) original.call(renderer, pose, partialTick);
    }

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;bobView(Lcom/mojang/blaze3d/vertex/PoseStack;F)V"), require = 1)
    private void kasuga$noExtraWalkBob(GameRenderer renderer, PoseStack pose, float partialTick, Operation<Void> original) {
        if (MinecraftWorldViews.currentView() == null) original.call(renderer, pose, partialTick);
    }

    @ModifyExpressionValue(method = "renderLevel", at = {
            @At(value = "FIELD", target = "Lnet/minecraft/client/player/LocalPlayer;oSpinningEffectIntensity:F"),
            @At(value = "FIELD", target = "Lnet/minecraft/client/player/LocalPlayer;spinningEffectIntensity:F")}, require = 2)
    private float kasuga$noExtraNausea(float original) {
        return MinecraftWorldViews.currentView() == null ? original : 0;
    }

    @ModifyExpressionValue(method = "renderLevel", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderHand:Z"), require = 1)
    private boolean kasuga$noExtraHand(boolean original) {
        return original && MinecraftWorldViews.currentView() == null;
    }

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;shouldRenderBlockOutline()Z"), require = 1)
    private boolean kasuga$noExtraOutline(GameRenderer renderer, Operation<Boolean> original) {
        return MinecraftWorldViews.currentView() == null && original.call(renderer);
    }

    @Inject(method = "getProjectionMatrix", at = @At("HEAD"), cancellable = true)
    private void kasuga$projection(double fov, CallbackInfoReturnable<Matrix4f> ci) {
        if (MinecraftWorldViews.currentView() != null)
            ci.setReturnValue(MinecraftWorldViews.projection(((GameRenderer) (Object) this).getDepthFar()));
    }
}
