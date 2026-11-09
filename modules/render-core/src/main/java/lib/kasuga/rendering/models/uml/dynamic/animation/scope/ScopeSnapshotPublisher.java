package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.List;
import java.util.Objects;

/** Allocates monotonic revisions for successfully committed logical steps. */
public final class ScopeSnapshotPublisher implements AutoCloseable {

    private long revision;
    private boolean closed;

    public long currentRevision() {
        return revision;
    }

    public ScopeSnapshot publish(
            TemporalStep timeStep,
            RootTimelineSnapshot rootTimeline,
            ScopeInputSnapshot inputs,
            List<PlaybackTimeResolution> playbacks
    ) {
        Objects.requireNonNull(timeStep, "timeStep");
        Objects.requireNonNull(rootTimeline, "rootTimeline");
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(playbacks, "playbacks");
        if (closed) {
            throw new IllegalStateException("scope snapshot publisher is closed");
        }
        if (revision == Long.MAX_VALUE) {
            throw new IllegalStateException("animation revision exhausted");
        }
        return new ScopeSnapshot(++revision, timeStep, rootTimeline, inputs, playbacks);
    }

    @Override
    public void close() {
        closed = true;
    }
}
