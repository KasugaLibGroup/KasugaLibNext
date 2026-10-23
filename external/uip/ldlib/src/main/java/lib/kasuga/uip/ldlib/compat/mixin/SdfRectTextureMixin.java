package lib.kasuga.uip.ldlib.compat.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.lowdragmc.lowdraglib2.gui.texture.SDFRectTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** LDLib2's SDF shader outputs premultiplied RGBA; its default blend replaces destination alpha. */
@Mixin(value = SDFRectTexture.class, remap = false)
abstract class SdfRectTextureMixin {
    @WrapMethod(method = "drawInternal")
    private void kasuga$restoreBlend(GuiGraphics graphics, float mouseX, float mouseY, float x, float y,
                                    float width, float height, float partialTick, Operation<Void> original) {
        int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        try {
            original.call(graphics, mouseX, mouseY, x, y, width, height, partialTick);
        } finally {
            RenderSystem.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
        }
    }

    @WrapOperation(method = "drawInternal", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;defaultBlendFunc()V"), require = 1, allow = 1)
    private void kasuga$preserveCanvasAlpha(Operation<Void> original) {
        // Correct for LDLib2's shader in both ordinary screens and world canvases.
        RenderSystem.blendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }
}
