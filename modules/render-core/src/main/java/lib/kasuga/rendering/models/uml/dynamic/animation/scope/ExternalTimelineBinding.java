package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationTimeline;

import java.util.Objects;
import java.util.function.Consumer;

/** Owns one observer registration and the latest immutable external-timeline snapshot. */
public final class ExternalTimelineBinding implements AutoCloseable {

    private AnimationTimeline timeline;
    private AnimationTimeline.Snapshot snapshot;
    private AutoCloseable registration;
    private long generation;

    public synchronized void subscribe(
            AnimationTimeline nextTimeline,
            Consumer<AnimationTimeline.Snapshot> observer
    ) {
        Objects.requireNonNull(nextTimeline, "timeline");
        Objects.requireNonNull(observer, "observer");
        clear();
        long subscribedGeneration = generation;
        timeline = nextTimeline;
        registration = nextTimeline.observe(snapshot ->
                deliver(subscribedGeneration, observer, snapshot));
    }

    public synchronized AnimationTimeline.Snapshot swap(AnimationTimeline.Snapshot next) {
        AnimationTimeline.Snapshot previous = snapshot;
        snapshot = next;
        return previous;
    }

    public synchronized AnimationTimeline.Snapshot snapshot() {
        return snapshot;
    }

    public synchronized AnimationTimeline timeline() {
        return timeline;
    }

    public AnimationTimeline effectiveTimeline(AnimationTimeline fallback, boolean fallbackActive) {
        AnimationTimeline external = timeline();
        return external != null ? external : fallbackActive ? fallback : null;
    }

    public boolean isPlaying(boolean fallbackActive, boolean fallbackPlaying) {
        AnimationTimeline external = timeline();
        if (external == null) {
            return fallbackActive && fallbackPlaying;
        }
        AnimationTimeline.Snapshot current = snapshot();
        return current != null && current.playing();
    }

    public synchronized void clear() {
        generation++;
        AutoCloseable current = registration;
        registration = null;
        timeline = null;
        snapshot = null;
        if (current != null) {
            try {
                current.close();
            } catch (Exception ignored) {
                // Timeline observers are in-memory registrations.
            }
        }
    }

    @Override
    public void close() {
        clear();
    }

    private synchronized void deliver(
            long subscribedGeneration,
            Consumer<AnimationTimeline.Snapshot> observer,
            AnimationTimeline.Snapshot next
    ) {
        if (subscribedGeneration != generation || timeline == null) {
            return;
        }
        observer.accept(next);
    }
}
