package lib.kasuga.registration.data_driven.pipeline;

import lib.kasuga.registration.data_driven.context.JsonRegistryGroup;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;

/**
 * Result of applying one prepared session to a registration target.
 */
public record BuildResult(
        String hostModId,
        Status status,
        @Nullable JsonRegistryGroup rootGroup,
        List<BuildDiagnostic> diagnostics,
        List<Object> finalizerResults
) {
    public BuildResult {
        Objects.requireNonNull(hostModId, "hostModId");
        Objects.requireNonNull(status, "status");
        diagnostics = List.copyOf(diagnostics);
        finalizerResults = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(finalizerResults));
    }

    public boolean successful() {
        return status == Status.APPLIED;
    }

    public boolean applied() {
        return status != Status.REJECTED;
    }

    public enum Status {
        REJECTED,
        APPLIED,
        FAILED
    }
}
