package lib.kasuga.mixins.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets =
        "com.mojang.blaze3d.platform.GlStateManager$TextureState",
remap = false)
@Deprecated
public interface AccessorTextureState {

    @Accessor("binding")
    int getBinding();
}
