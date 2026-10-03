package lib.kasuga.rendering.models.uml.dynamic.physics.core;

import java.util.List;
import java.util.Objects;

public record EnvironmentCell(List<EnvironmentBox> solids) {
    public static final EnvironmentCell EMPTY = new EnvironmentCell(List.of());

    public EnvironmentCell {
        solids = List.copyOf(Objects.requireNonNull(solids, "solids"));
    }
}
