package lib.kasuga.create.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageBogey;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.station.StationBlockEntity;
import lib.kasuga.create.CreateConfig;
import lib.kasuga.create.content.train.CarriageAccess;
import lib.kasuga.create.content.train.CarriageContraptionAccess;
import lib.kasuga.create.content.train.sync.CarriageExtraBogeySync;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Collapses a multi-bogey carriage into the leading + trailing pair the station assembly expects.
 *
 * <p>Vanilla pairs carriage {@code i} with the station bogey at {@code i + 1}; a multi-bogey group
 * {@code i..j} breaks both the {@code not_connected_in_order} guard and the pairing (interior
 * bogeys would each become their own carriage). When the contraption reports a contiguous,
 * correctly ordered group, the trailing bogey's location, type, orientation and track points are
 * copied into slot {@code i + 1} — so vanilla's expected-position check passes and
 * {@code bogeySpacing} becomes the full carriage length — the interior bogeys are built from the
 * original slots, and the loop index jumps to {@code j}. Validation is order-insensitive because
 * capture is BFS order; anything invalid falls back to vanilla behaviour. Interior bogeys are
 * synced to clients after the vanilla {@code AddTrainPacket}.
 */
@Mixin(value = StationBlockEntity.class, remap = false)
public abstract class StationBlockEntityMixin {

    @Shadow
    Direction assemblyDirection;

    @Shadow
    int[] bogeyLocations;

    @Shadow
    AbstractBogeyBlock<?>[] bogeyTypes;

    @Shadow
    boolean[] upsideDownBogeys;

    @Shadow
    int bogeyCount;

    /** Station index the vanilla pairing should jump to after collapsing a multi-bogey group. */
    @Unique
    private int ksg$trailingBogeyIndex = -1;

    /** Interior bogeys of the group the current loop iteration is collapsing. */
    @Unique
    @Nullable
    private List<CarriageBogey> ksg$pendingRedundantBogeys = null;

    /** The same interior bogeys, positioned relative to the carriage's local origin. */
    @Unique
    @Nullable
    private List<BlockPos> ksg$pendingRedundantBogeyPositions = null;

    /**
     * Identifies a contiguous multi-bogey group, snapshots its interior bogeys (before the slot
     * copy, since slot {@code i + 1} may itself be interior) and collapses the group onto the slot
     * vanilla reads. Per-iteration state is reset on every call.
     */
    @WrapOperation(
            method = "assemble",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/simibubi/create/content/trains/entity/CarriageContraption;getSecondBogeyPos()Lnet/minecraft/core/BlockPos;"
            )
    )
    private BlockPos kasuga$collapseMultiBogeyCarriage(
            CarriageContraption contraption, Operation<BlockPos> original,
            @Local(name = "bogeyIndex") int bogeyIndex,
            @Local(name = "bogeyPosOffset") BlockPos bogeyPosOffset,
            @Local(name = "upsideDownBogeyPosOffset") BlockPos upsideDownBogeyPosOffset,
            @Local(name = "points") List<TravellingPoint> points) {
        BlockPos secondBogeyPos = original.call(contraption);
        ksg$trailingBogeyIndex = -1;
        ksg$pendingRedundantBogeys = null;
        ksg$pendingRedundantBogeyPositions = null;

        CarriageContraptionAccess accessor = (CarriageContraptionAccess) (Object) contraption;
        if (!CreateConfig.isMultiBogeyCarriageEnabled())
            return secondBogeyPos;
        if (secondBogeyPos == null || !accessor.kasuga$isMoreThan2Bogeys())
            return secondBogeyPos;

        Set<BlockPos> captured = new HashSet<>(accessor.kasuga$getRedundantBogeys());
        captured.add(secondBogeyPos);

        List<Integer> interiorIndices = new ArrayList<>();
        int trailing = -1;
        int matched = 0;
        for (int index = bogeyIndex + 1; index < bogeyCount; index++) {
            if (captured.remove(kasuga$expectedPosition(index, bogeyPosOffset, upsideDownBogeyPosOffset))) {
                matched++;
                trailing = index;
                interiorIndices.add(index);
            }
        }

        // Accept only a contiguous group starting right after the leading bogey: matched ==
        // trailing - bogeyIndex means no gaps, an empty set means no captures outside the group.
        if (trailing <= bogeyIndex + 1 || !captured.isEmpty() || matched != trailing - bogeyIndex)
            return secondBogeyPos;

        // The last match is the trailing bogey; the rest sit inside the carriage. Build them from
        // the original slots: pairSlot itself is interior at this point and is overwritten below.
        interiorIndices.remove(interiorIndices.size() - 1);
        List<CarriageBogey> redundantBogeys = new ArrayList<>(interiorIndices.size());
        List<BlockPos> redundantPositions = new ArrayList<>(interiorIndices.size());
        for (int index : interiorIndices) {
            BlockPos position = kasuga$expectedPosition(index, bogeyPosOffset, upsideDownBogeyPosOffset);
            if (!(kasuga$level().getBlockEntity(position) instanceof AbstractBogeyBlockEntity bogeyEntity))
                continue;
            redundantBogeys.add(new CarriageBogey(bogeyTypes[index], upsideDownBogeys[index],
                    bogeyEntity.getBogeyData(), points.get(index * 2), points.get(index * 2 + 1)));
            redundantPositions.add(position.subtract(contraption.anchor));
        }
        ksg$pendingRedundantBogeys = redundantBogeys;
        ksg$pendingRedundantBogeyPositions = redundantPositions;

        // Collapse the group onto the slot vanilla reads: with the trailing data in place,
        // expectedPosition(pairSlot) equals the returned position, so the equality check passes.
        int pairSlot = bogeyIndex + 1;
        bogeyLocations[pairSlot] = bogeyLocations[trailing];
        bogeyTypes[pairSlot] = bogeyTypes[trailing];
        upsideDownBogeys[pairSlot] = upsideDownBogeys[trailing];
        points.set(pairSlot * 2, points.get(trailing * 2));
        points.set(pairSlot * 2 + 1, points.get(trailing * 2 + 1));

        ksg$trailingBogeyIndex = trailing;
        return kasuga$expectedPosition(trailing, bogeyPosOffset, upsideDownBogeyPosOffset);
    }

    /**
     * Advances the loop past the group after the vanilla pairing consumed the collapsed pair.
     * Setting the local to {@code trailing - 1} lets the inner and for-update increments land on
     * {@code trailing} and {@code trailing + 1}; every other read of {@code bogeyIndex} for this
     * pairing is already evaluated here.
     */
    @Inject(
            method = "assemble",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/simibubi/create/content/trains/entity/CarriageBogey;<init>(Lcom/simibubi/create/content/trains/bogey/AbstractBogeyBlock;ZLnet/minecraft/nbt/CompoundTag;Lcom/simibubi/create/content/trains/entity/TravellingPoint;Lcom/simibubi/create/content/trains/entity/TravellingPoint;)V",
                    ordinal = 1
            )
    )
    private void kasuga$skipInteriorBogeys(CallbackInfo ci, @Local(name = "bogeyIndex") LocalIntRef bogeyIndex) {
        if (ksg$trailingBogeyIndex < 0)
            return;
        bogeyIndex.set(ksg$trailingBogeyIndex - 1);
        ksg$trailingBogeyIndex = -1;
    }

    /**
     * Attaches the pending interior bogeys once vanilla adds the new {@link Carriage} (the first
     * moment it exists). All {@code List.add} sites share the erased descriptor, hence the
     * {@code instanceof Carriage} check. Clear the pending lists to keep them per-iteration.
     */
    @WrapOperation(
            method = "assemble",
            at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z")
    )
    private boolean kasuga$registerRedundantBogeys(List<?> list, Object added, Operation<Boolean> original) {
        if (added instanceof Carriage carriage &&
                ksg$pendingRedundantBogeys != null &&
                !ksg$pendingRedundantBogeys.isEmpty()) {
            for (CarriageBogey redundant : ksg$pendingRedundantBogeys)
                redundant.carriage = carriage;
            CarriageAccess access = (CarriageAccess) (Object) carriage;
            access.kasuga$addRedundantBogeys(ksg$pendingRedundantBogeys);
            if (ksg$pendingRedundantBogeyPositions != null)
                access.kasuga$addRedundantBogeyPositions(ksg$pendingRedundantBogeyPositions);
            ksg$pendingRedundantBogeys = null;
            ksg$pendingRedundantBogeyPositions = null;
        }
        return original.call(list, added);
    }

    /**
     * Pushes the fresh train's interior bogeys to every online player after the vanilla
     * {@code AddTrainPacket} broadcast, so the client already knows the {@link Train} when
     * {@link CarriageExtraBogeySync}'s handler runs.
     */
    @Inject(
            method = "assemble",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/createmod/catnip/platform/services/NetworkHelper;sendToAllClients(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V",
                    shift = At.Shift.AFTER
            )
    )
    private void kasuga$syncExtraBogeys(CallbackInfo ci, @Local(name = "train") Train train) {
        if (!CreateConfig.isMultiBogeyCarriageEnabled())
            return;
        MinecraftServer server = kasuga$level().getServer();
        if (server == null)
            return;
        for (ServerPlayer player : server.getPlayerList().getPlayers())
            CarriageExtraBogeySync.send(player, train);
    }

    /** Expected world position of station bogey {@code index}, mirroring the vanilla expression. */
    @Unique
    private BlockPos kasuga$expectedPosition(int index, BlockPos bogeyPosOffset, BlockPos upsideDownBogeyPosOffset) {
        return (upsideDownBogeys[index] ? upsideDownBogeyPosOffset : bogeyPosOffset)
                .relative(assemblyDirection, bogeyLocations[index] + 1);
    }

    /** The enclosing {@link StationBlockEntity}'s level, without adding a field shadow. */
    @Unique
    private Level kasuga$level() {
        return ((BlockEntity) (Object) this).getLevel();
    }
}
