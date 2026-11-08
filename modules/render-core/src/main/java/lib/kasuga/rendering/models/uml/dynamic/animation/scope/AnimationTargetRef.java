package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.Objects;

/**
 * Stable logical address of one animation write endpoint.
 *
 * <p>The channel identifies the output contract and the address identifies one destination inside
 * the owning scope. Bone, morph, material and IK channels belong inside one model-pose target; a
 * part-root target addresses one rigid model instance inside a model group.
 */
public record AnimationTargetRef(TargetChannel channel, String address) {

    public static final TargetChannel MODEL_POSE = new TargetChannel("kasuga:model_pose");
    public static final TargetChannel CAMERA_POSE = new TargetChannel("kasuga:camera_pose");
    public static final TargetChannel PART_ROOT = new TargetChannel("kasuga:part_root");
    public static final TargetChannel PROPERTY = new TargetChannel("kasuga:property");

    public AnimationTargetRef {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(address, "address");
        if (address.isBlank()) {
            throw new IllegalArgumentException("target address must not be blank");
        }
    }

    public static AnimationTargetRef modelPose() {
        return new AnimationTargetRef(MODEL_POSE, "pose");
    }

    public static AnimationTargetRef cameraPose() {
        return new AnimationTargetRef(CAMERA_POSE, "pose");
    }

    public static AnimationTargetRef partRoot(String address) {
        return new AnimationTargetRef(PART_ROOT, address);
    }

    public static AnimationTargetRef partRoot(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("part index must be non-negative");
        }
        return partRoot("part/" + index);
    }

    /** @deprecated use {@link #partRoot(int)}. */
    @Deprecated(forRemoval = false)
    public static AnimationTargetRef part(int index) {
        return partRoot(index);
    }

    public static AnimationTargetRef property(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("property name must not be blank");
        }
        return new AnimationTargetRef(PROPERTY, name);
    }
}
