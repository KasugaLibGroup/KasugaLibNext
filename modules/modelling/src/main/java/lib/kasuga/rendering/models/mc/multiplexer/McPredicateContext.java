package lib.kasuga.rendering.models.mc.multiplexer;

import lib.kasuga.rendering.models.uml.dynamic.multiplexer.SelectorPredicates;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.function.Predicate;

/** Static factories for Minecraft-specific {@link McContext} predicates. */
public final class McPredicateContext {

    private McPredicateContext() {}

    public static Predicate<McContext> powerAtLeast(int min) {
        return context -> context.redstonePower() >= min;
    }

    public static Predicate<McContext> hasTag(ResourceLocation tag) {
        return context -> context.tags().contains(tag);
    }

    /** {@code direction} is a 0..5 index into the neighbor list (down/up/north/south/west/east). */
    public static Predicate<McContext> neighborIs(int direction, String blockId) {
        return context -> {
            List<String> neighbors = context.neighbors();
            if (direction < 0 || direction >= neighbors.size()) {
                return false;
            }
            return blockId.equals(neighbors.get(direction));
        };
    }

    /** Delegates to the generic property predicate; provided here for discoverability. */
    public static Predicate<McContext> propertyIs(String name, String value) {
        return SelectorPredicates.propertyIs(name, value);
    }
}
