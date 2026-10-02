package lib.kasuga.mixins.modelling;

import lib.kasuga.rendering.output.mc.MinecraftWorldViews;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.neoforged.fml.ModList;
import org.spongepowered.asm.mixin.Final;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelRenderer.class)
abstract class WorldViewLevelRendererMixin {
    @Shadow private Frustum cullingFrustum;
    @Shadow private ViewArea viewArea;
    @Shadow private SectionRenderDispatcher sectionRenderDispatcher;
    @Shadow @Final private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;
    @Shadow protected abstract void applyFrustum(Frustum frustum);
    @Unique private int kasuga$originX = Integer.MIN_VALUE;
    @Unique private int kasuga$originZ = Integer.MIN_VALUE;

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;setupRender(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;ZZ)V"), require = 1)
    private void kasuga$viewTerrain(LevelRenderer renderer, Camera camera, Frustum frustum,
                                    boolean captured, boolean spectator, Operation<Void> original) {
        if (MinecraftWorldViews.currentView() == null || ModList.get().isLoaded("sodium") || viewArea == null) {
            original.call(renderer, camera, frustum, captured, spectator);
            return;
        }
        // Vanilla setupRender roots the section grid at minecraft.player, even
        // when Camera is detached. Our renderer must track the actual view.
        var position = camera.getPosition();
        int x = net.minecraft.core.SectionPos.blockToSectionCoord(position.x);
        int z = net.minecraft.core.SectionPos.blockToSectionCoord(position.z);
        if (x != kasuga$originX || z != kasuga$originZ) {
            viewArea.repositionCamera(position.x, position.z);
            kasuga$originX = x; kasuga$originZ = z;
        }
        // Direct frustum checks avoid waiting for a graph rooted at a different pose.
        visibleSections.clear();
        sectionRenderDispatcher.setCamera(position);
        for (var section : viewArea.sections)
            if (frustum.isVisible(section.getBoundingBox())) visibleSections.add(section);
    }

    @ModifyExpressionValue(method = "renderLevel", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;capturedFrustum:Lnet/minecraft/client/renderer/culling/Frustum;"))
    private Frustum kasuga$ignoreMainDebugFrustum(Frustum original) {
        return MinecraftWorldViews.currentView() == null ? original : null;
    }

    @Inject(method = "prepareCullFrustum", at = @At("RETURN"))
    private void kasuga$refreshVisibility(Vec3 position, Matrix4f view, Matrix4f projection, CallbackInfo ci) {
        // Also refresh when only the FOV/aspect changes at the same pose.
        if (MinecraftWorldViews.currentFrameToken() != null)
            applyFrustum(LevelRenderer.offsetFrustum(cullingFrustum));
    }

    @Inject(method = "shouldShowEntityOutlines", at = @At("HEAD"), cancellable = true)
    private void kasuga$noSharedOutlineTarget(CallbackInfoReturnable<Boolean> ci) {
        if (MinecraftWorldViews.currentView() != null) ci.setReturnValue(false);
    }
}
