package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import lib.kasuga.rendering.models.uml.math.Transform;
import org.jetbrains.annotations.Nullable;

/**
 * Pure local-motion sampler for one rigid {@link ModelPart}.
 *
 * <p>The sampler owns only the state required by the motion's explicit time domain. Tick motion
 * stores the previous/current tick, while frame motion stores one snapshot from each frame
 * publication and returns copies during render sampling. It has no model destination, root
 * placement, or lifecycle.
 */
final class PartMotionSampler {

    /** Motion of this part, or {@code null} when it only follows its host. */
    @Nullable private final ModelPart.Motion motion;

    /** Tick-spin history, already unwrapped and scaled, in degrees. */
    private float previousDegrees;
    private float currentDegrees = Float.NaN;
    private float lastRawDegrees = Float.NaN;
    private float continuousDegrees;

    /** Tick-transform history. Frame transforms never write these fields. */
    @Nullable private Transform previousMotion;
    @Nullable private Transform currentMotion;
    private PartMotionSnapshot snapshot = PartMotionSnapshot.staticSnapshot();

    PartMotionSampler(@Nullable ModelPart.Motion motion) {
        this.motion = motion;
    }

    /** Publishes the initial value for the motion's domain and returns its immutable snapshot. */
    PartMotionSnapshot initialSnapshot(@Nullable StateMachine<?> machine) {
        ModelPart.Motion motion = this.motion;
        if (motion == null) {
            snapshot = PartMotionSnapshot.staticSnapshot();
            return snapshot;
        }
        if (motion.timeDomain() == ModelPart.TimeDomain.FRAME) {
            publishFrame();
        } else {
            publishTick(machine);
        }
        return snapshot;
    }

    /** Publishes one game tick. Frame-domain motion ignores logical ticks. */
    void publishTick(@Nullable StateMachine<?> machine) {
        ModelPart.Motion motion = this.motion;
        if (motion == null || motion.timeDomain() == ModelPart.TimeDomain.FRAME) {
            return;
        }
        if (motion instanceof ModelPart.Spin spin) {
            publishSpinTick(machine, spin);
        } else if (motion instanceof ModelPart.TransformMotion.Tick tick) {
            publishTransformTick(machine, tick);
        }
    }

    /** Publishes one already-interpolated host frame. Tick-domain motion ignores frame publication. */
    void publishFrame() {
        ModelPart.Motion motion = this.motion;
        if (motion == null || motion.timeDomain() == ModelPart.TimeDomain.TICK) {
            return;
        }
        if (motion instanceof ModelPart.TransformMotion.Frame frame) {
            Transform transform = finiteCopy(frame.source().current());
            snapshot = transform == null
                    ? PartMotionSnapshot.staticSnapshot()
                    : new PartMotionSnapshot.FrameTransform(transform);
        } else if (motion instanceof ModelPart.Spin.FrameSource frame) {
            float rawDegrees = frame.angle().degrees();
            float scaledDegrees = rawDegrees * frame.scale();
            snapshot = Float.isFinite(rawDegrees) && Float.isFinite(scaledDegrees)
                    ? new PartMotionSnapshot.FrameSpin(scaledDegrees)
                    : PartMotionSnapshot.staticSnapshot();
        }
    }

    /** Current immutable sampler value captured into the next model group frame. */
    PartMotionSnapshot snapshot() {
        return snapshot;
    }

    private void publishSpinTick(@Nullable StateMachine<?> machine, ModelPart.Spin spin) {
        float rawDegrees = switch (spin) {
            case ModelPart.Spin.Param param -> param.degrees(machine);
            case ModelPart.Spin.TickSource source -> source.angle().degrees();
            case ModelPart.Spin.FrameSource ignored -> Float.NaN;
        };
        if (!Float.isFinite(rawDegrees)) {
            clearTickState();
            return;
        }

        float scale = spin.scale();
        if (Float.isNaN(lastRawDegrees)) {
            lastRawDegrees = rawDegrees;
            continuousDegrees = rawDegrees * scale;
        } else {
            float delta = wrapDegrees(rawDegrees - lastRawDegrees);
            lastRawDegrees = rawDegrees;
            continuousDegrees += delta * scale;
        }
        previousDegrees = Float.isNaN(currentDegrees) ? continuousDegrees : currentDegrees;
        currentDegrees = continuousDegrees;
        snapshot = new PartMotionSnapshot.TickSpin(previousDegrees, currentDegrees);
    }

    private void publishTransformTick(
            @Nullable StateMachine<?> machine,
            ModelPart.TransformMotion.Tick motion
    ) {
        Transform next = finiteCopy(motion.source().atTick(machine));
        if (next == null) {
            previousMotion = null;
            currentMotion = null;
            snapshot = PartMotionSnapshot.staticSnapshot();
            return;
        }
        previousMotion = currentMotion == null ? next : currentMotion;
        currentMotion = next;
        snapshot = new PartMotionSnapshot.TickTransform(previousMotion, currentMotion);
    }

    /** Maps any angle into {@code (-180, 180]}. */
    private static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360f;
        if (wrapped >= 180f) return wrapped - 360f;
        if (wrapped < -180f) return wrapped + 360f;
        return wrapped;
    }

    @Nullable
    private static Transform finiteCopy(@Nullable Transform transform) {
        if (transform == null) {
            return null;
        }
        Transform copy = transform.copy();
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                if (!Float.isFinite(copy.transform().get(row, column))) {
                    return null;
                }
            }
        }
        return copy;
    }

    private void clearTickState() {
        previousDegrees = Float.NaN;
        currentDegrees = Float.NaN;
        lastRawDegrees = Float.NaN;
        continuousDegrees = 0f;
        previousMotion = null;
        currentMotion = null;
        snapshot = PartMotionSnapshot.staticSnapshot();
    }
}
