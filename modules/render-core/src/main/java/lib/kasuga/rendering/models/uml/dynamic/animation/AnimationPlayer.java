package lib.kasuga.rendering.models.uml.dynamic.animation;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.PoseDriver;
import lib.kasuga.rendering.models.uml.dynamic.fsm.ModelInstancePoseSink;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Pose;

/**
 * Format-agnostic playback clock + write end: the animation {@link PoseDriver} implementation.
 * A single {@link AnimationPlayer} plays any {@link AnimationSampler} / data pair, following the
 * same dual-cadence pattern as {@code FsmPoseDriver}:
 *
 * <ul>
 *   <li><b>{@link #tick(float)} — main thread, game tick.</b> Advances the playback clock and publishes a
 *       {@code volatile} {@link Snapshot} (sampler + data + prev/current seconds + loop/speed/playing).
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
    private volatile Snapshot<T> snapshot;

    public AnimationPlayer(ModelInstance model) {
        this.model = model;
        this.sink = new ModelInstancePoseSink(model);
    }

    public ModelInstance model() {
        return model;
    }

    /** Start (or restart) playback of {@code data} through {@code sampler} from the beginning. */
    public void play(AnimationSampler<T> sampler, T data, boolean loop) {
        if (sampler == null) {
            throw new IllegalArgumentException("sampler required");
        }
        if (data == null) {
            throw new IllegalArgumentException("data required");
        }
        snapshot = new Snapshot<>(sampler, data, 0f, 0f, 1f, loop, true);
    }

    /** Stop playback; subsequent {@link #sample(float)} calls are no-ops until {@link #play} again. */
    public void stop() {
        snapshot = null;
    }

    public boolean isPlaying() {
        Snapshot<T> current = snapshot;
        return current != null && current.playing;
    }

    /** Set playback speed (non-negative finite); inert when nothing is playing. */
    public void setSpeed(float speed) {
        if (!Float.isFinite(speed) || speed < 0f) {
            throw new IllegalArgumentException("speed must be finite and non-negative");
        }
        Snapshot<T> current = snapshot;
        if (current == null) {
            return;
        }
        snapshot = new Snapshot<>(current.sampler, current.data, current.prevSeconds, current.seconds,
                speed, current.loop, current.playing);
    }

    /** Current clock seconds of the latest snapshot (debug/status; monotonic across loops). */
    public float currentTime() {
        Snapshot<T> current = snapshot;
        return current == null ? 0f : current.seconds;
    }

    /** The data currently being played, or {@code null} when stopped (debug). */
    public T currentData() {
        Snapshot<T> current = snapshot;
        return current == null ? null : current.data;
    }

    /** Main-thread game-tick advance: advance the playback clock, publish a fresh snapshot. */
    @Override
    public void tick(float dt) {
        if (!Float.isFinite(dt) || dt < 0f) {
            return;
        }
        Snapshot<T> current = snapshot;
        if (current == null || !current.playing) {
            return;
        }
        float duration = current.sampler.duration(current.data);
        float next = current.seconds + dt * current.speed;
        boolean playing = current.loop || next < duration;
        if (!current.loop && next > duration) {
            next = duration;
        }
        snapshot = new Snapshot<>(current.sampler, current.data, current.seconds, next,
                current.speed, current.loop, playing);
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
        Snapshot<T> current = snapshot;
        if (current == null) {
            return;
        }
        float fraction = Math.clamp(partialTick, 0f, 1f);
        float elapsed = Math.fma(current.seconds - current.prevSeconds, fraction, current.prevSeconds);
        float duration = current.sampler.duration(current.data);
        float time = duration <= 0f ? 0f
                : current.loop ? elapsed % duration : Math.min(elapsed, duration);
        Pose pose = current.sampler.sample(current.data, time);
        sink.applyPose(pose);
    }

    /**
     * Re-target a fresh {@link ModelInstance} (resource reload / model rebind): install a new
     * {@link ModelInstancePoseSink} for {@code fresh}. Playback progress is unchanged.
     */
    public void rebind(ModelInstance fresh) {
        this.model = fresh;
        this.sink = new ModelInstancePoseSink(fresh);
    }

    /** Thread handoff: the main thread writes it (volatile), the render thread reads it. */
    private record Snapshot<T>(
            AnimationSampler<T> sampler,
            T data,
            float prevSeconds,
            float seconds,
            float speed,
            boolean loop,
            boolean playing
    ) {
    }
}