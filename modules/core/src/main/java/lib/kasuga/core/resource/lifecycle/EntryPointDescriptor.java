package lib.kasuga.core.resource.lifecycle;

import java.util.Objects;
import java.util.Set;

public record EntryPointDescriptor(String id, Set<String> inputTypes, Set<ResourceEvent> events,
                                   Set<LogicalSide> sides, Set<String> dependencies, boolean reloadable) {
    public EntryPointDescriptor {
        Objects.requireNonNull(id); inputTypes = Set.copyOf(inputTypes); events = Set.copyOf(events);
        sides = Set.copyOf(sides); dependencies = Set.copyOf(dependencies);
        if (sides.contains(LogicalSide.BOTH)) throw new IllegalArgumentException("Declare concrete supported sides");
    }
}
