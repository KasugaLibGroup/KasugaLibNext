package lib.kasuga.rendering.models.uml.dynamic.fsm;

import lib.kasuga.formula.compute.data.Namespace;
import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationSampler;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Immutable snapshot of a {@link StateMachine}'s per-layer pose state at one game tick, published by the main
 * thread (under a {@code volatile} reference) for the render thread to sample. Carries only the runtime-mutable
 * bits the render thread needs to (re)compose the pose — the active state, the in-flight transition and its
 * elapsed seconds, the active state's clip clock — plus each layer's static blend props. {@link State} /
 * {@link Transition} pose data is immutable after build, so the render thread can call {@code buildPose()}
 * on the referenced states safely.
 *
 * <p>This is the thread handoff: the main thread owns every {@link Layer} mutation ({@code active},
 * {@code activeTransition}, {@code transitionElapsed}, the clip clock); the render thread never reads those
 * fields directly, only this snapshot. Built once per game tick, read many times per frame.
 */
public record PoseTarget(
        List<LayerTarget> layers,
        List<BlendMode> modes,
        List<Float> weights,
        List<BoneMask> masks
) {
    public PoseTarget {
        layers = List.copyOf(layers);
        modes = List.copyOf(modes);
        weights = List.copyOf(weights);
        masks = List.copyOf(masks);
    }

    /** One layer's pose target: the active state, the in-flight cross-fade (if any), its elapsed seconds, and the clip clock. */
    public record LayerTarget(
            @Nullable State<?> activeState,
            @Nullable Transition<?> activeTransition,
            float transitionElapsed,
            @Nullable ClipTarget clip
    ) {
    }

    /**
     * The active state's clip clock at snapshot time: the sampler/data pair plus the previous and current
     * clip seconds, so the render thread can interpolate by {@code partialTick} the same way it interpolates
     * {@code transitionElapsed}. {@code prevTime} → {@code time} is <b>monotonic</b> (loop wrap is applied
     * at sample time by the composer, mirroring {@code AnimationPlayer}) so the partialTick lerp never
     * interpolates backward across a loop seam.
     */
    public record ClipTarget(
            AnimationSampler<?> sampler,
            Object data,
            boolean loop,
            float prevTime,
            float time
    ) {

        /** Duration in seconds of the referenced clip. */
        public float duration() {
            return duration(sampler, data);
        }

        /** Sample the referenced clip at {@code time} seconds (loop-normalized by the caller). */
        public Pose sample(float time) {
            return sample(time, null);
        }

        /** Sample the referenced clip at {@code time} seconds with a formula {@link Namespace} for {@code query.*} injection. */
        public Pose sample(float time, @Nullable Namespace namespace) {
            return sample(sampler, data, time, namespace);
        }

        @SuppressWarnings("unchecked")
        static <T> float duration(AnimationSampler<T> sampler, Object data) {
            return sampler.duration((T) data);
        }

        @SuppressWarnings("unchecked")
        static <T> Pose sample(AnimationSampler<T> sampler, Object data, float time) {
            return sampler.sample((T) data, time);
        }

        @SuppressWarnings("unchecked")
        static <T> Pose sample(AnimationSampler<T> sampler, Object data, float time, @Nullable Namespace namespace) {
            return sampler.sample((T) data, time, namespace);
        }
    }
}
