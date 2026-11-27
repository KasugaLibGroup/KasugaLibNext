package lib.kasuga.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import lib.kasuga.registration.minecraft.block.shape.BlockShapeBindings;
import lib.kasuga.registration.minecraft.block.shape.ShapeSlot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BlockBehaviour.BlockStateBase.class)
abstract class BlockStateShapeMixin {
    @WrapOperation(
            method = "getShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/Block;getShape(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;"
            )
    )
    private VoxelShape kasuga$shape(
            Block block,
            BlockState state,
            BlockGetter level,
            BlockPos pos,
            CollisionContext context,
            Operation<VoxelShape> original
    ) {
        return BlockShapeBindings.resolve(
                state,
                ShapeSlot.SHAPE,
                () -> original.call(block, state, level, pos, context)
        );
    }

    @WrapOperation(
            method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/Block;getCollisionShape(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;"
            )
    )
    private VoxelShape kasuga$collision(
            Block block,
            BlockState state,
            BlockGetter level,
            BlockPos pos,
            CollisionContext context,
            Operation<VoxelShape> original
    ) {
        return BlockShapeBindings.resolve(
                state,
                ShapeSlot.COLLISION,
                () -> original.call(block, state, level, pos, context)
        );
    }

    @WrapOperation(
            method = "getVisualShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/Block;getVisualShape(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;"
            )
    )
    private VoxelShape kasuga$visual(
            Block block,
            BlockState state,
            BlockGetter level,
            BlockPos pos,
            CollisionContext context,
            Operation<VoxelShape> original
    ) {
        return BlockShapeBindings.resolve(
                state,
                ShapeSlot.VISUAL,
                () -> original.call(block, state, level, pos, context)
        );
    }

    @WrapOperation(
            method = "getInteractionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/Block;getInteractionShape(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;"
            )
    )
    private VoxelShape kasuga$interaction(
            Block block,
            BlockState state,
            BlockGetter level,
            BlockPos pos,
            Operation<VoxelShape> original
    ) {
        return BlockShapeBindings.resolve(
                state,
                ShapeSlot.INTERACTION,
                () -> original.call(block, state, level, pos)
        );
    }

    @WrapOperation(
            method = "getBlockSupportShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/Block;getBlockSupportShape(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;"
            )
    )
    private VoxelShape kasuga$support(
            Block block,
            BlockState state,
            BlockGetter level,
            BlockPos pos,
            Operation<VoxelShape> original
    ) {
        return BlockShapeBindings.resolve(
                state,
                ShapeSlot.SUPPORT,
                () -> original.call(block, state, level, pos)
        );
    }

    @WrapOperation(
            method = "getOcclusionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/Block;getOcclusionShape(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;"
            )
    )
    private VoxelShape kasuga$occlusion(
            Block block,
            BlockState state,
            BlockGetter level,
            BlockPos pos,
            Operation<VoxelShape> original
    ) {
        return BlockShapeBindings.resolve(
                state,
                ShapeSlot.OCCLUSION,
                () -> original.call(block, state, level, pos)
        );
    }
}
