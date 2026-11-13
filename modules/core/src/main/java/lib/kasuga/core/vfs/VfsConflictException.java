package lib.kasuga.core.vfs;

import java.util.List;

/**
 * Thrown when multiple VFS delegates supply conflicting entries under {@link VfsConflictPolicy#THROW_ON_CONFLICT}.
 */
public class VfsConflictException extends VfsException {
    private final VfsPath path;
    private final List<String> conflictingSources;

    public VfsConflictException(VfsPath path, List<String> conflictingSources) {
        super("Conflicting VFS entries for path '" + path + "' from sources: " + conflictingSources);
        this.path = path;
        this.conflictingSources = List.copyOf(conflictingSources);
    }

    public VfsPath getPath() {
        return path;
    }

    public List<String> getConflictingSources() {
        return conflictingSources;
    }
}
