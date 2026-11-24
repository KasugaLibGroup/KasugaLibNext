package lib.kasuga.create.mixins.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.contraptions.Contraption;
import dev.engine_room.flywheel.lib.transform.PoseTransformStack;
import com.simibubi.create.content.trains.bogey.BogeySizes;
import com.simibubi.create.content.trains.bogey.BogeyStyle;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageBogey;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntityRenderer;
import lib.kasuga.create.CreateConfig;
import lib.kasuga.create.content.train.CarriageAccess;
import lib.kasuga.create.content.train.CarriageContraptionEntityAccess;
import lib.kasuga.create.mixins.CarriageBogeyAccessor;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Draws the interior bogeys of a multi-bogey carriage the same way Create draws the leading and
 * trailing ones.
 *
 * <p>Vanilla's {@code translateBogey} translates by the distance to the leading bogey; projecting
 * the stored relative position onto the assembly direction gives exactly that, and the local
 * position doubles as the portal-clipping position. The Flywheel visual only knows
 * {@code carriage.bogeys}, so the extras are drawn here unconditionally (gate this if an extra-bogey
 * visual is ever registered). Coupling anchors are intentionally not updated.
 *
 * <p>The descriptor pins the {@code CarriageContraptionEntity} overload; the bridge overloads
 * delegate to it and must not be injected as well.
 */
@Mixin(value = CarriageContraptionEntityRenderer.class, remap = false)
public class CarriageContraptionEntityRendererMixin {

    /**
     * Draws the interior bogeys after vanilla drew its two. {@code TAIL} targets the final
     * {@code RETURN}, so the early returns of the vanilla method are unaffected.
     */
    @Inject(
            method = "render(Lcom/simibubi/create/content/trains/entity/CarriageContraptionEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("TAIL")
    )
    private void kasuga$renderRedundantBogeys(CarriageContraptionEntity entity, float yaw, float partialTicks,
                                              PoseStack ms, MultiBufferSource buffers, int overlay,
                                              CallbackInfo ci) {
        if (!CreateConfig.isMultiBogeyCarriageEnabled())
            return;
        Carriage carriage = entity.getCarriage();
        if (carriage == null)
            return;
        Contraption contraption = entity.getContraption();
        if (contraption == null)
            return;

        CarriageAccess access = (CarriageAccess) (Object) carriage;
        List<CarriageBogey> bogeys = access.kasuga$getRedundantBogeys();
        List<BlockPos> positions = access.kasuga$getRedundantBogeyPositions();
        int count = Math.min(bogeys.size(), positions.size());
        if (count == 0)
            return;

        Direction forward = entity.getInitialOrientation().getCounterClockWise();
        float viewYRot = entity.getViewYRot(partialTicks);
        float viewXRot = entity.getViewXRot(partialTicks);
        double viewYRadians = Math.toRadians(viewYRot);
        CarriageContraptionEntityAccess entityAccess = (CarriageContraptionEntityAccess) (Object) entity;

        for (int i = 0; i < count; i++) {
            CarriageBogey bogey = bogeys.get(i);
            BlockPos localPos = positions.get(i);
            if (contraption.isHiddenInPortal(localPos))
                continue;

            int distance = localPos.getX() * forward.getStepX()
                    + localPos.getY() * forward.getStepY()
                    + localPos.getZ() * forward.getStepZ();

            // The lateral axis of translateBogey's carriage frame maps to world
            // (sin viewYRot, 0, cos viewYRot). Shift the extra bogey off the straight chord and
            // onto the track's arc so it stays on the rails in curves.
            float lateralOffset = entityAccess.kasuga$getRedundantBogeyOffset(i, partialTicks);

            ms.pushPose();
            if (lateralOffset != 0) {
                ms.translate(lateralOffset * Math.sin(viewYRadians), 0.0D,
                        lateralOffset * Math.cos(viewYRadians));
            }
            CarriageContraptionEntityRenderer.translateBogey(ms, bogey, distance, viewYRot, viewXRot,
                    partialTicks);
            int light = CarriageContraptionEntityRenderer.getBogeyLightCoords(entity, bogey, partialTicks);
            bogey.getStyle().render(bogey.getSize(), partialTicks, ms, buffers, light, overlay,
                    ((CarriageBogeyAccessor) (Object) bogey).kasuga$getWheelAngle().getValue(partialTicks),
                    bogey.bogeyData, true);
            ms.popPose();
        }
    }

//    @ModifyExpressionValue(
//            method = "render(Lcom/simibubi/create/content/trains/entity/CarriageContraptionEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
//            at = @At(
//                    value = "INVOKE",
//                    target = "Lcom/simibubi/create/content/trains/entity/CarriageContraptionEntity;getPosition(F)Lnet/minecraft/world/phys/Vec3;"
//            )
//    )
//    private Vec3 kasuga$modifyRenderPosition(Vec3 original,
//                                             @Local(argsOnly = true) CarriageContraptionEntity entity) {
//        CarriageContraptionEntityAccess access = (CarriageContraptionEntityAccess) (Object) entity;
//        return original.add(0, access.kasuga$getCarriageYOffset(), 0);
//    }

//    @WrapOperation(
//            method = "translateBogey",
//            at = @At(
//                    value = "INVOKE",
//                    target = "Ldev/engine_room/flywheel/lib/transform/PoseTransformStack;translate(FFF)Ldev/engine_room/flywheel/lib/transform/PoseTransformStack;",
//                    ordinal = 0
//            )
//    )
//    private static PoseTransformStack kasuga$modifyBogeyTranslation(PoseTransformStack stack, float x, float y,
//                                                                    float z, Operation<PoseTransformStack> original,
//                                                                    @Local(argsOnly = true) CarriageBogey bogey) {
//        // During rendering this is the CarriageContraptionEntity currently being drawn; null only
//        // if the carriage has no live entity. Cast to CarriageContraptionEntityAccess for the
//        // kasuga$ accessors where needed.
//        CarriageContraptionEntity entity = bogey.carriage.anyAvailableEntity();
//        if (!(entity instanceof CarriageContraptionEntityAccess access))
//            return original.call(stack, x, y, z);
//        return original.call(stack, x, y + access.kasuga$getCarriageYOffset(), z);
//    }
}
