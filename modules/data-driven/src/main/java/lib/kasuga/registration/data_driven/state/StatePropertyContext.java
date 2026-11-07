package lib.kasuga.registration.data_driven.state;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

import java.util.Objects;
import java.util.function.Supplier;

/** Scoped bridge for Block's superclass constructor, which builds the state definition first. */
public final class StatePropertyContext {
    private static final ThreadLocal<StatePropertySet> CURRENT = new ThreadLocal<>();

    private StatePropertyContext() {}

    /** Construct one opted-in block; nested calls and exceptions restore the previous context. */
    public static <T> T construct(StatePropertySet properties, Supplier<T> constructor) {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(constructor, "constructor");
        StatePropertySet previous = CURRENT.get();
        CURRENT.set(properties);
        try {
            return constructor.get();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    /** Called from an opted-in block's {@code createBlockStateDefinition} override. */
    public static void addTo(StateDefinition.Builder<Block, BlockState> builder) {
        StatePropertySet properties = CURRENT.get();
        if (properties != null) properties.addTo(builder);
    }

    /** Called after {@code super(properties)} and before registering the block's default state. */
    public static BlockState applyDefaults(BlockState state) {
        StatePropertySet properties = CURRENT.get();
        return properties == null ? state : properties.applyDefaults(state);
    }
}
