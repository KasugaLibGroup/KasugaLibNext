package lib.kasuga.mixins.modelling;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import lib.kasuga.rendering.output.camera.CameraRenderSettings;
import lib.kasuga.rendering.output.mc.CameraAssets;
import lib.kasuga.rendering.output.mc.CameraSceneRenderers;
import lib.kasuga.rendering.output.mc.CameraRenderContext;
import lib.kasuga.rendering.output.mc.MinecraftWorldViews;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.concurrent.CompletableFuture;

@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$RebuildTask")
abstract class WorldViewVanillaChunkJobMixin {
    @Unique private CameraAssets kasuga$assets;
    @Unique private CameraSceneRenderers kasuga$scene;
    @Unique private CameraRenderSettings kasuga$settings;
    @Unique private boolean kasuga$captured, kasuga$running, kasuga$released;
    @Inject(method = "<init>", at = @At("RETURN"))
    private void kasuga$capture(CallbackInfo ci) {
        if (kasuga$captured) return;
        kasuga$captured = true;
        kasuga$assets = CameraAssets.current(); kasuga$scene = CameraSceneRenderers.current();
        kasuga$settings = MinecraftWorldViews.currentSettings();
        if (kasuga$assets != null) kasuga$assets.retain();
    }
    @WrapMethod(method = "doTask")
    private CompletableFuture<?> kasuga$build(SectionBufferBuilderPack buffers, Operation<CompletableFuture<?>> original) {
        synchronized (this) {
            if (kasuga$released) return original.call(buffers); // Already cancelled: vanilla returns without rebuilding.
            kasuga$running = true;
        }
        try (var assets = CameraAssets.use(kasuga$assets); var scene = CameraSceneRenderers.use(kasuga$scene);
             var settings = new CameraRenderContext(kasuga$settings)) { return original.call(buffers); }
        finally { synchronized (this) { kasuga$running = false; kasuga$release(); } }
    }
    @Inject(method = "cancel", at = @At("RETURN"))
    private void kasuga$cancel(CallbackInfo ci) {
        synchronized (this) { if (!kasuga$running) kasuga$release(); }
    }
    @Unique private void kasuga$release() {
        if (kasuga$released) return;
        kasuga$released = true;
        if (kasuga$assets != null) Minecraft.getInstance().execute(kasuga$assets::close);
    }
}
