package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.uml.math.Transform;

import java.util.Objects;

/** Immutable logical output of one rigid model-group part. */
record RigidPartOutput(
        RootSnapshot root,
        PartMotionSnapshot motion,
        Transform local
) {

    RigidPartOutput {
        root = Objects.requireNonNull(root, "root");
        motion = Objects.requireNonNull(motion, "motion");
        local = Objects.requireNonNull(local, "local").copy();
    }

    @Override
    public Transform local() {
        return local.copy();
    }

    Transform sample(double interpolationAlpha) {
        if (!Double.isFinite(interpolationAlpha)
                || interpolationAlpha < 0.0
                || interpolationAlpha > 1.0) {
            throw new IllegalArgumentException(
                    "interpolationAlpha must be finite and in [0, 1]");
        }
        float alpha = (float) interpolationAlpha;
        Transform placement = root.at(alpha).mul(local);
        Transform localMotion = motion.sample(alpha);
        if (localMotion != null) {
            placement.mul(localMotion);
        }
        return placement;
    }
}
