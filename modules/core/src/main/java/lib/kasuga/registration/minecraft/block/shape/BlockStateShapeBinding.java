package lib.kasuga.registration.minecraft.block.shape;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Immutable selections for the six Minecraft shape slots of one exact block state. */
public final class BlockStateShapeBinding {
    private final Map<ShapeSlot, ShapeSelection> selections;

    public BlockStateShapeBinding(Map<ShapeSlot, ShapeSelection> selections) {
        Objects.requireNonNull(selections, "selections");
        EnumMap<ShapeSlot, ShapeSelection> copy = new EnumMap<>(ShapeSlot.class);
        for (Map.Entry<ShapeSlot, ShapeSelection> entry : selections.entrySet()) {
            copy.put(
                    Objects.requireNonNull(entry.getKey(), "shape slot"),
                    Objects.requireNonNull(entry.getValue(), "shape selection")
            );
        }
        this.selections = Collections.unmodifiableMap(copy);
    }

    public ShapeSelection selection(ShapeSlot slot) {
        return selections.getOrDefault(Objects.requireNonNull(slot, "slot"), ShapeSelection.MISSING);
    }

    public Map<ShapeSlot, ShapeSelection> selections() {
        return selections;
    }
}
