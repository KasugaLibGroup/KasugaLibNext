package lib.kasuga.core.resource.lifecycle;

import java.util.Objects;
import java.util.concurrent.Executor;

public record InvocationContext(String scopeId, long generation, ResourceEvent event, LogicalSide side,
                                Executor preparationExecutor, Executor publicationExecutor) {
    public InvocationContext {
        Objects.requireNonNull(scopeId); Objects.requireNonNull(event); Objects.requireNonNull(side);
        Objects.requireNonNull(preparationExecutor); Objects.requireNonNull(publicationExecutor);
        if (side == LogicalSide.BOTH || generation < 1) throw new IllegalArgumentException("Concrete invocation required");
    }
    public InvocationContext neutral() { return new InvocationContext(scopeId, generation, event, LogicalSide.NONE, preparationExecutor, publicationExecutor); }
}
