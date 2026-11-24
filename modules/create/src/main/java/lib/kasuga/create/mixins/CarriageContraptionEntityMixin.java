package lib.kasuga.create.mixins;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageBogey;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.graph.TrackGraph;
import lib.kasuga.create.CreateConfig;
import lib.kasuga.create.content.train.CarriageAccess;
import lib.kasuga.create.content.train.CarriageContraptionEntityAccess;
import net.createmod.catnip.animation.LerpedFloat;
import net.createmod.catnip.math.AngleHelper;
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
        double maxLateralOffset = CreateConfig.maxBogeyOffset();

        // Orientation from the real track. Interpolating the end bogeys' yaw/pitch is badly off
        // when the carriage spans a node (S-bends: the end tangents are nearly parallel while the
        // middle follows a whole curve). Instead walk the graph from the carriage's leading wheel
        // point towards the trailing one and read the tangent at the extra bogey's position; the
        // walk handles both Bezier turns and plain edges. Unavailable graph -> keep the lerp.
        TrackGraph graph = carriage.train == null ? null : carriage.train.graph;
        TravellingPoint leadingPoint = null;
        TravellingPoint trailingPoint = null;
        int rearSign = 1;
        AbstractBogeyBlock<?> leadingType = leadingAngles.kasuga$getType();
        if (graph != null && !carriage.train.derailed) {
            TravellingPoint start = carriage.getLeadingPoint();
            TravellingPoint toward = carriage.getTrailingPoint();
            if (start.edge != null && toward.edge != null && start.node1 != null && toward.node1 != null
                    && start.edge.getLength() > 0) {
                Vec3 rearDirection = toward.getPosition(graph).subtract(start.getPosition(graph));
                rearSign = start.edge.getDirection(true).dot(rearDirection) >= 0 ? 1 : -1;
                leadingPoint = start;
                trailingPoint = toward;
            }
        }

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
                ksg$redundantBogeyOffset.get(i)
                        .setValue(Mth.clamp(lateralOffset, -maxLateralOffset, maxLateralOffset));
            }

            double angleDiff = 360 * distanceTo / (Math.PI * 2 * type.getWheelRadius());
            angles.kasuga$getWheelAngle()
                    .setValue((angles.kasuga$getWheelAngle().getValue() - angleDiff) % 360);
            if (!kasuga$sampleTrackAngles(graph, leadingPoint, trailingPoint, rearSign, leadingType, type,
                    bogey.isUpsideDown(), along, angles)) {
                angles.kasuga$getYaw().setValue(Mth.rotLerp(t, leadingYaw, trailingYaw));
                angles.kasuga$getPitch().setValue(Mth.lerp(t, leadingPitch, trailingPitch));
            }
        }
    }

    /**
     * Computes an extra bogey's orientation from the real track: samples the track at the midpoints
     * of its two wheel contact points (walking the graph, so Bezier turns and nodes are handled)
     * and derives yaw/pitch exactly like vanilla {@code updateAngles} derives them from a real
     * bogey's two travelling points.
     *
     * @return false when the graph or the end points are unavailable, so the caller falls back to
     *         interpolating the end bogeys' angles.
     */
    @Unique
    private static boolean kasuga$sampleTrackAngles(TrackGraph graph, TravellingPoint start,
                                                    TravellingPoint toward, int rearSign,
                                                    AbstractBogeyBlock<?> leadingType,
                                                    AbstractBogeyBlock<?> type, boolean upsideDown,
                                                    int along, CarriageBogeyAccessor angles) {
        if (graph == null || start == null || toward == null)
            return false;

        double halfLeading = leadingType == null ? 0 : leadingType.getWheelPointSpacing() / 2;
        double halfExtra = type.getWheelPointSpacing() / 2;
        double centerDistance = halfLeading + along;
        Vec3 front = kasuga$sampleTrackPoint(graph, start, toward, rearSign,
                centerDistance - halfExtra, upsideDown);
        Vec3 rear = kasuga$sampleTrackPoint(graph, start, toward, rearSign,
                centerDistance + halfExtra, upsideDown);
        if (front == null || rear == null)
            return false;

        Vec3 diff = front.subtract(rear);
        if (diff.lengthSqr() < 1.0e-8)
            return false;

        double horizontal = Math.sqrt(diff.x * diff.x + diff.z * diff.z);
        float yRot = AngleHelper.deg(Math.atan2(diff.z, diff.x)) + 90;
        float xRot = AngleHelper.deg(Math.atan2(diff.y, horizontal));
        angles.kasuga$getYaw().setValue(-yRot);
        angles.kasuga$getPitch().setValue(xRot);
        return true;
    }

    /**
     * Walks {@code distance} along the graph from {@code start} towards {@code toward} and returns
     * the track surface point, or null when the walk is blocked or invalid.
     */
    @Unique
    private static Vec3 kasuga$sampleTrackPoint(TrackGraph graph, TravellingPoint start,
                                                TravellingPoint toward, int rearSign, double distance,
                                                boolean upsideDown) {
        TravellingPoint scratch = new TravellingPoint(start.node1, start.node2, start.edge, start.position,
                upsideDown);
        TravellingPoint.ITrackSelector selector = scratch.follow(toward);
        double remaining = rearSign * distance;
        while (Math.abs(remaining) > 1.0e-4) {
            // Stride at most 5 blocks: TrackEdge#incrementT only maps distance to the Bezier
            // parameter properly for short strides, longer ones fall back to a linear guess.
            double step = Mth.clamp(remaining, -5.0, 5.0);
            double traveled = scratch.travel(graph, step, selector);
            if (scratch.blocked || Math.abs(traveled) < Math.abs(step) * 0.5)
                return null;
            remaining -= traveled;
        }
        if (scratch.edge == null)
            return null;
        return scratch.getPosition(graph);
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
        float offset = kasuga$getCarriageYOffset();
        return offset == 0 ? original : original.subtract(0.0D, offset, 0.0D);
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
        float offset = kasuga$getCarriageYOffset();
        return offset == 0 ? original : original.subtract(0.0D, offset, 0.0D);
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
        float offset = kasuga$getCarriageYOffset();
        if (offset == 0) return original;
        if (fromServerPrevPos) return original.add(0D, offset, 0D);
        if (!(this instanceof AbstractContraptionEntityAccessor accessor)) return original;
        if (accessor.getPrevPosInvalid()) return original;
        return original.add(0D, offset, 0D);
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
     * Runs every entity tick on both sides. The server mirrors the carriage's authoritative offset
     * into the entity's synched data (vanilla sends it to tracking clients when it changes). The
     * client advances its own interpolation towards the last received value; carriages themselves
     * are only ticked server-side, so the client chase has to be driven from here.
     */
    @Inject(method = "tickContraption", at = @At("HEAD"))
    private void kasuga$handleYOffsetTick(CallbackInfo ci) {
        if (carriage == null)
            return;
        CarriageContraptionEntity entity = (CarriageContraptionEntity) (Object) this;
        CarriageAccess access = (CarriageAccess) (Object) carriage;
        if (entity.level().isClientSide()) {
            access.kasuga$tickYOffsetInterpolation();
            return;
        }
        entity.getEntityData().set(KSG_Y_OFFSET, access.kasuga$getYOffset());
    }

    /**
     * Client side: hands a freshly synced server value to the client carriage, which starts (or
     * re-targets) its interpolation towards it instead of snapping.
     */
    @Inject(method = "onSyncedDataUpdated", at = @At("TAIL"))
    private void kasuga$pullYOffset(EntityDataAccessor<?> key, CallbackInfo ci) {
        if (!KSG_Y_OFFSET.equals(key) || carriage == null)
            return;
        CarriageContraptionEntity entity = (CarriageContraptionEntity) (Object) this;
        if (!entity.level().isClientSide())
            return;
        ((CarriageAccess) (Object) carriage)
                .kasuga$applySyncedYOffset(entity.getEntityData().get(KSG_Y_OFFSET));
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
