package lib.kasuga.rendering.models.uml.dynamic.fsm.function;

import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateContext;

/**
 * Guard predicate resolved at runtime from a data-driven {@link Id}.
 * Generic over the owner type so a Java-side condition reads a typed {@code StateContext<MyActor>} (no cast);
 * the data-driven factory stores {@code FsmCondition<?>} and casts to its own {@code <O>} at the boundary.
 */
@FunctionalInterface
public interface FsmCondition<O> {

    boolean test(StateContext<O> ctx);
}
