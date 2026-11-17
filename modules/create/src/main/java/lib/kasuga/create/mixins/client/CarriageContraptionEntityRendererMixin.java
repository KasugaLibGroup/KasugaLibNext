package lib.kasuga.create.mixins.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.trains.bogey.BogeySizes;
import com.simibubi.create.content.trains.bogey.BogeyStyle;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageBogey;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntityRenderer;
import lib.kasuga.create.CreateConfig;
import lib.kasuga.create.content.train.CarriageAccess;
import lib.kasuga.create.mixins.CarriageBogeyAccessor;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
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

        for (int i = 0; i < count; i++) {
            CarriageBogey bogey = bogeys.get(i);
            BlockPos localPos = positions.get(i);
            if (contraption.isHiddenInPortal(localPos))
                continue;

            int distance = localPos.getX() * forward.getStepX()
                    + localPos.getY() * forward.getStepY()
                    + localPos.getZ() * forward.getStepZ();

            ms.pushPose();
            CarriageContraptionEntityRenderer.translateBogey(ms, bogey, distance, viewYRot, viewXRot,
                    partialTicks);
            int light = CarriageContraptionEntityRenderer.getBogeyLightCoords(entity, bogey, partialTicks);
            bogey.getStyle().render(bogey.getSize(), partialTicks, ms, buffers, light, overlay,
                    ((CarriageBogeyAccessor) (Object) bogey).kasuga$getWheelAngle().getValue(partialTicks),
                    bogey.bogeyData, true);
            ms.popPose();
        }
    }
}
