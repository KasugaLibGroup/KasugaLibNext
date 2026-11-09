package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/** Observable result of advancing a scope by one host submission. */
public record ScopeAdvanceResult(
        UUID scopeId,
        List<TemporalStep> steps,
        long firstRevision,
        long lastRevision,
        int droppedSteps,
        boolean paused,
        boolean closed,
        boolean aborted,
        @Nullable RuntimeException failure
) {

    public ScopeAdvanceResult(
            UUID scopeId,
            List<TemporalStep> steps,
            long firstRevision,
            long lastRevision,
            int droppedSteps,
            boolean paused,
            boolean closed,
            boolean aborted
    ) {
        this(scopeId, steps, firstRevision, lastRevision, droppedSteps,
                paused, closed, aborted, null);
    }

    public ScopeAdvanceResult {
        steps = List.copyOf(steps);
        if (!aborted && failure != null) {
            throw new IllegalArgumentException(
                    "a successful scope result cannot carry a failure");
        }
    }
}
