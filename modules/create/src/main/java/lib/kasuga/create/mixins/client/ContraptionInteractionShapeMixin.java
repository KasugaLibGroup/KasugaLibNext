package lib.kasuga.create.mixins.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ContraptionHandlerClient;
import lib.kasuga.create.interaction.ContraptionInteractionShapes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Supplies local ownership context only to blocks that explicitly request it. */
@Mixin(value = ContraptionHandlerClient.class, remap = false)
public abstract class ContraptionInteractionShapeMixin {
    @WrapOperation(
            method = "lambda$rayTraceContraption$0",
            at = @At(value = "INVOKE", target =
                    "Lnet/minecraft/world/level/block/state/BlockState;getShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;")
    )
    private static VoxelShape kasuga$localSelectionShape(
            BlockState state,
            BlockGetter originalWorld,
            BlockPos placeholder,
            Operation<VoxelShape> original,
            @Local(argsOnly = true) Contraption contraption,
            @Local(ordinal = 1) BlockPos localPos
    ) {
        return ContraptionInteractionShapes.shape(state, contraption, originalWorld, localPos,
                () -> original.call(state, originalWorld, placeholder));
    }

    @Inject(method = "rayTraceContraption", at = @At("RETURN"), cancellable = true)
    private static void kasuga$extendedSelection(
            Vec3 origin,
            Vec3 target,
            AbstractContraptionEntity entity,
            CallbackInfoReturnable<BlockHitResult> result
    ) {
        var contraption = entity.getContraption();
        result.setReturnValue(ContraptionInteractionShapes.pick(
                contraption, contraption.getContraptionWorld(),
                entity.toLocalVector(origin, 1f), entity.toLocalVector(target, 1f), result.getReturnValue()));
    }
}
