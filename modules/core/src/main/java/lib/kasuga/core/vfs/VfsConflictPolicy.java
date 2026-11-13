package lib.kasuga.core.vfs;

/**
 * Conflict resolution policy for {@link lib.kasuga.core.vfs.backend.CompositeVfs}.
 */
public enum VfsConflictPolicy {
    FIRST_WINS,
    LAST_WINS,
    THROW_ON_CONFLICT
}
