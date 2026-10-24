package lib.kasuga.uip.ldlib.compat.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.lowdragmc.lowdraglib2.gui.texture.SDFRectTexture;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** LDLib2 2.2.44's SDF shader outputs premultiplied RGBA, requiring ONE for source RGB. */
@Mixin(value = SDFRectTexture.class, remap = false)
abstract class SdfRectTextureMixin {
    @WrapMethod(method = "drawInternalWithColorVecs")
    private void kasuga$restoreBlend(GuiGraphics graphics, float x, float y, float width, float height,
                                    Vector4f fill, Vector4f border, Operation<Void> original) {
        int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        try {
            original.call(graphics, x, y, width, height, fill, border);
        } finally {
            RenderSystem.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
        }
    }

    @WrapOperation(method = "drawInternalWithColorVecs", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;blendFuncSeparate(Lcom/mojang/blaze3d/platform/GlStateManager$SourceFactor;Lcom/mojang/blaze3d/platform/GlStateManager$DestFactor;Lcom/mojang/blaze3d/platform/GlStateManager$SourceFactor;Lcom/mojang/blaze3d/platform/GlStateManager$DestFactor;)V"), require = 1, allow = 1)
    private void kasuga$premultipliedRgb(GlStateManager.SourceFactor srcRgb, GlStateManager.DestFactor dstRgb,
                                       GlStateManager.SourceFactor srcAlpha, GlStateManager.DestFactor dstAlpha,
                                       Operation<Void> original) {
        // Correct for LDLib2's shader in both ordinary screens and world canvases.
        RenderSystem.blendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }
}
