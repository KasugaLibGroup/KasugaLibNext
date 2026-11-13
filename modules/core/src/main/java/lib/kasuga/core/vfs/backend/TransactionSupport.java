package lib.kasuga.core.vfs.backend;

import lib.kasuga.core.vfs.VfsChangeEvent;
import lib.kasuga.core.vfs.VfsPreparedTransaction;
import lib.kasuga.core.vfs.VfsWriteTransaction;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/** Serializes reservation acquisition so concurrent coordinators cannot acquire backends in reverse order. */
final class TransactionSupport {
    private static final ReentrantLock PREPARATION_LOCK = new ReentrantLock();

    private TransactionSupport() {}

    static List<VfsChangeEvent> commit(List<? extends VfsWriteTransaction> transactions) throws IOException {
        List<VfsPreparedTransaction> prepared = new ArrayList<>();
        PREPARATION_LOCK.lock();
        try {
            for (VfsWriteTransaction transaction : transactions) {
                prepared.add(transaction.prepare());
            }
            for (VfsPreparedTransaction participant : prepared) {
                participant.apply();
            }
            for (VfsPreparedTransaction participant : prepared) {
                participant.release();
            }
        } catch (IOException | RuntimeException | Error failure) {
            for (int i = prepared.size() - 1; i >= 0; i--) {
                try {
                    prepared.get(i).rollback();
                } catch (IOException | RuntimeException recoveryFailure) {
                    failure.addSuppressed(recoveryFailure);
                }
            }
            throw failure;
        } finally {
            PREPARATION_LOCK.unlock();
        }
        // No backend or coordinator lock may be held while invoking user callbacks.
        List<VfsChangeEvent> events = new ArrayList<>();
        for (VfsPreparedTransaction participant : prepared) {
            events.add(participant.publish());
        }
        return events;
    }
}
