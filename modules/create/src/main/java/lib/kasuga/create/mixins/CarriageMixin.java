package lib.kasuga.create.mixins;

import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageBogey;
import com.simibubi.create.content.trains.graph.DimensionPalette;
import com.simibubi.create.content.trains.graph.TrackGraph;
import lib.kasuga.create.CreateConfig;
import lib.kasuga.create.content.train.CarriageAccess;
import net.createmod.catnip.animation.LerpedFloat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Stores and persists the extra interior bogeys of a multi-bogey carriage.
 *
 * <p>{@link #ksg$redundantBogeys} and {@link #ksg$redundantBogeyPositions} are index-aligned and
 * filled during station assembly. {@code write} appends them as {@code KasugaRedundantBogeys}: a
 * list of {@code {BogeyData, Position}} tuples where {@code BogeyData} is a full
 * {@code CarriageBogey#write} tag (type, travelling points, orientation and style data), so
 * {@code read} can rebuild each bogey exactly with the same graph and dimension palette as the
 * vanilla bogey entries. This NBT path is only used on world save/load; client sync goes through
 * {@code CarriageExtraBogeySync}.
 */
@Mixin(value = Carriage.class, remap = false)
public class CarriageMixin implements CarriageAccess {

    /** NBT key of the list of {@code (BogeyData, Position)} tuples appended at {@code write} return. */
    @Unique
    private static final String KSG_REDUNDANT_BOGEYS_KEY = "KasugaRedundantBogeys";

    /*
     * These fields deliberately have no initialisers: Mixin merges field initialisers into the
     * target constructor by line-number range, and a multi-line initialiser added here made it
     * drop the earlier ones (the lists stayed null after construction). The lazy accessors below
     * are independent of initialiser merging.
     */

    /** Interior bogeys in track order; index-aligned with {@link #ksg$redundantBogeyPositions}. */
    @Unique
    private List<CarriageBogey> ksg$redundantBogeys;

    /** Carriage-local positions of {@link #ksg$redundantBogeys}; same index means same bogey. */
    @Unique
    private List<BlockPos> ksg$redundantBogeyPositions;

    /**
     * Server-authoritative vertical offset. A plain value: the server updates it once per tick and
     * never interpolates. Lives on the carriage rather than the entity so every per-dimension
     * entity of the carriage shares it and it survives world saves.
     */
    @Unique
    private float ksg$yOffset;

    /**
     * Client-side smoothed offset. Null until the first sync on the client; it eases towards the
     * last synced server value instead of snapping to it.
     */
    @Unique
    private LerpedFloat ksg$clientYOffset;

    /** Units per tick the client offset may move towards the synced value. */
    @Unique
    private static final float KSG_Y_OFFSET_CHASE_SPEED = 0.5f;

    /** Phase of the demo offset animation, advanced once per server tick. */
    @Unique
    private float ksg$yOffsetTick;

    /** NBT key of the persisted offset. */
    @Unique
    private static final String KSG_Y_OFFSET_KEY = "KasugaYOffset";

    @Unique
    private List<CarriageBogey> ksg$redundantBogeys() {
        if (ksg$redundantBogeys == null)
            ksg$redundantBogeys = new ArrayList<>();
        return ksg$redundantBogeys;
    }

    @Unique
    private List<BlockPos> ksg$redundantBogeyPositions() {
        if (ksg$redundantBogeyPositions == null)
            ksg$redundantBogeyPositions = new ArrayList<>();
        return ksg$redundantBogeyPositions;
    }

    @Unique
    private LerpedFloat ksg$clientYOffset() {
        if (ksg$clientYOffset == null) {
            ksg$clientYOffset = LerpedFloat.linear();
            ksg$clientYOffset.startWithValue(ksg$yOffset);
        }
        return ksg$clientYOffset;
    }

    @Override
    public void kasuga$addRedundantBogeys(Collection<CarriageBogey> bogeys) {
        ksg$redundantBogeys().addAll(bogeys);
    }

    @Override
    public List<CarriageBogey> kasuga$getRedundantBogeys() {
        return ksg$redundantBogeys();
    }

    @Override
    public void kasuga$addRedundantBogeyPositions(Collection<BlockPos> positions) {
        ksg$redundantBogeyPositions().addAll(positions);
    }

    @Override
    public List<BlockPos> kasuga$getRedundantBogeyPositions() {
        return ksg$redundantBogeyPositions();
    }

    @Override
    public float kasuga$getYOffset() {
        return ksg$clientYOffset == null ? ksg$yOffset : ksg$clientYOffset.getValue();
    }

    @Override
    public float kasuga$getInterpolatedYOffset(float partialTicks) {
        return ksg$clientYOffset == null ? ksg$yOffset : ksg$clientYOffset.getValue(partialTicks);
    }

    @Override
    public void kasuga$restoreYOffset(float value) {
        ksg$yOffset = value;
        if (ksg$clientYOffset != null)
            ksg$clientYOffset.startWithValue(value);
    }

    @Override
    public void kasuga$applySyncedYOffset(float value) {
        ksg$clientYOffset().chase(value, KSG_Y_OFFSET_CHASE_SPEED, LerpedFloat.Chaser.LINEAR);
    }

    @Override
    public void kasuga$tickYOffsetInterpolation() {
        if (ksg$clientYOffset != null)
            ksg$clientYOffset.tickChaser();
    }

    /**
     * Advances the offset once per server tick. The client copy is driven by the synced entity
     * data instead, so this deliberately ignores the client-side runs of {@code manageEntities}.
     * This is the feature's single source of truth: when the config switch is off the value is
     * forced to 0, which makes every consumer below a no-op on both sides.
     */
    @Inject(method = "manageEntities", at = @At("TAIL"))
    private void kasuga$tickYOffset(Level level, CallbackInfo ci) {
        if (level.isClientSide())
            return;
        if (!CreateConfig.isCarriageYOffestEnabled()) {
            ksg$yOffset = 0;
            return;
        }
        // TODO: replace the demo sine with the real posture computation.
        ksg$yOffset = .5f * (float) Math.sin(ksg$yOffsetTick * 2 * Math.PI);
        ksg$yOffsetTick += 0.01f;
        if (ksg$yOffsetTick > 1) ksg$yOffsetTick = 0;
    }

    /**
     * Appends the redundant bogeys to the tag vanilla {@code write} just produced. Paired with
     * {@code Math.min} so a size drift cannot index out of bounds; normal carriages keep their NBT
     * unchanged.
     */
    @Inject(method = "write", at = @At("RETURN"))
    private void kasuga$writeRedundantBogeys(DimensionPalette dimensions, HolderLookup.Provider registries,
                                             CallbackInfoReturnable<CompoundTag> cir) {
        if (CreateConfig.isCarriageYOffestEnabled() && ksg$yOffset != 0)
            cir.getReturnValue().putFloat(KSG_Y_OFFSET_KEY, ksg$yOffset);
        if (!CreateConfig.isMultiBogeyCarriageEnabled())
            return;
        List<CarriageBogey> bogeys = ksg$redundantBogeys();
        List<BlockPos> positions = ksg$redundantBogeyPositions();
        int count = Math.min(bogeys.size(), positions.size());
        if (count == 0)
            return;

        ListTag tuples = new ListTag();
        for (int i = 0; i < count; i++) {
            CompoundTag tuple = new CompoundTag();
            tuple.put("BogeyData", bogeys.get(i).write(dimensions));
            tuple.put("Position", NbtUtils.writeBlockPos(positions.get(i)));
            tuples.add(tuple);
        }
        cir.getReturnValue().put(KSG_REDUNDANT_BOGEYS_KEY, tuples);
    }

    /**
     * Rebuilds the redundant bogeys before vanilla {@code read} returns, using the same
     * {@code graph}/{@code dimensions} as the vanilla bogey entries. A tuple without a readable
     * position is skipped as a whole so both lists stay index-aligned.
     */
    @Inject(method = "read", at = @At("RETURN"))
    private static void kasuga$readRedundantBogeys(CompoundTag tag, HolderLookup.Provider registries,
                                                   TrackGraph graph, DimensionPalette dimensions,
                                                   CallbackInfoReturnable<Carriage> cir) {
        Carriage carriage = cir.getReturnValue();
        if (CreateConfig.isCarriageYOffestEnabled()) {
            ((CarriageAccess) (Object) carriage).kasuga$restoreYOffset(tag.getFloat(KSG_Y_OFFSET_KEY));
        }
        if (!CreateConfig.isMultiBogeyCarriageEnabled())
            return;
        ListTag tuples = tag.getList(KSG_REDUNDANT_BOGEYS_KEY, Tag.TAG_COMPOUND);
        if (tuples.isEmpty())
            return;
        List<CarriageBogey> bogeys = new ArrayList<>(tuples.size());
        List<BlockPos> positions = new ArrayList<>(tuples.size());
        for (int i = 0; i < tuples.size(); i++) {
            CompoundTag tuple = tuples.getCompound(i);
            Optional<BlockPos> position = NbtUtils.readBlockPos(tuple, "Position");
            if (position.isEmpty())
                continue;
            CarriageBogey bogey = CarriageBogey.read(tuple.getCompound("BogeyData"), graph, dimensions);
            bogey.carriage = carriage;
            bogeys.add(bogey);
            positions.add(position.get());
        }

        CarriageAccess access = (CarriageAccess) (Object) carriage;
        access.kasuga$addRedundantBogeys(bogeys);
        access.kasuga$addRedundantBogeyPositions(positions);
    }
}
