package lib.kasuga.registration.core;

import lib.kasuga.registration.Reg;

import java.util.function.BiFunction;

/**
 * Walks up the {@link Reg} parent chain from a child registration, looking for the nearest
 * ancestor whose projected value matches the requested type.
 * <p>
 * This is what lets a child (for example a fluid block or a bucket) reach into the fluid
 * registration that owns it without the owner having to be resolved from a registry first,
 * so no cross-registry object is resolved while its own registration event is still running.
 */
public final class ChildrenLookup {
    private ChildrenLookup() {}

    /**
     * @param start      the child registration to search from.
     * @param projection projects a registration to the value we are looking for, may return null.
     * @param type       the expected value type.
     * @return the nearest projected value of {@code type}, or {@code null} if no ancestor provides one.
     */
    @SuppressWarnings("unchecked")
    public static <T> T findParent(Reg<?, ?> start, Class<T> type, BiFunction<Reg<?, ?>, Class<T>, Object> projection) {
        Reg<?, ?> current = start;
        while (current != null) {
            Object projected = projection.apply(current, type);
            if (type.isInstance(projected)) {
                return (T) projected;
            }
            current = current.getParentReg();
        }
        return null;
    }
}
