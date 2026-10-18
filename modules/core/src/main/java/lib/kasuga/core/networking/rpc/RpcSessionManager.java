package lib.kasuga.core.networking.rpc;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

public class RpcSessionManager<T> {
    protected AtomicLong sessionIdCounter = new AtomicLong(0);

    public record Session<T>(Long id, CompletableFuture<T> future, long timeoutAt, Object accepts) implements Comparable<Session<T>> {
        @Override
        public int compareTo(@NotNull RpcSessionManager.Session<T> o) {
            return Long.compare(this.timeoutAt, o.timeoutAt);
        }

        public <S> IdentifiedRpcPacketType<S>.Packet wrap(IdentifiedRpcPacketType<S> wrapper,S value) {
            return wrapper.wrap(id, value);
        }
    }

    /**
     * Serializes every mutation of {@link #sessions} / {@link #sessionMap}. CompletableFutures are
     * completed *outside* the lock so a completed future's callbacks can never re-enter this manager
     * while it is still holding the lock (deadlock / re-entrancy guard).
     */
    protected final Object lock = new Object();

    protected PriorityQueue<Session<T>> sessions = new PriorityQueue<>(16);
    protected HashMap<Long, Session<T>> sessionMap = new HashMap<>(16);

    protected long nextSessionId() {
        return sessionIdCounter.incrementAndGet();
    }

    public Session<T> assign(long timeout, Object accepts) {
        Session<T> session;
        synchronized (lock) {
            long id = nextSessionId();
            // saturatedAdd: a huge timeout (e.g. Long.MAX_VALUE - 1) must not overflow the deadline
            // into the past, which would make every session time out on the first checkTimeout().
            session = new Session<>(id, new CompletableFuture<>(), saturatedAdd(System.currentTimeMillis(), timeout), accepts);
            sessions.add(session);
            sessionMap.put(id, session);
        }
        return session;
    }

    /**
     * Drain every expired session (deadline &le; now) and complete its future exceptionally.
     * Returns the timed-out sessions in deadline order; an empty list when none expired.
     * Completion runs outside the internal lock. The caller decides the thread this runs on
     * (typically {@code RpcApi.tick()} on the network thread).
     */
    public List<Session<T>> checkTimeout() {
        long now = System.currentTimeMillis();
        List<Session<T>> expired = new ArrayList<>();
        synchronized (lock) {
            Session<T> session;
            while ((session = sessions.peek()) != null && session.timeoutAt <= now) {
                sessions.poll();
                sessionMap.remove(session.id);
                expired.add(session);
            }
        }
        for (Session<T> session : expired) {
            session.future.completeExceptionally(new RpcTimeoutException("RPC Session " + session.id + " timed out."));
        }
        return expired;
    }

    public void accept(Long id, T result, Object player) {
        Session<T> session = take(id, player);
        if (session != null) {
            session.future.complete(result);
        }
    }

    public void reject(Long id, Exception e, Object player) {
        Session<T> session = take(id, player);
        if (session != null) {
            session.future.completeExceptionally(e);
        }
    }

    /**
     * Lock-protected bookkeeping for accept/reject: player matching + cleanup from both structures.
     * Returns the session whose future must be completed by the caller (outside the lock), or null
     * when the session is unknown or the player is not the expected acceptor.
     */
    private Session<T> take(Long id, Object player) {
        synchronized (lock) {
            Session<T> session = sessionMap.get(id);
            if (session == null || (session.accepts() != null && !session.accepts.equals(player))) {
                return null;
            }
            sessions.remove(session);
            sessionMap.remove(id);
            return session;
        }
    }

    /** {@code base + delta} saturated at {@link Long#MAX_VALUE} — addition overflow must never turn
     * a timeout deadline negative (the "double-kill" bug where the first tick() dropped every session). */
    static long saturatedAdd(long base, long delta) {
        try {
            return Math.addExact(base, delta);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
