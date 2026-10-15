package lib.kasuga.mixins.client;

import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(TextureAtlas.class)
public interface AccessorTextureAtlas {

    @Invoker("getWidth")
    int callGetWidth();

    @Invoker("getHeight")
    int callGetHeight();
}
