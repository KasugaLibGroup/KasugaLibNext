package lib.kasuga.rendering.models.uml.dynamic.animation;

/**
 * Shared playback clock. One host advances it once per tick; any number of targets sample its immutable
 * volatile snapshot. Controls and tick are serialized; sampling never writes or advances the clock.
 */
public final class AnimationTimeline {
    private volatile Snapshot snapshot;
    private final boolean owned;

    public AnimationTimeline() { this(false); }
    private AnimationTimeline(boolean owned) { this.owned = owned; }
    static AnimationTimeline owned() { return new AnimationTimeline(true); }
    public boolean isOwned() { return owned; }

    public synchronized void play(float duration, boolean loop) {
        if (!Float.isFinite(duration) || duration < 0)
            throw new IllegalArgumentException("Duration must be finite and non-negative");
        snapshot = new Snapshot(0, 0, duration, 1, loop, true);
    }

    public synchronized void stop() { snapshot = null; }
    public Snapshot snapshot() { return snapshot; }
    public boolean isPlaying() { var current = snapshot; return current != null && current.playing(); }
    public double currentTime() { var current = snapshot; return current == null ? 0 : current.seconds(); }

    public synchronized void pause() {
        if (snapshot != null) snapshot = snapshot.at(snapshot.seconds(), false);
    }

    public synchronized void resume() {
        if (snapshot != null && (snapshot.loop() || snapshot.seconds() < snapshot.duration()))
            snapshot = snapshot.at(snapshot.seconds(), true);
    }

    public synchronized void seek(float seconds) {
        if (!Float.isFinite(seconds) || seconds < 0)
            throw new IllegalArgumentException("Seek time must be finite and non-negative");
        if (snapshot == null) return;
        double time = snapshot.loop() ? seconds : Math.min(seconds, snapshot.duration());
        snapshot = snapshot.at(time, snapshot.playing() && (snapshot.loop() || time < snapshot.duration()));
    }

    public synchronized void setSpeed(float speed) {
        if (!Float.isFinite(speed) || speed < 0)
            throw new IllegalArgumentException("Speed must be finite and non-negative");
        if (snapshot != null) snapshot = new Snapshot(snapshot.seconds(), snapshot.seconds(),
                snapshot.duration(), speed, snapshot.loop(), snapshot.playing());
    }

    /** Invalid deltas are ignored, preserving the model driver's tick contract. */
    public void tick(float dt) {
        if (owned) throw new IllegalStateException("A standalone player owns this timeline's ticks");
        advance(dt);
    }

    void tickOwned(float dt) { advance(dt); }

    private synchronized void advance(float dt) {
        if (!Float.isFinite(dt) || dt < 0 || snapshot == null) return;
        var current = snapshot;
        if (!current.playing()) {
            // Retire the last interpolation interval after completion; later frames hold the endpoint.
            if (current.previous() != current.seconds()) snapshot = current.at(current.seconds(), false);
            return;
        }
        double next = current.seconds() + (double) dt * current.speed();
        if (!current.loop()) next = Math.min(next, current.duration());
        snapshot = new Snapshot(current.seconds(), next, current.duration(), current.speed(), current.loop(),
                current.loop() || next < current.duration());
    }

    public record Snapshot(double previous, double seconds, float duration, float speed, boolean loop, boolean playing) {
        private Snapshot at(double time, boolean playing) { return new Snapshot(time, time, duration, speed, loop, playing); }

        public float sampleTime(float partialTick) {
            if (!Float.isFinite(partialTick)) throw new IllegalArgumentException("Partial tick must be finite");
            double elapsed = Math.fma(seconds - previous, Math.clamp(partialTick, 0, 1), previous);
            return duration == 0 ? 0 : (float) (loop ? elapsed % duration : Math.min(elapsed, duration));
        }
    }
}
