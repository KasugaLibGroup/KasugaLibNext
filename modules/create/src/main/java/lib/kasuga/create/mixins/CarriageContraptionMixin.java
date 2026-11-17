package lib.kasuga.create.mixins;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import lib.kasuga.create.CreateConfig;
import lib.kasuga.create.content.train.CarriageContraptionAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.apache.commons.lang3.tuple.Pair;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Allows a connected carriage structure to capture more than two bogeys and exposes the bogeys
 * between the leading and the trailing one as redundant.
 *
 * <p>Vanilla enforces the two-bogey limit twice: {@code assemble} throws when the captured count
 * exceeds two, and {@code capture} only remembers the second bogey. The guard is defused in place by
 * clamping the counter (cancelling would skip the rest of {@code assemble}), and the {@code capture}
 * check is widened to {@code >= 2} so the last captured bogey is still recorded.
 */
@Mixin(value = CarriageContraption.class, remap = false)
public class CarriageContraptionMixin implements CarriageContraptionAccess {

    /** Vanilla captured-bogey counter, parked at {@code 2} while the guard is defused. */
    @Shadow
    private int bogeys;

    /** Real captured-bogey count, saved right before the {@code bogeys > 2} guard. */
    @Unique
    private int ksg$bogeys;

    /**
     * Captured bogeys between the leading and the last one; only valid when more than two were
     * captured.
     */
    @Unique
    private final List<BlockPos> ksg$redundantBogeys = new ArrayList<>();

    /**
     * Defuses the {@code train_assembly.too_many_bogeys} guard by saving the real count and parking
     * the field at {@code 2}, so {@code assemble} falls through to its remaining logic (a cancel
     * would skip it). The last captured bogey stays in {@code secondBogeyPos}, so it is trimmed from
     * {@link #ksg$redundantBogeys}.
     */
    @Inject(
            method = "assemble",
            at = @At(
                    value = "FIELD",
                    opcode = Opcodes.GETFIELD,
                    target = "Lcom/simibubi/create/content/trains/entity/CarriageContraption;bogeys:I",
                    ordinal = 1
            )
    )
    private void kasuga$continuePastTooManyBogeys(Level level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (!CreateConfig.isMultiBogeyCarriageEnabled())
            return;
        if (bogeys > 2) {
            ksg$bogeys = bogeys;
            bogeys = 2;
            if (!ksg$redundantBogeys.isEmpty())
                ksg$redundantBogeys.removeLast();
        }
    }

    /**
     * Widens {@code capture}'s {@code bogeys == 2} test to {@code >= 2} by clamping the compared
     * read to two; every bogey from the second onwards then overwrites {@code secondBogeyPos}. The
     * read feeding {@code bogeys++} is untouched, so the counter stays exact.
     */
    @ModifyExpressionValue(
            method = "capture",
            at = @At(
                    value = "FIELD",
                    opcode = Opcodes.GETFIELD,
                    target = "Lcom/simibubi/create/content/trains/entity/CarriageContraption;bogeys:I",
                    ordinal = 1
            )
    )
    private int kasuga$widenSecondBogeyCheck(int bogeys) {
        return CreateConfig.isMultiBogeyCarriageEnabled() ? Math.min(bogeys, 2) : bogeys;
    }

    /** Records every bogey assigned to {@code secondBogeyPos}; the last one is trimmed in {@code assemble}. */
    @Inject(
            method = "capture",
            at = @At(
                    value = "FIELD",
                    opcode = Opcodes.PUTFIELD,
                    target = "Lcom/simibubi/create/content/trains/entity/CarriageContraption;secondBogeyPos:Lnet/minecraft/core/BlockPos;"
            )
    )
    private void kasuga$collectRedundantBogey(Level level, BlockPos pos,
                                              CallbackInfoReturnable<Pair<StructureBlockInfo, BlockEntity>> cir) {
        if (CreateConfig.isMultiBogeyCarriageEnabled())
            ksg$redundantBogeys.add(pos);
    }

    /** True when more than two bogeys were captured; the other accessors are only meaningful then. */
    @Override
    public boolean kasuga$isMoreThan2Bogeys() {
        return CreateConfig.isMultiBogeyCarriageEnabled() && ksg$bogeys > 2;
    }

    /** Live list; see {@link #ksg$redundantBogeys} for contents and validity. */
    @Override
    public List<BlockPos> kasuga$getRedundantBogeys() {
        return ksg$redundantBogeys;
    }
}
