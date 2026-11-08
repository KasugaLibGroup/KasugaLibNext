package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.uml.dynamic.fsm.TransformLerp;
import lib.kasuga.rendering.models.uml.math.Transform;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Immutable local-motion value captured when a model group publishes a frame.
 *
 * <p>Render sampling never reads the motion source or sampler history. Each value in an
 * {@link ModelGroupFrame} can therefore be sampled repeatedly without observing a later publication.
 */
sealed interface PartMotionSnapshot {

    /** Samples this published value at a render alpha. */
    @Nullable Transform sample(float partialTick);

    /** A part with no local motion, or with a source that produced no finite value. */
    record Static() implements PartMotionSnapshot {

        static final Static INSTANCE = new Static();

        @Override
        public @Nullable Transform sample(float partialTick) {
            requireFiniteAlpha(partialTick);
            return null;
        }
    }

    /** Tick-domain axial spin between the previous and current logical tick. */
    record TickSpin(float previousDegrees, float currentDegrees) implements PartMotionSnapshot {

        public TickSpin {
            requireFinite(previousDegrees, "previousDegrees");
            requireFinite(currentDegrees, "currentDegrees");
        }

        @Override
        public Transform sample(float partialTick) {
            float alpha = clampAlpha(partialTick);
            float degrees = previousDegrees + (currentDegrees - previousDegrees) * alpha;
            return new Transform().rotate(degrees, 0f, 0f, true);
        }
    }

    /** Frame-domain axial spin already interpolated by the host. */
    record FrameSpin(float degrees) implements PartMotionSnapshot {

        public FrameSpin {
            requireFinite(degrees, "degrees");
        }

        @Override
        public Transform sample(float partialTick) {
            requireFiniteAlpha(partialTick);
            return new Transform().rotate(degrees, 0f, 0f, true);
        }
    }

    /** Tick-domain complete local transform between the previous and current logical tick. */
    record TickTransform(Transform previous, Transform current) implements PartMotionSnapshot {

        public TickTransform {
            previous = finiteCopy(previous, "previous");
            current = finiteCopy(current, "current");
        }

        @Override
        public Transform previous() {
            return previous.copy();
        }

        @Override
        public Transform current() {
            return current.copy();
        }

        @Override
        public Transform sample(float partialTick) {
            float alpha = clampAlpha(partialTick);
            Transform result = new Transform();
            TransformLerp.lerpInto(previous, current, alpha, result, new TransformLerp.Scratch());
            return result;
        }
    }

    /** Frame-domain complete local transform already interpolated by the host. */
    record FrameTransform(Transform transform) implements PartMotionSnapshot {

        public FrameTransform {
            transform = finiteCopy(transform, "transform");
        }

        @Override
        public Transform transform() {
            return transform.copy();
        }

        @Override
        public Transform sample(float partialTick) {
            requireFiniteAlpha(partialTick);
            return transform.copy();
        }
    }

    static PartMotionSnapshot staticSnapshot() {
        return Static.INSTANCE;
    }

    private static float clampAlpha(float partialTick) {
        requireFiniteAlpha(partialTick);
        return Math.clamp(partialTick, 0f, 1f);
    }

    private static void requireFiniteAlpha(float partialTick) {
        if (!Float.isFinite(partialTick)) {
            throw new IllegalArgumentException("partialTick must be finite");
        }
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static Transform finiteCopy(@Nullable Transform transform, String name) {
        Transform copy = Objects.requireNonNull(transform, name).copy();
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                if (!Float.isFinite(copy.transform().get(row, column))) {
                    throw new IllegalArgumentException(name + " transform must be finite");
                }
            }
        }
        return copy;
    }
}
