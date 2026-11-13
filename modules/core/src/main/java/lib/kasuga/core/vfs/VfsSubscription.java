package lib.kasuga.core.vfs;

/**
 * Handle representing an active {@link VfsWatchListener} registration.
 * Closing is idempotent and detaches the listener safely.
 */
public interface VfsSubscription extends AutoCloseable {
    @Override
    void close();

    boolean isClosed();

    VfsPath watchedPrefix();
}
