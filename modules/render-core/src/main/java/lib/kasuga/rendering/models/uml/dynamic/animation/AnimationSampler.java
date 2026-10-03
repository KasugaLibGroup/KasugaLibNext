package lib.kasuga.rendering.models.uml.dynamic.animation;

import lib.kasuga.formula.compute.data.Namespace;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Pose;
import org.jetbrains.annotations.Nullable;

/**
 * Format-specific pose evaluator: a pure-function interpolation from animation data to a {@link Pose}.
 *
 * <p>The pipeline separates the two concerns:
 * <ul>
 *   <li><b>{@link AnimationSampler} — interpolation only.</b> Stateless (repeated calls with equal
 *       arguments return equivalent results), no clock, no skeleton writes, no {@code ModelInstance}
 *       dependency — name resolution (if any) is held as an immutable map captured at construction.</li>
 *   <li><b>{@link AnimationPlayer} — clock + write end.</b> Owns playback state (loop / speed /
 *       seconds) and flushes the sampled pose through a {@code PoseSink}. The player normalizes
 *       {@code time} (loop → {@code time % duration}; non-loop → {@code min(time, duration)}), so a
 *       sampler never implements loop semantics itself.</li>
 * </ul>
 *
 * <p>Implementations live in their format package: {@link ClipSampler} ({@link AnimationClip}),
 * {@code GltfSampler} (glTF), BE / VMD samplers (later phases).
 *
 * @param <T> the format's animation data type
 */
public interface AnimationSampler<T> {

    /**
     * Total duration in seconds (used by the player for loop modulo and end-clamp).
     *
     * @param data the animation data
     * @return duration in seconds; implementations return e.g. {@code AnimationClip.durationSeconds()}
     */
    float duration(T data);

    /**
     * Evaluate the animation at {@code time} seconds, producing the per-bone / morph / material-frame
     * pose. {@code time} is already normalized by the player ({@code ≥ 0}, within loop range), so this
     * method performs pure interpolation only.
     *
     * @param data the animation data
     * @param time normalized seconds within {@code [0, duration]} (clamped by the player)
     * @return the interpolated pose
     */
    Pose sample(T data, float time);

    /**
     * Evaluate the animation at {@code time} seconds with a formula {@link Namespace} available to formula
     * tracks (the parameter-store render projection — {@code query.*} variables injected per entity).
     * The default forwards to {@link #sample(Object, float)}, so formula-agnostic samplers (glTF, VMD, …)
     * keep their existing two-arg behavior with zero changes.
     *
     * @param data      the animation data
     * @param time      normalized seconds within {@code [0, duration]}
     * @param namespace formula namespace carrying the injected {@code query.*} variables, or {@code null}
     *                  to skip formula-track evaluation entirely
     * @return the interpolated pose
     */
    default Pose sample(T data, float time, @Nullable Namespace namespace) {
        return sample(data, time);
    }
}