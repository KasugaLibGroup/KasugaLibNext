package lib.kasuga.core.vfs;

import java.io.IOException;

/**
 * Reversible commit participant, confined to the thread that prepared it. Preparation reserves
 * backend state and must not change visible files or invoke listeners. Coordinators prepare all
 * participants, apply all, then release all reservations before publishing any notifications.
 * On failure, roll back every prepared participant in reverse order instead of releasing it.
 * This protocol handles in-process failures, not power loss or external process isolation.
 */
public interface VfsPreparedTransaction extends AutoCloseable {
    /** Applies staged mutations without notifying listeners; remains reversible until release. */
    void apply() throws IOException;

    /** Restores pre-transaction state and releases the reservation. Must tolerate repeated calls. */
    void rollback() throws IOException;

    /** Makes a successful apply irreversible and releases resources, without invoking listeners. */
    void release();

    /** Publishes the successful event after every participant has been released. */
    VfsChangeEvent publish();

    @Override
    default void close() throws IOException {
        rollback();
    }
}
