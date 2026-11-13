package lib.kasuga.scripting.discovery;

import jakarta.annotation.Nullable;
import lib.kasuga.scripting.ScriptEngine;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class ScriptThreadGroup {
    private final String packageName;
    private final ThreadGroup threadGroup;
    private final Map<String, ScriptThread> threads = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<ScriptThreadGroup> children = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private boolean closed;
    private final CompletableFuture<Void> termination = new CompletableFuture<>();
    @Nullable
    private final ScriptThreadGroup parent;

    public ScriptThreadGroup(String packageName, @Nullable ScriptThreadGroup parent) {
        this.packageName = packageName;
        this.parent = parent;
        this.threadGroup = parent != null
            ? new ThreadGroup(parent.threadGroup, packageName)
            : new ThreadGroup(packageName);
    }

    public synchronized ScriptThread createThread(String entryName, ScriptEngine engine) {
        if (closed || threads.containsKey(entryName)) throw new IllegalStateException("Closed group or duplicate script thread");
        ScriptThread thread = new ScriptThread(this, engine, entryName);
        threads.put(entryName, thread);
        thread.start();
        return thread;
    }

    public void dispatchTick() {
        for (ScriptThread thread : threads.values()) {
            thread.dispatchTick();
        }
        for (ScriptThreadGroup child : children) {
            child.dispatchTick();
        }
    }

    public synchronized ScriptThreadGroup createChild(String childPackageName) {
        if (closed) throw new IllegalStateException("Script thread group closed");
        ScriptThreadGroup child = new ScriptThreadGroup(childPackageName, this);
        children.add(child);
        return child;
    }

    public CompletableFuture<Void> shutdown() {
        List<ScriptThread> ownThreads;
        List<ScriptThreadGroup> ownChildren;
        synchronized (this) {
            if (closed) return termination;
            closed = true; ownThreads = List.copyOf(threads.values()); ownChildren = List.copyOf(children);
            threads.clear(); children.clear();
        }
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (ScriptThread thread : ownThreads) {
            futures.add(thread.shutdown());
        }
        for (ScriptThreadGroup child : ownChildren) {
            futures.add(child.shutdown());
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).whenComplete((ignored, failure) -> {
            if (failure == null) termination.complete(null); else termination.completeExceptionally(failure);
        });
        return termination;
    }

    void onThreadTerminated(ScriptThread thread) {
        threads.values().removeIf(candidate -> candidate == thread);
    }

    public String getPackageName() { return packageName; }
    public ThreadGroup getThreadGroup() { return threadGroup; }
}
