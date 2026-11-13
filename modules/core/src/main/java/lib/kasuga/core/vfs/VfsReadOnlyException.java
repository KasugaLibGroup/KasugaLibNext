package lib.kasuga.core.vfs;

/**
 * Thrown when a mutating operation is attempted against a read-only VFS mount.
 */
public class VfsReadOnlyException extends VfsException {
    public VfsReadOnlyException(String message) {
        super(message);
    }
}
