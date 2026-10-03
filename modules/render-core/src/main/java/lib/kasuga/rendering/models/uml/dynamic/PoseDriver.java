package lib.kasuga.rendering.models.uml.dynamic;

/**
 * Drives the pose of a {@link ModelInstance}. The pluggable animation source the model pipeline consumes: the
 * FSM ({@code FsmPoseDriver}) is one implementation; future keyframe / procedural drivers can share the same slot.
 *
 * <p>A driver advances on one of two cadences — override the method(s) it needs:
 * <ul>
 *   <li><b>{@link #tick(float)}</b> — the main-thread, game-tick (@20&nbsp;Hz) entry. Hosts call it once per game
 *       tick to advance animation that is coupled to logic (the FSM: transition timers, authoritative state).</li>
 *   <li><b>{@link #sample(float)}</b> — the render-thread, per-frame entry. The backend calls it each frame with
 *       {@code partialTick ∈ [0,1)} (the fraction between the last and next game tick) so the driver can
 *       interpolate + flush the pose at frame rate, then the backend uploads to the GPU.</li>
 * </ul>
 *
 * <p><b>Threading contract.</b> {@code tick} runs on the host tick thread; {@code sample} runs on the render
 * thread — they MUST be safe to call concurrently from those two threads. A render-rate driver reads only a
 * thread-safe snapshot its own {@code tick} publishes (e.g. a {@code volatile} immutable target) and never
 * touches the host's mutable machine state directly. {@link ModelInstance#update()} is called separately on the
 * render thread to flush to the GPU; drivers write the pose during {@code sample}, the backend uploads after.
 */
public interface PoseDriver {

    /**
     * Main-thread game-tick advance. Advance animation coupled to logic by {@code dt} seconds. Default no-op for
     * render-only drivers.
     *
     * @param dt seconds since the last game tick (the engine tick rate, {@code 1f/20f} by default)
     */
    default void tick(float dt) {
    }

    /**
     * Render-thread per-frame sample. Compose the current pose (interpolating by {@code partialTick} where
     * relevant) and flush it into the attached {@link ModelInstance}; the backend uploads to the GPU afterwards.
     * Default no-op for tick-only drivers.
     *
     * @param partialTick fraction in {@code [0,1)} between the last game tick and the next
     */
    default void sample(float partialTick) {
    }
}
