package lib.kasuga.rendering.models.mc.component;

import java.util.Objects;

/** Main-thread debounce and single-flight gate for completed editor publications. */
public final class LiveModelSyncState {
    private final long quietMillis;
    private String observed;
    private String applied;
    private String inFlight;
    private long changedAt;

    public LiveModelSyncState(long quietMillis) {
        if (quietMillis < 0) throw new IllegalArgumentException("negative debounce");
        this.quietMillis = quietMillis;
    }

    public void observe(String signature, long nowMillis) {
        if (!Objects.equals(observed, signature)) {
            observed = signature;
            changedAt = nowMillis;
        }
    }

    public String beginReload(long nowMillis) {
        if (inFlight != null || observed == null || Objects.equals(observed, applied)
                || nowMillis - changedAt < quietMillis) return null;
        inFlight = observed;
        return inFlight;
    }

    /** Failed generations are acknowledged too; the next save can retry without a reload storm. */
    public void completeReload(String signature) {
        if (!Objects.equals(inFlight, signature)) return;
        applied = signature;
        inFlight = null;
    }
}
