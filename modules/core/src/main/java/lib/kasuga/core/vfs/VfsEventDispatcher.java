package lib.kasuga.core.vfs;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Thread-safe, lock-free-callback event dispatcher for VFS watch hooks.
 * Never holds backend state locks while invoking listener callbacks.
 */
public final class VfsEventDispatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(VfsEventDispatcher.class);

    private final CopyOnWriteArrayList<SubscriptionImpl> subscriptions = new CopyOnWriteArrayList<>();
    private final AtomicLong generationCounter = new AtomicLong(0L);
    private final Consumer<Throwable> errorHandler;

    public VfsEventDispatcher() {
        this(err -> LOGGER.error("Unhandled exception in VfsWatchListener callback", err));
    }

    public VfsEventDispatcher(Consumer<Throwable> errorHandler) {
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
    }

    public VfsSubscription register(VfsPath prefix, VfsWatchListener listener) {
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(listener, "listener");
        SubscriptionImpl sub = new SubscriptionImpl(prefix, listener);
        subscriptions.add(sub);
        return sub;
    }

    public @Nullable VfsChangeEvent dispatch(
            String sourceId,
            VfsChangeEvent.ChangeOrigin origin,
            List<VfsChangeEvent.EntryChange> changes
    ) {
        if (changes == null || changes.isEmpty()) {
            return null;
        }
        long gen = generationCounter.incrementAndGet();
        VfsChangeEvent event = new VfsChangeEvent(sourceId, origin, gen, System.currentTimeMillis(), changes);
        for (SubscriptionImpl sub : subscriptions) {
            if (sub.isClosed() || !event.affects(sub.watchedPrefix())) {
                continue;
            }
            try {
                sub.listener.onChange(event);
            } catch (Throwable t) {
                errorHandler.accept(t);
            }
        }
        return event;
    }

    public int listenerCount() {
        return subscriptions.size();
    }

    public void closeAll() {
        for (SubscriptionImpl sub : subscriptions) {
            sub.closed.set(true);
        }
        subscriptions.clear();
    }

    private final class SubscriptionImpl implements VfsSubscription {
        private final VfsPath prefix;
        private final VfsWatchListener listener;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private SubscriptionImpl(VfsPath prefix, VfsWatchListener listener) {
            this.prefix = prefix;
            this.listener = listener;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                subscriptions.remove(this);
            }
        }

        @Override
        public boolean isClosed() {
            return closed.get();
        }

        @Override
        public VfsPath watchedPrefix() {
            return prefix;
        }
    }
}
