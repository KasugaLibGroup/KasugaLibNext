package lib.kasuga.core.vfs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background daemon polling service that monitors registered {@link VirtualFileSystem} mounts
 * for external disk modifications consistently across Windows, macOS, and Linux.
 */
public final class VfsWatchService implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(VfsWatchService.class);

    private final CopyOnWriteArrayList<WatchedMount> watched = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler;

    public VfsWatchService(Duration pollInterval) {
        Objects.requireNonNull(pollInterval, "pollInterval");
        long intervalMs = Math.max(10L, pollInterval.toMillis());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "KasugaLib-VfsWatcher");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler.scheduleWithFixedDelay(this::pollSafely, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    public VfsWatchService(long pollIntervalMillis) {
        this(Duration.ofMillis(pollIntervalMillis));
    }

    public VfsSubscription register(VirtualFileSystem vfs) {
        Objects.requireNonNull(vfs, "vfs");
        if (closed.get()) {
            throw new IllegalStateException("VfsWatchService is closed");
        }
        WatchedMount mount = new WatchedMount(vfs);
        watched.add(mount);
        return mount;
    }

    public int pollNow() {
        if (closed.get()) {
            return 0;
        }
        int eventsEmitted = 0;
        for (WatchedMount mount : watched) {
            if (mount.isClosed()) {
                continue;
            }
            try {
                if (mount.vfs.pollExternalChanges().isPresent()) {
                    eventsEmitted++;
                }
            } catch (Throwable t) {
                LOGGER.warn("Error polling external VFS changes for source '{}'", mount.vfs.sourceId(), t);
            }
        }
        return eventsEmitted;
    }

    private void pollSafely() {
        try {
            pollNow();
        } catch (Throwable t) {
            LOGGER.warn("Unexpected error during VfsWatchService poll cycle", t);
        }
    }

    public int watchedCount() {
        return watched.size();
    }

    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (WatchedMount mount : watched) {
            mount.mountClosed.set(true);
        }
        watched.clear();
        scheduler.shutdownNow();
    }

    private final class WatchedMount implements VfsSubscription {
        private final VirtualFileSystem vfs;
        private final AtomicBoolean mountClosed = new AtomicBoolean(false);

        private WatchedMount(VirtualFileSystem vfs) {
            this.vfs = vfs;
        }

        @Override
        public void close() {
            if (mountClosed.compareAndSet(false, true)) {
                watched.remove(this);
            }
        }

        @Override
        public boolean isClosed() {
            return mountClosed.get() || closed.get();
        }

        @Override
        public VfsPath watchedPrefix() {
            return VfsPath.ROOT;
        }
    }
}
