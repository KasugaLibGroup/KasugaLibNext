package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.vpd;

import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationSampler;
import lib.kasuga.rendering.models.uml.dynamic.fsm.ApplyMode;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Pose;

/**
 * {@link AnimationSampler} for a static {@link VpdPose}: a VPD pose is a single snapshot, so sampling at
 * any time yields the same pose (the {@link lib.kasuga.rendering.models.uml.typo.miku_miku_dance.vmd.VmdSampler VMD}
 * path interpolates; this one has nothing to
 * interpolate). Replaces the former {@code VpdPlayer} (deleted) — the pose now feeds the animation
 * pipeline ({@code AnimationPlayer} / FSM clip states) via {@code PoseSink.applyPose}.
 */
public final class VpdSampler implements AnimationSampler<VpdPose> {

    public static final VpdSampler INSTANCE = new VpdSampler();

    private VpdSampler() {
    }

    /** A static pose has no duration; {@code AnimationPlayer} treats {@code ≤0} as time 0 (constant pose). */
    @Override
    public float duration(VpdPose data) {
        return 0f;
    }

    @Override
    public Pose sample(VpdPose data, float time) {
        Pose.Builder builder = new Pose.Builder();
        data.bones().forEach((name, transform) -> builder.bone(name, transform, ApplyMode.REPLACE));
        data.morphs().forEach((name, weight) -> builder.morph(name, weight, 1f));
        return builder.build();
    }
}