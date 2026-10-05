package lib.kasuga.rendering.models.uml.dynamic.animation;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.PoseDriver;
import lib.kasuga.rendering.models.uml.dynamic.fsm.ModelInstancePoseSink;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Pose;

import java.util.function.Consumer;

/**
 * Format-agnostic playback clock + write end: the animation {@link PoseDriver} implementation.
 * A single {@link AnimationPlayer} plays any {@link AnimationSampler} / data pair, following the
 * same dual-cadence pattern as {@code FsmPoseDriver}:
 *
 * <ul>
 *   <li><b>{@link #tick(float)} — main thread, game tick.</b> Advances the playback clock and publishes a
 *       immutable clock snapshot through the shared {@link AnimationPlayback} engine.
 *       The host drives this via {@link ModelInstance#animate(float)}.</li>
 *   <li><b>{@link #sample(float)} — render thread, per frame.</b> Reads the latest snapshot, interpolates
 *       the clock by {@code partialTick}, samples the data through the {@link AnimationSampler} and flushes
 *       the pose through the driver's own {@link ModelInstancePoseSink}.</li>
 * </ul>
 *
 * <p><b>Clock semantics (locked).</b> {@code tick} advances {@code seconds} monotonically by
 * {@code dt·speed}; loop normalization happens on the render side ({@code sample}: {@code time = loop ?
 * elapsed % duration : min(elapsed, duration)}) so the {@code prevSeconds → seconds} partialTick lerp never
 * interpolates across a loop wrap. On a non-loop clip, reaching {@code duration} clamps the clock, stops
 * advancement ({@link #isPlaying()} → false), and {@code sample} keeps writing the final frame until
 * {@link #stop()}.
 *
 * <p>The driver owns the sink (a model's state machine, if any, keeps its own); on resource-reload rebind,
 * {@link #rebind(ModelInstance)} swaps only the sink target — playback progress survives.
 *
 * @param <T> the animation data type played through the attached {@link AnimationSampler}
 */
public final class AnimationPlayer<T> implements PoseDriver {

    private ModelInstance model;
    private volatile ModelInstancePoseSink sink;
    private final AnimationPlayback<T, Pose> playback = new AnimationPlayback<>();
    private final Consumer<Pose> applyPose = pose -> sink.applyPose(pose);

    public AnimationPlayer(ModelInstance model) {
        this.model = model;
        this.sink = new ModelInstancePoseSink(model);
    }

    public ModelInstance model() {
        return model;
    }

    /** Start (or restart) playback of {@code data} through {@code sampler} from the beginning. */
    public void play(AnimationSampler<T> sampler, T data, boolean loop) {
        playback.play(sampler, data, loop);
    }

    /** Follow a shared clock; ModelInstance.animate() will not advance it again. */
    public void follow(AnimationSampler<T> sampler, T data, AnimationTimeline timeline) {
        playback.follow(sampler, data, timeline);
    }

    public AnimationTimeline timeline() { return playback.timeline(); }
    public void pause() { playback.pause(); }
    public void resume() { playback.resume(); }
    public void seek(float seconds) { playback.seek(seconds); }

    /** Stop playback; subsequent {@link #sample(float)} calls are no-ops until {@link #play} again. */
    public void stop() {
        playback.stop();
    }

    public boolean isPlaying() {
        return playback.isPlaying();
    }

    /** Set playback speed (non-negative finite); inert when nothing is playing. */
    public void setSpeed(float speed) {
        playback.setSpeed(speed);
    }

    /** Current clock seconds of the latest snapshot (debug/status; monotonic across loops). */
    public float currentTime() {
        return (float) playback.currentTime();
    }

    /** The data currently being played, or {@code null} when stopped (debug). */
    public T currentData() {
        return playback.currentData();
    }

    /** Main-thread game-tick advance: advance the playback clock, publish a fresh snapshot. */
    @Override
    public void tick(float dt) {
        playback.tick(dt);
    }

    /**
     * Render-thread per-frame sample: interpolate the clock by {@code partialTick}, sample at the
     * resulting (loop-normalized) time and flush the pose through the sink. No-op until {@link #play}.
     * <p>A completed non-loop clip keeps its snapshot (playing=false) so the final frame is still
     * written — "play once" holds the last pose instead of freezing one tick early. {@link #stop}
     * drops the snapshot and stops writing entirely.
     */
    @Override
    public void sample(float partialTick) {
        playback.sampleInto(partialTick, applyPose);
    }

    /**
     * Re-target a fresh {@link ModelInstance} (resource reload / model rebind): install a new
     * {@link ModelInstancePoseSink} for {@code fresh}. Playback progress is unchanged.
     */
    public void rebind(ModelInstance fresh) {
        this.model = fresh;
        this.sink = new ModelInstancePoseSink(fresh);
    }

}
