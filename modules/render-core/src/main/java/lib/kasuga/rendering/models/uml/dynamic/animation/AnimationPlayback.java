package lib.kasuga.rendering.models.uml.dynamic.animation;

import java.util.Objects;
import java.util.function.Consumer;

/** Common playback engine for model and camera adapters. A shared timeline has an explicit external tick owner. */
public final class AnimationPlayback<T, R> {
    private volatile Binding<T, R> binding;

    public void play(AnimationSource<T, R> source, T data, boolean loop) {
        float duration = duration(source, data);
        var timeline = AnimationTimeline.owned();
        timeline.play(duration, loop);
        replace(new Binding<>(source, data, duration, timeline, true));
    }

    /** Bind without restarting or advancing the timeline. Shorter clips hold their endpoint until the shared loop wraps. */
    public void follow(AnimationSource<T, R> source, T data, AnimationTimeline timeline) {
        float duration = duration(source, data);
        Objects.requireNonNull(timeline, "timeline");
        var current = binding;
        if (current != null && current.ownsClock() && current.timeline() == timeline)
            throw new IllegalArgumentException("Cannot follow this player's owned clock; use an external timeline");
        replace(new Binding<>(source, data, duration, timeline, false));
    }

    public void stop() { replace(null); }
    /** Replaces source data while keeping the current clock and its ownership. */
    public void retarget(AnimationSource<T, R> source, T data) {
        float duration = duration(source, data);
        var current = binding;
        if (current == null) return;
        if (current.ownsClock()) current.timeline().resizeOwned(duration);
        binding = new Binding<>(source, data, duration, current.timeline(), current.ownsClock());
    }
    public T currentData() { var current = binding; return current == null ? null : current.data(); }
    public AnimationTimeline timeline() { var current = binding; return current == null ? null : current.timeline(); }
    public boolean isPlaying() { var current = binding; return current != null && current.timeline().isPlaying(); }
    public double currentTime() { var current = binding; return current == null ? 0 : current.timeline().currentTime(); }

    public void pause() { var current = binding; if (current != null) current.timeline().pause(); }
    public void resume() { var current = binding; if (current != null) current.timeline().resume(); }
    public void seek(float seconds) {
        if (!Float.isFinite(seconds) || seconds < 0) throw new IllegalArgumentException("Seek time must be finite and non-negative");
        var current = binding; if (current != null) current.timeline().seek(seconds);
    }
    public void setSpeed(float speed) {
        if (!Float.isFinite(speed) || speed < 0) throw new IllegalArgumentException("Speed must be finite and non-negative");
        var current = binding; if (current != null) current.timeline().setSpeed(speed);
    }

    /** Only standalone players own clock advancement. Shared followers deliberately do nothing here. */
    public void tick(float dt) { var current = binding; if (current != null && current.ownsClock()) current.timeline().tickOwned(dt); }

    /** Null means there is no active timeline. Sampled values must be non-null. */
    public R sample(float partialTick) {
        var current = binding;
        if (current == null) return null;
        var clock = current.timeline().snapshot();
        if (clock == null) return null;
        return Objects.requireNonNull(evaluate(current, clock, partialTick), "Animation source returned null");
    }

    /** Flush only when a timeline is active. Keeps legacy model sinks' null-pose reset behavior. */
    public void sampleInto(float partialTick, Consumer<? super R> sink) {
        Objects.requireNonNull(sink, "sink");
        var current = binding;
        if (current == null) return;
        var clock = current.timeline().snapshot();
        if (clock != null) sink.accept(evaluate(current, clock, partialTick));
    }

    private R evaluate(Binding<T, R> current, AnimationTimeline.Snapshot clock, float partialTick) {
        float time = Math.min(clock.sampleTime(partialTick), current.duration());
        return current.source().sample(current.data(), time);
    }

    private void replace(Binding<T, R> next) {
        var previous = binding;
        if (previous != null && previous.ownsClock()) previous.timeline().stop();
        binding = next;
    }

    private static <T, R> float duration(AnimationSource<T, R> source, T data) {
        if (source == null || data == null) throw new IllegalArgumentException("Animation source and data are required");
        float duration = source.duration(data);
        if (!Float.isFinite(duration)) throw new IllegalArgumentException("Animation duration must be finite");
        return Math.max(0, duration); // Legacy model samplers use non-positive duration for static poses.
    }

    private record Binding<T, R>(AnimationSource<T, R> source, T data, float duration,
                                 AnimationTimeline timeline, boolean ownsClock) {}
}
