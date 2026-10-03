package lib.kasuga.rendering.models.uml.dynamic.fsm;

import lib.kasuga.rendering.models.uml.math.Transform;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A pose: morph weights, bone targets, and material/sprite frame picks. Immutable; {@link Bone} copies
 * its {@link Transform} on construction. Used both as a state's target pose and as a layer's cross-faded output.
 */
public record Pose(
        Map<Object, Morph> morphs,
        Map<String, Bone> bones,
        Map<Object, Frame> frames
) {

    public record Morph(float value, float factor) {}

    public record Bone(Transform transform, ApplyMode mode) {
        public Bone {
            Objects.requireNonNull(transform, "transform");
            Objects.requireNonNull(mode, "mode");
            transform = transform.copy();
        }
    }

    public record Frame(Object materialRef, int frame) {
        public Frame {
            Objects.requireNonNull(materialRef, "materialRef");
        }
    }

    public Pose {
        morphs = Map.copyOf(morphs);
        bones = Map.copyOf(bones);
        frames = Map.copyOf(frames);
    }

    private static final Pose EMPTY = new Pose(Map.of(), Map.of(), Map.of());

    public static Pose empty() {
        return EMPTY;
    }

    public static Pose morph(Object id, float value, float factor) {
        return new Builder().morph(id, value, factor).build();
    }

    public static Pose morph(Object id, float value) {
        return morph(id, value, 1f);
    }

    public static Pose bone(String name, Transform transform, ApplyMode mode) {
        return new Builder().bone(name, transform, mode).build();
    }

    public static Pose bone(String name, Transform transform) {
        return bone(name, transform, ApplyMode.REPLACE);
    }

    public static Pose frame(Object materialRef, int frame) {
        return new Builder().frame(materialRef, frame).build();
    }

    public boolean isEmpty() {
        return morphs.isEmpty() && bones.isEmpty() && frames.isEmpty();
    }

    /**
     * Mutable accumulator for building a {@link Pose} — used by {@link State} (its target pose) and by
     * the runtime's cross-fade blending.
     */
    public static final class Builder {

        private final Map<Object, Morph> morphs = new LinkedHashMap<>();
        private final Map<String, Bone> bones = new LinkedHashMap<>();
        private final Map<Object, Frame> frames = new LinkedHashMap<>();

        public Builder morph(Object id, float value, float factor) {
            morphs.put(id, new Morph(value, factor));
            return this;
        }

        public Builder bone(String name, Transform transform, ApplyMode mode) {
            bones.put(name, new Bone(transform, mode));
            return this;
        }

        public Builder frame(Object materialRef, int frame) {
            frames.put(materialRef, new Frame(materialRef, frame));
            return this;
        }

        public Builder merge(Pose other) {
            if (other == null) {
                return this;
            }
            morphs.putAll(other.morphs());
            bones.putAll(other.bones());
            frames.putAll(other.frames());
            return this;
        }

        public boolean isEmpty() {
            return morphs.isEmpty() && bones.isEmpty() && frames.isEmpty();
        }

        public Pose build() {
            return new Pose(morphs, bones, frames);
        }
    }
}
