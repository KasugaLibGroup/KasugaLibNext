package lib.kasuga.mixins.modelling;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read the native camera even while a detached view is installed. */
@Mixin(GameRenderer.class)
public interface PlayerCameraAccessor {
    @Accessor("mainCamera") Camera kasuga$playerCamera();
}
