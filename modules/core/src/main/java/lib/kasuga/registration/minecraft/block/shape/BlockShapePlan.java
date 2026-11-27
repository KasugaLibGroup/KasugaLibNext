package lib.kasuga.registration.minecraft.block.shape;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Immutable, fully compiled shape selections for a block's states.
 *
 * <p>The plan contains host-neutral float geometry. Minecraft conversion and cache construction
 * happen only when {@link BlockShapeBindings#install} runs, before the block states initialize
 * their caches.</p>
 */
public final class BlockShapePlan {
    private static final BlockShapePlan EMPTY = new BlockShapePlan(Map.of());

    private final SortedMap<BlockStateKey, BlockStateShapeBinding> states;

    private BlockShapePlan(Map<BlockStateKey, BlockStateShapeBinding> states) {
        this.states = Collections.unmodifiableSortedMap(new TreeMap<>(states));
    }

    public static BlockShapePlan empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Map<BlockStateKey, BlockStateShapeBinding> states() {
        return states;
    }

    public boolean isEmpty() {
        return states.isEmpty();
    }

    public static final class Builder {
        private final TreeMap<BlockStateKey, BlockStateShapeBinding> states = new TreeMap<>();

        public Builder state(BlockStateKey key, BlockStateShapeBinding binding) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(binding, "binding");
            if (states.putIfAbsent(key, binding) != null) {
                throw new IllegalArgumentException("Duplicate block state " + key.values());
            }
            return this;
        }

        public Builder state(
                BlockStateKey key,
                Map<ShapeSlot, ShapeSelection> selections
        ) {
            return state(key, new BlockStateShapeBinding(selections));
        }

        public BlockShapePlan build() {
            if (states.isEmpty()) return EMPTY;
            return new BlockShapePlan(states);
        }
    }
}
