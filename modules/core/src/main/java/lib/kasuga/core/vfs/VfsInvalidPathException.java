package lib.kasuga.core.vfs;

/**
 * Thrown when a logical or physical VFS path violates cross-platform naming or sandbox rules.
 */
public class VfsInvalidPathException extends IllegalArgumentException {
    public VfsInvalidPathException(String message) {
        super(message);
    }

    public VfsInvalidPathException(String message, Throwable cause) {
        super(message, cause);
    }
}
