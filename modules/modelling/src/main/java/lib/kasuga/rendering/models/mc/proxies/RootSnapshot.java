package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.uml.dynamic.fsm.TransformLerp;
import lib.kasuga.rendering.models.uml.math.Transform;

import java.util.Objects;

/** Immutable previous/current model-group root used by every rigid part in one scope. */
public record RootSnapshot(Transform previous, Transform current) {

    public RootSnapshot {
        previous = copyFinite(previous, "previous");
        current = copyFinite(current, "current");
    }

    @Override
    public Transform previous() {
        return previous.copy();
    }

    @Override
    public Transform current() {
        return current.copy();
    }

    /** Samples into a caller-owned transform, avoiding a per-part result allocation. */
    public Transform at(float partialTick, Transform target) {
        if (!Float.isFinite(partialTick)) {
            throw new IllegalArgumentException("partialTick must be finite");
        }
        TransformLerp.lerpInto(previous, current, partialTick,
                Objects.requireNonNull(target, "target"), new TransformLerp.Scratch());
        return target;
    }

    /** Convenience form for callers that do not already own a scratch transform. */
    public Transform at(float partialTick) {
        return at(partialTick, new Transform());
    }

    private static Transform copyFinite(Transform value, String name) {
        Transform copy = Objects.requireNonNull(value, name).copy();
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
