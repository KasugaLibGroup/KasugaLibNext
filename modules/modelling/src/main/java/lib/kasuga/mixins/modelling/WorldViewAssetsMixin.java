package lib.kasuga.mixins.modelling;
import lib.kasuga.rendering.output.mc.CameraAssets;
import lib.kasuga.rendering.output.mc.CameraSceneRenderers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(Minecraft.class) abstract class WorldViewAssetsMixin {
    @Inject(method = "getResourceManager", at = @At("HEAD"), cancellable = true)
    private void kasuga$getResourceManager(CallbackInfoReturnable<ResourceManager> ci) {
        var assets = CameraAssets.current();
        if (assets != null) ci.setReturnValue(assets.resources);
    }
    @Inject(method = "getTextureManager", at = @At("HEAD"), cancellable = true)
    private void kasuga$getTextureManager(CallbackInfoReturnable<TextureManager> ci) {
        var assets = CameraAssets.current();
        if (assets != null) ci.setReturnValue(assets.textures);
    }
    @Inject(method = "getModelManager", at = @At("HEAD"), cancellable = true)
    private void kasuga$getModelManager(CallbackInfoReturnable<ModelManager> ci) {
        var assets = CameraAssets.current();
        if (assets != null) ci.setReturnValue(assets.models);
    }
    @Inject(method = "getBlockRenderer", at = @At("HEAD"), cancellable = true)
    private void kasuga$getBlockRenderer(CallbackInfoReturnable<BlockRenderDispatcher> ci) {
        var scene = CameraSceneRenderers.current();
        if (scene != null) { ci.setReturnValue(scene.blocks); return; }
        var assets = CameraAssets.current();
        if (assets != null) ci.setReturnValue(assets.blocks);
    }
    @Inject(method = "getItemRenderer", at = @At("HEAD"), cancellable = true)
    private void kasuga$getItemRenderer(CallbackInfoReturnable<ItemRenderer> ci) {
        var scene = CameraSceneRenderers.current();
        if (scene != null) { ci.setReturnValue(scene.items); return; }
        var assets = CameraAssets.current();
        if (assets != null) ci.setReturnValue(assets.items);
    }
    @Inject(method = "getEntityRenderDispatcher", at = @At("HEAD"), cancellable = true)
    private void kasuga$getEntityRenderDispatcher(CallbackInfoReturnable<EntityRenderDispatcher> ci) {
        var scene = CameraSceneRenderers.current();
        if (scene != null) { ci.setReturnValue(scene.entities); return; }
        var assets = CameraAssets.current();
        if (assets != null) ci.setReturnValue(assets.entities);
    }
    @Inject(method = "getBlockEntityRenderDispatcher", at = @At("HEAD"), cancellable = true)
    private void kasuga$getBlockEntityRenderDispatcher(CallbackInfoReturnable<BlockEntityRenderDispatcher> ci) {
        var scene = CameraSceneRenderers.current();
        if (scene != null) { ci.setReturnValue(scene.blockEntities); return; }
        var assets = CameraAssets.current();
        if (assets != null) ci.setReturnValue(assets.blockEntities);
    }
    @Inject(method = "getEntityModels", at = @At("HEAD"), cancellable = true)
    private void kasuga$getEntityModels(CallbackInfoReturnable<EntityModelSet> ci) {
        var assets = CameraAssets.current();
        if (assets != null) ci.setReturnValue(assets.entityModels);
    }
}
