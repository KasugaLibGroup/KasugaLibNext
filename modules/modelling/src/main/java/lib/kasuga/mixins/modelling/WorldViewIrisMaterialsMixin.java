package lib.kasuga.mixins.modelling;
import lib.kasuga.rendering.output.mc.iris.CameraIrisSession;
import net.irisshaders.iris.shaderpack.materialmap.*;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import it.unimi.dsi.fastutil.objects.*;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.*;

/** Thread-local material routing also applies inside Sodium's asynchronous meshing jobs. */
@Pseudo @Mixin(value = WorldRenderingSettings.class, remap = false)
abstract class WorldViewIrisMaterialsMixin {
    private WorldRenderingSettings kasuga$selected() {
        var selected = CameraIrisSession.currentMaterials();
        return selected == (Object) this ? null : selected;
    }
    @Inject(method = "isReloadRequired", at = @At("HEAD"), cancellable = true)
    private void kasuga$isReloadRequired(CallbackInfoReturnable<Boolean> ci) {
        var selected = kasuga$selected();
        if (selected != null) ci.setReturnValue(selected.isReloadRequired());
    }
    @Inject(method = "getBlockStateIds", at = @At("HEAD"), cancellable = true)
    private void kasuga$getBlockStateIds(CallbackInfoReturnable<Object2IntMap<BlockState>> ci) {
        var selected = kasuga$selected();
        if (selected != null) ci.setReturnValue(selected.getBlockStateIds());
    }
    @Inject(method = "getBlockTypeIds", at = @At("HEAD"), cancellable = true)
    private void kasuga$getBlockTypeIds(CallbackInfoReturnable<Map<Block, BlockRenderType>> ci) {
        var selected = kasuga$selected();
        if (selected != null) ci.setReturnValue(selected.getBlockTypeIds());
    }
    @Inject(method = "getEntityIds", at = @At("HEAD"), cancellable = true)
    private void kasuga$getEntityIds(CallbackInfoReturnable<Object2IntFunction<NamespacedId>> ci) {
        var selected = kasuga$selected();
        if (selected != null) ci.setReturnValue(selected.getEntityIds());
    }
    @Inject(method = "getItemIds", at = @At("HEAD"), cancellable = true)
    private void kasuga$getItemIds(CallbackInfoReturnable<Object2IntFunction<NamespacedId>> ci) {
        var selected = kasuga$selected();
        if (selected != null) ci.setReturnValue(selected.getItemIds());
    }
    @Inject(method = "getAmbientOcclusionLevel", at = @At("HEAD"), cancellable = true)
    private void kasuga$getAmbientOcclusionLevel(CallbackInfoReturnable<Float> ci) {
        var selected = kasuga$selected();
        if (selected != null) ci.setReturnValue(selected.getAmbientOcclusionLevel());
    }
    @Inject(method = "shouldDisableDirectionalShading", at = @At("HEAD"), cancellable = true)
    private void kasuga$shouldDisableDirectionalShading(CallbackInfoReturnable<Boolean> ci) {
        var selected = kasuga$selected();
        if (selected != null) ci.setReturnValue(selected.shouldDisableDirectionalShading());
    }
    @Inject(method = "shouldUseSeparateAo", at = @At("HEAD"), cancellable = true)
    private void kasuga$shouldUseSeparateAo(CallbackInfoReturnable<Boolean> ci) {
        var selected = kasuga$selected();
        if (selected != null) ci.setReturnValue(selected.shouldUseSeparateAo());
    }
    @Inject(method = "getVertexFormat", at = @At("HEAD"), cancellable = true)
    private void kasuga$getVertexFormat(CallbackInfoReturnable<ChunkVertexType> ci) {
        var selected = kasuga$selected();
        if (selected != null) ci.setReturnValue(selected.getVertexFormat());
    }
    @Inject(method = "shouldVoxelizeLightBlocks", at = @At("HEAD"), cancellable = true)
    private void kasuga$shouldVoxelizeLightBlocks(CallbackInfoReturnable<Boolean> ci) {
        var selected = kasuga$selected();
        if (selected != null) ci.setReturnValue(selected.shouldVoxelizeLightBlocks());
    }
    @Inject(method = "shouldSeparateEntityDraws", at = @At("HEAD"), cancellable = true)
    private void kasuga$shouldSeparateEntityDraws(CallbackInfoReturnable<Boolean> ci) {
        var selected = kasuga$selected();
        if (selected != null) ci.setReturnValue(selected.shouldSeparateEntityDraws());
    }
    @Inject(method = "hasVillagerConversionId", at = @At("HEAD"), cancellable = true)
    private void kasuga$hasVillagerConversionId(CallbackInfoReturnable<Boolean> ci) {
        var selected = kasuga$selected();
        if (selected != null) ci.setReturnValue(selected.hasVillagerConversionId());
    }
    @Inject(method = "setBlockStateIds", at = @At("HEAD"), cancellable = true)
    private void kasuga$setBlockStateIds(Object2IntMap<BlockState> value, CallbackInfo ci) {
        var selected = kasuga$selected();
        if (selected != null) { selected.setBlockStateIds(value); ci.cancel(); }
    }
    @Inject(method = "setBlockTypeIds", at = @At("HEAD"), cancellable = true)
    private void kasuga$setBlockTypeIds(Map<Block, BlockRenderType> value, CallbackInfo ci) {
        var selected = kasuga$selected();
        if (selected != null) { selected.setBlockTypeIds(value); ci.cancel(); }
    }
    @Inject(method = "setEntityIds", at = @At("HEAD"), cancellable = true)
    private void kasuga$setEntityIds(Object2IntFunction<NamespacedId> value, CallbackInfo ci) {
        var selected = kasuga$selected();
        if (selected != null) { selected.setEntityIds(value); ci.cancel(); }
    }
    @Inject(method = "setItemIds", at = @At("HEAD"), cancellable = true)
    private void kasuga$setItemIds(Object2IntFunction<NamespacedId> value, CallbackInfo ci) {
        var selected = kasuga$selected();
        if (selected != null) { selected.setItemIds(value); ci.cancel(); }
    }
    @Inject(method = "setAmbientOcclusionLevel", at = @At("HEAD"), cancellable = true)
    private void kasuga$setAmbientOcclusionLevel(float value, CallbackInfo ci) {
        var selected = kasuga$selected();
        if (selected != null) { selected.setAmbientOcclusionLevel(value); ci.cancel(); }
    }
    @Inject(method = "setDisableDirectionalShading", at = @At("HEAD"), cancellable = true)
    private void kasuga$setDisableDirectionalShading(boolean value, CallbackInfo ci) {
        var selected = kasuga$selected();
        if (selected != null) { selected.setDisableDirectionalShading(value); ci.cancel(); }
    }
    @Inject(method = "setUseSeparateAo", at = @At("HEAD"), cancellable = true)
    private void kasuga$setUseSeparateAo(boolean value, CallbackInfo ci) {
        var selected = kasuga$selected();
        if (selected != null) { selected.setUseSeparateAo(value); ci.cancel(); }
    }
    @Inject(method = "setVertexFormat", at = @At("HEAD"), cancellable = true)
    private void kasuga$setVertexFormat(ChunkVertexType value, CallbackInfo ci) {
        var selected = kasuga$selected();
        if (selected != null) { selected.setVertexFormat(value); ci.cancel(); }
    }
    @Inject(method = "setVoxelizeLightBlocks", at = @At("HEAD"), cancellable = true)
    private void kasuga$setVoxelizeLightBlocks(boolean value, CallbackInfo ci) {
        var selected = kasuga$selected();
        if (selected != null) { selected.setVoxelizeLightBlocks(value); ci.cancel(); }
    }
    @Inject(method = "setSeparateEntityDraws", at = @At("HEAD"), cancellable = true)
    private void kasuga$setSeparateEntityDraws(boolean value, CallbackInfo ci) {
        var selected = kasuga$selected();
        if (selected != null) { selected.setSeparateEntityDraws(value); ci.cancel(); }
    }
    @Inject(method = "clearReloadRequired", at = @At("HEAD"), cancellable = true)
    private void kasuga$clearReloadRequired(CallbackInfo ci) {
        var selected = kasuga$selected();
        if (selected != null) { selected.clearReloadRequired(); ci.cancel(); }
    }
}
