package lib.kasuga.mixins.modelling;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import lib.kasuga.rendering.output.mc.HeadlessClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(RenderSystem.class)
abstract class HeadlessRenderSystemMixin {
    @WrapOperation(method = "limitDisplayFPS", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwWaitEventsTimeout(D)V"))
    private static void kasuga$wait(double seconds, Operation<Void> original) {
        // GLFW Null's event wait is a no-op; MC otherwise spins until the frame deadline.
        if (HeadlessClient.egl()) java.util.concurrent.locks.LockSupport.parkNanos(Math.max(1, (long) (seconds * 1_000_000_000L)));
        else original.call(seconds);
    }
}
