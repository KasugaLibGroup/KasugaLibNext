package lib.kasuga.rendering.models.uml.dynamic.animation;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.PoseDriver;
import lib.kasuga.rendering.models.uml.dynamic.RebindablePoseDriver;

import java.util.Objects;

/**
 * Format-agnostic playback clock + write end: the animation {@link PoseDriver} implementation.
 * A single {@link AnimationPlayer} plays any {@link AnimationSampler} / data pair:
 *
 * <ul>
 *   <li><b>Standalone {@link #play} — private Scope.</b> The Scope owns logical time and samples the
 *       animation source while publishing each fixed substep. It also exposes a compatibility
 *       {@link AnimationTimeline} for existing shared-clock followers.</li>
 *   <li><b>Shared {@link #follow} — external clock.</b> A private Scope observes a timeline supplied by
 *       another owner, publishes committed poses, and never advances that timeline itself.</li>
 *   <li><b>{@link #sample(float)} — render thread, per frame.</b> Both clock modes resolve committed
 *       substep poses; render sampling does not call the animation source again.</li>
 * </ul>
 *
 * <p><b>Clock semantics (locked).</b> {@code tick} advances {@code seconds} monotonically by
 * {@code dt·speed}. Loop boundaries publish both endpoint poses, so render interpolation cannot blend
 * the end and start of separate iterations. On a non-loop clip, reaching {@code duration} clamps the
 * clock, stops advancement ({@link #isPlaying()} → false), and {@code sample} keeps writing the final
 * frame until {@link #stop()}.
 *
 * <p>The driver owns the sink (a model's state machine, if any, keeps its own); on resource-reload rebind,
 * {@link #rebind(ModelInstance)} swaps only the sink target — playback progress survives.
 *
 * @param <T> the animation data type played through the attached {@link AnimationSampler}
 */
public final class AnimationPlayer<T> implements RebindablePoseDriver {

    private ModelInstance model;
    private final ScopedPlayerPosePlayback<T> scopedPlayback;

    public AnimationPlayer(ModelInstance model) {
        this.model = model;
        this.scopedPlayback = new ScopedPlayerPosePlayback<>(model);
    }

    public ModelInstance model() {
        return model;
    }

    /** Start (or restart) playback of {@code data} through {@code sampler} from the beginning. */
    public void play(AnimationSampler<T> sampler, T data, boolean loop) {
        scopedPlayback.play(sampler, data, loop);
    }

    /** Follow a shared clock; ModelInstance.animate() will not advance it again. */
    public void follow(AnimationSampler<T> sampler, T data, AnimationTimeline timeline) {
        Objects.requireNonNull(timeline, "timeline");
        if (scopedPlayback.ownedTimeline() == timeline) {
            throw new IllegalArgumentException(
                    "Cannot follow this player's owned clock; use an external timeline");
        }
        scopedPlayback.follow(sampler, data, timeline);
    }

    public AnimationTimeline timeline() {
        return scopedPlayback.timeline();
    }
    public void pause() {
        scopedPlayback.pause();
    }

    public void resume() {
        scopedPlayback.resume();
    }

    public void seek(float seconds) {
        scopedPlayback.seek(seconds);
    }

    /** Stop playback; subsequent {@link #sample(float)} calls are no-ops until {@link #play} again. */
    public void stop() {
        scopedPlayback.stop();
    }

    public boolean isPlaying() {
        return scopedPlayback.isPlaying();
    }

    /** Set playback speed (non-negative finite); inert when nothing is playing. */
    public void setSpeed(float speed) {
        scopedPlayback.setSpeed(speed);
    }

    /** Current clock seconds of the latest snapshot (debug/status; monotonic across loops). */
    public float currentTime() {
        return (float) scopedPlayback.currentTime();
    }

    /** The data currently being played, or {@code null} when stopped (debug). */
    public T currentData() {
        return scopedPlayback.data();
    }

    /** Main-thread game-tick advance: advance the playback clock, publish a fresh snapshot. */
    @Override
    public void tick(float dt) {
        scopedPlayback.tick(dt);
    }

    /**
     * Render-thread per-frame sample. Playback resolves the committed substep poses at the interpolated
     * logical time. No-op until {@link #play} or {@link #follow}; render sampling never calls the
     * animation source.
     * <p>A completed non-loop clip keeps its snapshot (playing=false) so the final frame is still
     * written — "play once" holds the last pose instead of freezing one tick early. {@link #stop}
     * drops the snapshot and stops writing entirely.
     */
    @Override
    public void sample(float partialTick) {
        scopedPlayback.sample(partialTick);
    }

    /**
     * Re-target a fresh {@link ModelInstance} (resource reload / model rebind): install a new
     * {@link lib.kasuga.rendering.models.uml.dynamic.fsm.ModelInstancePoseSink} for {@code fresh}.
     * Playback progress is unchanged.
     */
    public void rebind(ModelInstance fresh) {
        scopedPlayback.rebind(fresh);
        this.model = fresh;
    }

    /** Refresh data and target together; shared followers and owned-clock followers retain the same clock. */
    public void rebind(ModelInstance fresh, AnimationSampler<T> sampler, T data) {
        scopedPlayback.retarget(sampler, data);
        scopedPlayback.rebind(fresh);
        this.model = fresh;
    }

}
