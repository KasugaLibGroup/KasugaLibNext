package lib.kasuga.rendering.models.uml.dynamic.animation;

import lib.kasuga.formula.compute.data.Namespace;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Pose;
import org.jetbrains.annotations.Nullable;

/**
 * Pure pose evaluator: interpolation from arbitrary animation data to a {@link Pose}.
 *
 * <p>The pipeline separates the two concerns:
 * <ul>
 *   <li><b>{@link AnimationSampler} — interpolation only.</b> Stateless (repeated calls with equal
 *       arguments return equivalent results), no clock, no skeleton writes, no {@code ModelInstance}
 *       dependency — name resolution (if any) is held as an immutable map captured at construction.</li>
 *   <li><b>{@link AnimationPlayer} — playback + write end.</b> Standalone playback uses a private
 *       Scope to sample this source at fixed logical substeps; render sampling only resolves the
 *       committed poses. Loop and endpoint normalization still happen before this sampler is called,
 *       so a sampler never implements clock or loop semantics itself.</li>
 * </ul>
 *
 * <p>Common data uses {@link ClipSampler} / {@link AnimationClip}; format readers can supply their own
 * sampler without introducing a format-specific runtime subsystem.
 *
 * @param <T> the animation data type
 */
public interface AnimationSampler<T> extends AnimationSource<T, Pose> {

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
     * tracks ({@code query.*} variables injected from the frozen logical-step snapshot).
     * The default forwards to {@link #sample(Object, float)}, so formula-agnostic samplers (glTF, VMD, …)
     * keep their existing two-arg behavior with zero changes.
     *
     * @param data      the animation data
     * @param time      normalized seconds within {@code [0, duration]}
     * @param namespace formula namespace carrying the step's {@code query.*} values, or {@code null}
     *                  to skip formula-track evaluation entirely
     * @return the interpolated pose
     */
    default Pose sample(T data, float time, @Nullable Namespace namespace) {
        return sample(data, time);
    }
}
