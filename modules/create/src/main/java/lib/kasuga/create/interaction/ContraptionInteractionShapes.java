package lib.kasuga.create.interaction;

import com.simibubi.create.content.contraptions.Contraption;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/** Selection adaptation for explicitly opted-in blocks, including shapes outside their anchor cell. */
public final class ContraptionInteractionShapes {
    private ContraptionInteractionShapes() {
    }

    public static VoxelShape shape(
            BlockState state,
            Contraption contraption,
            BlockGetter dimensions,
            BlockPos localPos,
            Supplier<VoxelShape> original
    ) {
        return state.getBlock() instanceof ContraptionShapeProvider provider
                ? provider.getContraptionShape(state, new ContraptionBlockView(contraption, dimensions), localPos)
                : original.get();
    }

    /** Create's native search omits anchors below the ray cell; preserve its nearest ordinary hit. */
    public static @Nullable BlockHitResult pick(
            Contraption contraption,
            BlockGetter dimensions,
            Vec3 localOrigin,
            Vec3 localTarget,
            @Nullable BlockHitResult nativeHit
    ) {
        BlockHitResult closest = nativeHit;
        double distance = nativeHit == null ? Double.POSITIVE_INFINITY
                : localOrigin.distanceToSqr(nativeHit.getLocation());
        BlockGetter localBlocks = new ContraptionBlockView(contraption, dimensions);
        for (var entry : contraption.getBlocks().entrySet()) {
            BlockPos localPos = entry.getKey();
            BlockState state = entry.getValue().state();
            if (!(state.getBlock() instanceof ContraptionShapeProvider provider)
                    || contraption.isHiddenInPortal(localPos)) {
                continue;
            }
            BlockHitResult hit = provider.getContraptionShape(state, localBlocks, localPos)
                    .clip(localOrigin, localTarget, localPos);
            if (hit == null) {
                continue;
            }
            double candidateDistance = localOrigin.distanceToSqr(hit.getLocation());
            if (candidateDistance < distance) {
                closest = hit;
                distance = candidateDistance;
            }
        }
        return closest;
    }
}
