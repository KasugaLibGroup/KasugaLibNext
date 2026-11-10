package lib.kasuga.create.client.rendering;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Coalesces a resource cleanup request onto the game thread.
 *
 * <p>Flywheel may delete visuals from a worker thread, while model pipeline publication is owned by
 * the Minecraft game thread. Keeping the pending flag here prevents a frame-rate caller from
 * scheduling the same cleanup repeatedly and prevents replacement visuals from attaching until the
 * previous session has actually closed.
 */
final class DeferredGameThreadTask {

    private final Executor gameThread;
    private boolean pending;

    DeferredGameThreadTask(Executor gameThread) {
        this.gameThread = Objects.requireNonNull(gameThread, "gameThread");
    }

    synchronized boolean schedule(Runnable task) {
        Objects.requireNonNull(task, "task");
        if (pending) {
            return false;
        }
        pending = true;
        try {
            gameThread.execute(() -> {
                try {
                    task.run();
                } finally {
                    synchronized (DeferredGameThreadTask.this) {
                        pending = false;
                    }
                }
            });
            return true;
        } catch (RuntimeException | Error failure) {
            pending = false;
            throw failure;
        }
    }

    synchronized boolean isPending() {
        return pending;
    }
}
