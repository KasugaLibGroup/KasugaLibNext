package lib.kasuga.rendering.models.mc.dynamic.fsm;

import lib.kasuga.rendering.models.uml.dynamic.fsm.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.sync.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.function.*;
import lib.kasuga.rendering.models.mc.dynamic.fsm.cap.AnimationCapabilities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

/**
 * Lookup helpers for host machines. Primary path is the {@link AnimationCapabilities#MACHINE_BLOCK}
 * block capability (registered for every BE type by {@code AnimationCapabilityRegistrar}); a direct
 * {@code instanceof AnimationHost} scan on the block entity is the fallback.
 */
public final class MachineQuery {

    private MachineQuery() {}

    /** Machine of the block at {@code pos}, or {@code null} when absent / not an animation host. */
    @Nullable
    public static StateMachine<?> machineAt(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return null;
        }
        AnimationHost host = level.getCapability(AnimationCapabilities.MACHINE_BLOCK, pos, null);
        if (host != null) {
            return host.machine();
        }
        if (level.getBlockEntity(pos) instanceof AnimationHost fallback) {
            return fallback.machine();
        }
        return null;
    }

    /** Machine of the given block entity, or {@code null} when it is not an animation host. */
    @Nullable
    public static StateMachine<?> machineOf(BlockEntity blockEntity) {
        if (blockEntity instanceof AnimationHost host) {
            return host.machine();
        }
        return null;
    }
}
