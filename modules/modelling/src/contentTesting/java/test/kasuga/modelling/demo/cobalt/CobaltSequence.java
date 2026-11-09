package test.kasuga.modelling.demo.cobalt;

import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationTimeline;

/** Demo rules only. Clocks and interpolation are provided by Kasuga's shared timeline API. */
public final class CobaltSequence {
    public enum Phase { WAITING, HOLDING, INSERTING, READY, OPENING, OCEAN }
    public static final float INSERT_SECONDS = 2;
    public static final float OPEN_SECONDS = 14;
    private final AnimationTimeline insertion = new AnimationTimeline();
    private final AnimationTimeline playback = new AnimationTimeline();
    private Phase phase = Phase.WAITING;

    public Phase phase() { return phase; }
    public boolean ready() { return phase == Phase.READY; }
    public boolean playing() { return phase == Phase.OPENING || phase == Phase.OCEAN; }
    /** Player feet may couple to the water only after the room has fully opened to the ocean. */
    public boolean waterInteractionEnabled() { return phase == Phase.OCEAN; }
    public boolean grab() { if (phase != Phase.WAITING) return false; phase = Phase.HOLDING; return true; }
    public boolean release() { if (phase != Phase.HOLDING) return false; phase = Phase.WAITING; return true; }
    public boolean insert() {
        if (phase != Phase.HOLDING) return false;
        phase = Phase.INSERTING; insertion.play(INSERT_SECONDS, false); return true;
    }
    public boolean play() {
        if (!ready()) return false;
        phase = Phase.OPENING; playback.play(86400, false); return true;
    }
    public void tick(float seconds) {
        insertion.tick(seconds); playback.tick(seconds);
        if (phase == Phase.INSERTING && insertion.currentTime() >= INSERT_SECONDS) phase = Phase.READY;
        if (phase == Phase.OPENING && playback.currentTime() >= OPEN_SECONDS) phase = Phase.OCEAN;
    }
    public float insertion(float partial) {
        return insertion.snapshot() == null ? 0 : insertion.snapshot().sampleTime(partial) / INSERT_SECONDS;
    }
    public float playbackTime() { return (float) playback.currentTime(); }
    public float playbackTime(float partial) {
        return playback.snapshot() == null ? 0 : playback.snapshot().sampleTime(partial);
    }
    public static float ease(float value) {
        float t = Math.clamp(value, 0, 1);
        return t * t * t * (t * (t * 6 - 15) + 10);
    }
}
