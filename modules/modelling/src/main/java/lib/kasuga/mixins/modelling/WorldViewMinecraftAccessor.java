package lib.kasuga.mixins.modelling;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Minecraft.class)
public interface WorldViewMinecraftAccessor {
    @Mutable @Accessor("levelRenderer") void kasuga$setLevelRenderer(LevelRenderer renderer);
    @Accessor("renderBuffers") RenderBuffers kasuga$getRenderBuffers();
    @Mutable @Accessor("renderBuffers") void kasuga$setRenderBuffers(RenderBuffers buffers);
}
