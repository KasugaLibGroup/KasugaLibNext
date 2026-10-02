package lib.kasuga.mixins.modelling;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.Window;
import lib.kasuga.rendering.output.mc.HeadlessClient;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

@Mixin(Window.class)
abstract class HeadlessWindowMixin {
    @Shadow private boolean fullscreen;
    @Shadow private boolean actuallyFullscreen;
    @WrapOperation(method = "<init>", at = @At(value = "INVOKE", target =
            "Lnet/neoforged/fml/loading/ImmediateWindowHandler;setupMinecraftWindow(Ljava/util/function/IntSupplier;Ljava/util/function/IntSupplier;Ljava/util/function/Supplier;Ljava/util/function/LongSupplier;)J"))
    private long kasuga$create(IntSupplier width, IntSupplier height, Supplier<String> title, LongSupplier monitor, Operation<Long> original) {
        if (!HeadlessClient.enabled()) return original.call(width, height, title, monitor);
        fullscreen = actuallyFullscreen = false;
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
        if (HeadlessClient.egl()) GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_CREATION_API, GLFW.GLFW_EGL_CONTEXT_API);
        long window = GLFW.glfwCreateWindow(width.getAsInt(), height.getAsInt(), title.get(), 0, 0);
        if (window == 0) throw new IllegalStateException("Cannot create " + HeadlessClient.backend() + " headless GL context");
        return window;
    }
    @Inject(method = "toggleFullScreen", at = @At("HEAD"), cancellable = true)
    private void kasuga$fullscreen(CallbackInfo ci) { if (HeadlessClient.enabled()) ci.cancel(); }
    @WrapOperation(method = "updateVsync", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSwapInterval(I)V"))
    private void kasuga$vsync(int interval, Operation<Void> original) { original.call(HeadlessClient.enabled() ? 0 : interval); }
}
