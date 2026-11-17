package lib.kasuga.create.mixins;

import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageBogey;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import lib.kasuga.create.CreateConfig;
import lib.kasuga.create.content.train.CarriageAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Updates the extra interior bogeys' wheel angle, yaw and pitch each tick.
 *
 * <p>Vanilla {@code CarriageBogey#updateAngles} cannot be used here: the client-side extras have
 * empty travelling points, so it would always take the derailed fallback pose. The wheel angle is
 * integrated exactly like vanilla; yaw and pitch interpolate the two real bogeys by the bogey's
 * along-carriage fraction ({@code along / bogeySpacing}), which is exact on straight level track and
 * a first-order approximation on curves.
 */
@Mixin(value = CarriageContraptionEntity.class, remap = false)
public class CarriageContraptionEntityMixin {

    @Shadow
    private Carriage carriage;

    /**
     * Runs after the second vanilla {@code updateAngles} call, when both end bogeys hold this tick's
     * angles. That call only runs when {@code carriage.isOnTwoBogeys()}, which assembly guarantees.
     */
    @Inject(
            method = "tickContraption",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/simibubi/create/content/trains/entity/CarriageBogey;updateAngles(Lcom/simibubi/create/content/trains/entity/CarriageContraptionEntity;D)V",
                    ordinal = 1,
                    shift = At.Shift.AFTER
            )
    )
    private void kasuga$updateRedundantBogeyAngles(CallbackInfo ci, @Local(name = "distanceTo") double distanceTo) {
        if (!CreateConfig.isMultiBogeyCarriageEnabled() || carriage == null)
            return;

        CarriageAccess access = (CarriageAccess) (Object) carriage;
        List<CarriageBogey> redundant = access.kasuga$getRedundantBogeys();
        List<BlockPos> positions = access.kasuga$getRedundantBogeyPositions();
        int count = Math.min(redundant.size(), positions.size());
        if (count == 0)
            return;

        CarriageBogey leading = carriage.bogeys.getFirst();
        CarriageBogey trailing = carriage.bogeys.getSecond();
        if (leading == null || trailing == null)
            return;

        // Current-tick values (getValue, not getValue(partialTicks)) so the extras have no extra
        // frame of smoothing relative to the real bogeys.
        CarriageBogeyAccessor leadingAngles = (CarriageBogeyAccessor) (Object) leading;
        CarriageBogeyAccessor trailingAngles = (CarriageBogeyAccessor) (Object) trailing;
        float leadingYaw = leadingAngles.kasuga$getYaw().getValue();
        float trailingYaw = trailingAngles.kasuga$getYaw().getValue();
        float leadingPitch = leadingAngles.kasuga$getPitch().getValue();
        float trailingPitch = trailingAngles.kasuga$getPitch().getValue();
        float spacing = Math.max(1f, carriage.bogeySpacing);

        CarriageContraptionEntity entity = (CarriageContraptionEntity) (Object) this;
        Direction forward = entity.getInitialOrientation().getCounterClockWise();

        for (int i = 0; i < count; i++) {
            CarriageBogey bogey = redundant.get(i);
            CarriageBogeyAccessor angles = (CarriageBogeyAccessor) (Object) bogey;
            AbstractBogeyBlock<?> type = angles.kasuga$getType();
            if (type == null)
                continue;

            BlockPos local = positions.get(i);
            // Exact along-carriage distance: the relative position is direction * (loc[k] - loc[i]).
            int along = local.getX() * forward.getStepX()
                    + local.getY() * forward.getStepY()
                    + local.getZ() * forward.getStepZ();
            float t = Mth.clamp(along / spacing, 0f, 1f);

            double angleDiff = 360 * distanceTo / (Math.PI * 2 * type.getWheelRadius());
            angles.kasuga$getWheelAngle()
                    .setValue((angles.kasuga$getWheelAngle().getValue() - angleDiff) % 360);
            angles.kasuga$getYaw().setValue(Mth.rotLerp(t, leadingYaw, trailingYaw));
            angles.kasuga$getPitch().setValue(Mth.lerp(t, leadingPitch, trailingPitch));
        }
    }
}
