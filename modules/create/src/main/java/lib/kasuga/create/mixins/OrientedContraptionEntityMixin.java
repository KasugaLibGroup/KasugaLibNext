package lib.kasuga.create.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.contraptions.OrientedContraptionEntity;
import dev.engine_room.flywheel.lib.transform.PoseTransformStack;
import dev.engine_room.flywheel.lib.transform.Rotate;
import lib.kasuga.create.content.train.CarriageContraptionEntityAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = OrientedContraptionEntity.class, remap = false)
public class OrientedContraptionEntityMixin {

    /**
     * Runs right after {@code .rotateYDegrees(angleInitialYaw)} in
     * {@link OrientedContraptionEntity#applyLocalTransforms(PoseStack, float)}, before
     * {@code .uncenter()}.
     *
     * <p>The handler receives the transform chain ({@code stack}), the angle argument
     * ({@code degrees}, i.e. {@code angleInitialYaw}), and the call's return value
     * ({@code result}). Since the handler is an instance method, {@code this} is the
     * {@link OrientedContraptionEntity} being rendered. Apply extra transforms on {@code result}
     * (cast it to {@link PoseTransformStack} for the full Translate/Scale API) and return it so
     * the chain continues on the same object.
     *
     * <p>{@code ordinal = 1} picks the second {@code rotateYDegrees(F)} call: the first one is
     * {@code rotateYDegrees(angleYaw)}.
     */
    @WrapOperation(
            method = "applyLocalTransforms(Lcom/mojang/blaze3d/vertex/PoseStack;F)V",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/engine_room/flywheel/lib/transform/PoseTransformStack;rotateYDegrees(F)Ldev/engine_room/flywheel/lib/transform/Rotate;",
                    ordinal = 1
            )
    )
    private Rotate kasuga$afterInitialYaw(PoseTransformStack stack, float degrees, Operation<Rotate> original,
                                          @Local(argsOnly = true) float partialTicks) {
        Rotate result = original.call(stack, degrees);

        // `this` is the entity (CarriageContraptionEntity when a carriage is rendered; cast to
        // CarriageContraptionEntityAccess for the kasuga$ accessors). The interpolated offset keeps
        // the contraption body in step with the bogey visuals, which use the same interpolation.
        if (this instanceof CarriageContraptionEntityAccess access) {
            float offset = access.kasuga$getInterpolatedYOffset(partialTicks);
            if (offset != 0)
                stack.translate(0, offset, 0);
        }
        return result;
    }
}
