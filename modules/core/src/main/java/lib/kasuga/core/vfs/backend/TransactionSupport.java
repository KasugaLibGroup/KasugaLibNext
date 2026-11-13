package lib.kasuga.core.vfs.backend;

import lib.kasuga.core.vfs.VfsChangeEvent;
import lib.kasuga.core.vfs.VfsPreparedTransaction;
import lib.kasuga.core.vfs.VfsWriteTransaction;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.Comparator;
import java.util.IdentityHashMap;

/** Orders backend reservations consistently, allowing unrelated transactions to prepare in parallel. */
final class TransactionSupport {
    private static final ReentrantLock HASH_COLLISION_LOCK = new ReentrantLock();

    private TransactionSupport() {}

    static List<VfsChangeEvent> commit(List<? extends VfsWriteTransaction> transactions) throws IOException {
        return commit(transactions, VfsWriteTransaction::coordinationKey);
    }

    static List<VfsChangeEvent> commit(List<? extends VfsWriteTransaction> transactions,
                                     java.util.function.Function<VfsWriteTransaction, Object> identity) throws IOException {
        List<VfsPreparedTransaction> prepared = new ArrayList<>();
        List<VfsWriteTransaction> ordered = new ArrayList<>(transactions);
        ordered.sort(Comparator.comparingInt(tx -> System.identityHashCode(identity.apply(tx))));
        boolean collision = false;
        var identities = new IdentityHashMap<Object, Boolean>();
        Integer previousHash = null;
        for (var tx : ordered) {
            Object key = identity.apply(tx);
            if (identities.put(key, Boolean.TRUE) != null) throw new IOException("Duplicate backend reservation in one commit");
            int hash = System.identityHashCode(key);
            collision |= previousHash != null && previousHash == hash;
            previousHash = hash;
        }
        if (collision) HASH_COLLISION_LOCK.lock();
        try {
            for (VfsWriteTransaction transaction : ordered) transaction.stage();
            for (VfsWriteTransaction transaction : ordered) {
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
            for (VfsWriteTransaction transaction : ordered) {
                try { transaction.rollback(); }
                catch (RuntimeException recoveryFailure) { failure.addSuppressed(recoveryFailure); }
            }
            throw failure;
        } finally {
            if (collision) HASH_COLLISION_LOCK.unlock();
        }
        // No backend or coordinator lock may be held while invoking user callbacks.
        List<VfsChangeEvent> events = new ArrayList<>();
        for (VfsPreparedTransaction participant : prepared) {
            events.add(participant.publish());
        }
        return events;
    }
}
