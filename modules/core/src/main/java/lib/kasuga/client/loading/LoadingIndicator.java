package lib.kasuga.client.loading;

import java.util.concurrent.atomic.AtomicReference;

/** Thread-safe detail indicator rendered on top of Minecraft's reload overlay. */
public final class LoadingIndicator {
    private static final AtomicReference<Snapshot> CURRENT =
            new AtomicReference<>(new Snapshot(false, "", 0, 1));

    private LoadingIndicator() {}

    public static void begin(String label, int total) {
        CURRENT.set(new Snapshot(true, label, 0, Math.max(1, total)));
    }

    public static void update(String label, int current, int total) {
        CURRENT.set(new Snapshot(true, label, Math.max(0, current), Math.max(1, total)));
    }

    public static void label(String label) {
        CURRENT.updateAndGet(snapshot -> new Snapshot(
                true, label, snapshot.current(), snapshot.total()
        ));
    }

    public static void complete() {
        CURRENT.updateAndGet(snapshot -> new Snapshot(
                false, snapshot.label(), snapshot.total(), snapshot.total()
        ));
    }

    public static Snapshot snapshot() {
        return CURRENT.get();
    }

    public record Snapshot(boolean active, String label, int current, int total) {
        public float progress() {
            return Math.clamp((float) current / total, 0.0f, 1.0f);
        }
    }
}
