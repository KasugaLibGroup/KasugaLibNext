package lib.kasuga.scripting.resource;

import lib.kasuga.core.resource.script.ScriptContract;
import lib.kasuga.core.resource.script.ScriptTicket;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded MPSC submission ring; its single consumer is exactly the engine's ScriptThread. */
public final class ScriptSubmissionRing implements AutoCloseable {
    @FunctionalInterface public interface Invocation { Object invoke(List<Object> arguments) throws Exception; }
    private record Request(ScriptTicket.Submission completion, ScriptContract contract, List<Object> arguments, Invocation invocation) {}
    private final String scopeId;
    private final long generation;
    private final Thread owner;
    private final Runnable wakeup;
    private final ArrayBlockingQueue<Request> queue;
    private final Map<Long, ScriptTicket.Submission> pending = new ConcurrentHashMap<>();
    private final Object lifecycle = new Object();
    private volatile boolean closed;

    public ScriptSubmissionRing(String scopeId, long generation, Thread owner, int capacity, Runnable wakeup) {
        if (capacity < 1 || capacity > 65536) throw new IllegalArgumentException("Invalid ring capacity");
        this.scopeId = Objects.requireNonNull(scopeId); this.generation = generation;
        this.owner = Objects.requireNonNull(owner); this.wakeup = Objects.requireNonNull(wakeup);
        queue = new ArrayBlockingQueue<>(capacity);
    }
    public ScriptTicket submit(ScriptContract contract, List<?> arguments, Invocation invocation) {
        ScriptTicket.Submission completion = ScriptTicket.submission(scopeId, generation);
        ScriptTicket ticket = completion.ticket();
        List<Object> data;
        try { data = contract.freezeArguments(arguments); }
        catch (RuntimeException invalid) { completion.reject(invalid.getMessage()); return ticket; }
        synchronized (lifecycle) {
            if (closed) { ticket.cancel(); return ticket; }
            Request request = new Request(completion, contract, data, Objects.requireNonNull(invocation));
            pending.put(ticket.id(), completion);
            if (!queue.offer(request)) { pending.remove(ticket.id()); completion.reject("Script submission ring is full"); return ticket; }
        }
        wakeup.run();
        return ticket;
    }
    public boolean hasPending() { return !queue.isEmpty(); }
    public int pendingCount() { return pending.size(); }
    public int drain(int maximumRequests, long budgetNanos) {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Only the owning ScriptThread may drain submissions");
        if (maximumRequests < 1 || budgetNanos < 1) throw new IllegalArgumentException("Invalid drain budget");
        long start = System.nanoTime(); int processed = 0;
        while (processed < maximumRequests && !closed && System.nanoTime() - start < budgetNanos) {
            Request request = queue.poll(); if (request == null) break;
            var completion = request.completion();
            try {
                if (!closed && completion.start()) {
                    Object result = request.contract().freezeResult(request.invocation().invoke(request.arguments()));
                    if (closed) completion.ticket().cancel(); else completion.succeed(result);
                } else if (closed) completion.ticket().cancel();
            } catch (Throwable failure) {
                completion.fail(failure);
                if (failure instanceof Error fatal && !(fatal instanceof AssertionError)) throw fatal;
            }
            finally { pending.remove(completion.ticket().id()); }
            processed++;
        }
        return processed;
    }
    @Override public void close() {
        List<ScriptTicket.Submission> cancelled;
        synchronized (lifecycle) {
            if (closed) return; closed = true;
            cancelled = List.copyOf(pending.values()); pending.clear(); queue.clear();
        }
        cancelled.forEach(completion -> completion.ticket().cancel());
        wakeup.run();
    }
}
