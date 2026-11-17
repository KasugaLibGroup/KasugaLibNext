package lib.kasuga.create.mixins;

import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageBogey;
import com.simibubi.create.content.trains.graph.DimensionPalette;
import com.simibubi.create.content.trains.graph.TrackGraph;
import lib.kasuga.create.CreateConfig;
import lib.kasuga.create.content.train.CarriageAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
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

    /** Interior bogeys in track order; index-aligned with {@link #ksg$redundantBogeyPositions}. */
    @Unique
    private final List<CarriageBogey> ksg$redundantBogeys = new ArrayList<>();

    /** Carriage-local positions of {@link #ksg$redundantBogeys}; same index means same bogey. */
    @Unique
    private final List<BlockPos> ksg$redundantBogeyPositions = new ArrayList<>();

    @Unique
    private List<CarriageBogey> getKsg$redundantBogeys() {
        return ksg$redundantBogeys;
    }

    @Override
    public void kasuga$addRedundantBogeys(Collection<CarriageBogey> bogeys) {
        ksg$redundantBogeys.addAll(bogeys);
    }

    @Override
    public List<CarriageBogey> kasuga$getRedundantBogeys() {
        return ksg$redundantBogeys;
    }

    @Override
    public void kasuga$addRedundantBogeyPositions(Collection<BlockPos> positions) {
        ksg$redundantBogeyPositions.addAll(positions);
    }

    @Override
    public List<BlockPos> kasuga$getRedundantBogeyPositions() {
        return ksg$redundantBogeyPositions;
    }

    /**
     * Appends the redundant bogeys to the tag vanilla {@code write} just produced. Paired with
     * {@code Math.min} so a size drift cannot index out of bounds; normal carriages keep their NBT
     * unchanged.
     */
    @Inject(method = "write", at = @At("RETURN"))
    private void kasuga$writeRedundantBogeys(DimensionPalette dimensions, HolderLookup.Provider registries,
                                             CallbackInfoReturnable<CompoundTag> cir) {
        if (!CreateConfig.isMultiBogeyCarriageEnabled())
            return;
        int count = Math.min(ksg$redundantBogeys.size(), ksg$redundantBogeyPositions.size());
        if (count == 0)
            return;

        ListTag tuples = new ListTag();
        for (int i = 0; i < count; i++) {
            CompoundTag tuple = new CompoundTag();
            tuple.put("BogeyData", ksg$redundantBogeys.get(i).write(dimensions));
            tuple.put("Position", NbtUtils.writeBlockPos(ksg$redundantBogeyPositions.get(i)));
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
        if (!CreateConfig.isMultiBogeyCarriageEnabled())
            return;
        ListTag tuples = tag.getList(KSG_REDUNDANT_BOGEYS_KEY, Tag.TAG_COMPOUND);
        if (tuples.isEmpty())
            return;

        Carriage carriage = cir.getReturnValue();
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
