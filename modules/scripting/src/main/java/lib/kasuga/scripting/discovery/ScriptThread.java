package lib.kasuga.scripting.discovery;

import com.mojang.logging.LogUtils;
import lib.kasuga.scripting.ScriptEngine;
import lib.kasuga.scripting.ScriptException;
import lib.kasuga.scripting.resource.ScriptSubmissionRing;
import org.slf4j.Logger;

import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CopyOnWriteArrayList;

public class ScriptThread extends Thread {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final ScriptThreadGroup group;
    private final ScriptEngine engine;
    private final Queue<Runnable> pendingTasks = new ArrayDeque<>();
    private final CopyOnWriteArrayList<ScriptSubmissionRing> submissionRings = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Runnable> terminationActions = new CopyOnWriteArrayList<>();
    private final AtomicBoolean shouldShutdown = new AtomicBoolean(false);
    private volatile boolean tickReady = true;
    private final CompletableFuture<Void> shutdownFuture = new CompletableFuture<>();

    ScriptThread(ScriptThreadGroup group, ScriptEngine engine, String entryName) {
        super(group.getThreadGroup(), "Script-" + group.getPackageName() + "-" + entryName);
        this.group = group;
        this.engine = engine;
    }

    @Override
    public void run() {
        try {
            while (!shouldShutdown.get()) {
                try {
                    Runnable task;
                    synchronized (pendingTasks) {
                        task = pendingTasks.poll();
                    }
                    if (task != null) task.run();
                    for (ScriptSubmissionRing ring : submissionRings) ring.drain(64, 2_000_000);
                    boolean tick;
                    synchronized (this) { tick = tickReady; tickReady = false; }
                    if (tick) engine.tick();

                    synchronized (this) {
                        while (!tickReady && !hasWork() && !shouldShutdown.get()) {
                            this.wait();
                        }
                    }
                } catch (InterruptedException e) {
                    if (shouldShutdown.get()) break;
                } catch (RuntimeException e) {
                    LOGGER.error("Error in script thread {}", getName(), e);
                }
            }
        } finally {
            submissionRings.forEach(ScriptSubmissionRing::close);
            try {
                for (Runnable action : terminationActions) {
                    try { action.run(); } catch (RuntimeException error) { LOGGER.error("Script cleanup failed", error); }
                }
                engine.close();
            } finally {
                group.onThreadTerminated(this);
                shutdownFuture.complete(null);
            }
        }
    }

    void dispatchTick() {
        synchronized (this) {
            tickReady = true;
            this.notify();
        }
    }

    public void recordCall(Runnable task) {
        synchronized (pendingTasks) {
            if (shouldShutdown.get()) throw new java.util.concurrent.RejectedExecutionException("Script thread closed");
            if (pendingTasks.size() >= 4096) throw new java.util.concurrent.RejectedExecutionException("Script task queue full");
            pendingTasks.add(task);
        }
        wakeup();
    }

    private boolean hasWork() {
        synchronized (pendingTasks) { if (!pendingTasks.isEmpty()) return true; }
        return submissionRings.stream().anyMatch(ScriptSubmissionRing::hasPending);
    }

    public void wakeup() { synchronized (this) { this.notifyAll(); } }

    public ScriptSubmissionRing createSubmissionRing(String scopeId, long generation, int capacity) {
        synchronized (pendingTasks) {
            if (shouldShutdown.get()) throw new java.util.concurrent.RejectedExecutionException("Script thread closed");
            ScriptSubmissionRing ring = new ScriptSubmissionRing(scopeId, generation, this, capacity, this::wakeup);
            submissionRings.add(ring); return ring;
        }
    }

    /** Engine-valued resources must be released on their owner before engine.close(). */
    public void onTermination(Runnable action) {
        synchronized (pendingTasks) {
            if (shouldShutdown.get()) throw new java.util.concurrent.RejectedExecutionException("Script thread closed");
            terminationActions.add(action);
        }
    }

    public boolean owns(ScriptEngine candidate) { return engine == candidate; }

    public CompletableFuture<Void> shutdown() {
        synchronized (pendingTasks) { shouldShutdown.set(true); pendingTasks.clear(); }
        submissionRings.forEach(ScriptSubmissionRing::close);
        this.interrupt();
        return shutdownFuture;
    }
}
