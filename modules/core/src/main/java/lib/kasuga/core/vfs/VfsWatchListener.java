package lib.kasuga.core.vfs;

/**
 * Callback invoked when files or directories inside a watched VFS prefix change.
 */
@FunctionalInterface
public interface VfsWatchListener {
    void onChange(VfsChangeEvent event);
}
