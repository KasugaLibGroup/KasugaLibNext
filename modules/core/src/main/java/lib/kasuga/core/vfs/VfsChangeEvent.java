package lib.kasuga.core.vfs;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Immutable change notification emitted by a {@link VirtualFileSystem} upon internal writes
 * or detected external disk modifications.
 */
public record VfsChangeEvent(
        String sourceId,
        ChangeOrigin origin,
        long generation,
        long timestampMillis,
        List<EntryChange> changes
) {
    public VfsChangeEvent {
        changes = List.copyOf(changes);
    }

    /**
     * Checks whether any recorded change in this event affects {@code prefix} or its descendants.
     */
    public boolean affects(VfsPath prefix) {
        if (prefix.isRoot()) {
            return !changes.isEmpty();
        }
        for (EntryChange change : changes) {
            if (change.path().startsWith(prefix)
                    || (change.previousPath() != null && change.previousPath().startsWith(prefix))) {
                return true;
            }
        }
        return false;
    }

    public enum ChangeKind {
        CREATED,
        MODIFIED,
        DELETED,
        MOVED,
        BATCH
    }

    public enum ChangeOrigin {
        INTERNAL_WRITE,
        EXTERNAL_DISK
    }

    public record EntryChange(
            ChangeKind kind,
            VfsPath path,
            @Nullable VfsPath previousPath,
            @Nullable VfsNodeStat stat
    ) {
    }
}
