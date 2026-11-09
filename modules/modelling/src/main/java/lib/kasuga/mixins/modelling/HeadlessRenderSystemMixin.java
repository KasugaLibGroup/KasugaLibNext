package lib.kasuga.mixins.modelling;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import lib.kasuga.rendering.output.mc.HeadlessClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(RenderSystem.class)
abstract class HeadlessRenderSystemMixin {
    @WrapOperation(method = "flipFrame", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSwapBuffers(J)V"))
    private static void kasuga$present(long window, Operation<Void> original) {
        // Offscreen outputs are consumed from their FBO. Swapping a hidden Cocoa window can
        // wait indefinitely for presentation even with swap interval zero.
        if (!HeadlessClient.enabled()) original.call(window);
    }

    @WrapOperation(method = "limitDisplayFPS", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwWaitEventsTimeout(D)V"))
    private static void kasuga$wait(double seconds, Operation<Void> original) {
        // A hidden macOS window can remain in GLFW's native event wait after
        // focus is lost, even though Minecraft's active-window query is forced
        // true. Headless clients do not need native event delivery, so use the
        // same deterministic frame pacing for both hidden and EGL backends.
        if (HeadlessClient.enabled()) {
            java.util.concurrent.locks.LockSupport.parkNanos(Math.max(1,
                    (long) (seconds * 1_000_000_000L)));
        } else {
            original.call(seconds);
        }
    }
}
