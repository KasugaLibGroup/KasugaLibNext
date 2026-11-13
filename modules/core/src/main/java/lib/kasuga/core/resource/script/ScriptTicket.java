package lib.kasuga.core.resource.script;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Nonblocking completion token. Deliberately has no get(), join(), or engine-valued result. */
public final class ScriptTicket {
    public enum Status { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED, REJECTED }
    public record Failure(String type, String message) {}
    public record Outcome(Status status, Object value, Failure failure) {}
    private static final AtomicLong IDS = new AtomicLong();
    private final long id = IDS.incrementAndGet();
    private final String scopeId;
    private final long generation;
    private final AtomicReference<Status> status = new AtomicReference<>(Status.QUEUED);
    private final CompletableFuture<Outcome> completion = new CompletableFuture<>();
    private ScriptTicket(String scopeId, long generation) { this.scopeId = Objects.requireNonNull(scopeId); this.generation = generation; }
    public static Submission submission(String scopeId, long generation) { return new Submission(new ScriptTicket(scopeId, generation)); }
    /** Kept by the producer; only its read-only ticket is handed to a resource caller. */
    public static final class Submission {
        private final ScriptTicket ticket;
        private Submission(ScriptTicket ticket) { this.ticket = ticket; }
        public ScriptTicket ticket() { return ticket; }
        public boolean start() { return ticket.status.compareAndSet(Status.QUEUED, Status.RUNNING); }
        public void succeed(Object value) { ticket.finish(Status.SUCCEEDED, value, null); }
        public void fail(Throwable error) { ticket.finish(Status.FAILED, null, failure(error)); }
        public void reject(String message) { ticket.finish(Status.REJECTED, null, new Failure("RejectedExecutionException", message)); }
    }
    public long id() { return id; }
    public String scopeId() { return scopeId; }
    public long generation() { return generation; }
    public Status status() { return status.get(); }
    public Optional<Outcome> poll() { return Optional.ofNullable(completion.getNow(null)); }
    public CompletionStage<Outcome> completion() { return completion.minimalCompletionStage(); }
    public CompletionStage<Void> onComplete(Executor executor, Consumer<Outcome> callback) {
        return completion.thenAcceptAsync(callback, executor);
    }
    public boolean cancel() { return finish(Status.CANCELLED, null, null); }
    private static Failure failure(Throwable error) { return new Failure(error.getClass().getName(), String.valueOf(error.getMessage())); }
    private boolean finish(Status terminal, Object value, Failure failure) {
        while (true) {
            Status old = status.get();
            if (old != Status.QUEUED && old != Status.RUNNING) return false;
            if (status.compareAndSet(old, terminal)) { completion.complete(new Outcome(terminal, value, failure)); return true; }
        }
    }
}
