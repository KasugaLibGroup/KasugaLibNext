package lib.kasuga.compat.iris.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import lib.kasuga.rendering.output.mc.iris.CameraIrisSession;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobTyped;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo @Mixin(value = ChunkJobTyped.class, remap = false)
abstract class WorldViewIrisChunkJobMixin {
    @Unique private WorldRenderingSettings kasuga$materials;
    @Inject(method = "<init>", at = @At("RETURN"))
    private void kasuga$irisCaptureMaterials(CallbackInfo ci) { kasuga$materials = CameraIrisSession.currentMaterials(); }
    @WrapMethod(method = "execute(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;)V")
    private void kasuga$irisWorkerMaterials(ChunkBuildContext context, Operation<Void> original) throws Exception {
        try (var ignored = CameraIrisSession.useMaterials(kasuga$materials)) { original.call(context); }
    }
}
