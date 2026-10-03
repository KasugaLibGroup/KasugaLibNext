package lib.kasuga.rendering.models.uml.dynamic.multiplexer;

import lib.kasuga.rendering.models.uml.dynamic.multiplexer.Blackboard;

import java.util.Objects;
import java.util.function.Predicate;

/** Static factories for common {@link Context} predicates. */
public final class SelectorPredicates {

    private SelectorPredicates() {}

    public static <C extends Context> Predicate<C> always() {
        return c -> true;
    }

    public static <C extends Context> Predicate<C> propertyIs(String name, String value) {
        return context -> value.equals(context.property(name));
    }

    /** Match a typed custom channel on the input's {@link Blackboard} (a {@code Boolean} key). */
    public static <C extends Context> Predicate<C> dataFlag(Blackboard.Key<Boolean> key) {
        return context -> Boolean.TRUE.equals(context.data().get(key));
    }

    /** Match a typed custom channel on the input's {@link Blackboard} by equality. */
    public static <C extends Context, T> Predicate<C> dataEquals(Blackboard.Key<T> key, T expected) {
        return context -> Objects.equals(expected, context.data().get(key));
    }

    /** Match a raw (string-named) custom channel by equality. */
    public static <C extends Context> Predicate<C> rawEquals(String name, Object expected) {
        return context -> Objects.equals(expected, context.data().get(name));
    }
}
