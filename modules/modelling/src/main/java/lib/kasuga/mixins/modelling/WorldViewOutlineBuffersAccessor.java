package lib.kasuga.mixins.modelling;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(OutlineBufferSource.class) public interface WorldViewOutlineBuffersAccessor {
    @Accessor("outlineBufferSource") MultiBufferSource.BufferSource kasuga$outline();
}
