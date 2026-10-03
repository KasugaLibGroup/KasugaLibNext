package lib.kasuga.rendering.models.uml.backend;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Thread-owned resources shared by all passes of a frame, retired only at frame end. */
public final class FrameResourceCache<K, V> implements AutoCloseable {
    private final Map<K, V> resources = new LinkedHashMap<>();
    private final Set<K> used = new HashSet<>();
    private final Consumer<V> release;
    private boolean frameOpen;

    public FrameResourceCache(Consumer<V> release) {
        this.release = Objects.requireNonNull(release, "release");
    }

    /** Also abandons the usage marks of an interrupted frame without prematurely deleting its resources. */
    public void beginFrame() {
        used.clear();
        frameOpen = true;
    }

    public boolean isFrameOpen() {
        return frameOpen;
    }

    public V acquire(K key, Supplier<V> factory) {
        if (!frameOpen) throw new IllegalStateException("beginFrame must precede resource acquisition");
        Objects.requireNonNull(key, "key");
        V value = resources.computeIfAbsent(key, ignored -> Objects.requireNonNull(factory.get(), "resource"));
        used.add(key);
        return value;
    }

    public int size() {
        return resources.size();
    }

    public void endFrame() {
        if (!frameOpen) return;
        frameOpen = false;
        RuntimeException failure = null;
        var entries = resources.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            if (used.contains(entry.getKey())) continue;
            entries.remove();
            try {
                release.accept(entry.getValue());
            } catch (RuntimeException exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
        }
        used.clear();
        if (failure != null) throw failure;
    }

    @Override
    public void close() {
        used.clear();
        frameOpen = true;
        endFrame();
    }
}
