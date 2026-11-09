package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.List;

/** Result of one transactional command submission. */
public record CommandCommit(
        List<CommandResult> applied,
        List<CommandResult> rejected,
        List<CommandResult> duplicates,
        int idempotentReuses,
        List<CommandResult> discarded,
        boolean aborted,
        RuntimeException failure
) {

    public CommandCommit {
        applied = List.copyOf(applied);
        rejected = List.copyOf(rejected);
        duplicates = List.copyOf(duplicates);
        discarded = List.copyOf(discarded);
    }

    public static CommandCommit aborted(RuntimeException failure) {
        return new CommandCommit(List.of(), List.of(), List.of(), 0, List.of(), true, failure);
    }
}
