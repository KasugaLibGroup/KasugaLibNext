package lib.kasuga.create.mixins;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageBogey;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import lib.kasuga.create.CreateConfig;
import lib.kasuga.create.content.train.CarriageAccess;
import lib.kasuga.create.content.train.CarriageContraptionEntityAccess;
import net.createmod.catnip.animation.LerpedFloat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
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
public class CarriageContraptionEntityMixin implements CarriageContraptionEntityAccess {

    @Shadow
    private Carriage carriage;

    @Unique
    private ArrayList<LerpedFloat> ksg$redundantBogeyOffset = null;

    /** Synched-data key carrying the carriage's vertical offset from the server to clients. */
    @Unique
    private static final EntityDataAccessor<Float> KSG_Y_OFFSET =
            SynchedEntityData.defineId(CarriageContraptionEntity.class, EntityDataSerializers.FLOAT);

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

        if (kasuga$getCarriageContraptionEntity().level().isClientSide()
                && (ksg$redundantBogeyOffset == null || ksg$redundantBogeyOffset.size() != count)) {
            ksg$redundantBogeyOffset = new ArrayList<>(count);
            for (int i = 0; i < count; i++)
                ksg$redundantBogeyOffset.add(LerpedFloat.linear());
        }

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

            // The carriage body is the straight chord between the two real bogeys, while the
            // track between them follows the curve. The arc deviates from the chord towards the
            // outside of the turn; its height at fraction t is the sagitta of the circular arc
            // spanned by the chord, whose end tangents differ by yawDelta (the two end bogeys'
            // yaw difference). Positive = right of the leading-to-trailing chord.
            double yawDelta = Mth.wrapDegrees(leadingYaw - trailingYaw);
            double lateralOffset = 0;
            if (ksg$redundantBogeyOffset != null) {
                if (carriage.train != null && !carriage.train.derailed && Math.abs(yawDelta) > 1.0e-3) {
                    double halfAngle = Math.toRadians(Math.abs(yawDelta)) / 2;
                    double radius = spacing / (2 * Math.sin(halfAngle));
                    double u = (t - 0.5) * spacing;
                    lateralOffset = Math.max(0, Math.sqrt(Math.max(0, radius * radius - u * u))
                            - radius * Math.cos(halfAngle)) * -Math.signum(yawDelta);
                }
                ksg$redundantBogeyOffset.get(i).setValue(lateralOffset);
            }

            double angleDiff = 360 * distanceTo / (Math.PI * 2 * type.getWheelRadius());
            angles.kasuga$getWheelAngle()
                    .setValue((angles.kasuga$getWheelAngle().getValue() - angleDiff) % 360);
            angles.kasuga$getYaw().setValue(Mth.rotLerp(t, leadingYaw, trailingYaw));
            angles.kasuga$getPitch().setValue(Mth.lerp(t, leadingPitch, trailingPitch));
        }
    }

    /**
     * The wheel angle is integrated from the distance the entity moved this tick, computed as
     * {@code position() - (xo, yo, zo)}. {@code position()} carries the visual y offset while the
     * {@code xo/yo/zo} fields are raw, so the offset would be misread as vertical movement and spin
     * the wheels at the wrong speed (even when the train stands still). Remove the offset from this
     * one call; the horizontal components and therefore the direction signum are unaffected.
     *
     * <p>{@code ordinal = 1}: the first {@code position()} call in the method belongs to the
     * server-side game event and keeps its value. This call is reached before this tick's offset is
     * updated, so {@code getValue()} is exactly the value {@code position()} added.
     */
    @ModifyExpressionValue(
            method = "tickContraption",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/simibubi/create/content/trains/entity/CarriageContraptionEntity;position()Lnet/minecraft/world/phys/Vec3;",
                    ordinal = 1
            )
    )
    private Vec3 kasuga$excludeOffsetFromTravelDistance(Vec3 original) {
        return original.subtract(0.0D, kasuga$getCarriageYOffset(), 0.0D);
    }

    /**
     * {@code serverPrevPos} is captured from {@code position()}, which now carries the y offset on
     * the server as well. Store the raw position instead: {@code getPrevPositionVec()} adds the
     * current offset at query time, so {@code position() - getPrevPositionVec()} still cancels the
     * offset for collision and motion computations. This runs before this tick's offset update, so
     * the subtracted value is exactly the one {@code position()} contributed.
     */
    @ModifyExpressionValue(
            method = "setServerSidePrevPosition",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/simibubi/create/content/trains/entity/CarriageContraptionEntity;position()Lnet/minecraft/world/phys/Vec3;"
            )
    )
    private Vec3 kasuga$storeRawServerPrevPos(Vec3 original) {
        return original.subtract(0.0D, kasuga$getCarriageYOffset(), 0.0D);
    }

    /**
     * Rewrites the value returned by {@link CarriageContraptionEntity#getPrevPositionVec()}.
     *
     * <p>The method has two exits, and the two injections keep them distinguishable: the first
     * {@code RETURN} is the server-side shortcut returning {@code serverPrevPos}, the second is
     * the {@code super.getPrevPositionVec()} fallback. Both hand the original value and which
     * path produced it to {@link #kasuga$modifyPrevPositionVec(Vec3, boolean)}, then write the
     * replacement back through {@link CallbackInfoReturnable#setReturnValue(Object)}.
     */
    @Inject(
            method = "getPrevPositionVec",
            at = @At(value = "RETURN", ordinal = 0),
            cancellable = true
    )
    private void kasuga$modifyServerPrevPos(CallbackInfoReturnable<Vec3> cir) {
        cir.setReturnValue(kasuga$modifyPrevPositionVec(cir.getReturnValue(), true));
    }

    @Inject(
            method = "getPrevPositionVec",
            at = @At(value = "RETURN", ordinal = 1),
            cancellable = true
    )
    private void kasuga$modifySuperPrevPos(CallbackInfoReturnable<Vec3> cir) {
        cir.setReturnValue(kasuga$modifyPrevPositionVec(cir.getReturnValue(), false));
    }

    /**
     * The single place for the actual adjustment. {@code fromServerPrevPos} is {@code true} when
     * the value came from the {@code serverPrevPos} shortcut and {@code false} when it came from
     * {@code super.getPrevPositionVec()}. Returns the value unchanged for now.
     */
    @Unique
    private Vec3 kasuga$modifyPrevPositionVec(Vec3 original, boolean fromServerPrevPos) {
        if (fromServerPrevPos) return original.add(0D, kasuga$getCarriageYOffset(), 0D);
        if (!(this instanceof AbstractContraptionEntityAccessor accessor)) return original;
        if (accessor.getPrevPosInvalid()) return original;
        return original.add(0D, kasuga$getCarriageYOffset(), 0D);
    }

    @Override
    public CarriageContraptionEntity kasuga$getCarriageContraptionEntity() {
        return (CarriageContraptionEntity) (Object) this;
    }

    @Override
    public float kasuga$getCarriageYOffset() {
        return carriage == null ? 0 : ((CarriageAccess) (Object) carriage).kasuga$getYOffset();
    }

    @Override
    public float kasuga$getInterpolatedYOffset(float partialTicks) {
        return carriage == null ? 0
                : ((CarriageAccess) (Object) carriage).kasuga$getInterpolatedYOffset(partialTicks);
    }

    @Inject(method = "defineSynchedData", at = @At("TAIL"))
    private void kasuga$defineYOffset(SynchedEntityData.Builder builder, CallbackInfo ci) {
        builder.define(KSG_Y_OFFSET, 0f);
    }

    /**
     * Server side: mirrors the carriage's authoritative offset into the entity's synched data.
     * Vanilla sends it to tracking clients whenever it changes, so the offset stays in step with
     * the value used by {@code position()} on both sides.
     */
    @Inject(method = "tickContraption", at = @At("HEAD"))
    private void kasuga$pushYOffset(CallbackInfo ci) {
        CarriageContraptionEntity entity = (CarriageContraptionEntity) (Object) this;
        if (entity.level().isClientSide() || carriage == null)
            return;
        entity.getEntityData().set(KSG_Y_OFFSET, kasuga$getCarriageYOffset());
    }

    /**
     * Client side: writes the synced offset into the client carriage, whose {@code LerpedFloat}
     * interpolates between the previous and the newly received value for smooth rendering.
     */
    @Inject(method = "onSyncedDataUpdated", at = @At("TAIL"))
    private void kasuga$pullYOffset(EntityDataAccessor<?> key, CallbackInfo ci) {
        if (!KSG_Y_OFFSET.equals(key) || carriage == null)
            return;
        CarriageContraptionEntity entity = (CarriageContraptionEntity) (Object) this;
        if (!entity.level().isClientSide())
            return;
        ((CarriageAccess) (Object) carriage)
                .kasuga$setYOffset(entity.getEntityData().get(KSG_Y_OFFSET));
    }

    @Override
    public float kasuga$getRedundantBogeyOffset(int index, float partialTicks) {
        if (ksg$redundantBogeyOffset == null || index < 0 || index >= ksg$redundantBogeyOffset.size())
            return 0;
        return ksg$redundantBogeyOffset.get(index).getValue(partialTicks);
    }

    @Override
    public Carriage kasuga$getCarriage() {
        return carriage;
    }
}
