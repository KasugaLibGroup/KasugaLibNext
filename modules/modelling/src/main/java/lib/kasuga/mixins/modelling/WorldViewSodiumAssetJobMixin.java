package lib.kasuga.mixins.modelling;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import lib.kasuga.rendering.output.mc.CameraAssets;
import lib.kasuga.rendering.output.mc.CameraSceneRenderers;
import lib.kasuga.rendering.output.mc.CameraRenderContext;
import lib.kasuga.rendering.output.mc.MinecraftWorldViews;
import lib.kasuga.rendering.output.camera.CameraRenderSettings;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobTyped;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Pseudo @Mixin(value = ChunkJobTyped.class, remap = false)
abstract class WorldViewSodiumAssetJobMixin {
    @Unique private CameraAssets kasuga$assets;
    @Unique private CameraSceneRenderers kasuga$scene;
    @Unique private CameraRenderSettings kasuga$settings;
    @Inject(method = "<init>", at = @At("RETURN"))
    private void kasuga$capture(CallbackInfo ci) { kasuga$assets = CameraAssets.current(); kasuga$scene = CameraSceneRenderers.current(); kasuga$settings = MinecraftWorldViews.currentSettings(); }
    @WrapMethod(method = "execute")
    private void kasuga$assets(ChunkBuildContext context, Operation<Void> original) {
        try (var ignored = CameraAssets.use(kasuga$assets); var settings = new CameraRenderContext(kasuga$settings); var scene = CameraSceneRenderers.use(kasuga$scene)) { original.call(context); }
    }
}
