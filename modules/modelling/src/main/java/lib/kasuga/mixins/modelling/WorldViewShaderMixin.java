package lib.kasuga.mixins.modelling;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.vertex.VertexFormat;
import lib.kasuga.rendering.output.mc.MinecraftWorldViews;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShaderInstance.class)
abstract class WorldViewShaderMixin {
    @Shadow @Final public Uniform SCREEN_SIZE;

    @Inject(method = "setDefaultUniforms", at = @At("RETURN"))
    private void kasuga$viewSize(VertexFormat.Mode mode, Matrix4f view, Matrix4f projection,
                                 Window window, CallbackInfo ci) {
        var target = MinecraftWorldViews.currentTarget();
        if (target != null && SCREEN_SIZE != null) SCREEN_SIZE.set((float) target.viewWidth, (float) target.viewHeight);
    }
}
