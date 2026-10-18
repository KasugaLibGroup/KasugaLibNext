package lib.kasuga.mixins.modelling;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(LevelRenderer.class) public interface WorldViewSkyBuffersAccessor {
    @Accessor("starBuffer") VertexBuffer kasuga$stars();
    @Accessor("skyBuffer") VertexBuffer kasuga$sky();
    @Accessor("darkBuffer") VertexBuffer kasuga$dark();
    @Accessor("cloudBuffer") VertexBuffer kasuga$clouds();
}
