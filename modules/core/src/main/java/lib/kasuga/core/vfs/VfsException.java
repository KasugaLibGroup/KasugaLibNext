package lib.kasuga.core.vfs;

import java.io.IOException;

/**
 * Base checked exception for Virtual File System I/O and state failures.
 */
public class VfsException extends IOException {
    public VfsException(String message) {
        super(message);
    }

    public VfsException(String message, Throwable cause) {
        super(message, cause);
    }
}
